package br.com.ex2.live3d

import android.content.Context
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.filamat.MaterialBuilder
import com.google.android.filament.filamat.MaterialBuilder.BlendingMode
import com.google.android.filament.filamat.MaterialBuilder.CullingMode
import com.google.android.filament.filamat.MaterialBuilder.ParameterPrecision
import com.google.android.filament.filamat.MaterialBuilder.SamplerFormat
import com.google.android.filament.filamat.MaterialBuilder.SamplerType
import com.google.android.filament.filamat.MaterialBuilder.Shading
import com.google.android.filament.filamat.MaterialBuilder.UniformType
import com.google.android.filament.filamat.MaterialBuilder.Variable
import com.google.android.filament.filamat.MaterialBuilder.VertexAttribute
import com.google.android.filament.filamat.MaterialBuilder.VertexDomain
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Materiais próprios da cena (céu, chão do lago, montanhas, brilho das lanternas, chuva).
 * São compilados no aparelho na primeira vez (filamat) e guardados em cache na pasta do app,
 * então só a primeira abertura depois de instalar paga a compilação.
 */
class Materials(ctx: Context, private val engine: Engine) {
    // os materiais ficam prontos em arquivo (cache por versão); aqui só são carregados
    val sky: Material
    val ground: Material
    val catcher: Material
    val picture: Material
    val glow: Material
    val rain: Material
    val mountain: Material
    val spot: Material
    val badge: Material
    val beam: Material
    val road: Material
    val label: Material
    val shade: Material
    val sprite: Material
    val backdrop: Material

    init {
        prepare(ctx)   // normalmente já feito em segundo plano pelo Startup
        sky = load(ctx, "sky"); ground = load(ctx, "ground"); catcher = load(ctx, "catcher"); picture = load(ctx, "picture")
        glow = load(ctx, "glow"); rain = load(ctx, "rain"); mountain = load(ctx, "mountain"); spot = load(ctx, "spot")
        badge = load(ctx, "badge"); beam = load(ctx, "beam"); road = load(ctx, "road")
        label = load(ctx, "label")
        shade = load(ctx, "shade")
        sprite = load(ctx, "sprite")
        backdrop = load(ctx, "backdrop")
    }

    private fun load(ctx: Context, name: String): Material {
        val bytes = file(ctx, name).readBytes()
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.flip()
        return Material.Builder().payload(buf, buf.remaining()).build(engine)
    }

    fun destroy() {
        listOf(sky, ground, catcher, picture, glow, rain, mountain, spot, badge, beam, road, label, shade, sprite, backdrop).forEach { engine.destroyMaterial(it) }
    }

