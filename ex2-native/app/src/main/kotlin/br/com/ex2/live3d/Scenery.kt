package br.com.ex2.live3d

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cenário 3D do site trazido para o app: cores do céu/terra por hora e clima (palette) e as
 * três cordilheiras em anel em volta do lago (malhas geradas por ruído, como o buildRange do sky.js).
 */
object Scenery {

    /* ---------------- cores ---------------- */

    class Palette(
        val top: FloatArray, val mid: FloatArray, val hor: FloatArray, val sun: FloatArray, val cloud: FloatArray,
        val day: Float, val dusk: Float, val starAlpha: Float, val salt: FloatArray, val drift: Float
    )

    private fun c(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)
    private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private val DAY = arrayOf(c(16, 72, 160), c(48, 122, 200), c(172, 204, 230))
    // noite como no site, só um pouco mais clara: preto puro parecia que a cena não tinha carregado
    private val NIGHT = arrayOf(c(9, 15, 32), c(17, 27, 50), c(40, 54, 80))
    private val DUSK = arrayOf(c(190, 146, 122), c(236, 176, 124), c(255, 214, 152))
    private val OVERCAST = arrayOf(c(91, 108, 125), c(139, 153, 165), c(192, 201, 207))
    private val STORM = arrayOf(c(10, 15, 24), c(28, 38, 51), c(58, 72, 86))
    private val SALT = arrayOf(c(28, 37, 54), c(198, 194, 186), c(214, 172, 136))   // noite, dia, entardecer

    fun palette(st: SkyClock.State): Palette {
        val w = st.weather
        val day = st.day
        val dusk = st.golden.coerceIn(0f, 1f) * (1 - .8f * w.cover)
        val out = Array(3) { i ->
            val gray = mix(OVERCAST[i], STORM[i], w.storm)
            var v = mix(NIGHT[i], DAY[i], day)
            v = mix(v, gray, w.cover * (if (i == 2) .94f else 1f))
            mix(v, DUSK[i], dusk * (if (i == 2) 1f else .92f))
        }
        var salt = mix(SALT[0], SALT[1], day)
        salt = mix(salt, SALT[2], dusk * .64f)
        salt = mix(salt, out[2], w.cover * .28f)
        val k = .58f + day * .20f
        salt = floatArrayOf(salt[0] * k, salt[1] * k, salt[2] * k)
        val star = (1 - smooth(.05f, .40f, day)) * (1 - .97f * w.cover)
        val cloud = mix(c(22, 31, 44), mix(c(235, 240, 242), out[2], .26f), day)
        val sun = mix(c(255, 246, 220), c(255, 157, 79), dusk.coerceIn(0f, 1f) * .92f)
        val drift = when (w.id) { "sol" -> .55f; "nublado" -> 1f; "chuva" -> 1.35f; else -> 2.1f }
        return Palette(out[0], out[1], out[2], sun, cloud, day, dusk, star, salt, drift)
    }

    /* ---------------- textura de ruído (gerada uma vez, repete sem emenda) ---------------- */

    const val NOISE = 512
    /** cada célula da textura: R ruído suave (nuvens/rocha), G grão fino, B rachaduras (8 células por lado), A estrelas */
    @Volatile var noiseBytes: ByteBuffer? = null
        private set

