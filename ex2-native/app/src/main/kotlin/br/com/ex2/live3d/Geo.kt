package br.com.ex2.live3d

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Malhas simples da cena (quadros, esfera do céu, triângulo de tela cheia). */
class Mesh(val vb: VertexBuffer, val ib: IndexBuffer, val count: Int, val box: Box) {
    /** dados enviados à placa de vídeo: guardados enquanto a malha existir (a cópia é feita depois,
        e se o Java liberar a memória antes a malha sai deformada) */
    val keep = ArrayList<Any>()
    fun destroy(e: Engine) { e.destroyVertexBuffer(vb); e.destroyIndexBuffer(ib) }
}

object Geo {
    private fun floats(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(a).also { it.flip() }

    private fun shorts(a: ShortArray) = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder())
        .asShortBuffer().put(a).also { it.flip() }

    private fun build(e: Engine, data: FloatArray, idx: ShortArray, withUv: Boolean, box: Box): Mesh {
        val stride = if (withUv) 20 else 12
        val b = VertexBuffer.Builder().bufferCount(1).vertexCount(data.size * 4 / stride)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, stride)
        if (withUv) b.attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, stride)
        val vb = b.build(e)
        val fb = floats(data); val sb = shorts(idx)
        vb.setBufferAt(e, 0, fb)
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e)
        ib.setBuffer(e, sb)
        return Mesh(vb, ib, idx.size, box).also { it.keep.add(fb); it.keep.add(sb) }
    }

    /** malha só com posições (x, y, z por vértice) e triângulos */
    fun mesh(e: Engine, pos: FloatArray, idx: ShortArray): Mesh {
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        for (i in pos.indices) { val k = i % 3; mn[k] = minOf(mn[k], pos[i]); mx[k] = maxOf(mx[k], pos[i]) }
        val box = Box((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2,
            (mx[0] - mn[0]) / 2 + .01f, (mx[1] - mn[1]) / 2 + .01f, (mx[2] - mn[2]) / 2 + .01f)
        return build(e, pos, idx, false, box)
    }

    /** malha com posição + UV por vértice (x, y, z, u, v) e triângulos */
    fun meshUv(e: Engine, data: FloatArray, idx: ShortArray): Mesh {
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        for (v in 0 until data.size / 5) for (k in 0..2) { mn[k] = minOf(mn[k], data[v * 5 + k]); mx[k] = maxOf(mx[k], data[v * 5 + k]) }
        val box = Box((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2,
            (mx[0] - mn[0]) / 2 + .01f, (mx[1] - mn[1]) / 2 + .01f, (mx[2] - mn[2]) / 2 + .01f)
        return build(e, data, idx, true, box)
    }

    /** quadro unitário no plano XY, centrado na origem; v = 0 em cima */
    fun quad(e: Engine) = build(
        e,
        floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 1f,
            0.5f, -0.5f, 0f, 1f, 1f,
            0.5f, 0.5f, 0f, 1f, 0f,
            -0.5f, 0.5f, 0f, 0f, 0f
        ),
        shortArrayOf(0, 1, 2, 0, 2, 3), true, Box(0f, 0f, 0f, 0.5f, 0.5f, 0.01f)
    )

    /** quadro com cantos dados (4 pontos em ordem: baixo-esq, baixo-dir, cima-dir, cima-esq) */
    fun panel(e: Engine, p: Array<FloatArray>): Mesh {
        val d = FloatArray(20)
        val uv = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
        var mn = floatArrayOf(1e9f, 1e9f, 1e9f); var mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        for (i in 0 until 4) {
            for (k in 0 until 3) { d[i * 5 + k] = p[i][k]; mn[k] = minOf(mn[k], p[i][k]); mx[k] = maxOf(mx[k], p[i][k]) }
            d[i * 5 + 3] = uv[i][0]; d[i * 5 + 4] = uv[i][1]
        }
        val box = Box((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2,
            (mx[0] - mn[0]) / 2 + 0.01f, (mx[1] - mn[1]) / 2 + 0.01f, (mx[2] - mn[2]) / 2 + 0.01f)
        return build(e, d, shortArrayOf(0, 1, 2, 0, 2, 3), true, box)
    }

    /** polígono convexo (pontos em ordem anti-horária vistos da frente) com UVs dadas */
    fun polygon(e: Engine, p: List<FloatArray>, uv: List<FloatArray>): Mesh {
        val d = FloatArray(p.size * 5)
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        p.forEachIndexed { i, v ->
            for (k in 0..2) { d[i * 5 + k] = v[k]; mn[k] = minOf(mn[k], v[k]); mx[k] = maxOf(mx[k], v[k]) }
            d[i * 5 + 3] = uv[i][0]; d[i * 5 + 4] = uv[i][1]
        }
        val idx = ShortArray((p.size - 2) * 3)
        for (i in 1 until p.size - 1) { idx[(i - 1) * 3] = 0; idx[(i - 1) * 3 + 1] = i.toShort(); idx[(i - 1) * 3 + 2] = (i + 1).toShort() }
        val box = Box((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2,
            (mx[0] - mn[0]) / 2 + .01f, (mx[1] - mn[1]) / 2 + .01f, (mx[2] - mn[2]) / 2 + .01f)
        return build(e, d, idx, true, box)
    }

    /** plano horizontal (chão) em y, quadrado de lado 2r, em grade para a sombra de contato */
    fun ground(e: Engine, y: Float, r: Float, tangents: Boolean = false): Mesh {
        val n = 24
        val d = FloatArray((n + 1) * (n + 1) * 3)
        var i = 0
        for (j in 0..n) for (k in 0..n) {
            // grade mais densa perto do carro
            fun s(t: Float): Float { val u = t * 2 - 1; return Math.signum(u) * u * u * r }
            d[i++] = s(k / n.toFloat()); d[i++] = y; d[i++] = s(j / n.toFloat())
        }
        val idx = ShortArray(n * n * 6)
        i = 0
        for (j in 0 until n) for (k in 0 until n) {
            val a = (j * (n + 1) + k).toShort(); val b = (a + 1).toShort()
            val c = (a + n + 1).toShort(); val dd = (c + 1).toShort()
            idx[i++] = a; idx[i++] = c; idx[i++] = b; idx[i++] = b; idx[i++] = c; idx[i++] = dd
        }
        // a grade saía deformada no emulador: para o chão basta um retângulo (efeito é por ponto)
        if (!tangents) return build(e, floatArrayOf(-r, y, -r, r, y, -r, r, y, r, -r, y, r), shortArrayOf(0, 2, 1, 0, 3, 2), false, Box(0f, y, 0f, r, 0.05f, r))
        // camada que recebe sombra: precisa da orientação (normal para cima) em cada vértice
        val quad = floatArrayOf(-r, y, -r, r, y, -r, r, y, r, -r, y, r)
        val quadIdx = shortArrayOf(0, 2, 1, 0, 3, 2)
        val count = 4
        val vb = VertexBuffer.Builder().bufferCount(2).vertexCount(count)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(e)
        val pb = floats(quad)
        vb.setBufferAt(e, 0, pb)
        val q = FloatArray(count * 4)
        for (k in 0 until count) { q[k * 4] = -0.70710677f; q[k * 4 + 3] = 0.70710677f }   // gira +Z para +Y
        val tb = floats(q)
        vb.setBufferAt(e, 1, tb)
        val ib = IndexBuffer.Builder().indexCount(quadIdx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e)
        val sb = shorts(quadIdx)
        ib.setBuffer(e, sb)
        return Mesh(vb, ib, quadIdx.size, Box(0f, y, 0f, r, 0.05f, r)).also { it.keep.addAll(listOf(pb, tb, sb)) }
    }

    fun sphere(e: Engine, radius: Float): Mesh {
        val seg = 40; val rings = 20
        val d = FloatArray((seg + 1) * (rings + 1) * 3)
        var i = 0
        for (r in 0..rings) {
            val phi = Math.PI * r / rings
            for (s in 0..seg) {
                val th = 2 * Math.PI * s / seg
                d[i++] = (radius * Math.sin(phi) * Math.cos(th)).toFloat()
                d[i++] = (radius * Math.cos(phi)).toFloat()
                d[i++] = (radius * Math.sin(phi) * Math.sin(th)).toFloat()
            }
        }
        val idx = ShortArray(seg * rings * 6)
        i = 0
        for (r in 0 until rings) for (s in 0 until seg) {
            val a = (r * (seg + 1) + s).toShort(); val b = (a + 1).toShort()
            val c = (a + seg + 1).toShort(); val dd = (c + 1).toShort()
            idx[i++] = a; idx[i++] = c; idx[i++] = b; idx[i++] = b; idx[i++] = c; idx[i++] = dd
        }
        return build(e, d, idx, false, Box(0f, 0f, 0f, radius, radius, radius))
    }

    /** triângulo que cobre a tela (domínio DEVICE) */
    fun screen(e: Engine) = build(
        e, floatArrayOf(-1f, -1f, 1f, 3f, -1f, 1f, -1f, 3f, 1f), shortArrayOf(0, 1, 2), false,
        Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f)
    )

    /**
     * Cone do feixe do farol (coordenadas do mundo): sai de [s] para a frente (+Z), levemente para
     * baixo, abrindo até ~1,2 m de raio a 5,5 m; mais largo que alto. Por vértice: posição e
     * (normal do cone, t = 0 no farol … 1 na ponta).
     */
    fun beamCone(e: Engine, s: FloatArray, len: Float = 5.5f, slope: Float = -.09f, r1: Float = 1.2f, flat: Float = .55f): Mesh {
        val seg = 20; val rings = 10; val r0 = .07f
        var ax = 0f; var ay = slope; var az = 1f
        val al = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat(); ax /= al; ay /= al; az /= al
        // b1 = lateral (X); b2 = a × b1 (para cima, perpendicular ao eixo)
        val b1 = floatArrayOf(1f, 0f, 0f)
        val b2 = floatArrayOf(0f, az, -ay)
        val n = (rings + 1) * (seg + 1)
        val d = FloatArray(n * 7)
        var k = 0
        val mn = floatArrayOf(1e9f, 1e9f, 1e9f); val mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        for (j in 0..rings) {
            val t = j.toFloat() / rings
            val r = r0 + (r1 - r0) * t
            for (i in 0..seg) {
                val th = 2 * Math.PI * i / seg
                val c = Math.cos(th).toFloat(); val sn = Math.sin(th).toFloat()
                val dx = b1[0] * c + b2[0] * sn * flat; val dy = b1[1] * c + b2[1] * sn * flat; val dz = b1[2] * c + b2[2] * sn * flat
                val p = floatArrayOf(s[0] + ax * len * t + dx * r, s[1] + ay * len * t + dy * r, s[2] + az * len * t + dz * r)
                for (q in 0..2) { mn[q] = minOf(mn[q], p[q]); mx[q] = maxOf(mx[q], p[q]) }
                d[k++] = p[0]; d[k++] = p[1]; d[k++] = p[2]
                // normal "para fora" do cone (achatado: a normal compensa o achatamento)
                var nx = b1[0] * c + b2[0] * sn / flat; var ny = b1[1] * c + b2[1] * sn / flat; var nz = b1[2] * c + b2[2] * sn / flat
                val nl = Math.sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat(); nx /= nl; ny /= nl; nz /= nl
                d[k++] = nx; d[k++] = ny; d[k++] = nz; d[k++] = t
            }
        }
        val idx = ShortArray(rings * seg * 6)
        k = 0
        for (j in 0 until rings) for (i in 0 until seg) {
            val a = (j * (seg + 1) + i).toShort(); val b = (a + 1).toShort()
            val c = (a + seg + 1).toShort(); val dd = (c + 1).toShort()
            idx[k++] = a; idx[k++] = c; idx[k++] = b; idx[k++] = b; idx[k++] = c; idx[k++] = dd
        }
        val vb = VertexBuffer.Builder().bufferCount(1).vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 28)
            .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 12, 28)
            .build(e)
        val fb = floats(d)
        vb.setBufferAt(e, 0, fb)
        val sb = shorts(idx)
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(e)
        ib.setBuffer(e, sb)
        val box = Box((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2,
            (mx[0] - mn[0]) / 2 + .05f, (mx[1] - mn[1]) / 2 + .05f, (mx[2] - mn[2]) / 2 + .05f)
        return Mesh(vb, ib, idx.size, box).also { it.keep.add(fb); it.keep.add(sb) }
    }

    fun renderable(e: Engine, mesh: Mesh, mi: MaterialInstance, priority: Int = 4, shadows: Boolean = false): Int {
        val ent = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(mesh.box)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vb, mesh.ib, 0, mesh.count)
            .material(0, mi)
            .priority(priority)
            .castShadows(false)
            .receiveShadows(shadows)
            .culling(false)
            .build(e, ent)
        return ent
    }
}