    companion object {
    private fun file(ctx: Context, name: String) =
        File(File(ctx.cacheDir, "mat-v${BuildConfig.VERSION_CODE}").apply { mkdirs() }, "$name.filamat")

    private val sources: Map<String, () -> MaterialBuilder> get() = mapOf(
        "sky" to { sky() }, "ground" to { ground() }, "catcher" to { catcher() }, "picture" to { picture() }, "glow" to { glow() }, "rain" to { rain() },
        "mountain" to { mountain() }, "spot" to { spot() }, "badge" to { badge() }, "beam" to { beam() }, "road" to { road() }, "label" to { label() }, "shade" to { shade() }, "sprite" to { sprite() }, "backdrop" to { backdrop() })

    /**
     * Compila (uma vez por versão do app) os materiais que faltam (em segundo plano).
     * Chamado na abertura e, em segundo plano, logo depois de instalar uma atualização.
     */
    @Synchronized fun prepare(ctx: Context) {
        val missing = sources.filter { !file(ctx, it.key).isFile }
        if (missing.isEmpty()) return
        if (!compilerReady) { MaterialBuilder.init(); compilerReady = true }
        val t0 = System.currentTimeMillis()
        // um por vez (o compilador nativo não é garantido para uso simultâneo); otimização leve = rápido
        for ((name, make) in missing) {
            val t1 = System.currentTimeMillis()
            val pkg = make().name(name)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.OPENGL)
                .optimization(MaterialBuilder.Optimization.PREPROCESSOR)
                .build()
            check(pkg.isValid) { "material $name não compilou" }
            val b = pkg.buffer
            val bytes = ByteArray(b.remaining()).also { b.get(it) }
            val f = file(ctx, name)
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes); tmp.renameTo(f)
            Diag.log("Material $name: ${System.currentTimeMillis() - t1} ms")
        }
        Diag.log("Materiais compilados: ${missing.keys} em ${System.currentTimeMillis() - t0} ms")
    }

    private var compilerReady = false

    /** teste (emulador): material simples com o mesmo tipo de mistura do chão */
    fun testMaterial(engine: Engine, blend: String, depthWrite: Boolean, body: String): Material {
        if (!compilerReady) { MaterialBuilder.init(); compilerReady = true }
        val b = base().variable(Variable.CUSTOM0, "pos")
            .uniformParameter(UniformType.FLOAT, "mirror")
        if (blend.contains("+ground")) b.uniformParameter(UniformType.FLOAT, "contact").uniformParameter(UniformType.FLOAT4, "pool")
        if (blend.contains("+tex")) b.samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.DEFAULT, "texA")
        b
            .blending(when (blend.substringBefore('+')) { "opaque" -> BlendingMode.OPAQUE; "fade" -> BlendingMode.FADE; "add" -> BlendingMode.ADD; else -> BlendingMode.TRANSPARENT })
            .culling(CullingMode.NONE).depthWrite(depthWrite)
            .materialVertex("void materialVertex(inout MaterialVertexInputs material) { material.pos.xyz = getPosition().xyz; }")
            .material(commonGlsl + "\nvoid material(inout MaterialInputs material) { prepareMaterial(material); vec3 p = variable_pos.xyz; $body }")
        val pkg = b.name("test").platform(MaterialBuilder.Platform.MOBILE).targetApi(MaterialBuilder.TargetApi.OPENGL)
            .optimization(MaterialBuilder.Optimization.PREPROCESSOR).build()
        Diag.log("teste material válido=${pkg.isValid}")
        val buf = pkg.buffer
        return Material.Builder().payload(buf, buf.remaining()).build(engine)
    }

    private fun base(shading: Shading = Shading.UNLIT) = MaterialBuilder()
        .shading(shading)
        .flipUV(false)

    /* Cenário 3D leve: o ruído (nuvens, rocha, rachaduras, estrelas) vem de UMA textura pronta
       (Scenery.prepareNoise), lida como uma foto. Nada de ruído calculado por pixel: é isso que
       deixava a 2.4.0 lenta. Cores como no site, devolvidas ao Filament pela inversa do Filmic.
       Coordenadas em precisão alta (em média precisão a textura "quebra" em quadrados). */
    private val commonGlsl = """
        precision highp float;
        vec3 invFilmic(vec3 y) {
            y = clamp(y, vec3(0.0), vec3(0.97));
            vec3 A = 2.43 * y - 2.51; vec3 B = 0.59 * y - 0.03; vec3 C = 0.14 * y;
            return (-B - sqrt(max(B * B - 4.0 * A * C, 0.0))) / (2.0 * A);
        }
        vec3 toHdr(vec3 c) { return invFilmic(pow(clamp(c, 0.0, 1.0), vec3(2.2))); }
        // sombra do carro "de estúdio" (2.9.0): no formato da planta do EX2 (4,13 x 1,81 m, retângulo de
        // cantos redondos), escura por baixo, borda suave que sai pouco da lataria e contato sob os pneus.
        // Antes eram manchas ovais (miolo escuro e redondo no meio: o usuário achou pobre). Só contas simples.
        float carShadow(vec2 p) {
            vec2 d = vec2(p.x, p.y - 0.13);
            vec2 h = vec2(0.84, 1.98); float r = 0.42;
            vec2 e = abs(d) - (h - r);
            float sd = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0) - r;   // < 0 dentro da planta
            float inner = 1.0 - smoothstep(-0.62, 0.06, sd);               // embaixo do carro
            float outer = (1.0 - smoothstep(-0.10, 0.50, sd)) * 0.42;       // penumbra fora da lataria
            // frente e traseira (balanços altos) um pouco mais claras que o meio
            float along = 1.0 - 0.22 * smoothstep(1.0, 2.0, abs(d.y));
            vec2 w1 = vec2(abs(p.x) - 0.79, p.y - 1.44) / vec2(0.12, 0.27);
            vec2 w2 = vec2(abs(p.x) - 0.79, p.y + 1.18) / vec2(0.12, 0.27);
            float tires = (exp(-dot(w1, w1) * 2.2) + exp(-dot(w2, w2) * 2.2)) * 0.75;
            return clamp(max(inner * 0.80 * along, outer) + tires * (1.0 - inner * 0.35), 0.0, 1.0);
        }
    """.trimIndent()

    private fun MaterialBuilder.noise() =
        samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.HIGH, "noise")

    /* céu: degradê, sol/lua, estrelas e Via Láctea à noite, nuvens (textura que desliza com o vento).
       Abaixo do horizonte é o céu refletido no lago (montanhas e carro espelhados ficam por cima). */
    private fun sky() = base()
        .noise()
        .uniformParameter(UniformType.FLOAT4, "skyTop")     // rgb, a = estrelas
        .uniformParameter(UniformType.FLOAT4, "skyMid")     // rgb, a = nuvens (cobertura)
        .uniformParameter(UniformType.FLOAT4, "skyHor")     // rgb, a = tempestade
        .uniformParameter(UniformType.FLOAT4, "sunCol")     // rgb, a = dia (1) / noite (0)
        .uniformParameter(UniformType.FLOAT4, "cloudCol")   // rgb, a = vento
        .uniformParameter(UniformType.FLOAT4, "sunDir")     // xyz, a = relâmpago
        .uniformParameter(UniformType.FLOAT4, "lake")       // rgb cor do lago, a = espelho
        .uniformParameter(UniformType.FLOAT4, "misc")       // x = tempo, y = nuvens ligadas
        .culling(CullingMode.NONE)
        .depthWrite(false)
        .material(
            commonGlsl + """
            vec3 skyColor(vec3 dir) {
                float starAlpha = materialParams.skyTop.a, cover = materialParams.skyMid.a, storm = materialParams.skyHor.a;
                float isDay = materialParams.sunCol.a, drift = materialParams.cloudCol.a, time = materialParams.misc.x;
                vec3 sunD = materialParams.sunDir.xyz;
                float h = clamp(dir.y, -0.12, 1.0);
                vec3 col = mix(materialParams.skyHor.rgb, materialParams.skyMid.rgb, smoothstep(-0.03, 0.20, h));
                col = mix(col, materialParams.skyTop.rgb, smoothstep(0.08, 0.48, h));
                float above = smoothstep(-0.04, 0.10, dir.y);
                // cúpula: direção → plano acima da câmera (as texturas não se esticam no horizonte)
                vec2 dome = dir.xz / (max(dir.y, 0.015) + 0.24);
                if (starAlpha > 0.01) {
                    vec4 s = texture(materialParams_noise, dome * 0.42);
                    float mw = exp(-pow(abs(dot(dir, normalize(vec3(0.30, 0.82, 0.48)))) * 5.2, 2.0));
                    col += vec3(0.42, 0.44, 0.54) * mw * (0.22 + s.r * 1.45) * starAlpha * above * 0.6;
                    vec3 starCol = mix(vec3(0.68, 0.80, 1.0), vec3(1.0, 0.89, 0.72), s.g);
                    // perto do horizonte a cúpula estica os pontos: as estrelas somem ali (névoa)
                    col += starCol * s.a * 1.8 * starAlpha * smoothstep(0.06, 0.28, dir.y);
                }
                float sd = max(dot(dir, sunD), 0.0);
                float radius = isDay > 0.5 ? 0.013 : 0.009;
                float disc = smoothstep(cos(radius), cos(radius * 0.62), sd);
                float halo = pow(sd, 55.0) * (isDay > 0.5 ? 0.34 : 0.16) + pow(sd, 420.0) * 0.55 + pow(sd, 6.0) * 0.16 * (1.0 - smoothstep(0.05, 0.35, sunD.y));
                col += materialParams.sunCol.rgb * halo * (1.0 - cover * 0.62);
                col = mix(col, materialParams.sunCol.rgb * (isDay > 0.5 ? 1.25 : 0.82), disc * (1.0 - cover * 0.72));
                if (materialParams.misc.y > 0.5) {
                    vec2 cp = dome * 0.82 + vec2(time * 0.006 * drift, time * 0.0017 * drift);
                    float n = texture(materialParams_noise, cp * 0.165).r;
                    float cl = smoothstep(0.57 - cover * 0.55, 0.75 - cover * 0.45, n) * smoothstep(-0.025, 0.13, dir.y);
                    float shading = clamp(texture(materialParams_noise, cp * 0.26 + 0.37).r * 0.65 + dir.y * 0.5, 0.0, 1.0);
                    vec3 cc = materialParams.cloudCol.rgb * (0.58 + shading * 0.54) * (1.0 - storm * 0.38);
                    col = mix(col, cc, cl * (0.68 + cover * 0.30));
                }
                col += vec3(0.48, 0.61, 0.86) * materialParams.sunDir.w * (0.35 + 0.65 * above);
                return col;
            }
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec3 dir = normalize(getWorldPosition() - getWorldCameraPosition());
                vec3 col;
                if (dir.y >= 0.0) col = skyColor(dir);
                else {
                    // lago ao longe: espelho do céu sobre a crosta de sal
                    vec3 refl = skyColor(vec3(dir.x, -dir.y, dir.z));
                    col = mix(materialParams.lake.rgb, refl * 0.96, materialParams.lake.a);
                }
                material.baseColor = vec4(toHdr(col) * (1.0 + materialParams.sunDir.w * 1.4), 1.0);
            }
            """.trimIndent()
        )

    /* cordilheira: rocha com neve acima da linha de neve, luz do sol, névoa ao longe.
       A cópia espelhada (reflexo no lago) usa a mesma malha virada; misc.y = 1 nela. */
    private fun mountain() = base()
        .require(VertexAttribute.CUSTOM0)
        .require(VertexAttribute.CUSTOM1)
        .variable(Variable.CUSTOM0, "nrm")
        .variable(Variable.CUSTOM1, "col")
        .noise()
        .uniformParameter(UniformType.FLOAT4, "sunDir")     // xyz, a = luz ambiente
        .uniformParameter(UniformType.FLOAT4, "sunCol")     // rgb, a = relâmpago
        .uniformParameter(UniformType.FLOAT4, "fog")        // rgb, a = névoa
        .uniformParameter(UniformType.FLOAT4, "lake")       // rgb cor do lago, a = espelho
        .uniformParameter(UniformType.FLOAT4, "misc")       // x = linha de neve, y = 1 no reflexo
        .culling(CullingMode.NONE)
        .materialVertex("void materialVertex(inout MaterialVertexInputs material) { material.nrm = vec4(mesh_custom0.xyz, 0.0); material.col = mesh_custom1; }")
        .material(
            commonGlsl + """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec3 w = getUserWorldPosition();
                bool mirrored = materialParams.misc.y > 0.5;
                if (mirrored) w.y = 2.0 * ${World.GROUND_Y} - w.y;
                vec3 n = normalize(variable_nrm.xyz);
                float vSnow = variable_col.a;
                float snowLine = materialParams.misc.x;
                // duas leituras da textura: rocha (grande) e detalhe/estrias (fino, esticado na vertical)
                vec4 t1 = texture(materialParams_noise, w.xz * 0.026 + vec2(w.y * 0.024, -w.y * 0.016));
                vec4 t2 = texture(materialParams_noise, vec2(atan(w.z, w.x) * 6.0, w.y * 0.045));
                float rock = t1.r, fine = t1.g, streak = t2.r;
                float bands = 0.5 + 0.5 * sin(w.y * 3.6 + rock * 6.0);
                float slope = n.y + (rock - 0.5) * 0.30 + (fine - 0.5) * 0.10 + (streak - 0.5) * 0.30;
                float snowMask = smoothstep(snowLine - 0.07, snowLine + 0.07, vSnow + (rock - 0.5) * 0.34) * smoothstep(0.36, 0.64, slope);
                snowMask = max(snowMask, smoothstep(0.86, 0.98, vSnow) * smoothstep(0.1, 0.4, slope));
                vec3 rockCol = variable_col.rgb * (0.58 + rock * 0.56) * (0.88 + bands * 0.14) * (0.84 + fine * 0.30);
                vec3 albedo = mix(rockCol, vec3(0.93, 0.95, 0.98) * (0.94 + fine * 0.08), snowMask);
                vec3 sunD = normalize(materialParams.sunDir.xyz);
                float ndl = dot(n, sunD);
                float diffuse = max(ndl, 0.0);
                float wrap = max(ndl * 0.5 + 0.5, 0.0);
                float skyK = 0.55 + 0.45 * n.y;
                vec3 skyTint = mix(vec3(1.0), vec3(0.66, 0.80, 1.08), snowMask * (1.0 - diffuse));
                float crevice = mix(1.0, 0.73, 1.0 - snowMask);
                float flash = materialParams.sunCol.a;
                vec3 col = albedo * skyTint * (materialParams.sunDir.w * skyK * crevice + flash * 0.65)
                         + albedo * materialParams.sunCol.rgb * (diffuse * (0.85 + snowMask * 0.25) + wrap * 0.10);
                float dist = length(getWorldPosition() - getWorldCameraPosition());
                float haze = materialParams.fog.a;
                float fogK = smoothstep(40.0, 200.0, dist) * haze;
                float low = 1.0 - smoothstep(0.0, 3.5, w.y);
                col = mix(col, materialParams.fog.rgb, clamp(fogK + low * haze * 0.35, 0.0, 1.0));
                if (mirrored) col = mix(materialParams.lake.rgb, col * 0.96, materialParams.lake.a);
                material.baseColor = vec4(toHdr(col), 1.0);
            }
            """.trimIndent()
        )

    /* lago de sal: crosta em polígonos perto do carro, lâmina d'água espelhada ao longe.
       É translúcido: o que aparece por baixo é o mundo espelhado (carro, montanhas e céu). */
    private fun ground() = base()
        .noise()
        .uniformParameter(UniformType.FLOAT, "mirror")      // 0 = sem reflexo, 1 = com reflexo
        .uniformParameter(UniformType.FLOAT4, "lake")       // rgb cor da crosta, a = chuva
        .uniformParameter(UniformType.FLOAT, "contact")     // sombra de contato sob o carro
        .uniformParameter(UniformType.FLOAT4, "pool")       // rgb poça do farol, a = força
        .uniformParameter(UniformType.FLOAT4, "misc")       // x = relâmpago
        .blending(BlendingMode.TRANSPARENT)
        .culling(CullingMode.NONE)
        .depthWrite(true)
        .material(commonGlsl + "\n" + groundBody)

    private val groundBody = """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 p = getUserWorldPosition().xz;
                float rain = materialParams.lake.a;
                float dist = length(getWorldPosition() - getWorldCameraPosition());
                float near = 1.0 - smoothstep(9.0, 75.0, dist);
                // rachaduras: células grandes (~1 m) e pequenas; a textura tem 8 células por repetição
                vec4 ta = texture(materialParams_noise, p * (0.95 / 8.0));
                vec4 tb = texture(materialParams_noise, p * (2.7 / 8.0) + vec2(0.37, 0.61));
                float wetN = texture(materialParams_noise, p * 0.0075 + vec2(0.46, 0.2)).r;
                float edge = ta.b * (0.55 + 0.45 * tb.g);
                float edgeS = tb.b;
                float carZone = 1.0 - smoothstep(2.6, 6.0, length(vec2(p.x * 1.3, p.y - 0.13)));
                float grain = ta.g * 0.6 + tb.g * 0.4;
                float wet = smoothstep(0.40, 0.64, wetN);
                wet = clamp(wet + rain * 0.7, 0.0, 1.0);
                wet *= 1.0 - carZone * 0.8 * (1.0 - rain);
                float ridge = (edge * 0.75 + edgeS * 0.22) * (1.0 - wet * 0.7) * near;
                vec3 viewDir = normalize(getWorldCameraPosition() - getWorldPosition());
                float fresnel = pow(1.0 - clamp(viewDir.y, 0.0, 1.0), 3.0);
                float m = mix(0.92, 0.34 + wet * 0.52, near) + fresnel * 0.12 - ridge * 0.45 - carZone * 0.12 * (1.0 - rain);
                m = clamp(m, 0.10, 0.95) * materialParams.mirror;
                vec3 crust = materialParams.lake.rgb * (0.80 + grain * 0.24) + materialParams.lake.rgb * ridge * 0.22;
                crust += vec3(0.20, 0.29, 0.43) * materialParams.misc.x * 0.35;
                float a = 1.0 - m;
                // sombra do carro (no lugar do carro refletido): miolo escuro sob a carroceria, penumbra
                // larga que sai um pouco da lataria e contato mais escuro sob os pneus; no espelho d'água
                // ela escurece o que é refletido (céu) também, como um reflexo escuro e suave
                float blob = carShadow(p) * materialParams.contact;
                crust *= 1.0 - blob * 0.92;
                a = max(a, min(1.0, blob * 1.15));
                vec3 c = toHdr(crust);
                vec2 b1 = vec2((p.x - 0.62) / 0.55, (p.y - 3.2) / 1.6);
                vec2 b2 = vec2((p.x + 0.62) / 0.55, (p.y - 3.2) / 1.6);
                vec2 b3 = vec2(p.x / 1.9, (p.y - 3.4) / 1.5);
                // (nomes locais diferentes dos parâmetros: igual quebra o material em algumas GPUs)
                float beamGlow = (exp(-dot(b1, b1) * 2.0) + exp(-dot(b2, b2) * 2.0)) * 0.5 + exp(-dot(b3, b3) * 1.6) * 0.6;
                c += materialParams.pool.rgb * beamGlow * materialParams.pool.a;
                a = max(a, min(1.0, beamGlow * materialParams.pool.a));
                float edgeFade = 1.0 - smoothstep(16.0, 38.0, length(p));
                a *= edgeFade;
                material.baseColor = vec4(c * a, a);
            }
            """.trimIndent()

    /* ponto de toque do papel de parede (mesmo desenho dos pontos do app): anel translúcido com
       borda branca e bolinha no meio; azul quando a peça está aberta. Sempre por cima do carro. */
    private fun spot() = base()
        .require(VertexAttribute.UV0)
        .uniformParameter(UniformType.FLOAT4, "state")     // x = aberta, y = opacidade
        .blending(BlendingMode.TRANSPARENT)
        .depthWrite(false)
        .depthCulling(false)
        .culling(CullingMode.NONE)
        .material(
            commonGlsl + """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                bool open = materialParams.state.x > 0.5;
                float r = length(getUV0() * 2.0 - 1.0) * 1.15;          // borda do anel em r = 1
                float aa = max(fwidth(r) * 1.2, 0.02);
                float inside = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, r);
                float stroke = inside * smoothstep(0.91 - aa, 0.91 + aa, r);
                float dotR = open ? 0.38 : 0.30;
                float dotK = 1.0 - smoothstep(dotR - aa, dotR + aa, r);
                // composição (pré-multiplicada): preenchimento, borda branca 75%, bolinha branca
                float fa = (open ? 0.45 : 0.28) * inside;
                vec3 c = (open ? vec3(0.24, 0.545, 1.0) : vec3(1.0)) * fa;
                float a = fa;
                float s = stroke * 0.75;
                c = c * (1.0 - s) + vec3(s); a = a * (1.0 - s) + s;
                c = c * (1.0 - dotK) + vec3(dotK); a = a * (1.0 - dotK) + dotK;
                a *= materialParams.state.y; c *= materialParams.state.y;
                vec3 straight = a > 0.001 ? c / a : vec3(0.0);
                // branco "de tela" (0,85): o branco puro vira luz forte demais e o anel parecia sólido
                material.baseColor = vec4(toHdr(straight * 0.85) * a, a);
            }
            """.trimIndent()
        )

    /* emblema "EX2" da tampa traseira (como no site): a placa curva do modelo mostra só as letras,
       recortadas de uma imagem do texto; metal claro com reflexo. As coordenadas da placa (mm, no
       espaço do próprio modelo) viram coordenadas da imagem já no vértice. */
    private fun badge() = base()
        .samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.DEFAULT, "text")
        .uniformParameter(UniformType.FLOAT3, "tint")
        .variable(Variable.CUSTOM0, "uvt")
        .blending(BlendingMode.MASKED)
        .culling(CullingMode.NONE)
        .materialVertex("""
            void materialVertex(inout MaterialVertexInputs material) {
                vec3 p = getPosition().xyz;
                // placa: largura no eixo Y (322,8…539,3 mm, sentido de leitura), altura no Z (479,0…512,3 mm)
                material.uvt = vec4((p.y - 322.78) / 216.47, 1.0 - (p.z - 478.98) / 33.30, 0.0, 0.0);
            }
        """.trimIndent())
        .material(
            commonGlsl + """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                // prata clara sem depender da luz (a placa do modelo não tem orientação de superfície
                // para iluminar: com metal "de verdade" as letras ficavam pretas)
                float a = texture(materialParams_text, variable_uvt.xy).r;
                material.baseColor = vec4(toHdr(materialParams.tint), a);
            }
            """.trimIndent()
        )

    /* feixe de luz do farol: cone suave somado à cena (como névoa iluminada). Mais forte no meio
       do cone e perto do farol; as bordas e a ponta somem aos poucos. misc.x = 1 no reflexo. */
    private fun beam() = base()
        .require(VertexAttribute.CUSTOM0)
        .variable(Variable.CUSTOM0, "nt")
        .uniformParameter(UniformType.FLOAT4, "color")     // rgb, a = força
        .uniformParameter(UniformType.FLOAT4, "misc")      // x = 1 no reflexo
        .blending(BlendingMode.ADD)
        .depthWrite(false)
        .culling(CullingMode.NONE)
        .materialVertex("void materialVertex(inout MaterialVertexInputs material) { material.nt = mesh_custom0; }")
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                float t = variable_nt.w;
                vec3 n = normalize(variable_nt.xyz);
                if (materialParams.misc.x > 0.5) n.y = -n.y;
                vec3 v = normalize(getWorldCameraPosition() - getWorldPosition());
                float edge = pow(abs(dot(n, v)), 1.6);
                float along = pow(1.0 - t, 1.7) * smoothstep(0.0, 0.18, t);   // nasce suave junto ao farol
                float k = edge * along * materialParams.color.a;
                material.baseColor = vec4(materialParams.color.rgb * k, 0.0);
            }
            """.trimIndent()
        )

    /* cenário "Estrada": asfalto escuro que some ao longe no fundo escuro, faixas tracejadas
       (uma a cada 2,6 m) que correm com a velocidade e sombra de contato sob o carro */
    private fun road() = base()
        .uniformParameter(UniformType.FLOAT4, "misc")      // x = deslocamento (m), y = sombra, z = brilho do dia, w = tema claro (0..1)
        .uniformParameter(UniformType.FLOAT4, "reveal")    // transição: x = raio (m) a partir do carro, y = borda (m), z = brilho da borda
        .blending(BlendingMode.TRANSPARENT)
        .depthWrite(true)
        .culling(CullingMode.NONE)
        .material(
            commonGlsl + """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec3 P = getUserWorldPosition();
                vec2 p = P.xz;
                float z = p.y + materialParams.misc.x;           // faixas andam para trás com o carro
                float aa = max(fwidth(z), 0.002) * 1.5;
                // curva: as faixas acompanham as rodas dianteiras (arco de raio 1/k que passa pelo carro)
                float bend = 0.5 * materialParams.reveal.w * p.y * p.y;
                float ax = abs(p.x - bend);
                // tracejado no meio das faixas (x = ±1,75 m) e linha contínua nas bordas (x = ±5,25 m)
                float dashOn = smoothstep(0.0, aa, fract(z / 2.6) * 2.6 - 0.0) * (1.0 - smoothstep(1.3 - aa, 1.3, fract(z / 2.6) * 2.6));
                float lw = 0.06;
                float lineC = (1.0 - smoothstep(lw - 0.02, lw, abs(ax - 1.75))) * dashOn;
                float lineE = 1.0 - smoothstep(lw - 0.02, lw, abs(ax - 5.25));
                float lines = max(lineC, lineE);
                float dist = length(getWorldPosition() - getWorldCameraPosition());
                float fade = 1.0 - smoothstep(10.0, 34.0, length(p));
                float day = materialParams.misc.z;
                float light = materialParams.misc.w;
                // escuro: asfalto quase preto e faixas cinza-claro
                vec3 asphaltD = vec3(0.075, 0.08, 0.09) * (0.55 + 0.45 * day);
                vec3 lineD = vec3(0.78, 0.80, 0.82) * (0.6 + 0.4 * day);
                // claro ("estúdio de dia"): asfalto cinza médio levemente frio, mais claro sob o carro
                // (como um refletor suave) e que se dissolve na névoa clara do horizonte
                float pool = exp(-dot(p / vec2(5.5, 7.0), p / vec2(5.5, 7.0)));
                vec3 asphaltL = mix(vec3(0.50, 0.52, 0.55), vec3(0.62, 0.635, 0.66), pool);
                vec3 hazeL = vec3(0.86, 0.875, 0.895);
                asphaltL = mix(asphaltL, hazeL, smoothstep(7.0, 30.0, length(p)));
                vec3 lineL = vec3(0.95, 0.955, 0.96);
                vec3 asphalt = mix(asphaltD, asphaltL, light);
                // estrada escura: luz suave de cima em volta do carro (sem ela a sombra some no asfalto preto)
                asphalt += vec3(0.10, 0.105, 0.115) * pool * (1.0 - light) * (0.6 + 0.4 * day);
                vec3 c = mix(asphalt, mix(lineD, lineL, light), lines * mix(0.85, 0.8 * (1.0 - smoothstep(14.0, 30.0, length(p))), light));
                // sombra: miolo justo sob o carro + penumbra larga (sai um pouco da lataria) + contato dos pneus
                float blob = carShadow(p) * materialParams.misc.y;
                c *= 1.0 - min(blob, 0.88);
                float a = fade * (0.92 + 0.08 * lines);
                // transição: o asfalto nasce debaixo do carro e se espalha, com uma borda de luz fria
                float rr = length(p);
                float R = materialParams.reveal.x, F = materialParams.reveal.y;
                float shown = 1.0 - smoothstep(R - F, R, rr);
                float ring = exp(-pow((rr - R + F * 0.5) / (F * 0.45), 2.0)) * materialParams.reveal.z;
                a *= shown;
                vec3 col = toHdr(c) * a + vec3(0.30, 0.55, 1.0) * ring * 0.9;
                material.baseColor = vec4(col, max(a, ring * 0.6));
            }
            """.trimIndent()
        )

    /* texto sobre a tela (temperatura do papel de parede), numa vista sem pós-processamento:
       cores direto de tela. Imagem: vermelho = desenho, verde = sombra difusa. */
    private fun label() = base()
        .require(VertexAttribute.UV0)
        .samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.DEFAULT, "tex")
        .uniformParameter(UniformType.FLOAT4, "color")     // rgb, a = força da sombra
        .uniformParameter(UniformType.FLOAT, "opacity")    // aparecer/sumir (saudação)
        .blending(BlendingMode.TRANSPARENT)
        .depthWrite(false)
        .depthCulling(false)
        .culling(CullingMode.NONE)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 m = texture(materialParams_tex, getUV0()).rg;
                float s = m.y * materialParams.color.a;
                float o = materialParams.opacity;
                float a = (m.x + s * (1.0 - m.x)) * o;
                vec3 c = materialParams.color.rgb * m.x * o;  // sombra = preto
                material.baseColor = vec4(c, a);
            }
            """.trimIndent()
        )

    /* imagem pronta do carro (quadro já desenhado, guardado numa textura): a chuva e os textos do papel
       de parede são desenhados por cima dela sem redesenhar o carro */
    private fun backdrop() = base()
        .require(VertexAttribute.UV0)
        .samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.DEFAULT, "tex")
        .blending(BlendingMode.OPAQUE)
        .depthWrite(false)
        .depthCulling(false)
        .culling(CullingMode.NONE)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 uv = getUV0();
                material.baseColor = vec4(texture(materialParams_tex, vec2(uv.x, 1.0 - uv.y)).rgb, 1.0);
            }
            """.trimIndent()
        )

    /* imagem pronta sobre a tela (botão Fixo/Auto e faixa escura do topo no papel de parede) */
    private fun sprite() = base()
        .require(VertexAttribute.UV0)
        .samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.DEFAULT, "tex")
        .uniformParameter(UniformType.FLOAT, "opacity")
        .blending(BlendingMode.TRANSPARENT)
        .depthWrite(false)
        .depthCulling(false)
        .culling(CullingMode.NONE)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = texture(materialParams_tex, getUV0()) * materialParams.opacity;
            }
            """.trimIndent()
        )

    /* volume escuro dentro da carroceria: atrás das frestas (portas, capô, tampa) e dos vidros
       aparece escuro como num carro de verdade, em vez do céu claro passando pelas frestas */
    private fun shade() = base()
        .uniformParameter(UniformType.FLOAT3, "color")
        .culling(CullingMode.NONE)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = vec4(materialParams.color, 1.0);
            }
            """.trimIndent()
        )

    /* coletor de sombra: camada fina sobre o chão que só escurece onde o carro faz sombra
       (com shadowMultiplier a biblioteca desenha apenas a sombra; o resto fica invisível) */
    private fun catcher() = base()
        .uniformParameter(UniformType.FLOAT, "strength")
        .blending(BlendingMode.TRANSPARENT)
        .shadowMultiplier(true)
        .culling(CullingMode.NONE)
        .depthWrite(false)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = vec4(0.0, 0.0, 0.0, materialParams.strength);
            }
            """.trimIndent()
        )

    /* foto (interior) num quadro: textura com tom ajustável */
    private fun picture() = base()
        .require(VertexAttribute.UV0)
        .samplerParameter(SamplerType.SAMPLER_2D, SamplerFormat.FLOAT, ParameterPrecision.DEFAULT, "tex")
        .uniformParameter(UniformType.FLOAT3, "tint")
        .culling(CullingMode.NONE)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                material.baseColor = vec4(texture(materialParams_tex, getUV0()).rgb * materialParams.tint, 1.0);
            }
            """.trimIndent()
        )

    /* halo das lanternas: quadro virado para a câmera, soma luz (aditivo) */
    private fun glow() = base()
        .require(VertexAttribute.UV0)
        .uniformParameter(UniformType.FLOAT4, "color")
        .blending(BlendingMode.ADD)
        .depthWrite(false)
        .culling(CullingMode.NONE)
        .material(
            """
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec2 q = getUV0() * 2.0 - 1.0;
                float r = dot(q, q);
                float k = exp(-r * 5.0) * 0.85 + exp(-r * 22.0) * 0.6;
                k *= 1.0 - smoothstep(0.75, 1.0, r);
                material.baseColor = vec4(materialParams.color.rgb * (k * materialParams.color.a), 0.0);
            }
            """.trimIndent()
        )

    /* chuva: riscos procedurais na frente da câmera (tela inteira, aditivo) */
    private fun rain() = base()
        .vertexDomain(VertexDomain.DEVICE)
        .uniformParameter(UniformType.FLOAT4, "params")    // x tempo, y intensidade, z aspecto, w vento
        .variable(Variable.CUSTOM0, "uvs")
        .blending(BlendingMode.ADD)
        .depthWrite(false)
        .depthCulling(false)
        .culling(CullingMode.NONE)
        .materialVertex("void materialVertex(inout MaterialVertexInputs material) { material.uvs.xy = getPosition().xy * 0.5 + 0.5; }")
        .material(
            """
            precision highp float;
            float h21(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
            void material(inout MaterialInputs material) {
                prepareMaterial(material);
                vec4 P = materialParams.params;
                vec2 uv = variable_uvs.xy; uv.x *= P.z;
                float acc = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float cols = 70.0 + fi * 45.0;
                    vec2 p = vec2(uv.x * cols + uv.y * cols * P.w, uv.y * (3.0 + fi) + P.x * (2.2 + fi * 0.7));
                    vec2 id = floor(p);
                    vec2 f = fract(p);
                    float h = h21(id + fi * 17.0);
                    float on = step(1.0 - 0.33 * P.y, h);
                    float head = fract(h * 7.13);
                    float len = 0.18 + 0.12 * fract(h * 3.7);
                    float y = fract(f.y + head);
                    float streak = smoothstep(0.0, 0.02, y) * (1.0 - smoothstep(len * 0.5, len, y));
                    float w = 1.0 - smoothstep(0.03, 0.09, abs(f.x - 0.5));
                    acc += on * streak * w * (0.55 - fi * 0.12);
                }
                // desenhada por cima da imagem pronta (cores de tela, sem o Filmic): um pouco mais forte
                material.baseColor = vec4(vec3(0.62, 0.68, 0.76) * acc * P.y * 0.62, 0.0);
            }
            """.trimIndent()
        )

    }
}