    @Synchronized fun prepareNoise(): ByteBuffer {
        noiseBytes?.let { return it }
        val n = NOISE
        val out = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        fun hp(x: Int, y: Int, p: Int, s: Int): Float {
            val xx = ((x % p) + p) % p; val yy = ((y % p) + p) % p
            var h = xx * 374761393 + yy * 668265263 + s * 1442695041
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
        }
        fun vn(u: Float, v: Float, p: Int, s: Int): Float {   // ruído de valor com período p
            val x = u * p; val y = v * p
            val ix = floor(x).toInt(); val iy = floor(y).toInt()
            var fx = x - ix; var fy = y - iy
            fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
            return lerp(lerp(hp(ix, iy, p, s), hp(ix + 1, iy, p, s), fx), lerp(hp(ix, iy + 1, p, s), hp(ix + 1, iy + 1, p, s), fx), fy)
        }
        val cells = 8
        val cx = FloatArray(cells * cells); val cy = FloatArray(cells * cells)
        for (j in 0 until cells) for (i in 0 until cells) {
            cx[j * cells + i] = (i + .075f + .85f * hp(i, j, cells, 7)) / cells
            cy[j * cells + i] = (j + .075f + .85f * hp(i, j, cells, 19)) / cells
        }
        for (y in 0 until n) for (x in 0 until n) {
            val u = (x + .5f) / n; val v = (y + .5f) / n
            // R: fbm de 5 oitavas
            var f = 0f; var a = .5f; var p = 8
            for (o in 0 until 5) { f += a * vn(u, v, p, 3 + o); a *= .5f; p *= 2 }
            f /= .96875f
            // G: grão fino
            val g = vn(u, v, 128, 11) * .6f + vn(u, v, 256, 13) * .4f
            // B: rachaduras (bordas das células de Voronoi, com repetição)
            val gi = floor(u * cells).toInt(); val gj = floor(v * cells).toInt()
            var d1 = 9f; var d2 = 9f
            for (dj in -1..1) for (di in -1..1) {
                val ii = gi + di; val jj = gj + dj
                val wi = ((ii % cells) + cells) % cells; val wj = ((jj % cells) + cells) % cells
                val px = cx[wj * cells + wi] + floor(ii.toFloat() / cells) ; val py = cy[wj * cells + wi] + floor(jj.toFloat() / cells)
                val dx = (px - u) * cells; val dy = (py - v) * cells
                val d = sqrt(dx * dx + dy * dy)
                if (d < d1) { d2 = d1; d1 = d } else if (d < d2) d2 = d
            }
            val crack = 1f - smooth(.0f, .06f, d2 - d1)
            // A: estrelas (pontos esparsos com brilho variado)
            val sx = x / 4; val sy = y / 4
            val sr = hp(sx, sy, n / 4, 29)
            var star = 0f
            if (sr > .955f) {
                val ox = sx * 4 + 1 + (hp(sx, sy, n / 4, 31) * 2).toInt(); val oy = sy * 4 + 1 + (hp(sx, sy, n / 4, 37) * 2).toInt()
                val dd = abs(x - ox) + abs(y - oy)
                val b = .35f + .65f * ((sr - .955f) / .045f)
                star = if (dd == 0) b else if (dd == 1) b * .22f else 0f
            }
            out.put((f.coerceIn(0f, 1f) * 255).toInt().toByte())
            out.put((g.coerceIn(0f, 1f) * 255).toInt().toByte())
            out.put((crack * 255).toInt().toByte())
            out.put((star * 255).toInt().toByte())
        }
        out.flip()
        noiseBytes = out
        return out
    }

    /* ---------------- cordilheiras ---------------- */

    /** anel de montanhas: raio interno/externo, altura, semente, linha de neve, névoa, resolução, escala do ruído, cor da rocha */
    class Range(val inner: Float, val outer: Float, val height: Float, val seed: Int, val snowLine: Float, val haze: Float,
                val around: Int, val rows: Int, val freq: Float, val rock: FloatArray, val main: Boolean)

    // mesmos anéis do site, leves: ~20 mil triângulos no total (a central desenha cada um duas
    // vezes: real + reflexo); o detalhe da rocha/neve vem da textura de ruído, não da malha
    val ranges = listOf(
        Range(118f, 178f, 20f, 13, .30f, .40f, 240, 24, 1f / 26, floatArrayOf(.24f, .23f, .235f), true),
        Range(72f, 104f, 5.5f, 41, .80f, .28f, 160, 12, 1f / 18, floatArrayOf(.17f, .165f, .16f), false),
        Range(184f, 216f, 30f, 77, .22f, .70f, 180, 12, 1f / 34, floatArrayOf(.27f, .27f, .29f), false)
    )

    /** dados prontos de uma malha (gerados fora da thread da tela) */
    class Data(val verts: FloatArray, val idx: ShortArray, val box: Box)

    private fun h2(x: Float, y: Float): Float { val v = sin(x * 127.1 + y * 311.7) * 43758.5453; return (v - floor(v)).toFloat() }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun vnoise(x: Float, y: Float): Float {
        val ix = floor(x); val iy = floor(y); var fx = x - ix; var fy = y - iy
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
        return lerp(lerp(h2(ix, iy), h2(ix + 1, iy), fx), lerp(h2(ix, iy + 1), h2(ix + 1, iy + 1), fx), fy)
    }
    private fun ridged(x0: Float, y0: Float): Float {
        var x = x0; var y = y0; var sum = 0f; var amp = .55f; var prev = 1f; var norm = 0f
        for (o in 0 until 7) {
            var n = 1 - abs(vnoise(x, y) * 2 - 1); n *= n
            sum += n * amp * (.35f + .65f * prev); norm += amp; prev = n
            val nx = x * 1.6f - y * 1.2f + 5.3f; y = x * 1.2f + y * 1.6f + 1.7f; x = nx; amp *= .5f
        }
        return sum / norm
    }
    private fun massif(a: Float, seed: Int): Float {
        val u = (a + PI.toFloat()) / (2 * PI.toFloat()) * 9; val i = floor(u).toInt(); var f = u - i; f = f * f * (3 - 2 * f)
        return lerp(h2((((i % 9) + 9) % 9).toFloat(), seed.toFloat()), h2(((((i + 1) % 9) + 9) % 9).toFloat(), seed.toFloat()), f)
    }

