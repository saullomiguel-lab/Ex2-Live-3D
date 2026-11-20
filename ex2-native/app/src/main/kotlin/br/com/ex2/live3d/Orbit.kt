package br.com.ex2.live3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Câmera orbitando o carro: arrasto com inércia, pinça para zoom, voo suave entre vistas. */
class Orbit {
    class Pose(val yaw: Float, val pitch: Float, val dist: Float, val tx: Float, val ty: Float, val tz: Float) {
        fun encode() = "%.4f,%.4f,%.3f,%.3f,%.3f,%.3f".format(java.util.Locale.US, yaw, pitch, dist, tx, ty, tz)
        companion object {
            fun from(pos: FloatArray, target: FloatArray): Pose {
                val dx = pos[0] - target[0]; val dy = pos[1] - target[1]; val dz = pos[2] - target[2]
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                return Pose(atan2(dx, dz), asin(dy / d), d, target[0], target[1], target[2])
            }
            fun decode(s: String?): Pose? = s?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == 6 }?.let { Pose(it[0], it[1], it[2], it[3], it[4], it[5]) }
        }
    }

    var yaw = 0f; var pitch = 0f; var dist = 7f
    var tx = 0f; var ty = .72f; var tz = .15f
    private var gYaw = 0f; private var gPitch = 0f; private var gDist = 7f
    private var gtx = 0f; private var gty = .72f; private var gtz = .15f
    private var vYaw = 0f; private var vPitch = 0f
    private var ease = 10f

    var minPitch = .01f
    var maxPitch = (88 * PI / 180).toFloat()
    var minDist = 2.2f
    var maxDist = 11f

    fun pose() = Pose(gYaw, gPitch, gDist, gtx, gty, gtz)

    fun set(p: Pose, animate: Boolean) {
        // menor caminho ao girar
        var y = p.yaw
        while (y - yaw > PI) y -= (2 * PI).toFloat()
        while (y - yaw < -PI) y += (2 * PI).toFloat()
        gYaw = y; gPitch = p.pitch.coerceIn(minPitch, maxPitch); gDist = p.dist.coerceIn(minDist, maxDist)
        gtx = p.tx; gty = p.ty; gtz = p.tz
        vYaw = 0f; vPitch = 0f
        ease = if (animate) 3.2f else 10f
        if (!animate) { yaw = gYaw; pitch = gPitch; dist = gDist; tx = gtx; ty = gty; tz = gtz }
    }

    /** arrasto em pixels; sensibilidade: largura da tela ≈ 1,6 volta… meia volta */
    fun drag(dx: Float, dy: Float, width: Int) {
        val k = (PI * 1.4 / width).toFloat()
        gYaw -= dx * k
        gPitch = (gPitch + dy * k).coerceIn(minPitch, maxPitch)
        vYaw = 0f; vPitch = 0f; ease = 14f
    }

    fun fling(vx: Float, vy: Float, width: Int) {
        val k = (PI * 1.4 / width).toFloat()
        vYaw = -vx * k; vPitch = vy * k
    }

    fun stop() { vYaw = 0f; vPitch = 0f }

    fun zoom(factor: Float) { gDist = (gDist / factor).coerceIn(minDist, maxDist); ease = 14f }

    /** @return true enquanto ainda se move */
    fun step(dt: Float): Boolean {
        if (abs(vYaw) > 1e-3f || abs(vPitch) > 1e-3f) {
            gYaw += vYaw * dt
            gPitch = (gPitch + vPitch * dt).coerceIn(minPitch, maxPitch)
            val decay = exp(-dt * 3.2f)
            vYaw *= decay; vPitch *= decay
        } else { vYaw = 0f; vPitch = 0f }
        val a = 1f - exp(-dt * ease)
        yaw += (gYaw - yaw) * a; pitch += (gPitch - pitch) * a; dist += (gDist - dist) * a
        tx += (gtx - tx) * a; ty += (gty - ty) * a; tz += (gtz - tz) * a
        val moving = abs(gYaw - yaw) > 1e-4f || abs(gPitch - pitch) > 1e-4f || abs(gDist - dist) > 1e-4f ||
            abs(gtx - tx) > 1e-4f || abs(gty - ty) > 1e-4f || abs(gtz - tz) > 1e-4f || vYaw != 0f || vPitch != 0f
        if (!moving) { yaw = gYaw; pitch = gPitch; dist = gDist; tx = gtx; ty = gty; tz = gtz }
        return moving
    }

    fun eye() = doubleArrayOf(
        (tx + dist * cos(pitch) * sin(yaw)).toDouble(),
        (ty + dist * sin(pitch)).toDouble(),
        (tz + dist * cos(pitch) * cos(yaw)).toDouble()
    )

    companion object {
        /** vistas do configurador (posição, alvo) */
        val VIEWS = linkedMapOf(
            "front-34" to Pose.from(floatArrayOf(4.5f, .82f, 5.6f), floatArrayOf(0f, .72f, .15f)),
            "front" to Pose.from(floatArrayOf(0f, .78f, 7.4f), floatArrayOf(0f, .70f, .13f)),
            "side" to Pose.from(floatArrayOf(7.6f, .78f, .13f), floatArrayOf(0f, .70f, .13f)),
            "rear-34" to Pose.from(floatArrayOf(4.3f, .88f, -5.4f), floatArrayOf(0f, .72f, .05f)),
            // de cima, frente do carro para o alto da tela
            "top" to Pose(PI.toFloat(), (86 * PI / 180).toFloat(), 9f, 0f, 0f, .13f)
        )
        /** vista que mostra bem uma porta/tampa aberta (esquerda do carro = +x) */
        fun forOpening(name: String): Pose? = when (name) {
            Openings.FL -> Pose.from(floatArrayOf(5.6f, 1.25f, 3.4f), floatArrayOf(0f, .75f, .35f))
            Openings.RL -> Pose.from(floatArrayOf(5.8f, 1.25f, -2.4f), floatArrayOf(0f, .75f, -.35f))
            Openings.FR -> Pose.from(floatArrayOf(-5.6f, 1.25f, 3.4f), floatArrayOf(0f, .75f, .35f))
            Openings.RR -> Pose.from(floatArrayOf(-5.8f, 1.25f, -2.4f), floatArrayOf(0f, .75f, -.35f))
            Openings.TAILGATE -> Pose.from(floatArrayOf(3.2f, 1.7f, -5.6f), floatArrayOf(0f, .85f, -.6f))
            Openings.FRUNK -> Pose.from(floatArrayOf(3.0f, 1.9f, 5.6f), floatArrayOf(0f, .7f, .9f))
            else -> null
        }

        /** papel de parede: 3/4 de frente, carro mais baixo na tela (alvo acima do carro) */
        val WALLPAPER = Pose.from(floatArrayOf(4.9f, 1.25f, 6.1f), floatArrayOf(0f, 1.05f, .15f))
        /** carro andando: o mesmo enquadramento, visto por trás na diagonal */
        val WALLPAPER_DRIVE = Pose.from(floatArrayOf(4.9f, 1.25f, -6.1f), floatArrayOf(0f, 1.05f, -.05f))
    }
}
