package br.com.ex2.live3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.IBLPrefilterContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A cena 3D compartilhada (um só Engine no processo): o app e o papel de parede desenham
 * a mesma cena, cada um com sua câmera. Tudo roda na thread principal.
 */
class World private constructor(val ctx: Context) {
    val engine: Engine = Engine.create()
    val scene: Scene = engine.createScene()
    val mats = Materials(ctx, engine)
    private val em = EntityManager.get()
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private val lm = engine.lightManager

    /** muda sempre que algo visível muda: as telas redesenham */
    var version = 0L
        private set
    /** telas abertas: acordam quando a cena muda */
    val listeners = LinkedHashSet<() -> Unit>()
    private fun touch() { version++; for (l in listeners.toList()) l() }

    /** chuva/raios pedem animação contínua (as telas decidem quanto e a quantos fps) */
    var ambient = false
        private set
    /** raios só dentro da janela de animação da tela (cada raio redesenha o carro inteiro) */
    var flashOk = true
    val raining get() = rainOn

    /**
     * pingos da chuva (desenhados por cima da imagem pronta do carro): tempo "curto" (com o relógio
     * crescendo sem parar os riscos congelavam e sumiam), intensidade e vento. false = sem chuva.
     */
    fun rainParams(out: FloatArray): Boolean {
        val st = env ?: return false
        if (!rainOn) return false
        out[0] = clock % 600f; out[1] = st.weather.rain; out[2] = .08f + .25f * st.weather.storm
        return true
    }

    private var tickNanos = 0L
    private var tickBusy = false

    /** um passo de animação por quadro, mesmo com várias telas desenhando */
    fun tick(frameNanos: Long, dt: Float): Boolean {
        if (frameNanos != tickNanos) { tickNanos = frameNanos; tickBusy = update(dt) }
        return tickBusy
    }

    /* ---------------- céu, chão, sol ---------------- */