    /** vértice: posição (3), normal (3), cor da rocha + neve (4) */
    const val STRIDE = 10

    fun build(r: Range, groundY: Float): Data {
        val A = r.around; val rows = r.rows + 1
        val n = A * rows
        val pos = FloatArray(n * 3); val v = FloatArray(n * STRIDE)
        val seed = r.seed.toFloat()
        var maxY = 0f
        for (i in 0 until A) {
            val a = i.toFloat() / A * 2 * PI.toFloat(); val ca = cos(a); val sa = sin(a)
            val m = .45f + .55f * massif(a, r.seed)
            for (j in 0 until rows) {
                val t = j.toFloat() / r.rows
                val rad = r.inner + (r.outer - r.inner) * t
                val x = ca * rad; val z = sa * rad
                val prof = smooth(0f, .34f, t) * (1 - .5f * smooth(.72f, 1f, t))
                val wx = x * r.freq + seed; val wz = z * r.freq - seed
                val nn = ridged(wx + 1.3f * (vnoise(wx * .45f, wz * .45f) - .5f), wz + 1.3f * (vnoise(wx * .45f + 9.2f, wz * .45f + 3.7f) - .5f))
                // a base fica na linha d'água (um pouco acima: o reflexo nasce exatamente embaixo)
                val y = max(groundY + .02f, -.3f + r.height * prof * m * (.10f + .90f * nn))
                if (y > maxY) maxY = y
                val tone = .78f + .44f * vnoise(x * .09f + seed, z * .09f)
                val k = i * rows + j
                pos[k * 3] = x; pos[k * 3 + 1] = y; pos[k * 3 + 2] = z
                val o = k * STRIDE
                v[o] = x; v[o + 1] = y; v[o + 2] = z
                v[o + 6] = r.rock[0] * tone; v[o + 7] = r.rock[1] * tone; v[o + 8] = r.rock[2] * tone
                v[o + 9] = (y / r.height).coerceIn(0f, 1f)
            }
        }
        val idx = ShortArray(A * r.rows * 6)
        val nrm = FloatArray(n * 3)
        var q = 0
        for (ia in 0 until A) for (jr in 0 until r.rows) {
            val a0 = ia * rows + jr; val a1 = ((ia + 1) % A) * rows + jr; val b0 = a0 + 1; val b1 = a1 + 1
            for (tri in arrayOf(intArrayOf(a0, a1, b0), intArrayOf(b0, a1, b1))) {
                idx[q++] = tri[0].toShort(); idx[q++] = tri[1].toShort(); idx[q++] = tri[2].toShort()
                // normal da face somada nos 3 vértices (sentido para cima)
                val p0 = tri[0] * 3; val p1 = tri[1] * 3; val p2 = tri[2] * 3
                val ux = pos[p1] - pos[p0]; val uy = pos[p1 + 1] - pos[p0 + 1]; val uz = pos[p1 + 2] - pos[p0 + 2]
                val wx = pos[p2] - pos[p0]; val wy = pos[p2 + 1] - pos[p0 + 1]; val wz = pos[p2 + 2] - pos[p0 + 2]
                var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
                if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
                for (p in intArrayOf(p0, p1, p2)) { nrm[p] += nx; nrm[p + 1] += ny; nrm[p + 2] += nz }
            }
        }
        for (k in 0 until n) {
            val x = nrm[k * 3]; val y = nrm[k * 3 + 1]; val z = nrm[k * 3 + 2]
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            v[k * STRIDE + 3] = x / l; v[k * STRIDE + 4] = y / l; v[k * STRIDE + 5] = z / l
        }
        val box = Box(0f, (maxY + groundY) / 2, 0f, r.outer, (maxY - groundY) / 2 + .1f, r.outer)
        return Data(v, idx, box)
    }

    /** envia a malha para a placa de vídeo */
    fun mesh(e: Engine, d: Data): Mesh {
        val count = d.verts.size / STRIDE
        val vb = VertexBuffer.Builder().bufferCount(1).vertexCount(count)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, STRIDE * 4)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT3, 12, STRIDE * 4)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 24, STRIDE * 4)
            .build(e)
        val fb = ByteBuffer.allocateDirect(d.verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(d.verts).also { it.flip() }
        vb.setBufferAt(e, 0, fb)
        val sb = ByteBuffer.allocateDirect(d.idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(d.idx).also { it.flip() }
        val ib = IndexBuffer.Builder().indexCount(d.idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e)
        ib.setBuffer(e, sb)
        return Mesh(vb, ib, d.idx.size, d.box).also { it.keep.add(fb); it.keep.add(sb) }
    }
}
