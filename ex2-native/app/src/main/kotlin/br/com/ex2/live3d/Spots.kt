package br.com.ex2.live3d

import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import kotlin.math.sqrt

/**
 * Pontos de toque presos ao carro (portas, porta-malas, capô): posição na tela de cada um,
 * quanto aparece (some quando a peça fica de costas) e qual foi tocado.
 * Usado pela tela do app (Hotspots, desenho Android) e pelo papel de parede (WallSpots, desenho 3D).
 */
class SpotSet {
    /** ponto no carro (m) e direção para onde a peça "olha" (para sumir de costas) */
    class Spot(val name: String, val p: DoubleArray, val n: DoubleArray) {
        var x = 0f; var y = 0f; var alpha = 0f; var dist = 1.0
    }

    // lado esquerdo do carro = +x, frente = +z
    val spots = listOf(
        Spot(Openings.FL, doubleArrayOf(.90, .92, .30), doubleArrayOf(1.0, 0.0, 0.0)),
        Spot(Openings.RL, doubleArrayOf(.88, .92, -.62), doubleArrayOf(1.0, 0.0, 0.0)),
        Spot(Openings.FR, doubleArrayOf(-.90, .92, .30), doubleArrayOf(-1.0, 0.0, 0.0)),
        Spot(Openings.RR, doubleArrayOf(-.88, .92, -.62), doubleArrayOf(-1.0, 0.0, 0.0)),
        Spot(Openings.FRUNK, doubleArrayOf(0.0, .90, 1.45), doubleArrayOf(0.0, .70, .71)),
        Spot(Openings.TAILGATE, doubleArrayOf(0.0, 1.12, -1.86), doubleArrayOf(0.0, .30, -.95))
    )

    /** matrizes da câmera (coluna principal), olho e tamanho da tela em pixels */
    fun update(view: DoubleArray, proj: DoubleArray, eye: DoubleArray, w: Float, h: Float) {
        for (s in spots) {
            val p = s.p
            val vx = view[0] * p[0] + view[4] * p[1] + view[8] * p[2] + view[12]
            val vy = view[1] * p[0] + view[5] * p[1] + view[9] * p[2] + view[13]
            val vz = view[2] * p[0] + view[6] * p[1] + view[10] * p[2] + view[14]
            val cx = proj[0] * vx + proj[4] * vy + proj[8] * vz + proj[12]
            val cy = proj[1] * vx + proj[5] * vy + proj[9] * vz + proj[13]
            val cw = proj[3] * vx + proj[7] * vy + proj[11] * vz + proj[15]
            if (cw <= 0.01) { s.alpha = 0f; continue }
            s.x = ((cx / cw * .5 + .5) * w).toFloat()
            s.y = ((1 - (cy / cw * .5 + .5)) * h).toFloat()
            // de frente para a câmera = visível; de lado vai sumindo; de costas some
            val ex = eye[0] - p[0]; val ey = eye[1] - p[1]; val ez = eye[2] - p[2]
            val len = sqrt(ex * ex + ey * ey + ez * ez)
            s.dist = len
            val facing = (ex * s.n[0] + ey * s.n[1] + ez * s.n[2]) / len
            s.alpha = ((facing - .12) / .25).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** ponto visível mais perto do toque, dentro do raio (pixels) */
    fun hit(x: Float, y: Float, radius: Float): Spot? = spots.filter { it.alpha > .3f }
        .minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
        ?.takeIf { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < radius * radius }
}

/**
 * Pontos no papel de parede: o papel de parede não tem tela Android por cima, então cada ponto é
 * um quadradinho 3D (material "spot") virado para a câmera e com tamanho fixo na tela.
 * Ficam numa camada própria (bit 2) que só a câmera do papel de parede enxerga.
 */
class WallSpots(private val world: World, private val density: Float) {
    private val engine = world.engine
    private val rm = engine.renderableManager
    private val tm = engine.transformManager
    val set = SpotSet()
    private val mesh = Geo.quad(engine)
    private val ents = IntArray(set.spots.size)
    private val mis = arrayOfNulls<MaterialInstance>(set.spots.size)
    /** quanto aparecem (0..1): sobe ao girar com o dedo, desce ao voltar à vista padrão */
    var target = 0f
    private var fade = 0f
    /** quanto os pontos estão aparecendo agora (o botão Fixo/Auto acompanha) */
    val shown get() = fade

    init {
        for (i in set.spots.indices) {
            val mi = world.mats.spot.createInstance()
            mis[i] = mi
            val e = EntityManager.get().create()
            RenderableManager.Builder(1)
                .boundingBox(mesh.box)
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib, 0, mesh.count)
                .material(0, mi).priority(7).culling(false).castShadows(false).receiveShadows(false)
                .layerMask(0xff, LAYER)
                .build(engine, e)
            tm.create(e)
            world.scene.addEntity(e)
            ents[i] = e
        }
    }

    /** anima o aparecer/sumir; devolve true enquanto ainda está mudando */
    fun step(dt: Float): Boolean {
        if (fade == target) return false
        fade = if (target > fade) minOf(target, fade + dt / .3f) else maxOf(target, fade - dt / .3f)
        return true
    }

    /** a cada quadro do papel de parede: posiciona os pontos virados para a câmera */
    fun update(view: DoubleArray, proj: DoubleArray, eye: DoubleArray, w: Float, h: Float) {
        set.update(view, proj, eye, w, h)
        // direita e cima da câmera = linhas da matriz de vista
        val rx = view[0]; val ry = view[4]; val rz = view[8]
        val ux = view[1]; val uy = view[5]; val uz = view[9]
        for ((i, s) in set.spots.withIndex()) {
            val a = s.alpha * fade
            val ri = rm.getInstance(ents[i])
            if (a <= .01f) { rm.setLayerMask(ri, 0xff, 0); continue }
            rm.setLayerMask(ri, 0xff, LAYER)
            // meio-tamanho em metros para o anel ter RING px de raio a essa distância
            val half = RING * density / (h / 2) * s.dist / proj[5] * 1.15   // (folga para a borda suave)
            val sz = (half * 2).toFloat()
            tm.setTransform(tm.getInstance(ents[i]), floatArrayOf(
                (rx * sz).toFloat(), (ry * sz).toFloat(), (rz * sz).toFloat(), 0f,
                (ux * sz).toFloat(), (uy * sz).toFloat(), (uz * sz).toFloat(), 0f,
                0f, 0f, 1f, 0f,
                s.p[0].toFloat(), s.p[1].toFloat(), s.p[2].toFloat(), 1f))
            mis[i]!!.setParameter("state", if (world.isOpen(s.name)) 1f else 0f, a, 0f, 0f)
        }
    }

    fun hide() { for (e in ents) rm.setLayerMask(rm.getInstance(e), 0xff, 0) }

    /** botão X do papel de parede: some na hora (sem o esmaecer) */
    fun hideNow() { target = 0f; fade = 0f; hide() }

    /** toque no papel de parede: nome da peça tocada (só com os pontos aparecendo) */
    fun hit(x: Float, y: Float): String? = if (fade < .5f) null else set.hit(x, y, 30 * density)?.name

    fun destroy() {
        for (e in ents) { world.scene.remove(e); engine.destroyEntity(e) }
        for (m in mis) m?.let { engine.destroyMaterialInstance(it) }
        mesh.destroy(engine)
    }

    companion object {
        const val LAYER = 0x2
        /** raio do anel em dp (igual aos pontos do app) */
        const val RING = 17f
    }
}