    // céu além das montanhas mais distantes (216 m)
    private val skyMesh = Geo.sphere(engine, 300f)
    private val skyMi = mats.sky.createInstance()
    private val skyEntity = Geo.renderable(engine, skyMesh, skyMi, priority = 0)
    // 80 m de chão (maior que isso o emulador desenhava errado); além disso o céu continua o lago
    // mesmo tipo de retângulo (com coordenadas de textura) que funcionou nos testes do emulador
    private val groundMesh = Geo.panel(engine, arrayOf(floatArrayOf(-40f, GROUND_Y, 40f), floatArrayOf(40f, GROUND_Y, 40f),
        floatArrayOf(40f, GROUND_Y, -40f), floatArrayOf(-40f, GROUND_Y, -40f)))
    // a instância do lago é recriada quando o céu fica pronto (criada antes ficava invisível)
    private var groundMi = mats.ground.createInstance()
    private var groundFresh = false
    private val groundEntity = Geo.renderable(engine, groundMesh, groundMi, priority = 5)
    /* sombra do carro numa camada própria por cima do chão */
    private val catcherMesh = Geo.ground(engine, GROUND_Y + .002f, 7f, tangents = true)
    private var catcherMi = mats.catcher.createInstance().apply { setParameter("strength", .5f) }
    private val catcherEntity = Geo.renderable(engine, catcherMesh, catcherMi, priority = 6, shadows = true)
    private val screenMesh = Geo.screen(engine)
    private val rainMi = mats.rain.createInstance()
    private val rainEntity = Geo.renderable(engine, screenMesh, rainMi, priority = 7)
    private val sun = em.create()
    /** textura de ruído pronta (nuvens, rocha, rachaduras, estrelas): gerada uma vez, só lida */
    private val noiseTex: Texture = run {
        val bytes = Scenery.prepareNoise()
        val n = Scenery.NOISE
        val levels = (floor(kotlin.math.log2(n.toFloat())) + 1).toInt()
        Texture.Builder().width(n).height(n).levels(levels).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine).also { t ->
                bytes.rewind()
                t.setImage(engine, 0, Texture.PixelBufferDescriptor(bytes, Texture.Format.RGBA, Texture.Type.UBYTE))
                t.generateMipmaps(engine)
            }
    }
    private val noiseSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
    /** chaves de teste de desempenho (Ajustes) */
    private var showMountains = true
    private var mirrorMountains = true
    private var clouds = true
    /* cordilheiras 3D (real + reflexo no lago); malhas geradas em segundo plano */
    private class MountainPart(val range: Scenery.Range, val entity: Int, val mi: MaterialInstance, val mirror: Boolean)
    private val mountainParts = ArrayList<MountainPart>()
    private var mountainData: List<Scenery.Data>? = null
    private var mountainsReady = false
    private var alive = true
    private val ibl = HashMap<String, Pair<IndirectLight, Texture>>()
    private var iblKey = ""
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.REPEAT)
    private val clampSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)

    var env: SkyClock.State? = null
        private set
    private var envKey = ""
    private var rainOn = false
    private var flash = 0f
    private var flashT = -1f
    private var flashNext = 5f
    private var clock = 0f

    /* ---------------- carro ---------------- */

    private val provider = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, provider, em)
    private val resLoader = ResourceLoader(engine, true)
    private var asset: FilamentAsset? = null
    private var instances: List<FilamentInstance> = emptyList()
    var loaded = false
        private set
    var loadProgress = 0f
        private set

    private class Part(val tag: String, val renderables: List<Int>)   // um por instância (real, espelho)
    private val parts = ArrayList<Part>()
    private class Swap(val ri: Int, val prim: Int, val original: MaterialInstance, val paint: MaterialInstance, val black: MaterialInstance?, val mirror: Boolean)
    private val contrast = ArrayList<Swap>()
    private val strips = ArrayList<Swap>()
    private val paintMis = ArrayList<MaterialInstance>()
    /** cópias de materiais do carro feitas aqui (liberadas antes dos materiais do carregador) */
    private val ownMis = ArrayList<MaterialInstance>()
    private val plateMis = ArrayList<MaterialInstance>()
    private var plateTex: Texture? = null

    private class Lamp(val kind: String, val side: Int, val rank: Int, val mis: List<MaterialInstance>,
                       val center: FloatArray, val size: FloatArray, val normal: FloatArray,
                       val glow: Int, val glowMi: MaterialInstance) {
        var base = 0f; var color = WHITE
        /** peça da lanterna no carro e a posição inicial: o brilho acompanha a peça (porta-malas abrindo) */
        var entity = 0
        var center0 = FloatArray(3); var normal0 = FloatArray(3); var w0inv: FloatArray? = null
    }
    private val lamps = ArrayList<Lamp>()
    private val glowMesh = Geo.quad(engine)
    private val spots = ArrayList<Int>()

    private class Anim(val index: Int, val duration: Float) { var p = 0f; var target = 0f }
    private val anims = HashMap<String, Anim>()


    /* estado pedido (aplicado quando o modelo termina de carregar) */
    private var trim = CarSpec.trims[0]
    private var paint = CarSpec.paints[0]
    private var plate = ""
    private var lights = LightState()
    private var blinkT = 0f
    /** reflexo pedido pelo usuário; no cenário "Estrada" não há lago, então não há reflexo */
    private var reflectPref = true
    private val reflection get() = reflectPref && roadMix < 1f

    // (declarados antes do init: o init já usa)
    private val srcMaterial = HashMap<MaterialInstance, com.google.android.filament.Material>()
    /** materiais "sem luz" (KHR_materials_unlit) pelo nome, lidos do próprio car.glb */
    private val unlitNames = HashSet<String>()
    private val paramCache = HashMap<String, Boolean>()

    init {
        scene.addEntity(skyEntity)
        scene.addEntity(groundEntity)
        scene.addEntity(rainEntity)
        rm.setLayerMask(rm.getInstance(rainEntity), 0xff, 0)
        buildSun()
        groundMi.setParameter("contact", 0f)
        groundMi.setParameter("pool", 1f, .86f, .66f, 0f)
        groundMi.setParameter("mirror", 1f)
        groundMi.setParameter("noise", noiseTex, noiseSampler)
        skyMi.setParameter("noise", noiseTex, noiseSampler)
        Diag.log("Cena: céu, chão e sol ok · ${Diag.mem()}")
        Thread({
            val t0 = android.os.SystemClock.uptimeMillis()
            val d = Scenery.ranges.map { Scenery.build(it, GROUND_Y) }
            Diag.log("Montanhas geradas em ${android.os.SystemClock.uptimeMillis() - t0} ms")
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (alive) { mountainData = d; buildMountains() } }
        }, "ex2-montanhas").start()
        compileScenery()
        startLoading()
    }

    /* ---------------- parâmetros com segurança ---------------- */
    // mudar um parâmetro que o material não tem derruba o app (erro nativo): sempre consultar antes


    private fun materialOf(mi: MaterialInstance): com.google.android.filament.Material? =
        srcMaterial[mi] ?: try { mi.material as com.google.android.filament.Material? } catch (_: Throwable) { null }


    /**
     * As peças do carro vêm do gltfio sem o Material ligado no Java; então um parâmetro só é
     * mexido se TODOS os materiais do gerador (da mesma família: com/sem luz) o tiverem.
     */
    private fun has(mi: MaterialInstance, p: String): Boolean {
        materialOf(mi)?.let { return it.hasParameter(p) }
        val unlit = mi.name in unlitNames
        return paramCache.getOrPut("$unlit|$p") {
            val fam = if (unlit) probeUnlit else probeLit
            fam.isNotEmpty() && fam.all { it.hasParameter(p) }
        }
    }

    /* materiais de amostra do gerador (opaco, recortado, transparente); a lista completa
       do gerador (provider.materials) derruba o app nesta versão da biblioteca */
    private fun probe(unlit: Boolean): List<com.google.android.filament.Material> = (0..2).mapNotNull { alpha ->
        try {
            val key = com.google.android.filament.gltfio.MaterialProvider.MaterialKey().apply {
                this.unlit = unlit; alphaMode = alpha; hasClearCoat = !unlit
            }
            provider.getMaterial(key, IntArray(8), "probe")
        } catch (t: Throwable) { Diag.log("Amostra de material falhou: $t"); null }
    }
    private val probeLit by lazy { probe(false) }
    private val probeUnlit by lazy { probe(true) }

    private fun readUnlitNames(buf: ByteBuffer) {
        try {
            val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val len = b.getInt(12)
            val bytes = ByteArray(len)
            b.position(20); b.get(bytes)
            val mats = org.json.JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("materials") ?: return
            for (i in 0 until mats.length()) {
                val m = mats.getJSONObject(i)
                if (m.optJSONObject("extensions")?.has("KHR_materials_unlit") == true) unlitNames.add(m.optString("name"))
            }
            Diag.log("Materiais sem luz: $unlitNames")
        } catch (t: Throwable) { Diag.log("Leitura dos materiais falhou: $t") }
    }

    private fun set(mi: MaterialInstance, p: String, vararg v: Float) {
        if (!has(mi, p)) return
        when (v.size) {
            1 -> mi.setParameter(p, v[0])
            2 -> mi.setParameter(p, v[0], v[1])
            3 -> mi.setParameter(p, v[0], v[1], v[2])
            else -> mi.setParameter(p, v[0], v[1], v[2], v[3])
        }
    }

    /** etapa opcional: se falhar, registra e segue (o carro aparece mesmo assim) */
    private inline fun step(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Diag.log("Cena: etapa '$name' falhou: ${Log.trace(t)}") }
    }

    /* ---------------- carregamento ---------------- */

    private fun readAsset(name: String): ByteBuffer {
        ctx.assets.openFd(name).use { fd ->
            fd.createInputStream().channel.use { ch ->
                return ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            }
        }
    }

    private fun startLoading() {
        val buf = try { readAsset("car.glb") } catch (e: Exception) {
            // asset comprimido (não deveria): lê inteiro
            val bytes = ctx.assets.open("car.glb").use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
        }
        readUnlitNames(buf)
        // 2.8.3: uma cópia só do carro. O carro refletido no lago (2ª cópia, desenhada de cabeça para baixo)
        // custava o carro inteiro de novo e dava falhas de desenho na lataria e nos vidros na central;
        // no lugar dele, uma sombra suave pintada no próprio lago (material do chão)
        val inst = arrayOfNulls<FilamentInstance>(if (CAR_MIRROR) 2 else 1)
        val a = loader.createInstancedAsset(buf, inst) ?: throw IllegalStateException("car.glb inválido")
        asset = a
        instances = inst.filterNotNull()
        resLoader.asyncBeginLoad(a)
        Diag.log("Modelo: ${a.entities.size} entidades, ${instances.size} instâncias; sol=${lm.hasComponent(sun)}")
    }

    private fun finishLoading() {
        val a = asset ?: return
        Diag.log("Cena: modelo carregado, montando peças")
        a.releaseSourceData()
        val real = instances[0]
        val names = HashMap<Int, String>()
        val ents0 = real.entities
        for (e in ents0) a.getName(e)?.let { names[e] = it }
        // marca de cada entidade = nome X_… mais próximo subindo na hierarquia
        val tagOf = HashMap<Int, String>()
        fun tag(e: Int): String {
            tagOf[e]?.let { return it }
            val n = names[e] ?: ""
            val t = if (n.startsWith("X_")) n else {
                val parent = tm.getParent(tm.getInstance(e))
                if (parent != 0 && parent != e) tag(parent) else ""
            }
            tagOf[e] = t
            return t
        }
        val perInstance = instances.map { it.entities }
        val count = ents0.size
        for (k in instances.indices) check(perInstance[k].size == count) { "instâncias diferentes" }
        Diag.log("Montagem: nomes ok (${names.size})")

        // espelho do lago: segunda instância refletida no plano do chão
        if (instances.size > 1) {
            val m = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 2 * GROUND_Y, 0f, 1f)
            tm.setTransform(tm.getInstance(instances[1].root), m)
        }

        val byTag = LinkedHashMap<String, MutableList<Int>>()
        for (i in 0 until count) {
            val e0 = ents0[i]
            if (!rm.hasComponent(e0)) continue
            val t = tag(e0)
            for (k in instances.indices) {
                val e = perInstance[k][i]
                val ri = rm.getInstance(e)
                rm.setCastShadows(ri, k == 0)
                rm.setReceiveShadows(ri, k == 0)
                if (t.isNotEmpty()) byTag.getOrPut(t) { ArrayList() }.add(e)
            }
        }
        byTag.forEach { (t, list) -> parts.add(Part(t, list)) }
        Diag.log("Montagem: peças marcadas (${parts.size})")

        // otimização: interior das portas só existe com a porta abrindo; no reflexo, só a casca
        // (Body_2 = vedação preta: forma o contorno externo da porta, fica sempre)
        // (fora do interior: Body_2 = vedação; FL/FR Body_12 = espelho do retrovisor e FR Body_15 = fundo
        //  do retrovisor — escondidos com o carro fechado, o retrovisor ficava com um buraco)
        val doorBody = Regex("^E22H_Door_(FL|FR|RL|RR)_Body_(?!2$)(?!12$)\\d+$")
        val mirrorPart = Regex("^E22H_Door_FR_Body_15$")
        val mirrorSkip = Regex("^E22H_(Door_(FL|FR|RL|RR)_Body_\\d+|SuspensionSystem|shachepian_\\d+|Windshield_wiper_\\d+|Light_HeadLamp_\\d+|Qianzhijia|qiankou)$")
        // (2.8.0) no reflexo, das portas só sai o forro de dentro: a vedação preta de fora (Body_2: contorno
        // da janela e acabamento de baixo), o espelho e o fundo do retrovisor ficam — sem eles o carro
        // refletido tinha "buracos" (via-se o lago através dele)
        for (i in 0 until count) {
            val n = names[ents0[i]] ?: continue
            if (!rm.hasComponent(ents0[i])) continue
            val lining = !mirrorPart.matches(n) && doorBody.matches(n)
            if (lining) doorBody.find(n)?.let { m -> doorInterior.getOrPut(m.groupValues[1]) { ArrayList() }.add(ents0[i]) }
            val doorPart = n.contains("_Door_")
            if (instances.size > 1 && mirrorSkip.matches(n) && (!doorPart || lining)) mirrorHidden.add(perInstance[1][i])
            if (n.startsWith("E22H_shachepian")) brakeEnts.add(ents0[i])
        }
        Diag.log("Otimização: interior das portas ${doorInterior.values.sumOf { it.size }} peças, fora do reflexo ${mirrorHidden.size}")

        // materiais por instância
        val glassNames = setOf("E22H_Glass", "E22H_Glass (no uv1)", "E22H_Glass.003")
        Diag.log("Montagem: instâncias de material ${instances.map { it.materialInstances.size }}")
        Diag.log("Montagem: nomes ${instances[0].materialInstances.take(5).map { it.name }}")
        Diag.log("Montagem: amostras ${probeLit.size}+${probeUnlit.size}; verniz=${probeLit.all { it.hasParameter("clearCoatFactor") }} luz=${probeLit.all { it.hasParameter("emissiveStrength") }}")
        for (inst in instances) {
            for (mi in inst.materialInstances) {
                when (mi.name) {
                    PAINT -> paintMis.add(mi)
                    "plate" -> plateMis.add(mi)
                    in glassNames -> { set(mi, "baseColorFactor", .010f, .012f, .014f, 1f); glassMis.add(mi) }
                    // lanternas: no modelo a lente vermelha e as lentes transparentes eram METAL
                    // (pareciam cromadas); agora acrílico: vermelho escuro com brilho, sem metal
                    "E22H_G_light-R" -> { set(mi, "metallicFactor", 0f); set(mi, "roughnessFactor", .16f); set(mi, "baseColorFactor", .17f, .012f, .012f, 1f) }
                    // (a capa transparente dos faróis/lanternas e o refletor ficam como no modelo original:
                    //  trocando para plástico eles ficavam esbranquiçados)
                    // peças internas das lanternas com cor de "branco puro" no modelo (brilhavam como frestas)
                    "Color #333333ff" -> set(mi, "baseColorFactor", .2f, .2f, .2f, 1f)
                    // segmentos da seta (lanterna traseira e retrovisor): eram metal espelhado branco
                    // (triângulos azulados refletindo o céu); apagados = acrílico grafite, acesos brilham âmbar
                    "E22H_Rearview mirror light" -> { set(mi, "metallicFactor", 0f); set(mi, "roughnessFactor", .35f); set(mi, "baseColorFactor", .06f, .05f, .045f, 1f) }
                    "Color #ff0000ff" -> set(mi, "baseColorFactor", .22f, .015f, .015f, 1f)
                }
            }
        }
        Diag.log("Vidros: ${glassMis.size} · ${glassMis.map { it.name }.distinct()} · cor ok=${glassMis.map { has(it, "baseColorFactor") }.distinct()}")
        // tinta sem a oclusão (as peças de contraste não têm o UV da oclusão)
        val paintNoAo = paintMis.firstOrNull()?.let { src ->
            // a cópia não guarda o material de origem: anota para consultar os parâmetros
            MaterialInstance.duplicate(src, "paintNoAO")
        }
        Diag.log("Montagem: materiais ok (tinta ${paintMis.size}, placa ${plateMis.size})")
        paintNoAo?.let { set(it, "aoStrength", 0f); paintMis.add(it); ownMis.add(it) }
        Diag.log("Montagem: tinta sem oclusão ok")

        for ((k, inst) in instances.withIndex()) {
            val black = inst.materialInstances.firstOrNull { it.name == "E22H_Plastic Black" }
            for (i in 0 until count) {
                val e0 = ents0[i]
                val t = tagOf[e0] ?: continue
                val e = perInstance[k][i]
                if (!rm.hasComponent(e)) continue
                val ri = rm.getInstance(e)
                if (t.startsWith("X_CONTRAST") && paintNoAo != null) {
                    for (p in 0 until rm.getPrimitiveCount(ri))
                        contrast.add(Swap(ri, p, rm.getMaterialInstanceAt(ri, p), paintNoAo, null, t.contains("|mirror")))
                } else if (t.startsWith("X_STRIP") && black != null) {
                    for (p in 0 until rm.getPrimitiveCount(ri))
                        strips.add(Swap(ri, p, rm.getMaterialInstanceAt(ri, p), paintNoAo ?: black, black, false))
                }
            }
        }

        Diag.log("Montagem: contraste ${contrast.size}, frisos ${strips.size}")
        step("lanternas traseiras sem cromado") { tailLampLook(ents0, perInstance, names) }
        step("placas") { placePlates(ents0, perInstance, names) }
        step("interior") { loadInterior() }
        step("lanternas") { buildLamps(ents0, perInstance, tagOf) }
        step("feixes") { buildBeams() }
        step("emblema") { setupBadge(ents0, perInstance, names) }
        // (2.8.0: o volume escuro dentro da carroceria saiu, a pedido do usuário; os vidros escuros e
        //  o forro das portas sempre visível fazem o mesmo papel — ver updateDoorInteriors)
        if (USE_SHADE) step("interior escuro") { buildShade() }
        step("luz de freio central") { buildBrakeBar(ents0, names) }
        step("tampa do gancho de reboque") { buildTowCap() }
        if (CAR_MIRROR) step("reflexo leve") { loadMirrorLod() }
        // (2.8.1: a placa escura do "fundo do reflexo" saiu — vista de cima ela aparecia pelo lago como
        //  uma sombra quadrada embaixo do carro)
        step("calotas") { loadHubcaps() }
        step("rodas") { setupWheels() }

        // animações (mesmos índices nas duas instâncias)
        val an = real.animator
        for (i in 0 until an.animationCount) anims[an.getAnimationName(i)] = Anim(i, an.getAnimationDuration(i))
        Diag.log("Animações: ${anims.keys}")

        loaded = true
        step("cor e versão") { applyConfig() }
        step("luzes") { applyLights() }
        step("portas") { applyAnimations() }
        step("reflexo") { applyReflection() }
        Diag.log("Cena: carro pronto · ${Diag.mem()}")
        precompileCar()
        tryShowCar()
        touch()
    }

    /* ---------------- ordem de aparição: cenário → carro → (tela) pontos ---------------- */

    /** cenário (céu + lago) já desenhado de verdade */
    var sceneryReady = false
        private set
    var carShown = false
        private set
    val sceneryListeners = LinkedHashSet<() -> Unit>()
    val carListeners = LinkedHashSet<() -> Unit>()
    private var carRequested = false

    /** o carro já apareceu antes nesta versão do app: caches prontos, não precisa abrir em etapas */
    val warmStart = Prefs.get(ctx).getInt(SHOWN_VERSION, -1) == BuildConfig.VERSION_CODE

    /** sombra sob o carro: 0 até o carro entrar, depois aparece junto com ele (~0,4 s) */
    private var contactK = 0f
    private fun applyContact() {
        val day = env?.day ?: 1f
        groundMi.setParameter("contact", (.85f + .15f * day) * contactK)
        applyRoad()
    }
    private var warmUntil = 0f

    /** pedido da tela para o carro entrar (depois do cenário); entra assim que estiver carregado */
    fun requestCar() { carRequested = true; tryShowCar() }

    /**
     * Prepara (compila) antes os materiais que o carro usa: sem isso a placa de vídeo faz isso ao
     * desenhar pela primeira vez e o carro demora a aparecer. Avisa quando terminar (máx. 10 s).
     */
    private var carCompiled = false
    private fun precompileCar() {
        val list = (probeLit + probeUnlit).distinct()
        if (list.isEmpty()) { carCompiled = true; return }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var left = list.size
        val t0 = android.os.SystemClock.uptimeMillis()
        val done = Runnable {
            if (--left == 0 && !carCompiled) {
                carCompiled = true
                Diag.log("Materiais do carro prontos em ${android.os.SystemClock.uptimeMillis() - t0} ms")
                tryShowCar()
            }
        }
        val variants = com.google.android.filament.Material.UserVariantFilterBit.DIRECTIONAL_LIGHTING or
            com.google.android.filament.Material.UserVariantFilterBit.DYNAMIC_LIGHTING
        for (m in list) m.compile(com.google.android.filament.Material.CompilerPriorityQueue.HIGH, variants, main, done)
        main.postDelayed({ if (!carCompiled) { carCompiled = true; Diag.log("Materiais do carro: tempo esgotado"); tryShowCar() } }, 10_000)
    }

    private fun tryShowCar() {
        if (!carRequested || !loaded || !carCompiled || carShown) return
        carShown = true
        val lod = lodAsset
        if (lod != null) {
            // reflexo: carro leve no lugar do molde (o molde não é desenhado)
            scene.addEntities(instances[0].entities)
            scene.addEntities(lod.entities)
        } else for (inst in instances) scene.addEntities(inst.entities)
        hubAsset?.let { scene.addEntities(it.entities) }
        if (shadeEntity != 0) { scene.addEntity(shadeEntity); applyShade() }
        applyBeams()
        applyUnderbody()
        // a placa de vídeo prepara os materiais do carro em segundo plano: alguns quadros seguidos
        // garantem que ele apareça (sem isso só surgia no próximo toque)
        warmUntil = clock + 3f
        Diag.log("Carro na tela" + if (warmStart) " (abertura rápida)" else "")
        if (!warmStart) Prefs.get(ctx).edit().putInt(SHOWN_VERSION, BuildConfig.VERSION_CODE).apply()
        touch()
        android.os.Handler(android.os.Looper.getMainLooper()).post { carListeners.toList().forEach { it() } }
    }

    /* ---------------- ajustes de teste (adb) ---------------- */

    val exposureListeners = LinkedHashSet<(Float) -> Unit>()

    /** teste no emulador: adb shell am broadcast -a br.com.ex2.live3d.DEBUG --es k v ... */
    fun debug(k: String, v: String) {
        val f = v.toFloatOrNull() ?: 0f
        val li = lm.getInstance(sun)
        when (k) {
            "sun" -> lm.setIntensity(li, f)
            "ibl" -> scene.indirectLight?.intensity = f
            "ao" -> paintMis.forEach { mi -> try { mi.setParameter("aoStrength", f) } catch (t: Throwable) { Diag.log("ao: $t") } }
            "occl" -> paintMis.forEach { mi -> Diag.log("${mi.name} aoStrength? ${has(mi, "aoStrength")}") }
            "dir" -> v.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 3 }?.let { lm.setDirection(li, it[0], it[1], it[2]) }
            "shadow" -> lm.setShadowCaster(li, f > 0)
            "mirror" -> groundMi.setParameter("mirror", f)
            "steer" -> setMotion(0f, f)          // teste: esterça as rodas com o carro parado
            // teste: lados desenhados dos vidros (back/front/none)
            "shade" -> { if (shadeEntity != 0) rm.setLayerMask(rm.getInstance(shadeEntity), 0xff, if (f > 0) 1 else 0); touch() }
            "glassrough" -> { for (mi in glassMis) set(mi, "roughnessFactor", f); touch() }
            "glasscull" -> { val m = when (v) { "front" -> com.google.android.filament.Material.CullingMode.FRONT; "none" -> com.google.android.filament.Material.CullingMode.NONE; else -> com.google.android.filament.Material.CullingMode.BACK }
                for (mi in glassMis) mi.setCullingMode(m); touch() }
            // teste: esconde (ou mostra de novo, com "!") as peças cujo nome casa com a expressão
            "hide" -> {
                val show = v.startsWith("!"); val rx = Regex(v.removePrefix("!"))
                val a = asset
                var n = 0
                if (a != null) for (e in instances[0].entities) {
                    val nm = a.getName(e) ?: continue
                    if (rx.containsMatchIn(nm) && rm.hasComponent(e)) { rm.setLayerMask(rm.getInstance(e), 0xff, if (show) 1 else 0); n++; Diag.log("teste: ${if (show) "mostra" else "esconde"} $nm") }
                }
                Diag.log("teste: $n peças")
                touch()
            }
            "gtest" -> {   // ex.: "transparent|1|material.baseColor = vec4(0.5,0.0,0.0,0.5);"
                val parts = v.split('|', limit = 3)
                val m = Materials.testMaterial(engine, parts[0], parts.getOrNull(1) == "1",
                    parts.getOrNull(2) ?: "material.baseColor = vec4(0.5, 0.0, 0.0, 0.5);")
                val mi = m.createInstance()
                if (m.hasParameter("mirror")) mi.setParameter("mirror", .35f)
                rm.setMaterialInstanceAt(rm.getInstance(groundEntity), 0, mi)
            }
            "gsrc" -> Diag.log("fonte do chão: " + (try { mats.ground.javaClass.getMethod("getSource").invoke(mats.ground) } catch (t: Throwable) { t.toString() }).toString().take(3000))
            "gmat" -> rm.setMaterialInstanceAt(rm.getInstance(groundEntity), 0, groundMi)
            "hour" -> env?.let { st ->   // teste: céu em outra hora/clima (ex.: "1200,chuva")
                val parts = v.split(',')
                setEnvironment(SkyClock.compute(parts[0].toFloat() / 100 * 60, 6 * 60 + 35, 18 * 60 + 45, parts.getOrNull(1) ?: st.weather.id))
            }
            "gprio" -> rm.setPriority(rm.getInstance(groundEntity), f.toInt())
            "gy" -> {   // retângulo de teste na altura y=f (2 m de lado)
                val m = Geo.panel(engine, arrayOf(floatArrayOf(-2f, f, 3f), floatArrayOf(2f, f, 3f), floatArrayOf(2f, f, -3f), floatArrayOf(-2f, f, -3f)))
                rm.setGeometryAt(rm.getInstance(groundEntity), 0, com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES, m.vb, m.ib, 0, m.count)
                rm.setAxisAlignedBoundingBox(rm.getInstance(groundEntity), m.box)
            }
            "gquad" -> {
                val y = GROUND_Y; val r = f
                val m = Geo.panel(engine, arrayOf(floatArrayOf(-r, y, r), floatArrayOf(r, y, r), floatArrayOf(r, y, -r), floatArrayOf(-r, y, -r)))
                rm.setGeometryAt(rm.getInstance(groundEntity), 0, com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES, m.vb, m.ib, 0, m.count)
                rm.setAxisAlignedBoundingBox(rm.getInstance(groundEntity), m.box)
            }
            "hide" -> rm.setLayerMask(rm.getInstance(if (v == "sky") skyEntity else groundEntity), 0xff, 0)
            "coat" -> paintMis.forEach { set(it, "clearCoatFactor", f) }
            "rough" -> paintMis.forEach { set(it, "roughnessFactor", f) }
            "coatr" -> paintMis.forEach { set(it, "clearCoatRoughnessFactor", f) }
            "exp" -> exposureListeners.forEach { it(f) }
            "info" -> Diag.log("sol: entidade=$sun componente=${lm.hasComponent(sun)} inst=$li tipo=${if (li != 0) lm.getType(li) else null} " +
                "intensidade=${if (li != 0) lm.getIntensity(li) else 0f} luzes na cena=${scene.lightCount} renderizáveis=${scene.renderableCount}")
        }
        Diag.log("debug $k=$v")
        touch()
    }

    /* ---------------- placas ---------------- */

    /**
     * A placa Mercosul veio do site sem a escala/posição que o plate.js aplicava em tempo real.
     * Aqui cada malha "Object_4" é encaixada no recesso da placa (dianteira e traseira):
     * na malha, a face visível olha para -X, a altura é Y e o comprimento é Z.
     */
    private fun placePlates(ents0: IntArray, perInstance: List<IntArray>, names: Map<Int, String>) {
        val idx = ents0.indices.filter { names[ents0[it]] == "Object_4" && rm.hasComponent(ents0[it]) }
        val slots = listOf(floatArrayOf(.0007f, .4722f, 2.1744f, 0f), floatArrayOf(.0002f, .4735f, -1.9229f, 1f))
        val box = com.google.android.filament.Box()
        idx.take(2).forEachIndexed { n, i ->
            val s = slots[n]
            rm.getAxisAlignedBoundingBox(rm.getInstance(ents0[i]), box)
            val c = box.center; val h = box.halfExtent
            val sy = .1345f / (2 * h[1]); val sz = .40f / (2 * h[2]); val sx = sy
            val rear = s[3] > .5f
            // colunas (eixos locais no mundo): x → ±Z (normal -X vira para fora), y → Y, z → ±X
            val ax = if (rear) floatArrayOf(0f, 0f, 1f) else floatArrayOf(0f, 0f, -1f)
            // comprimento invertido: a arte da placa vem espelhada na malha (o Filament corrige o sentido dos triângulos)
            val az = if (rear) floatArrayOf(1f, 0f, 0f) else floatArrayOf(-1f, 0f, 0f)
            val face = floatArrayOf(c[0] - h[0], c[1], c[2])      // centro da face visível
            val m = FloatArray(16)
            for (k in 0..2) { m[k] = ax[k] * sx; m[4 + k] = (if (k == 1) 1f else 0f) * sy; m[8 + k] = az[k] * sz }
            m[15] = 1f
            val tgt = floatArrayOf(s[0], s[1], s[2] + (if (rear) -.004f else .004f))
            for (k in 0..2) m[12 + k] = tgt[k] - (m[k] * face[0] + m[4 + k] * face[1] + m[8 + k] * face[2])
            for ((k, ents) in perInstance.withIndex()) {
                val ti = tm.getInstance(ents[i])
                // filha da raiz do carro: acompanha a inclinação da carroceria quando ele anda
                // (presa ao mundo, a placa ficava parada enquanto o carro balançava)
                tm.setParent(ti, tm.getInstance(instances[k].root))
                tm.setTransform(ti, m)
            }
        }
        Diag.log("Placas encaixadas: ${idx.size}")
    }

    /* ---------------- lanternas traseiras ---------------- */

    /**
     * No modelo a capa das lanternas traseiras é metal 100% polido (um espelho) e o refletor de dentro
     * é cromo branco: no app a lanterna parecia cromada. No configurador original ela é fumê escura com
     * o LED vermelho. Os mesmos materiais estão nos faróis da frente, então aqui só as peças da
     * lanterna traseira ganham cópias ajustadas: capa de acrílico fumê (sem metal, bem transparente) e
     * fundo grafite fosco no lugar do cromo.
     */
    private fun tailLampLook(ents0: IntArray, perInstance: List<IntArray>, names: Map<Int, String>) {
        val cover = Regex("^E22H_(houdeng_5|Rear_Tail_Light_[67])$")
        val inner = Regex("^E22H_(houdeng_6|houdeng_2|Rear_Tail_Light_1)$")
        val made = HashMap<MaterialInstance, MaterialInstance>()
        fun copyOf(src: MaterialInstance, smoke: Boolean): MaterialInstance = made.getOrPut(src) {
            MaterialInstance.duplicate(src, src.name + if (smoke) "_tailSmoke" else "_tailMatte").also { mi ->
                ownMis.add(mi)
                if (smoke) {
                    set(mi, "metallicFactor", 0f); set(mi, "roughnessFactor", .06f)
                    set(mi, "baseColorFactor", .012f, .010f, .012f, .22f)
                } else {
                    set(mi, "metallicFactor", 0f); set(mi, "roughnessFactor", .55f)
                    set(mi, "baseColorFactor", .035f, .033f, .035f, 1f)
                }
            }
        }
        var n = 0
        for (i in ents0.indices) {
            val nm = names[ents0[i]] ?: continue
            val smoke = cover.matches(nm)
            if (!smoke && !inner.matches(nm)) continue
            for (ents in perInstance) {
                val e = ents[i]
                if (!rm.hasComponent(e)) continue
                val ri = rm.getInstance(e)
                for (p in 0 until rm.getPrimitiveCount(ri)) { rm.setMaterialInstanceAt(ri, p, copyOf(rm.getMaterialInstanceAt(ri, p), smoke)); n++ }
            }
        }
        Diag.log("Lanternas traseiras: $n partes sem cromado")
    }

    /* ---------------- reflexo leve ---------------- */

    /**
     * O reflexo no lago desenhava o carro inteiro de novo (~1 milhão de triângulos por quadro).
     * Agora ele usa car-mirror.glb (~20% dos triângulos). A 2ª instância do carro continua existindo
     * só como "molde": toda a lógica (cor, versão, rodas, peças escondidas) mexe nela, e
     * syncMirror() copia visibilidade e materiais para o carro leve, peça por peça (casadas pelo nome).
     */
    private var lodAsset: FilamentAsset? = null
    private val lodPairs = ArrayList<IntArray>()        // [entidade do molde, entidade leve]
    private val lodAnim = HashMap<Int, Int>()           // índice da animação no carro → no leve

    private fun loadMirrorLod() {
        if (instances.size < 2) return
        val a = try {
            val buf = readAsset("car-mirror.glb")
            loader.createAsset(buf)
        } catch (t: Throwable) { Diag.log("Reflexo leve indisponível: $t"); null } ?: return
        ResourceLoader(engine, true).apply { loadResources(a); destroy() }
        a.releaseSourceData()
        lodAsset = a
        val mold = instances[1]
        val byName = HashMap<String, ArrayDeque<Int>>()
        for (e in mold.entities) asset?.getName(e)?.let { byName.getOrPut(it) { ArrayDeque() }.add(e) }
        var prims = 0; var mismatch = 0
        for (e in a.entities) {
            val n = a.getName(e) ?: continue
            val m = byName[n]?.removeFirstOrNull() ?: continue
            // placas: mesma posição/encaixe calculados para o molde
            if (n == "Object_4") {
                val local = FloatArray(16)
                tm.getTransform(tm.getInstance(m), local)
                tm.setParent(tm.getInstance(e), tm.getInstance(a.root))
                tm.setTransform(tm.getInstance(e), local)
            }
            if (!rm.hasComponent(e) || !rm.hasComponent(m)) continue
            val ri = rm.getInstance(e)
            rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false)
            if (rm.getPrimitiveCount(ri) != rm.getPrimitiveCount(rm.getInstance(m))) mismatch++
            prims += rm.getPrimitiveCount(ri)
            lodPairs.add(intArrayOf(m, e))
        }
        tm.setTransform(tm.getInstance(a.root), floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 2 * GROUND_Y, 0f, 1f))
        val an = a.instance.animator
        val real = instances[0].animator
        for (i in 0 until real.animationCount) {
            val name = real.getAnimationName(i)
            for (j in 0 until an.animationCount) if (an.getAnimationName(j) == name) lodAnim[i] = j
        }
        Diag.log("Reflexo leve: ${lodPairs.size} peças, $prims partes, $mismatch diferentes, animações ${lodAnim.size}")
    }

    /* ---------------- movimento: rodas, esterço e carroceria ---------------- */

    /** um nó de roda (pneu, calota ou roda de liga) do carro ou do reflexo, com a pose original */
    private class WheelNode(val entity: Int, val base: FloatArray, val center: FloatArray, val front: Boolean)
    private val wheelNodes = ArrayList<WheelNode>()
    private var speedTarget = 0f; private var speedNow = 0f          // m/s (negativo = ré)
    private var steerTarget = 0f; private var steerNow = 0f          // graus, + = esquerda
    private var spinRad = 0f
    private var accelSmooth = 0f
    private var tiltPitch = 0f; private var tiltRoll = 0f
    private var bodyRoots = IntArray(0)

    /** velocidade (km/h, negativa = ré) e ângulo das rodas dianteiras (graus, + = esquerda) */
    fun setMotion(kmh: Float, wheelDeg: Float) {
        speedTarget = kmh / 3.6f; steerTarget = wheelDeg.coerceIn(-35f, 35f)
        touch()
    }

    /** nós das rodas: X_WHEEL|<tipo>|k (k par = pneu, ímpar = calota/roda; k/2 = posição) */
    private fun setupWheels() {
        val car = asset ?: return
        fun collect(ents: IntArray, nameOf: (Int) -> String?) {
            val byName = HashMap<String, Int>()
            for (e in ents) nameOf(e)?.let { if (it.startsWith("X_WHEEL|")) byName[it] = e }
            val box = com.google.android.filament.Box()
            for ((name, e) in byName) {
                val k = name.substringAfterLast('|').toIntOrNull() ?: continue
                val w = k / 2
                val hub = byName["X_WHEEL|cover15|${2 * w + 1}"] ?: byName["X_WHEEL|alloy16|${2 * w + 1}"] ?: continue
                val hm = FloatArray(16); tm.getTransform(tm.getInstance(hub), hm)
                val base = FloatArray(16); tm.getTransform(tm.getInstance(e), base)
                // cada peça gira em torno do PRÓPRIO centro: o pneu tem o centro ~4 mm fora do da calota
                // (girando em torno da calota ele "batia" para cima e para baixo = roda bamba)
                var c = floatArrayOf(hm[12], hm[13], hm[14])
                if (rm.hasComponent(e)) {
                    rm.getAxisAlignedBoundingBox(rm.getInstance(e), box)
                    val m = box.center
                    c = floatArrayOf(base[0] * m[0] + base[4] * m[1] + base[8] * m[2] + base[12],
                        base[1] * m[0] + base[5] * m[1] + base[9] * m[2] + base[13],
                        base[2] * m[0] + base[6] * m[1] + base[10] * m[2] + base[14])
                }
                wheelNodes.add(WheelNode(e, base, c, hm[14] > 0f))
            }
        }
        collect(instances[0].entities) { car.getName(it) }
        lodAsset?.let { l -> collect(l.entities) { l.getName(it) } }
        val roots = arrayListOf(tm.getInstance(instances[0].root))
        lodAsset?.let { roots.add(tm.getInstance(it.root)) }
        bodyRoots = roots.toIntArray()
        Diag.log("Rodas animadas: ${wheelNodes.size} nós")
    }

    /** um passo do movimento; true enquanto algo ainda se mexe */
    private fun updateMotion(dt: Float): Boolean {
        if (wheelNodes.isEmpty() || dt <= 0f) return false
        val prev = speedNow
        // inércia: a velocidade e o volante chegam ao valor do carro suavemente
        val dv = speedTarget - speedNow
        speedNow += dv.coerceIn(-5f * dt, 5f * dt)
        steerNow += (steerTarget - steerNow) * min(1f, dt * 6f)
        applyBrakes()
        val accel = (speedNow - prev) / dt
        accelSmooth += (accel - accelSmooth) * min(1f, dt * 3f)
        // giro visível limitado por quadro: acima de ~20 km/h a calota (5 raios) girava mais de meio raio
        // por quadro e o olho via a roda girando PARA TRÁS (efeito de filmagem); assim ela gira rápido
        // e sempre para a frente
        val step = (speedNow / WHEEL_RADIUS * dt).coerceIn(-MAX_SPIN_STEP, MAX_SPIN_STEP)
        spinRad = ((spinRad + step) % (2 * Math.PI.toFloat()))
        // carroceria: mergulha ao frear, levanta ao acelerar, inclina nas curvas (graus pequenos)
        val pitchT = (-accelSmooth * .35f).coerceIn(-1.4f, 1.4f)
        val rollT = (steerNow / 35f * min(1f, Math.abs(speedNow) / 12f) * 2.2f).coerceIn(-2.2f, 2.2f)
        tiltPitch += (pitchT - tiltPitch) * min(1f, dt * 4f)
        tiltRoll += (rollT - tiltRoll) * min(1f, dt * 4f)
        val moving = Math.abs(speedNow) > .02f || Math.abs(steerTarget - steerNow) > .05f ||
            Math.abs(tiltPitch) > .01f || Math.abs(tiltRoll) > .01f || Math.abs(dv) > .01f
        if (!moving && !motionDirty) return false
        motionDirty = moving
        val cs = cos(spinRad); val ss = sin(spinRad)
        val st = Math.toRadians(steerNow.toDouble()).toFloat()
        val cy = cos(st); val sy = sin(st)
        val out = FloatArray(16)
        // inclinação da carroceria em torno do centro do carro no chão
        val p = Math.toRadians(tiltPitch.toDouble()).toFloat(); val q = Math.toRadians(tiltRoll.toDouble()).toFloat()
        val cp = cos(p); val sp = sin(p); val cq = cos(q); val sq = sin(q)
        // Rx(pitch) · Rz(roll)
        val t = floatArrayOf(cq, sq * cp, sq * sp, 0f, -sq, cq * cp, cq * sp, 0f, 0f, -sp, cp, 0f, 0f, 0f, 0f, 1f)
        val pv = floatArrayOf(0f, GROUND_Y, .13f)
        t[12] = pv[0] - (t[0] * pv[0] + t[4] * pv[1] + t[8] * pv[2])
        t[13] = pv[1] - (t[1] * pv[0] + t[5] * pv[1] + t[9] * pv[2])
        t[14] = pv[2] - (t[2] * pv[0] + t[6] * pv[1] + t[10] * pv[2])
        // as rodas NÃO inclinam com a carroceria (afundavam no chão): desfaz a inclinação nelas
        val tInv = rigidInverse(t)
        val tmp = FloatArray(16)
        for (w in wheelNodes) {
            // M = T(c) · Ry(esterço, só dianteiras) · Rx(giro) · T(−c) · pose original
            val r = FloatArray(16)
            val ycos = if (w.front) cy else 1f; val ysin = if (w.front) sy else 0f
            // Ry · Rx (coluna principal)
            r[0] = ycos; r[1] = 0f; r[2] = -ysin; r[3] = 0f
            r[4] = ysin * ss; r[5] = cs; r[6] = ycos * ss; r[7] = 0f
            r[8] = ysin * cs; r[9] = -ss; r[10] = ycos * cs; r[11] = 0f
            val c = w.center
            r[12] = c[0] - (r[0] * c[0] + r[4] * c[1] + r[8] * c[2])
            r[13] = c[1] - (r[1] * c[0] + r[5] * c[1] + r[9] * c[2])
            r[14] = c[2] - (r[2] * c[0] + r[6] * c[1] + r[10] * c[2])
            r[15] = 1f
            mul(r, w.base, tmp)
            mul(tInv, tmp, out)      // (o pai das rodas é o nó da cena, sem transformação própria)
            tm.setTransform(tm.getInstance(w.entity), out)
        }
        bodyRoots.forEachIndexed { i, ri ->
            if (i == 0) tm.setTransform(ri, t)
            else { mul(MIRROR_M, t, out); tm.setTransform(ri, out) }     // reflexo: espelho · inclinação
        }
        return true
    }
    private var motionDirty = false

    /**
     * Freios (disco e pinça): são uma peça só para as 4 rodas e não viram com o volante; com a roda
     * esterçada eles atravessavam a calota. Com calota (Pro) nunca aparecem mesmo: ficam escondidos;
     * com roda de liga (Max) somem enquanto as rodas estão viradas.
     */
    private val brakeEnts = ArrayList<Int>()
    private var brakesShown = true
    private fun applyBrakes() {
        val show = trim.wheels != "cover15" && Math.abs(steerNow) < 3f
        if (show == brakesShown) return
        brakesShown = show
        for (e in brakeEnts) rm.setLayerMask(rm.getInstance(e), 0xff, if (show) 1 else 0)
    }

    /** inversa de rotação + translação (sem escala) */
    private fun rigidInverse(m: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (r in 0..2) for (c in 0..2) o[c * 4 + r] = m[r * 4 + c]
        for (r in 0..2) o[12 + r] = -(o[r] * m[12] + o[4 + r] * m[13] + o[8 + r] * m[14])
        o[15] = 1f
        return o
    }

    private fun mul(a: FloatArray, b: FloatArray, o: FloatArray) {
        for (col in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + row] * b[col * 4 + k]
            o[col * 4 + row] = s
        }
    }

    /* ---------------- calota da versão Pro ---------------- */

    /**
     * Calota da versão Pro = foto da calota real sobre um disco (hubcap.glb, 642 triângulos),
     * presa a cada roda do carro e do reflexo. Substitui a calota 3D do modelo (10,5 mil
     * triângulos por roda), que fica escondida.
     */
    private var hubAsset: FilamentAsset? = null
    private val hubEntities = ArrayList<Int>()
    private val hubMirror = HashSet<Int>()
    private val hubNode = Regex("^X_WHEEL\\|cover15\\|[1357]$")

    private fun loadHubcaps() {
        val car = asset ?: return
        val targets = ArrayList<Pair<Int, Boolean>>()          // (nó da roda, é reflexo)
        for (e in instances[0].entities) if (car.getName(e)?.let { hubNode.matches(it) } == true) targets.add(e to false)
        val lod = lodAsset
        if (lod != null) {
            for (e in lod.entities) if (lod.getName(e)?.let { hubNode.matches(it) } == true) targets.add(e to true)
        } else if (instances.size > 1) {
            for (e in instances[1].entities) if (car.getName(e)?.let { hubNode.matches(it) } == true) targets.add(e to true)
        }
        if (targets.isEmpty()) return
        val arr = arrayOfNulls<FilamentInstance>(targets.size)
        val a = try { loader.createInstancedAsset(readAsset("hubcap.glb"), arr) } catch (t: Throwable) { Diag.log("Calota: $t"); null } ?: return
        ResourceLoader(engine, true).apply { loadResources(a); destroy() }
        a.releaseSourceData()
        hubAsset = a
        // no nó da roda a calota é um disco de raio 0,202 no plano XY, de frente para +Z (para fora).
        // hubcap.glb: disco de raio 0,5 com a foto em z = +0,0235 → escala 0,404; a foto fica a ~1 cm
        // do centro do nó, onde estava a face da calota antiga
        val k = .404f
        val m = floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, .001f, 1f)
        targets.forEachIndexed { i, (node, mirror) ->
            val inst = arr[i] ?: return@forEachIndexed
            val ti = tm.getInstance(inst.root)
            tm.setParent(ti, tm.getInstance(node))
            tm.setTransform(ti, m)
            for (e in inst.entities) if (rm.hasComponent(e)) {
                val ri = rm.getInstance(e)
                rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false)
                hubEntities.add(e)
                if (mirror) hubMirror.add(e)
            }
        }
        // fundo da roda: o pneu é oco e a roda 3D antiga fica escondida (a calota é uma foto), então pelo
        // lado de dentro (e no reflexo, que mostra a roda por baixo) via-se um buraco. Disco escuro fechando.
        val seg = 28
        val pos = FloatArray((seg + 1) * 3); val idx = ShortArray(seg * 3)
        for (s in 0 until seg) {
            val a = s * 2 * Math.PI / seg
            pos[(s + 1) * 3] = (INNER_R * cos(a)).toFloat(); pos[(s + 1) * 3 + 1] = (INNER_R * sin(a)).toFloat(); pos[(s + 1) * 3 + 2] = INNER_Z
            idx[s * 3] = 0; idx[s * 3 + 1] = (s + 1).toShort(); idx[s * 3 + 2] = ((s + 1) % seg + 1).toShort()
        }
        pos[2] = INNER_Z
        val disc = Geo.mesh(engine, pos, idx)
        lateMeshes.add(disc)
        val dmi = mats.shade.createInstance().apply { setParameter("color", .012f, .012f, .013f) }
        innerMi = dmi
        for ((node, mirror) in targets) {
            val e = Geo.renderable(engine, disc, dmi, priority = 4)
            tm.create(e); tm.setParent(tm.getInstance(e), tm.getInstance(node))
            scene.addEntity(e)
            hubEntities.add(e); innerDiscs.add(e)
            if (mirror) hubMirror.add(e)
        }
        Diag.log("Calota Pro: ${targets.size} rodas, ${hubEntities.size} peças")
    }

    private val innerDiscs = ArrayList<Int>()
    private var innerMi: MaterialInstance? = null

    /** calota nova só na versão Pro (e a do reflexo só com reflexo ligado) */
    private fun applyHubcaps() {
        val pro = trim.wheels == "cover15"
        for (e in hubEntities) {
            val on = pro && (e !in hubMirror || reflection)
            rm.setLayerMask(rm.getInstance(e), 0xff, if (on) 1 else 0)
        }
    }

    /** copia do molde para o carro leve: o que está visível e com qual material */
    private fun syncMirror() {
        for (p in lodPairs) {
            val mri = rm.getInstance(p[0]); val lri = rm.getInstance(p[1])
            rm.setLayerMask(lri, 0xff, rm.getLayerMask(mri))
            val n = minOf(rm.getPrimitiveCount(mri), rm.getPrimitiveCount(lri))
            for (k in 0 until n) rm.setMaterialInstanceAt(lri, k, rm.getMaterialInstanceAt(mri, k))
        }
    }

    /* ---------------- interior 3D ---------------- */

    /** cabine simplificada (bancos, painel, volante, forro, porta-malas): só desenhada com algo aberto */
    private var interiorAsset: FilamentAsset? = null
    private var interiorShown = true

    private fun loadInterior() {
        try {
            val bytes = ctx.assets.open("interior.glb").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buf.flip()
            val a = loader.createAsset(buf) ?: return
            ResourceLoader(engine, true).apply { loadResources(a); destroy() }
            a.releaseSourceData()
            for (e in a.entities) if (rm.hasComponent(e)) {
                val ri = rm.getInstance(e)
                rm.setCastShadows(ri, false); rm.setReceiveShadows(ri, false)
            }
            scene.addEntities(a.entities)
            interiorAsset = a
            Diag.log("Interior 3D: ${a.renderableEntities.size} peças")
        } catch (t: Throwable) { Diag.log("Interior 3D falhou: $t") }
    }

    private fun setInteriorVisible(show: Boolean) {
        val a = interiorAsset ?: return
        if (show == interiorShown) return
        interiorShown = show
        for (e in a.entities) if (rm.hasComponent(e)) rm.setLayerMask(rm.getInstance(e), 0xff, if (show) 1 else 0)
    }

    private fun texture(bmp: Bitmap, mip: Boolean): Texture {
        val levels = if (mip) (floor(kotlin.math.log2(max(bmp.width, bmp.height).toFloat())) + 1).toInt() else 1
        val t = Texture.Builder().width(bmp.width).height(bmp.height).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        // a imagem só é liberada depois que a placa de vídeo terminar de copiá-la
        TextureHelper.setBitmap(engine, t, 0, bmp, android.os.Handler(android.os.Looper.getMainLooper()), Runnable { bmp.recycle() })
        if (mip) t.generateMipmaps(engine)
        return t
    }

    /* ---------------- lanternas ---------------- */

    private fun buildLamps(ents0: IntArray, perInstance: List<IntArray>, tagOf: Map<Int, String>) {
        val box = com.google.android.filament.Box()
        val w = FloatArray(16)
        for (i in ents0.indices) {
            val e0 = ents0[i]
            val t = tagOf[e0] ?: continue
            if (!t.startsWith("X_LAMP|") || !rm.hasComponent(e0)) continue
            val f = t.split('|')   // X_LAMP|kind|side|rank|n
            val mis = ArrayList<MaterialInstance>()
            for (k in instances.indices) {
                val ri = rm.getInstance(perInstance[k][i])
                for (p in 0 until rm.getPrimitiveCount(ri)) mis.add(rm.getMaterialInstanceAt(ri, p))
            }
            rm.getAxisAlignedBoundingBox(rm.getInstance(e0), box)
            tm.getWorldTransform(tm.getInstance(e0), w)
            val c = box.center; val h = box.halfExtent
            // caixa no mundo (8 cantos)
            val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
            for (n in 0 until 8) {
                val x = c[0] + (if (n and 1 != 0) h[0] else -h[0])
                val y = c[1] + (if (n and 2 != 0) h[1] else -h[1])
                val z = c[2] + (if (n and 4 != 0) h[2] else -h[2])
                for (k in 0..2) {
                    val v = w[k] * x + w[4 + k] * y + w[8 + k] * z + w[12 + k]
                    mn[k] = min(mn[k], v); mx[k] = max(mx[k], v)
                }
            }
            val center = FloatArray(3) { (mn[it] + mx[it]) / 2 }
            val size = FloatArray(3) { mx[it] - mn[it] }
            val nl = sqrt(center[0] * center[0] + center[2] * center[2]).takeIf { it > .1f } ?: 1f
            val normal = floatArrayOf(center[0] / nl, 0f, center[2] / nl)
            val gmi = mats.glow.createInstance()
            val ge = Geo.renderable(engine, glowMesh, gmi, priority = 6)
            rm.setLayerMask(rm.getInstance(ge), 0xff, 0)
            scene.addEntity(ge)
            lamps.add(Lamp(f[1], f.getOrNull(2)?.toIntOrNull() ?: 1, f.getOrNull(3)?.toIntOrNull() ?: 0, mis, center, size, normal, ge, gmi).also { l ->
                l.entity = e0; l.center0 = center.copyOf(); l.normal0 = normal.copyOf(); l.w0inv = invert(w)
            })
        }
        // segmentos da seta: de dentro para fora
        for (kind in listOf("frontSignal", "sideLens")) for (side in listOf(1, -1)) {
            val list = lamps.filter { it.kind == kind && it.side == side }.sortedBy { abs(it.center[0]) }
            list.forEachIndexed { r, l -> rankOf[l] = r }
        }
        // faróis: dois spots reais
        lamps.filter { it.kind == "beam" }.forEach { l ->
            val s = em.create()
            LightManager.Builder(LightManager.Type.FOCUSED_SPOT)
                .color(1f, .945f, .855f).intensity(0f)
                .position(l.center[0], l.center[1], l.center[2])
                .direction(-l.center[0] * .3f, -.08f, 1f)
                .spotLightCone(.25f, .5f).falloff(24f).castShadows(false)
                .build(engine, s)
            scene.addEntity(s)
            spots.add(s)
        }
        Diag.log("Lanternas: ${lamps.size}, faróis ${spots.size}")
    }
    private val rankOf = HashMap<Lamp, Int>()

    /* ---------------- configuração ---------------- */

    fun setConfig(trimId: String, paintId: String, plateCode: String) {
        trim = CarSpec.trim(trimId); paint = CarSpec.paint(paintId)
        val newPlate = CarSpec.cleanPlate(plateCode)
        val plateChanged = newPlate != plate || plateTex == null
        plate = newPlate
        if (loaded) applyConfig(plateChanged)
        touch()
    }

    private fun srgb(c: Int) = ((c / 255f + .055f) / 1.055f).pow(2.4f)

    private fun applyConfig(plateChanged: Boolean = true) {
        val r = srgb(paint.hex shr 16 and 255); val g = srgb(paint.hex shr 8 and 255); val b = srgb(paint.hex and 255)
        for (mi in paintMis) {
            set(mi, "baseColorFactor", r, g, b, 1f)
            set(mi, "metallicFactor", paint.metal)
            set(mi, "roughnessFactor", paint.rough)
            set(mi, "clearCoatFactor", 1f)
            set(mi, "clearCoatRoughnessFactor", paint.coatRough)
            // a oclusão pré-desenhada da pintura não casa com as portas (manchas escuras)
            set(mi, "aoStrength", 0f)
        }
        for (s in contrast) {
            val usePaint = !trim.twoTone && (!s.mirror || paint.bodyMirrors)
            rm.setMaterialInstanceAt(s.ri, s.prim, if (usePaint) s.paint else s.original)
        }
        for (s in strips) rm.setMaterialInstanceAt(s.ri, s.prim, if (trim.chrome) s.original else s.black!!)
        // peças do molde do reflexo só aparecem com reflexo ligado (no cenário Estrada não há reflexo:
        // antes "Editar carro" ligava as rodas do molde e apareciam pneus refletidos no asfalto)
        val mold = if (instances.size > 1) instances[1].entities.toHashSet() else emptySet<Int>()
        for (p in parts) {
            val t = p.tag
            val visible = when {
                hubAsset != null && hubNode.matches(t) -> false      // calota 3D antiga: trocada pela foto
                t.startsWith("X_WHEEL|") -> t.split('|')[1] == trim.wheels
                t.startsWith("X_CAMLENS") -> trim.cameras
                t.startsWith("X_CAMBLANK") -> !trim.cameras
                else -> true
            }
            for (e in p.renderables) {
                val on = visible && (e !in mold || (reflection && e !in mirrorHidden))
                rm.setLayerMask(rm.getInstance(e), 0xff, if (on) 1 else 0)
            }
        }
        if (plateChanged) applyPlate()
        applyBrakes()
        syncMirror()
        applyHubcaps()
    }

    private fun applyPlate() {
        val bmp = PlateArt.render(ctx, plate)
        val old = plateTex
        val t = texture(bmp, mip = true)
        for (mi in plateMis) if (has(mi, "baseColorMap")) mi.setParameter("baseColorMap", t, clampSampler)
        plateTex = t
        old?.let { engine.destroyTexture(it) }
    }

    fun setReflection(on: Boolean) { reflectPref = on; applyReflection(); touch() }

    /* ---------------- cenário "Estrada" (e a transição paisagem ↔ estrada) ---------------- */

    /** fundo liso, asfalto com faixas que correm com a velocidade (sem montanhas e lago) */
    private var roadMode = false            // destino
    private var roadMix = 0f                // 0 = paisagem … 1 = estrada (anima na transição)
    private var roadEntity = 0
    private var roadMi: MaterialInstance? = null
    private var roadScroll = 0f

    fun setRoadMode(on: Boolean) {
        if (on == roadMode && (!on || roadEntity != 0)) return
        // primeira vez (abrindo o app): sem transição
        val instant = !sceneryReady
        roadMode = on
        if (instant) roadMix = if (on) 1f else 0f
        Diag.log("Cenário: " + (if (on) "estrada" else "paisagem") + if (instant) "" else " (transição)")
        applySceneMode()
        touch()
    }

    private fun applySceneMode() {
        if ((roadMode || roadMix > 0f) && roadEntity == 0 && lakeReady) {
            val mesh = Geo.panel(engine, arrayOf(floatArrayOf(-14f, GROUND_Y + .001f, 50f), floatArrayOf(14f, GROUND_Y + .001f, 50f),
                floatArrayOf(14f, GROUND_Y + .001f, -50f), floatArrayOf(-14f, GROUND_Y + .001f, -50f)))
            lateMeshes.add(mesh)
            val mi = mats.road.createInstance()
            roadMi = mi
            roadEntity = Geo.renderable(engine, mesh, mi, priority = 5)
            scene.addEntity(roadEntity)
        }
        applyMix()
    }

    /** suave no começo e no fim */
    private fun ease(t: Float) = t * t * (3f - 2f * t)

    /**
     * A transição (≈2,8 s): o asfalto nasce debaixo do carro e se espalha até o horizonte com uma
     * borda de luz; as montanhas "derretem" para dentro do horizonte; o lago e o céu passam para as
     * cores do tema da estrada. Na volta, tudo ao contrário.
     */
    private fun applyMix() {
        val k = ease(roadMix)
        rm.setLayerMask(rm.getInstance(groundEntity), 0xff, if (roadMix < 1f) 1 else 0)
        if (roadEntity != 0) rm.setLayerMask(rm.getInstance(roadEntity), 0xff, if (roadMix > 0f) 1 else 0)
        // montanhas: achatam até o horizonte (o reflexo também)
        val s = 1f - k
        val g = GROUND_Y
        for (p in mountainParts) {
            if (!tm.hasComponent(p.entity)) tm.create(p.entity)
            val m = if (p.mirror) floatArrayOf(1f, 0f, 0f, 0f, 0f, -s, 0f, 0f, 0f, 0f, 1f, 0f, 0f, g + g * s, 0f, 1f)
                    else floatArrayOf(1f, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f, 0f, 0f, g * (1f - s), 0f, 1f)
            tm.setTransform(tm.getInstance(p.entity), m)
        }
        env?.let { applyScenery(it) }
        // reflexo: só quando liga/desliga (antes era refeito a cada quadro da transição — pintura,
        // frisos e o carro refletido inteiros; na central o carro "quebrava" durante a animação)
        if (reflection != mixReflection) { mixReflection = reflection; applyReflection() }
        applyRoad()
    }
    private var mixReflection: Boolean? = null

    private fun applyRoad() {
        val mi = roadMi ?: return
        val day = env?.day ?: 1f
        // sombra mais firme que no lago (de manhã, com pouca luz, ela quase sumia no asfalto claro)
        mi.setParameter("misc", roadScroll, (.68f + .2f * day) * contactK, day, roadLight)
        // revelação: raio (m) a partir do carro, largura da borda e brilho da borda de luz
        val k = ease(roadMix)
        val r = if (roadMix >= 1f) 1000f else k * 75f
        // w = curvatura das faixas (1/m): elas fazem a curva junto com as rodas dianteiras
        mi.setParameter("reveal", r, 7f, sin(Math.PI.toFloat() * roadMix), roadBend)
    }

    /** curvatura atual das faixas (suavizada); + = curva para a esquerda */
    private var roadBend = 0f
    private fun updateBend(dt: Float): Boolean {
        val target = (Math.tan(Math.toRadians(steerNow.toDouble())).toFloat() / WHEELBASE).coerceIn(-MAX_BEND, MAX_BEND)
        if (abs(target - roadBend) < 1e-5f) return false
        roadBend += (target - roadBend) * min(1f, dt * 5f)
        if (abs(target - roadBend) < 1e-5f) roadBend = target
        return true
    }

    /** tema da estrada: claro (dia) ou escuro (noite), seguindo o carro; troca com transição suave */
    private var roadLight = 0f
    private var roadLightTarget = 0f
    fun setRoadTheme(light: Boolean) {
        val t = if (light) 1f else 0f
        if (t == roadLightTarget) return
        roadLightTarget = t
        touch()
    }

    /** o fundo atrás do canto de cima é claro? (texto da temperatura em grafite em vez de branco) */
    fun backdropLight(): Boolean = roadMix > .5f && roadLightTarget > .5f

    /** última paleta da paisagem (para misturar com a da estrada na transição) */
    private var lastPal: Scenery.Palette? = null

    /** fundo da estrada (céu liso), misturado com o céu da paisagem durante a transição */
    private fun applyRoadSky() {
        val k = roadLight
        fun mix(d: FloatArray, l: FloatArray) = FloatArray(3) { d[it] + (l[it] - d[it]) * k }
        // claro: céu de estúdio, azul-acinzentado suave em cima clareando até a névoa do horizonte
        val top = mix(floatArrayOf(.035f, .045f, .065f), floatArrayOf(.58f, .64f, .72f))
        val mid = mix(floatArrayOf(.05f, .065f, .09f), floatArrayOf(.76f, .80f, .85f))
        val hor = mix(floatArrayOf(.07f, .085f, .11f), floatArrayOf(.86f, .875f, .895f))
        val m = ease(roadMix)
        val p = lastPal
        val st = env
        if (p == null || st == null || m >= 1f) {
            skyMi.setParameter("skyTop", top[0], top[1], top[2], 0f)        // sem estrelas
            skyMi.setParameter("skyMid", mid[0], mid[1], mid[2], 0f)        // sem nuvens
            skyMi.setParameter("skyHor", hor[0], hor[1], hor[2], 0f)
            skyMi.setParameter("sunCol", 0f, 0f, 0f, 1f)                    // sem sol/lua
            skyMi.setParameter("lake", hor[0], hor[1], hor[2], 0f)          // abaixo do horizonte: o mesmo fundo
            skyMi.setParameter("misc", clock, 0f, 0f, 0f)
            return
        }
        fun lerp(a: FloatArray, b: FloatArray) = FloatArray(3) { a[it] + (b[it] - a[it]) * m }
        val t = lerp(p.top, top); val md = lerp(p.mid, mid); val h = lerp(p.hor, hor); val sun = lerp(p.sun, floatArrayOf(0f, 0f, 0f))
        val lake = lerp(p.salt, hor)
        skyMi.setParameter("skyTop", t[0], t[1], t[2], p.starAlpha * (1f - m))
        skyMi.setParameter("skyMid", md[0], md[1], md[2], st.weather.cover * (1f - m))
        skyMi.setParameter("skyHor", h[0], h[1], h[2], st.weather.storm * (1f - m))
        skyMi.setParameter("sunCol", sun[0], sun[1], sun[2], if (st.isDay) 1f else 0f)
        skyMi.setParameter("lake", lake[0], lake[1], lake[2], LAKE_MIRROR * (1f - m))
        skyMi.setParameter("misc", clock, if (clouds && m < .5f) 1f else 0f, 0f, 0f)
    }

    /** chaves de teste de desempenho: montanhas, reflexo das montanhas, nuvens */
    fun setTestFlags(mountains: Boolean, mountainMirror: Boolean, cloudsOn: Boolean) {
        showMountains = mountains; mirrorMountains = mountainMirror; clouds = cloudsOn
        applyMountainVisibility()
        skyMi.setParameter("misc", clock, if (clouds) 1f else 0f, 0f, 0f)
        touch()
    }

    private fun applyMountainVisibility() {
        for (p in mountainParts) {
            val on = roadMix < 1f && showMountains && (!p.mirror || (reflection && mirrorMountains))
            rm.setLayerMask(rm.getInstance(p.entity), 0xff, if (on) 1 else 0)
        }
    }

    private fun applyReflection() {
        groundMi.setParameter("mirror", if (reflection) MIRROR else 0f)
        applyMountainVisibility()
        if (instances.size > 1 && loaded) {
            for (e in instances[1].entities) if (rm.hasComponent(e))
                rm.setLayerMask(rm.getInstance(e), 0xff, if (reflection && e !in mirrorHidden) 1 else 0)
            if (reflection) applyConfig(false)   // reaplica rodas/câmeras visíveis no espelho
            syncMirror()
        }
        applyHubcaps()
        applyBeams()
        applyUnderbody()
        updateDoorInteriors()
    }

    /** nós da porta no modelo (as animações têm o nome do lado real; o modelo veio trocado na frente) */
    private val doorNodeOf = mapOf(Openings.FL to "FR", Openings.FR to "FL", Openings.RL to "RL", Openings.RR to "RR")
    private val doorInterior = HashMap<String, MutableList<Int>>()
    private val mirrorHidden = HashSet<Int>()

    /**
     * Cabine e forro interno das portas: com o carro fechado ficam escondidos (os vidros são escuros
     * como insulfilm e não deixam ver dentro) — a central não desenha nada disso. Abriu QUALQUER
     * porta ou o porta-malas: aparece tudo, inclusive o forro das portas do outro lado (antes só
     * aparecia o da porta aberta e o carro ficava "oco"). O capô não mostra a cabine.
     */
    private fun updateDoorInteriors() {
        val cabinOpen = CABIN_OPENINGS.any { anims[it]?.let { a -> a.target > 0f || a.p > 0f } == true }
        setInteriorVisible(cabinOpen)
        // sem o volume escuro, o forro das portas fica sempre: ele fecha as frestas (senão o céu
        // aparecia por elas como linhas claras)
        val linings = cabinOpen || !USE_SHADE
        for (list in doorInterior.values) list.forEach { rm.setLayerMask(rm.getInstance(it), 0xff, if (linings) 1 else 0) }
        applyShade()
    }

    /* ---------------- volume escuro dentro da carroceria ---------------- */

    private var shadeEntity = 0
    private var shadeMi: MaterialInstance? = null

    /**
     * O carro leve não tem as peças internas: pelas frestas das portas, capô e tampa (e pelos vidros,
     * em alguns ângulos) aparecia o céu claro = linhas brancas e carro "vazio". Este volume escuro,
     * com o formato da carroceria por dentro (medido no modelo, 4 a 8 cm para dentro da lataria),
     * fica atrás de tudo isso. Some quando uma porta, o capô ou a tampa abrem (aí aparece o interior).
     */
    private fun buildShade() {
        // estações ao longo do carro (z, frente = +): topo (m) e se há roda ali (laterais mais estreitas)
        // (volume simples, medido à mão no modelo: fica sempre por dentro da lataria)
        val zs = floatArrayOf(-1.78f, -1.66f, -1.55f, -1.42f, -1.20f, -0.90f, -0.50f, 0f, .40f, .60f, .80f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 1.98f)
        // (atrás, sob o vidro traseiro inclinado, mais baixo: nos cantos de cima ele atravessava o vidro = dois "chifres")
        val tops = floatArrayOf(.94f, 1.00f, 1.02f, 1.20f, 1.40f, 1.44f, 1.46f, 1.46f, 1.43f, 1.36f, 1.24f, 1.08f, .99f, .94f, .90f, .85f, .78f)
        val wide = floatArrayOf(.56f, .70f, .76f, .80f, .80f, .80f, .80f, .80f, .80f, .80f, .80f, .80f, .80f, .80f, .79f, .76f, .66f)
        // "ombro" do teto (largura e altura), medido no modelo com 8 cm de folga, só na cabine: cobre os
        // cantos do teto e do para-brisa (frestas claras); −1 = sem ombro (fica na linha reta)
        // (junto da coluna A, z 0,4–0,8, folga de 5 cm: a fresta entre o volume e o para-brisa aparecia como um risco branco)
        val shW = floatArrayOf(-1f, -1f, -1f, -1f, .545f, .50f, .564f, .555f, .587f, .598f, .653f, -1f, -1f, -1f, -1f, -1f, -1f)
        val shY = floatArrayOf(0f, 0f, 0f, 0f, 1.30f, 1.34f, 1.36f, 1.36f, 1.33f, 1.26f, 1.14f, 0f, 0f, 0f, 0f, 0f, 0f)
        val topW = floatArrayOf(.45f, .45f, .45f, .45f, .464f, .487f, .491f, .495f, .45f, .45f, .45f, .45f, .45f, .45f, .45f, .45f, .45f)
        val pos = ArrayList<Float>(); val idx = ArrayList<Short>()
        val ring = 10   // pontos por seção (contorno fechado)
        for (s in zs.indices) {
            val z = zs[s]
            // perto das rodas a parte de baixo encolhe para não aparecer dentro do pneu
            val nearWheel = abs(z - 1.44f) < .42f || abs(z + 1.18f) < .42f
            val wB = if (nearWheel) .58f else wide[s]
            val wM = minOf(wide[s], if (z < -1.3f) .70f else .78f)
            val yt = tops[s]
            val yM = minOf(.98f, yt - .08f)
            val wT = minOf(topW[s], if (z < -1.3f) .38f else wM)
            val yLow = minOf(.76f, yM - .02f)
            // ombro: medido (cabine) ou no meio da linha reta entre a cintura e o teto (mesmo número de pontos)
            val wS = if (shW[s] > 0f) shW[s] else (wM + wT) / 2
            val yS = if (shW[s] > 0f) shY[s] else (yM + yt) / 2
            val pts = arrayOf(floatArrayOf(-wB, .40f), floatArrayOf(-wB, yLow), floatArrayOf(-wM, yM), floatArrayOf(-wS, yS), floatArrayOf(-wT, yt),
                floatArrayOf(wT, yt), floatArrayOf(wS, yS), floatArrayOf(wM, yM), floatArrayOf(wB, yLow), floatArrayOf(wB, .40f))
            for (p in pts) { pos.add(p[0]); pos.add(p[1]); pos.add(z) }
        }
        for (s in 0 until zs.size - 1) for (k in 0 until ring) {
            val a = (s * ring + k).toShort(); val b = (s * ring + (k + 1) % ring).toShort()
            val c = ((s + 1) * ring + k).toShort(); val d = ((s + 1) * ring + (k + 1) % ring).toShort()
            idx.addAll(listOf(a, c, b, b, c, d))
        }
        // tampas da frente e de trás
        for (cap in listOf(0, zs.size - 1)) for (k in 1 until ring - 1) {
            idx.addAll(listOf((cap * ring).toShort(), (cap * ring + k).toShort(), (cap * ring + k + 1).toShort()))
        }
        val mesh = Geo.mesh(engine, pos.toFloatArray(), idx.toShortArray())
        lateMeshes.add(mesh)
        val mi = mats.shade.createInstance().apply { setParameter("color", .006f, .007f, .009f) }
        shadeMi = mi
        shadeEntity = Geo.renderable(engine, mesh, mi, priority = 3)
        tm.create(shadeEntity)
        tm.setParent(tm.getInstance(shadeEntity), tm.getInstance(instances[0].root))   // inclina junto com a carroceria
        rm.setLayerMask(rm.getInstance(shadeEntity), 0xff, 0)
        Diag.log("Interior escuro: ${zs.size} seções")
    }

    /* ---------------- fundo escuro do carro refletido ---------------- */

    private var underEntity = 0
    private var underMi: MaterialInstance? = null

    /**
     * O modelo não tem assoalho: no reflexo, por baixo da carroceria aparecia o céu refletido (uma
     * faixa clara entre o carro e o reflexo dele). Uma placa escura na altura do assoalho, só no
     * reflexo, faz o papel da parte de baixo do carro.
     */
    /**
     * Furo do gancho de reboque no para-choque traseiro (E22H_EXT_1): no modelo da Geely a tampa não
     * existe e via-se o chão através dele ("bola" cinza ao lado da placa). Disco de plástico escuro
     * logo atrás da abertura (achada na malha: centro −0,488 / 0,453 / −1,872, ~5,5 cm). Preso à raiz
     * do carro (acompanha a inclinação da carroceria).
     */
    private fun buildTowCap() {
        val root = instances.firstOrNull()?.root ?: return
        val seg = 20; val r = .040f; val cx = -.488f; val cy = .453f; val cz = -1.862f
        val pos = FloatArray((seg + 1) * 3); val idx = ShortArray(seg * 3)
        pos[0] = cx; pos[1] = cy; pos[2] = cz
        for (s in 0 until seg) {
            val a = s * 2 * Math.PI / seg
            pos[(s + 1) * 3] = cx + (r * cos(a)).toFloat(); pos[(s + 1) * 3 + 1] = cy + (r * sin(a)).toFloat(); pos[(s + 1) * 3 + 2] = cz
            idx[s * 3] = 0; idx[s * 3 + 1] = (s + 1).toShort(); idx[s * 3 + 2] = ((s + 1) % seg + 1).toShort()
        }
        val mesh = Geo.mesh(engine, pos, idx)
        lateMeshes.add(mesh)
        val mi = mats.shade.createInstance().apply { setParameter("color", .004f, .004f, .005f) }
        ownMis.add(mi)
        val e = Geo.renderable(engine, mesh, mi, priority = 4)
        tm.create(e); tm.setParent(tm.getInstance(e), tm.getInstance(root))
        scene.addEntity(e)
        towCap = e
    }
    private var towCap = 0

    private fun buildUnderbody() {
        if (instances.size < 2) return
        val y = .24f
        val pos = floatArrayOf(-.84f, y, 1.95f, .84f, y, 1.95f, .84f, y, -1.90f, -.84f, y, -1.90f)
        val mesh = Geo.mesh(engine, pos, shortArrayOf(0, 1, 2, 0, 2, 3))
        lateMeshes.add(mesh)
        val mi = mats.shade.createInstance().apply { setParameter("color", .012f, .013f, .015f) }
        underMi = mi
        underEntity = Geo.renderable(engine, mesh, mi, priority = 3)
        tm.create(underEntity)
        // presa ao reflexo (acompanha a inclinação da carroceria refletida)
        val parent = lodAsset?.root ?: instances[1].root
        tm.setParent(tm.getInstance(underEntity), tm.getInstance(parent))
        rm.setLayerMask(rm.getInstance(underEntity), 0xff, 0)
        scene.addEntity(underEntity)
    }

    private fun applyUnderbody() {
        if (underEntity != 0) rm.setLayerMask(rm.getInstance(underEntity), 0xff, if (carShown && reflection) 1 else 0)
    }

    /* ---------------- luz de freio central ---------------- */

    private var brakeBar = 0
    private var brakeMi: MaterialInstance? = null

    /**
     * Barra vermelha fina no pé do vidro traseiro, centralizada, logo acima das letras GEELY (como na
     * foto do EX2). Presa ao vidro da tampa: acompanha quando o porta-malas abre. Medidas do modelo:
     * borda de baixo do vidro em y 1,07 / z −1,752; o vidro sobe em direção a y 1,40 / z −1,363.
     */
    private fun buildBrakeBar(ents0: IntArray, names: Map<Int, String>) {
        val glass = ents0.firstOrNull { names[it] == "E22H_Trunk_parts_2" } ?: return
        val up = floatArrayOf(0f, .647f, .763f)            // ao longo do vidro, para cima
        val inward = floatArrayOf(0f, -.763f, .647f)       // para dentro do carro
        // (o vidro do app é quase opaco: a barra fica rente à face externa para não sumir atrás dele)
        // 1 cm à frente do vidro (rente a ele a barra "picotava" e aparecia só em dois pedaços)
        val c = floatArrayOf(0f, 1.07f + up[1] * .03f - inward[1] * .010f, -1.752f + up[2] * .03f - inward[2] * .010f)
        val hw = .19f; val hh = .010f
        val world = ArrayList<FloatArray>()
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f))
            world.add(floatArrayOf(c[0] + sx * hw, c[1] + sy * hh * up[1], c[2] + sy * hh * up[2]))
        // para o espaço do vidro (assim a barra se move junto com a tampa)
        val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(glass), w)
        val inv = invert(w) ?: return
        val pos = FloatArray(12)
        world.forEachIndexed { i, p ->
            for (k in 0..2) pos[i * 3 + k] = inv[k] * p[0] + inv[4 + k] * p[1] + inv[8 + k] * p[2] + inv[12 + k]
        }
        val mesh = Geo.mesh(engine, pos, shortArrayOf(0, 1, 2, 0, 2, 3))
        lateMeshes.add(mesh)
        val mi = mats.shade.createInstance().apply { setParameter("color", 1.05f, .012f, .01f) }   // LED vermelho (forte demais o filtro da tela deixava laranja)
        brakeMi = mi
        brakeBar = Geo.renderable(engine, mesh, mi, priority = 6)
        tm.create(brakeBar)
        tm.setParent(tm.getInstance(brakeBar), tm.getInstance(glass))
        rm.setLayerMask(rm.getInstance(brakeBar), 0xff, 0)
        scene.addEntity(brakeBar)
    }

    /** inversa geral 4×4 (coluna principal); null se não inversível */
    private fun invert(m: FloatArray): FloatArray? {
        val d = DoubleArray(16) { m[it].toDouble() }
        val inv = DoubleArray(16)
        inv[0] = d[5]*d[10]*d[15]-d[5]*d[11]*d[14]-d[9]*d[6]*d[15]+d[9]*d[7]*d[14]+d[13]*d[6]*d[11]-d[13]*d[7]*d[10]
        inv[4] = -d[4]*d[10]*d[15]+d[4]*d[11]*d[14]+d[8]*d[6]*d[15]-d[8]*d[7]*d[14]-d[12]*d[6]*d[11]+d[12]*d[7]*d[10]
        inv[8] = d[4]*d[9]*d[15]-d[4]*d[11]*d[13]-d[8]*d[5]*d[15]+d[8]*d[7]*d[13]+d[12]*d[5]*d[11]-d[12]*d[7]*d[9]
        inv[12] = -d[4]*d[9]*d[14]+d[4]*d[10]*d[13]+d[8]*d[5]*d[14]-d[8]*d[6]*d[13]-d[12]*d[5]*d[10]+d[12]*d[6]*d[9]
        inv[1] = -d[1]*d[10]*d[15]+d[1]*d[11]*d[14]+d[9]*d[2]*d[15]-d[9]*d[3]*d[14]-d[13]*d[2]*d[11]+d[13]*d[3]*d[10]
        inv[5] = d[0]*d[10]*d[15]-d[0]*d[11]*d[14]-d[8]*d[2]*d[15]+d[8]*d[3]*d[14]+d[12]*d[2]*d[11]-d[12]*d[3]*d[10]
        inv[9] = -d[0]*d[9]*d[15]+d[0]*d[11]*d[13]+d[8]*d[1]*d[15]-d[8]*d[3]*d[13]-d[12]*d[1]*d[11]+d[12]*d[3]*d[9]
        inv[13] = d[0]*d[9]*d[14]-d[0]*d[10]*d[13]-d[8]*d[1]*d[14]+d[8]*d[2]*d[13]+d[12]*d[1]*d[10]-d[12]*d[2]*d[9]
        inv[2] = d[1]*d[6]*d[15]-d[1]*d[7]*d[14]-d[5]*d[2]*d[15]+d[5]*d[3]*d[14]+d[13]*d[2]*d[7]-d[13]*d[3]*d[6]
        inv[6] = -d[0]*d[6]*d[15]+d[0]*d[7]*d[14]+d[4]*d[2]*d[15]-d[4]*d[3]*d[14]-d[12]*d[2]*d[7]+d[12]*d[3]*d[6]
        inv[10] = d[0]*d[5]*d[15]-d[0]*d[7]*d[13]-d[4]*d[1]*d[15]+d[4]*d[3]*d[13]+d[12]*d[1]*d[7]-d[12]*d[3]*d[5]
        inv[14] = -d[0]*d[5]*d[14]+d[0]*d[6]*d[13]+d[4]*d[1]*d[14]-d[4]*d[2]*d[13]-d[12]*d[1]*d[6]+d[12]*d[2]*d[5]
        inv[3] = -d[1]*d[6]*d[11]+d[1]*d[7]*d[10]+d[5]*d[2]*d[11]-d[5]*d[3]*d[10]-d[9]*d[2]*d[7]+d[9]*d[3]*d[6]
        inv[7] = d[0]*d[6]*d[11]-d[0]*d[7]*d[10]-d[4]*d[2]*d[11]+d[4]*d[3]*d[10]+d[8]*d[2]*d[7]-d[8]*d[3]*d[6]
        inv[11] = -d[0]*d[5]*d[11]+d[0]*d[7]*d[9]+d[4]*d[1]*d[11]-d[4]*d[3]*d[9]-d[8]*d[1]*d[7]+d[8]*d[3]*d[5]
        inv[15] = d[0]*d[5]*d[10]-d[0]*d[6]*d[9]-d[4]*d[1]*d[10]+d[4]*d[2]*d[9]+d[8]*d[1]*d[6]-d[8]*d[2]*d[5]
        val det = d[0] * inv[0] + d[1] * inv[4] + d[2] * inv[8] + d[3] * inv[12]
        if (Math.abs(det) < 1e-12) return null
        return FloatArray(16) { (inv[it] / det).toFloat() }
    }

    /** vidros do carro: totalmente opacos com tudo fechado; um pouco transparentes com a cabine aberta */
    private val glassMis = ArrayList<MaterialInstance>()
    private var glassAlpha = -1f

    private fun applyShade() {
        val cabinOpen = CABIN_OPENINGS.any { anims[it]?.let { a -> a.target > 0f || a.p > 0f } == true }
        val ga = if (cabinOpen) GLASS_ALPHA else 1f
        // fechado: vidro bem escuro (insulfilm forte) — faz o papel do antigo volume escuro de dentro
        val gc = if (cabinOpen) .010f else GLASS_DARK
        if (ga != glassAlpha) { glassAlpha = ga; for (mi in glassMis) set(mi, "baseColorFactor", gc, gc * 1.15f, gc * 1.3f, ga) }
        if (shadeEntity == 0) return
        val anyOpen = Openings.all.any { anims[it]?.let { a -> a.target > 0f || a.p > 0f } == true }
        rm.setLayerMask(rm.getInstance(shadeEntity), 0xff, if (carShown && !anyOpen) 1 else 0)
    }

    /* ---------------- emblema "EX2" da tampa traseira ---------------- */

    private var badgeMi: MaterialInstance? = null
    private var badgeTex: Texture? = null

    /**
     * No site o "EX2" era desenhado por transparência sobre uma placa curva (…_decal); na exportação
     * a transparência se perdeu e sobrou um quadrado preto. Aqui a placa volta a mostrar só as
     * letras (material "badge") e a peça original, que o site escondia, fica escondida.
     */
    private fun setupBadge(ents0: IntArray, perInstance: List<IntArray>, names: Map<Int, String>) {
        val iDecal = ents0.indexOfFirst { names[it] == "E22H_GEELY_E2_decal" }
        val iBadge = ents0.indexOfFirst { names[it] == "E22H_GEELY_E2" }
        if (iDecal < 0) return
        val bmp = Bitmap.createBitmap(1024, 160, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(android.graphics.Color.BLACK)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            // mesmo aspecto das letras GEELY da tampa: traço fino, letras largas e bem espaçadas
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            textSize = 160 * .66f
            letterSpacing = .32f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val w = p.measureText("EX2")
        val sx = minOf(1024 * .86f / w, 1.25f)
        val fm = p.fontMetrics
        cv.save(); cv.translate(512f, 80f); cv.scale(sx, 1f)
        cv.drawText("EX2", 0f, -(fm.ascent + fm.descent) / 2, p)
        cv.restore()
        val t = texture(bmp, mip = true)
        badgeTex = t
        val mi = mats.badge.createInstance().apply {
            setParameter("text", t, clampSampler)
            setParameter("tint", .33f, .34f, .36f)   // cromado escuro, como o GEELY (antes: prata clara, chamava atenção)
        }
        badgeMi = mi
        for ((k, ents) in perInstance.withIndex()) {
            if (iBadge >= 0 && rm.hasComponent(ents[iBadge])) {
                rm.setLayerMask(rm.getInstance(ents[iBadge]), 0xff, 0)
                if (k > 0) mirrorHidden.add(ents[iBadge])
            }
            val e = ents[iDecal]
            if (!rm.hasComponent(e)) continue
            val ri = rm.getInstance(e)
            for (prim in 0 until rm.getPrimitiveCount(ri)) rm.setMaterialInstanceAt(ri, prim, mi)
        }
        Diag.log("Emblema EX2 refeito")
    }

    /* ---------------- feixe de luz dos faróis ---------------- */

    private val beamEnts = ArrayList<Int>()
    private val beamMis = ArrayList<MaterialInstance>()
    private val beamMirror = HashSet<Int>()
    private val beamHigh = HashSet<Int>()

    /**
     * Dois feixes por farol (e o reflexo de cada um no lago):
     *  baixo — curto, aberto e apontado para o chão à frente;
     *  alto  — longo e quase reto, alcança bem mais longe.
     */
    private fun buildBeams() {
        for (l in lamps.filter { it.kind == "beam" }) {
            val s = floatArrayOf(l.center[0], l.center[1], l.center[2] + .04f)
            for (high in listOf(false, true)) {
                val mesh = if (high) Geo.beamCone(engine, s, len = 8f, slope = -.03f, r1 = 1.05f, flat = .5f)
                           else Geo.beamCone(engine, s, len = 4.2f, slope = -.17f, r1 = 1.35f, flat = .42f)
                lateMeshes.add(mesh)
                for (mirror in listOf(false, true)) {
                    val mi = mats.beam.createInstance()
                    mi.setParameter("misc", if (mirror) 1f else 0f, 0f, 0f, 0f)
                    mi.setParameter("color", 1f, .95f, .86f, 0f)
                    val e = Geo.renderable(engine, mesh, mi, priority = 6)
                    if (mirror) {
                        tm.create(e)
                        tm.setTransform(tm.getInstance(e), floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 2 * GROUND_Y, 0f, 1f))
                        beamMirror.add(e)
                    }
                    if (high) beamHigh.add(e)
                    rm.setLayerMask(rm.getInstance(e), 0xff, 0)
                    scene.addEntity(e)
                    beamEnts.add(e); beamMis.add(mi)
                }
            }
        }
        Diag.log("Feixes dos faróis: ${beamEnts.size / 4} faróis × baixo/alto")
    }

    /** baixo aceso com o farol (e junto com o alto); alto só com o alto. Mais visíveis à noite. */
    private fun applyBeams() {
        val day = env?.day ?: 1f
        val k = .24f * (1f - .6f * day)
        for ((i, e) in beamEnts.withIndex()) {
            val mirror = e in beamMirror
            val high = e in beamHigh
            val lit = if (high) lights.alta else (lights.farol || lights.alta)
            val on = carShown && lit && (!mirror || (reflection && CAR_MIRROR))
            rm.setLayerMask(rm.getInstance(e), 0xff, if (on) 1 else 0)
            val strength = if (high) k * 1.1f else k
            beamMis[i].setParameter("color", 1f, .95f, .86f, if (mirror) strength * .45f else strength)
        }
    }

    fun setShadowQuality(size: Int) {
        // o tamanho do mapa só muda recriando a luz; usado na troca de qualidade
        lm.setShadowCaster(lm.getInstance(sun), size > 0)
    }

    /* ---------------- portas ---------------- */

    /** avisados quando uma porta/tampa começa a abrir (a câmera vai até ela) */
    val openListeners = LinkedHashSet<(String) -> Unit>()

    fun setOpening(name: String, open: Boolean) {
        val a = anims[name] ?: return
        if ((a.target > .5f) != open) Diag.log("Abertura: $name → ${if (open) "abrir" else "fechar"}")
        val wasOpen = a.target > .5f
        a.target = if (open) 1f else 0f
        updateDoorInteriors()
        touch()
        if (open && !wasOpen) for (l in openListeners.toList()) l(name)
    }

    fun isOpen(name: String) = (anims[name]?.target ?: 0f) > .5f

    private fun applyAnimations() {
        for (inst in instances) {
            val an = inst.animator
            for (a in anims.values) an.applyAnimation(a.index, a.p * a.duration * .999f)
        }
        lodAsset?.let { l ->
            val an = l.instance.animator
            for (a in anims.values) lodAnim[a.index]?.let { an.applyAnimation(it, a.p * a.duration * .999f) }
        }
    }

    /* ---------------- luzes ---------------- */

    fun setLights(s: LightState) {
        if (s.signal != lights.signal) blinkT = 0f
        lights = s
        if (loaded) applyLights()
        touch()
    }

    val lightState get() = lights

    private fun paintLamp(l: Lamp, strength: Float, color: FloatArray, glow: Float) {
        for (mi in l.mis) {
            if (has(mi, "emissiveStrength")) {
                set(mi, "emissiveFactor", color[0], color[1], color[2])
                set(mi, "emissiveStrength", strength)
            } else set(mi, "emissiveFactor", color[0] * strength, color[1] * strength, color[2] * strength)
        }
        l.base = glow; l.color = color
    }

    private fun applyLights() {
        val s = lights
        for (l in lamps) when (l.kind) {
            "beam" -> { val on = s.farol || s.alta; paintLamp(l, if (on) (if (s.alta) 18f else 14f) else 0f, BEAM, if (on) 1f else 0f) }
            // DRL como na foto do carro: linha de LED branca, fina e nítida (forte no próprio LED e
            // halo bem leve); antes: azulada, fraca e com um "borrão" grande em volta
            "drlBar" -> paintLamp(l, if (s.drl) (if (s.farol) 6f else 9f) else 0f, DRL, if (s.drl) .14f else 0f)
            "drlAccent" -> paintLamp(l, if (s.drl) (if (s.farol) 5f else 8f) else 0f, DRL, if (s.drl) .12f else 0f)
            // (2.9.0) halo bem mais fraco: o de antes passava da borda da lanterna e "vazava" vermelho na lataria
            "tail" -> paintLamp(l, if (s.brake) 3.4f else if (s.farol) 1.4f else 0f, TAIL, if (s.brake) .22f else if (s.farol) .08f else 0f)
            else -> paintLamp(l, 0f, AMBER, 0f)
        }
        for (sp in spots) lm.setIntensity(lm.getInstance(sp), if (s.farol || s.alta) 2600f else 0f)
        // luz de freio central (barra no pé do vidro traseiro): só com o freio
        if (brakeBar != 0) rm.setLayerMask(rm.getInstance(brakeBar), 0xff, if (s.brake && carShown) 1 else 0)
        // (a antiga "poça" de luz pintada no chão saiu: o feixe agora é só o cone)
        groundMi.setParameter("pool", 1f, .86f, .66f, 0f)
        applyBeams()
        updateSignals()
    }

    private fun updateSignals() {
        val sig = lights.signal
        val left = sig == 2 || sig == 1
        val right = sig == 2 || sig == -1
        val period = .78f
        val on = sig != 0 && (blinkT % period) < .5f
        val step = if (on) floor((blinkT % period) / .1f).toInt() + 1 else 0
        for (l in lamps) {
            if (l.kind != "frontSignal" && l.kind != "sideLens" && l.kind != "mirrorLens") continue
            var active = on && (if (l.side > 0) left else right)
            if (active && l.kind != "mirrorLens") active = (rankOf[l] ?: 0) < step
            paintLamp(l, if (active) 6f else 0f, AMBER, if (active) .85f else 0f)
        }
    }

    /* ---------------- céu / hora / clima ---------------- */

    fun setEnvironment(st: SkyClock.State) {
        val key = "${st.imageA}|${st.imageB}|${(st.mix * 200).toInt()}|${st.weather.id}|${(st.sunIntensity * 100).toInt()}"
        env = st
        if (key == envKey) return
        envKey = key
        applyScenery(st)
        applyEnvLights(st)
        // lago: instância nova quando a cor da crosta ou a luz ambiente mudam (ver newGroundInstance)
        val p = Scenery.palette(st)
        val gk = "$iblKey|${(p.salt[0] * 500).toInt()}|${(p.salt[1] * 500).toInt()}|${(p.salt[2] * 500).toInt()}|${st.weather.rain}"
        if (lakeReady && gk != groundKey) { groundKey = gk; newGroundInstance() }
    }
    private var groundKey = ""

    /** cores do céu, montanhas e lago para a hora/clima (e o clarão do relâmpago) */
    private fun applyScenery(st: SkyClock.State) {
        val p = Scenery.palette(st)
        lastPal = p
        val w = st.weather; val s = st.skySun; val fl = flash
        skyMi.setParameter("skyTop", p.top[0], p.top[1], p.top[2], p.starAlpha)
        skyMi.setParameter("skyMid", p.mid[0], p.mid[1], p.mid[2], w.cover)
        skyMi.setParameter("skyHor", p.hor[0], p.hor[1], p.hor[2], w.storm)
        skyMi.setParameter("sunCol", p.sun[0], p.sun[1], p.sun[2], if (st.isDay) 1f else 0f)
        skyMi.setParameter("cloudCol", p.cloud[0], p.cloud[1], p.cloud[2], p.drift)
        skyMi.setParameter("sunDir", s[0], s[1], s[2], fl)
        skyMi.setParameter("lake", p.salt[0], p.salt[1], p.salt[2], LAKE_MIRROR)
        skyMi.setParameter("misc", clock, if (clouds) 1f else 0f, 0f, 0f)
        if (!lakeReady) groundMi.setParameter("lake", p.salt[0], p.salt[1], p.salt[2], w.rain)
        if (roadMix > 0f) applyRoadSky()
        // luar: à noite as montanhas ainda aparecem recortadas contra o céu (antes ficava tudo preto)
        val night = floatArrayOf(62 / 255f, 82 / 255f, 120 / 255f)
        for (m in mountainParts) {
            val r = m.range
            val amb = (if (r.main) .22f + p.day * .52f else .18f + p.day * .395f) + fl * .3f
            m.mi.setParameter("sunDir", s[0], s[1], s[2], amb)
            m.mi.setParameter("sunCol", night[0] + (p.sun[0] - night[0]) * p.day, night[1] + (p.sun[1] - night[1]) * p.day,
                night[2] + (p.sun[2] - night[2]) * p.day, fl)
            m.mi.setParameter("fog", p.hor[0], p.hor[1], p.hor[2], r.haze)
            m.mi.setParameter("lake", p.salt[0], p.salt[1], p.salt[2], LAKE_MIRROR)
            m.mi.setParameter("misc", r.snowLine, if (m.mirror) 1f else 0f, 0f, 0f)
        }
    }

    /** cordilheiras: criadas depois do lago (malhas criadas cedo saíam quebradas no emulador) */
    private fun buildMountains() {
        if (!lakeReady || mountainsReady || !alive) return
        val data = mountainData ?: return
        mountainData = null
        val g2 = 2 * GROUND_Y
        Scenery.ranges.forEachIndexed { i, r ->
            val mesh = Scenery.mesh(engine, data[i])
            lateMeshes.add(mesh)
            for (mirror in listOf(false, true)) {
                val mi = mats.mountain.createInstance()
                mi.setParameter("noise", noiseTex, noiseSampler)
                val ent = Geo.renderable(engine, mesh, mi, priority = 2)
                if (mirror) {
                    tm.create(ent)
                    // espelho no plano do lago: y → 2·chão − y
                    tm.setTransform(tm.getInstance(ent), floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, g2, 0f, 1f))
                }
                scene.addEntity(ent)
                mountainParts.add(MountainPart(r, ent, mi, mirror))
            }
        }
        mountainsReady = true
        applyMountainVisibility()
        env?.let { applyScenery(it) }
        Diag.log("Montanhas na cena · ${Diag.mem()}")
        touch()
        maybeSceneryReady()
    }

    /**
     * Instância nova do material do lago com todos os valores. No emulador a instância antiga
     * parava de desenhar depois de uma troca de céu/luz ambiente: trocar a instância resolve.
     */
    private fun newGroundInstance() {
        val old = groundMi
        groundMi = mats.ground.createInstance()
        groundMi.setParameter("mirror", if (reflection) MIRROR else 0f)
        groundMi.setParameter("contact", 0f)
        groundMi.setParameter("pool", 1f, .86f, .66f, 0f)
        groundMi.setParameter("misc", 0f, 0f, 0f, 0f)
        groundMi.setParameter("noise", noiseTex, noiseSampler)
        env?.let { st ->
            val p = Scenery.palette(st)
            groundMi.setParameter("lake", p.salt[0], p.salt[1], p.salt[2], st.weather.rain)
        }
        rm.setMaterialInstanceAt(rm.getInstance(groundEntity), 0, groundMi)
        engine.destroyMaterialInstance(old)
        if (lakeReady) applyContact()
    }

    /**
     * O lago só funciona com uma instância de material criada depois que a tela já desenhou
     * (criada na abertura ficava invisível no emulador). Chamado no 3º quadro.
     */
    private fun refreshGround() {
        val st = env ?: return
        run {
            newGroundInstance()
            val oldC = catcherMi
            catcherMi = mats.catcher.createInstance().apply { setParameter("strength", .5f) }
            rm.setMaterialInstanceAt(rm.getInstance(catcherEntity), 0, catcherMi)
            engine.destroyMaterialInstance(oldC)
        }
        applyScenery(st)
        applyContact()
        // malhas também recriadas agora: as criadas na abertura saíam deformadas no emulador
        val g = Geo.panel(engine, arrayOf(floatArrayOf(-40f, GROUND_Y, 40f), floatArrayOf(40f, GROUND_Y, 40f),
            floatArrayOf(40f, GROUND_Y, -40f), floatArrayOf(-40f, GROUND_Y, -40f)))
        val gri = rm.getInstance(groundEntity)
        rm.setGeometryAt(gri, 0, com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES, g.vb, g.ib, 0, g.count)
        rm.setAxisAlignedBoundingBox(gri, g.box)
        lateMeshes.add(g)
        val c = Geo.ground(engine, GROUND_Y + .002f, 7f, tangents = true)
        val cri = rm.getInstance(catcherEntity)
        rm.setGeometryAt(cri, 0, com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES, c.vb, c.ib, 0, c.count)
        rm.setAxisAlignedBoundingBox(cri, c.box)
        lateMeshes.add(c)
        Diag.log("Lago recriado · ${Diag.mem()}")
        touch()
        lakeReady = true
        if (roadMode) applySceneMode()      // pedido antes do chão existir: cria a estrada agora
        buildMountains()
        maybeSceneryReady()
    }

    /* o cenário só "aparece" quando o lago existe E os materiais do céu/lago estão compilados */
    private var lakeReady = false
    private var ownCompiled = false

    private fun compileScenery() {
        val list = listOf(mats.sky, mats.ground, mats.catcher, mats.glow, mats.rain, mats.mountain, mats.spot, mats.badge, mats.beam, mats.road)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var left = list.size
        val t0 = android.os.SystemClock.uptimeMillis()
        val done = Runnable {
            if (--left == 0 && !ownCompiled) {
                ownCompiled = true
                Diag.log("Materiais do cenário prontos em ${android.os.SystemClock.uptimeMillis() - t0} ms")
                maybeSceneryReady()
            }
        }
        val variants = com.google.android.filament.Material.UserVariantFilterBit.DIRECTIONAL_LIGHTING or
            com.google.android.filament.Material.UserVariantFilterBit.DYNAMIC_LIGHTING
        for (m in list) m.compile(com.google.android.filament.Material.CompilerPriorityQueue.CRITICAL, variants, main, done)
        main.postDelayed({ if (!ownCompiled) { ownCompiled = true; maybeSceneryReady() } }, 8_000)
    }

    private fun maybeSceneryReady() {
        if (!lakeReady || !ownCompiled || !mountainsReady || sceneryReady) return
        // alguns quadros com o cenário desenhado antes de avisar a tela
        warmUntil = max(warmUntil, clock + 1f)
        touch()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            sceneryReady = true
            Diag.log("Cenário na tela")
            sceneryListeners.toList().forEach { it() }
        }, 250)
    }

    private fun applyEnvLights(st: SkyClock.State) {
        // reflexo da pintura FIXO: calculado uma única vez (recalcular a cada troca de hora/clima
        // corrompia na Mali da central: carro branco, quadrados no teto e no logo); só o brilho muda
        if (scene.indirectLight == null) {
            iblKey = FIXED_IBL
            scene.indirectLight = indirect(FIXED_IBL)
        }
        ensureSun()
        val li = lm.getInstance(sun)
        // luz do carro fixa quase a pino, como no showroom do site (0.9, 10, 1.6): sombra limpa
        // embaixo do carro; só a cor e a força acompanham a hora do dia
        lm.setDirection(li, -.0882f, -.9805f, -.1568f)
        lm.setColor(li, st.sunColor[0], st.sunColor[1], st.sunColor[2])
        lm.setIntensity(li, st.sunIntensity * (1f + flash * 1.2f))
        scene.indirectLight?.intensity = st.iblIntensity * IBL_SCALE * (1f + flash * 1.5f)
        rainOn = st.weather.rain > 0f
        // os pingos não são mais desenhados na cena: cada tela os desenha por cima da imagem pronta do
        // carro (Viewport3D: a chuva anima sempre sem redesenhar o carro); o chão molhado continua aqui
        rm.setLayerMask(rm.getInstance(rainEntity), 0xff, 0)
        applyContact()
        applyBeams()
        touch()
    }

    /** foto do céu reduzida: só para os reflexos na pintura (o céu da tela é o 3D) */
    private fun skyBitmap(key: String, sample: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return ctx.assets.open("sky-$key.jpg").use { BitmapFactory.decodeStream(it, null, o)!! }
    }

    private val keepAlive = ArrayList<Any>()
    private var iblTools: Triple<IBLPrefilterContext, IBLPrefilterContext.EquirectangularToCubemap, IBLPrefilterContext.SpecularFilter>? = null

    private fun buildSun() {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, .96f, .88f).intensity(1.4f).direction(-.0882f, -.9805f, -.1568f)
            // sem mapa de sombra: ele redesenhava o carro inteiro a cada quadro; a sombra
            // sob o carro é feita pelo próprio chão (sombra de contato)
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)
    }

    /** se a luz do sol sumiu (ver acima), recria */
    private fun ensureSun() {
        if (!lm.hasComponent(sun)) { Diag.log("Sol tinha sumido: recriado"); buildSun(); env?.let { envKey = "" } }
    }

    /**
     * Ambiente HDR para os reflexos: a foto do céu (devolvida à intensidade "real") escurecida,
     * mais três painéis de luz — um grande em cima e dois laterais nas direções das luzes de
     * preenchimento do site (5, 3.5, 6) e (-6, 2.5, -3). É isso que dá o brilho de verniz.
     */
    private fun studioEnvironment(sky: Bitmap): Texture {
        val w = 512; val h = 256
        val src = Bitmap.createScaledBitmap(sky, w, h, true)
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        if (src !== sky) src.recycle()
        fun lin(c: Int) = ((c / 255f + .055f) / 1.055f).pow(2.4f)
        fun inv(y0: Float): Float { val y = min(y0, .97f); val A = 2.43f * y - 2.51f; val B = .59f * y - .03f; val C = .14f * y
            return (-B - sqrt(max(B * B - 4 * A * C, 0f))) / (2 * A) }
        fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }
        val fill1 = norm(5f, 3.5f, 6f); val fill2 = norm(-6f, 2.5f, -3f)
        // painel: brilho dentro de um "retângulo" angular em volta da direção (largo na horizontal)
        fun panel(d: FloatArray, c: FloatArray, wide: Float, tall: Float): Float {
            val horiz = sqrt(d[0] * d[0] + d[2] * d[2]).coerceAtLeast(1e-4f)
            val ch = sqrt(c[0] * c[0] + c[2] * c[2])
            val dot = ((d[0] * c[0] + d[2] * c[2]) / (horiz * ch)).coerceIn(-1f, 1f)
            val dAz = kotlin.math.acos(dot)
            val dEl = abs(kotlin.math.asin(d[1].coerceIn(-1f, 1f)) - kotlin.math.asin(c[1]))
            val ea = 1f - ((dAz / wide - .8f) / .2f).coerceIn(0f, 1f)
            val ee = 1f - ((dEl / tall - .8f) / .2f).coerceIn(0f, 1f)
            return ea * ee
        }
        val half = java.nio.ByteBuffer.allocateDirect(w * h * 4 * 2).order(ByteOrder.nativeOrder())
        val sb = half.asShortBuffer()
        for (j in 0 until h) {
            val th = Math.PI * (j + .5) / h
            for (i in 0 until w) {
                val ph = ((i + .5) / w - .5) * 2 * Math.PI
                val d = floatArrayOf((Math.sin(ph) * Math.sin(th)).toFloat(), Math.cos(th).toFloat(), (-Math.cos(ph) * Math.sin(th)).toFloat())
                val p = px[j * w + i]
                var r = inv(lin(p shr 16 and 255)) * .45f
                var g = inv(lin(p shr 8 and 255)) * .45f
                var b = inv(lin(p and 255)) * .45f
                val top = ((d[1] - .80f) / .08f).coerceIn(0f, 1f) * 5f            // softbox em cima
                val s1 = panel(d, fill1, .55f, .10f) * 6f                             // faixa lateral 1
                val s2 = panel(d, fill2, .45f, .08f) * 3.5f                           // faixa lateral 2
                val add = top + s1 + s2
                r += add; g += add; b += add
                sb.put(android.util.Half.toHalf(r)); sb.put(android.util.Half.toHalf(g))
                sb.put(android.util.Half.toHalf(b)); sb.put(android.util.Half.toHalf(1f))
            }
        }
        half.rewind()
        val levels = (floor(kotlin.math.log2(w.toFloat())) + 1).toInt()
        val t = Texture.Builder().width(w).height(h).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA16F)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        keepAlive.add(half)   // a cópia para a placa de vídeo acontece depois
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(half, Texture.Format.RGBA, Texture.Type.HALF))
        t.generateMipmaps(engine)
        return t
    }

    /** luz ambiente (reflexos na pintura) gerada da própria foto do céu */
    private fun indirect(key: String): IndirectLight {
        ibl[key]?.let { return it.first }
        // reflexos de estúdio (como o site): céu escurecido + painéis de luz fortes
        val bmp = skyBitmap(key, 4)
        val eq = studioEnvironment(bmp)
        bmp.recycle()
        Diag.log("Cena: gerando luz ambiente de $key")
        // ferramenta criada uma vez só: liberar e recriar apagava a luz do sol (defeito da biblioteca)
        val tools = iblTools ?: IBLPrefilterContext(engine).let { c ->
            Triple(c, IBLPrefilterContext.EquirectangularToCubemap(c), IBLPrefilterContext.SpecularFilter(c))
        }.also { iblTools = it }
        val cube = tools.second.run(eq)
        val refl = tools.third.run(cube)
        engine.destroyTexture(eq); engine.destroyTexture(cube)
        ensureSun()
        val light = IndirectLight.Builder().reflections(refl).intensity(IBL_SCALE).build(engine)
        // memória: guarda no máximo 2 ambientes (o atual e o anterior)
        for (k in ibl.keys.toList()) {
            if (ibl.size < 2) break
            val (l, t) = ibl[k] ?: continue
            if (l === scene.indirectLight) continue
            ibl.remove(k)
            // libera só depois: a placa de vídeo ainda pode estar usando (trocas rápidas corrompiam a pintura)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (alive && l !== scene.indirectLight) { engine.destroyIndirectLight(l); engine.destroyTexture(t) }
            }, 2000)
        }
        ibl[key] = light to refl
        return light
    }

    /* ---------------- quadro a quadro ---------------- */

    /** avança animações; devolve true enquanto precisa de quadros contínuos */
    fun update(dt: Float): Boolean {
        clock += dt
        val busy: Boolean
        if (!loaded) {
            asset?.let {
                resLoader.asyncUpdateLoad()
                loadProgress = resLoader.asyncGetLoadProgress()
                if (loadProgress >= 1f) finishLoading()
            }
            return true
        }
        var moved = false
        for (a in anims.values) {
            if (a.p != a.target) {
                val step = dt / 1.15f
                a.p = if (a.target > a.p) min(a.target, a.p + step) else max(a.target, a.p - step)
                moved = true
            }
        }
        if (moved) { applyAnimations(); updateDoorInteriors(); touch() }
        if (lights.signal != 0) { blinkT += dt; updateSignals(); touch() }
        if (carShown && contactK < 1f) { contactK = min(1f, contactK + dt / .4f); applyContact(); touch() }
        val rolling = carShown && updateMotion(dt)
        val bending = carShown && updateBend(dt)
        if (rolling || bending) { roadScroll = (roadScroll + speedNow * dt) % 1000f; applyRoad(); touch() }
        // troca de tema da estrada (claro/escuro) em ~1,2 s
        val themeMoving = roadLight != roadLightTarget
        if (themeMoving) {
            roadLight = if (roadLightTarget > roadLight) min(roadLightTarget, roadLight + dt / 1.2f) else max(roadLightTarget, roadLight - dt / 1.2f)
            if (roadMix > 0f) { applyRoadSky(); applyRoad() }
            touch()
        }
        // transição paisagem ↔ estrada
        val mixTarget = if (roadMode) 1f else 0f
        val mixMoving = roadMix != mixTarget
        if (mixMoving) {
            roadMix = if (mixTarget > roadMix) min(1f, roadMix + dt / ROAD_TRANSITION_S) else max(0f, roadMix - dt / ROAD_TRANSITION_S)
            applyMix(); touch()
        }
        busy = moved || rolling || bending || themeMoving || mixMoving || lights.signal != 0 || clock < warmUntil
        val st = env
        if (st != null && st.weather.storm > .2f && flashOk) {
            flashNext -= dt
            if (flashNext <= 0f && flashT < 0f) { flashT = 0f; flashNext = 2.8f + Math.random().toFloat() * 6f }
            if (flashT >= 0f) {
                flashT += dt
                flash = if (flashT < .065f) flashT / .065f else max(0f, 1f - (flashT - .065f) / .25f)
                if (flashT > .32f) { flashT = -1f; flash = 0f }
                envKey = ""; setEnvironment(st)
            }
        } else flash = 0f
        ambient = st != null && (rainOn || st.weather.storm > .2f)
        return busy
    }

    /** animação ambiente pausada: não deixa o céu parado no meio de um relâmpago */
    fun calm() {
        if (flash > 0f || flashT >= 0f) {
            flash = 0f; flashT = -1f
            env?.let { envKey = ""; setEnvironment(it) }
        }
    }

    /** antes de desenhar uma câmera: céu centrado nela, halos virados para ela */
    private val lateMeshes = ArrayList<Mesh>()

    fun prepare(camModel: FloatArray, aspect: Float) {
        if (!groundFresh && env != null) {
            groundFresh = true
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refreshGround() }, 300)
        }
        val cx = camModel[12]; val cy = camModel[13]; val cz = camModel[14]
        tm.setTransform(tm.getInstance(skyEntity), floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, cx, cy, cz, 1f))
        skyMi.setParameter("misc", clock, if (clouds) 1f else 0f, 0f, 0f)   // nuvens andam com o tempo
        val lw = FloatArray(16); val dlt = FloatArray(16)
        for (l in lamps) {
            val ri = rm.getInstance(l.glow)
            if (l.base <= 0f) { rm.setLayerMask(ri, 0xff, 0); continue }
            // acompanha a peça (lanternas da tampa traseira sobem junto quando o porta-malas abre)
            l.w0inv?.let { inv ->
                tm.getWorldTransform(tm.getInstance(l.entity), lw)
                mul(lw, inv, dlt)
                val c0 = l.center0; val n0 = l.normal0
                for (k in 0..2) {
                    l.center[k] = dlt[k] * c0[0] + dlt[4 + k] * c0[1] + dlt[8 + k] * c0[2] + dlt[12 + k]
                    l.normal[k] = dlt[k] * n0[0] + dlt[4 + k] * n0[1] + dlt[8 + k] * n0[2]
                }
            }
            val dx = cx - l.center[0]; val dz = cz - l.center[2]; val dy = cy - l.center[1]
            val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
            val d = (dx * l.normal[0] + dz * l.normal[2]) / len
            val k = ((d - .02f) / .4f).coerceIn(0f, 1f)
            if (k <= .01f) { rm.setLayerMask(ri, 0xff, 0); continue }
            rm.setLayerMask(ri, 0xff, 1)
            val signal = l.kind == "frontSignal" || l.kind == "sideLens" || l.kind == "mirrorLens"
            // lanterna: halo do tamanho da peça (maior que ela pintava a lataria em volta)
            val tail = l.kind == "tail"
            val sx = max(l.size[0], l.size[2]) * (if (signal) 2f else if (l.kind == "beam") 1.7f else if (tail) .9f else 1.25f) + (if (tail) 0f else .03f)
            val sy = l.size[1] * (if (signal) 1.8f else if (l.kind == "beam") 1.6f else if (tail) .9f else 1.35f) + (if (tail) 0f else .03f)
            // empurra o halo um pouco para fora da lente, na direção da câmera
            val px = l.center[0] + dx / len * .02f; val py = l.center[1] + dy / len * .02f; val pz = l.center[2] + dz / len * .02f
            tm.setTransform(tm.getInstance(l.glow), floatArrayOf(
                camModel[0] * sx, camModel[1] * sx, camModel[2] * sx, 0f,
                camModel[4] * sy, camModel[5] * sy, camModel[6] * sy, 0f,
                camModel[8], camModel[9], camModel[10], 0f,
                px, py, pz, 1f))
            l.glowMi.setParameter("color", l.color[0], l.color[1], l.color[2], l.base * k)
        }
    }

    fun destroy() {
        alive = false
        for (m in mountainParts) { scene.remove(m.entity); engine.destroyEntity(m.entity); engine.destroyMaterialInstance(m.mi) }
        mountainParts.clear()
        for (e in beamEnts) { scene.remove(e); engine.destroyEntity(e) }
        beamMis.forEach { engine.destroyMaterialInstance(it) }
        beamEnts.clear(); beamMis.clear()
        if (roadEntity != 0) { scene.remove(roadEntity); engine.destroyEntity(roadEntity) }
        roadMi?.let { engine.destroyMaterialInstance(it) }
        roadEntity = 0; roadMi = null
        if (shadeEntity != 0) { scene.remove(shadeEntity); engine.destroyEntity(shadeEntity) }
        shadeMi?.let { engine.destroyMaterialInstance(it) }
        shadeEntity = 0; shadeMi = null
        if (underEntity != 0) { scene.remove(underEntity); engine.destroyEntity(underEntity) }
        underMi?.let { engine.destroyMaterialInstance(it) }
        underEntity = 0; underMi = null
        if (brakeBar != 0) { scene.remove(brakeBar); engine.destroyEntity(brakeBar) }
        if (towCap != 0) { scene.remove(towCap); engine.destroyEntity(towCap); towCap = 0 }
        brakeMi?.let { engine.destroyMaterialInstance(it) }
        brakeBar = 0; brakeMi = null
        // calotas presas às rodas do carro e do reflexo: saem primeiro
        for (e in innerDiscs) { scene.remove(e); engine.destroyEntity(e) }
        innerDiscs.clear(); innerMi?.let { engine.destroyMaterialInstance(it) }; innerMi = null
        hubAsset?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
        hubAsset = null
        // o carro leve usa materiais do molde: sai antes do carro
        lodAsset?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
        lodAsset = null
        asset?.let { a ->
            for (inst in instances) scene.removeEntities(inst.entities)
            resLoader.asyncCancelLoad()
            loader.destroyAsset(a)
        }
        // o interior também é do carregador: precisa sair ANTES de destruir os materiais dele
        // (depois disso a biblioteca abortava o app ao fechar → papel de parede preto até reabrir)
        interiorAsset?.let { scene.removeEntities(it.entities); loader.destroyAsset(it) }
        interiorAsset = null
        badgeMi?.let { engine.destroyMaterialInstance(it) }
        badgeTex?.let { engine.destroyTexture(it) }
        badgeMi = null; badgeTex = null
        ownMis.forEach { engine.destroyMaterialInstance(it) }
        ownMis.clear()
        resLoader.destroy(); loader.destroy(); provider.destroyMaterials(); provider.destroy()
        for (l in lamps) { engine.destroyEntity(l.glow); engine.destroyMaterialInstance(l.glowMi) }
        for (s in spots) engine.destroyEntity(s)
        for (e in listOf(skyEntity, groundEntity, catcherEntity, rainEntity, sun)) engine.destroyEntity(e)
        listOf(skyMi, groundMi, catcherMi, rainMi).forEach { engine.destroyMaterialInstance(it) }
        catcherMesh.destroy(engine)
        lateMeshes.forEach { it.destroy(engine) }
        ibl.values.forEach { (l, t) -> engine.destroyIndirectLight(l); engine.destroyTexture(t) }
        iblTools?.let { (c, a, b) -> b.destroy(); a.destroy(); c.destroy() }
        plateTex?.let { engine.destroyTexture(it) }
        engine.destroyTexture(noiseTex)
        listOf(skyMesh, groundMesh, screenMesh, glowMesh).forEach { it.destroy(engine) }
        mats.destroy()
        engine.destroyScene(scene)
        engine.destroy()
    }

    companion object {
        const val GROUND_Y = -.015f
        const val PAINT = "E22H_G_CarPaint_Green"
        const val IBL_SCALE = 1.0f
        /** força do reflexo do carro no chão (0 = nenhum, 1 = espelho) */
        const val FIXED_IBL = "day"
        /** raio do pneu (m) para o giro das rodas */
        const val WHEEL_RADIUS = .33f
        /** duração da transição paisagem ↔ estrada (s) */
        const val ROAD_TRANSITION_S = 2.8f
        /** giro máximo da roda por quadro (rad): ~1/3 do espaço entre os raios da calota */
        const val MAX_SPIN_STEP = .40f
        /** espelho no plano do lago (reflexo) */
        val MIRROR_M = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 2 * GROUND_Y, 0f, 1f)
        /** aberturas que mostram a cabine (o capô não) */
        val CABIN_OPENINGS = listOf(Openings.FL, Openings.FR, Openings.RL, Openings.RR, Openings.TAILGATE)
        /** opacidade dos vidros: quase opacos (insulfilm forte) para a cabine não precisar existir fechada */
        const val GLASS_ALPHA = .975f
        /** cor do vidro com tudo fechado (quase preto) */
        const val GLASS_DARK = .0035f
        /** volume escuro dentro da carroceria (retirado na 2.8.0) */
        const val USE_SHADE = false
        /** carro refletido no lago (desligado na 2.8.3: sombra suave no lugar) */
        const val CAR_MIRROR = false
        /** disco escuro que fecha o lado de dentro das rodas (no espaço do nó da roda: +Z = para fora) */
        const val INNER_R = .215
        const val INNER_Z = -.12f
        /** entre-eixos do EX2 (m): curvatura das faixas da estrada = tan(roda) ÷ entre-eixos */
        const val WHEELBASE = 2.65f
        /** curva mais fechada das faixas (raio mínimo ~9 m) */
        const val MAX_BEND = 1f / 9f
        const val LAKE_MIRROR = .92f   // lago ao longe: quanto espelha (resto é a crosta de sal)
        const val MIRROR = 1f   // reflexo do lago ligado (a força vem do próprio material)
        private val WHITE = floatArrayOf(1f, 1f, 1f)
        private val DRL = floatArrayOf(.93f, .97f, 1f)
        private val BEAM = floatArrayOf(1f, .93f, .80f)
        private val TAIL = floatArrayOf(1f, .1f, .03f)      // LED vermelho-alaranjado, como na foto
        private val AMBER = floatArrayOf(1f, .50f, .02f)

        private var instance: World? = null
        private var refs = 0
        private const val SHOWN_VERSION = "carShownVersion"

        fun acquire(ctx: Context): World {
            if (instance == null) {
                com.google.android.filament.utils.Utils.init()
                instance = World(ctx.applicationContext)
            }
            refs++
            return instance!!
        }

        fun release() {
            if (--refs <= 0) { instance?.destroy(); instance = null; refs = 0 }
        }
    }
}
