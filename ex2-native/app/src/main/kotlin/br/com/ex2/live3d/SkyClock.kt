package br.com.ex2.live3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hora do dia → céu e luz.
 * Amanhecer = horário em que já é dia pleno; Anoitecer = horário em que já é noite plena.
 * A transição (noite → dourado → dia, e o inverso) acontece nos [TRANSITION] minutos ANTES.
 */
object SkyClock {
    const val TRANSITION = 70

    class Weather(val id: String, val label: String, val cover: Float, val rain: Float, val storm: Float)

    val weathers = listOf(
        Weather("sol", "Sol", .14f, 0f, 0f),
        Weather("nublado", "Nublado", .76f, 0f, 0f),
        Weather("chuva", "Chuva", .90f, 1f, .12f),
        Weather("tempestade", "Tempestade", .98f, .72f, 1f)
    )

    fun weather(id: String?) = weathers.firstOrNull { it.id == id } ?: weathers[0]

    /** fotos do céu por clima: [noite, dourado, dia] */
    private fun images(w: String) = when (w) {
        "sol" -> arrayOf("night", "golden", "day")
        "tempestade" -> arrayOf("night-cloudy", "golden-cloudy", "storm")
        else -> arrayOf("night-cloudy", "golden-cloudy", "day-cloudy")
    }

    class State(
        val imageA: String, val imageB: String, val mix: Float,
        val day: Float,          // 0 noite … 1 dia
        val golden: Float,       // quanto de luz dourada
        val sunDir: FloatArray,  // direção PARA onde a luz vai (Filament)
        val sunColor: FloatArray,
        val sunIntensity: Float,
        val iblIntensity: Float,
        val weather: Weather,
        val skySun: FloatArray = floatArrayOf(0f, .3f, -1f),   // onde o sol (ou a lua) aparece no céu
        val isDay: Boolean = true
    )

    private fun mod(a: Int) = ((a % 1440) + 1440) % 1440

    /** estágio 0 (noite) … 2 (dia) e fase do sol 0..1 enquanto ele está no céu */
    fun stage(minute: Float, dawn: Int, dusk: Int): Pair<Float, Float> {
        val L = TRANSITION
        val start = dawn - L
        val rel = ((minute - start) % 1440f + 1440f) % 1440f
        val dayLen = mod(dusk - L - dawn).toFloat()
        val s = when {
            rel < L -> 2f * rel / L
            rel < L + dayLen -> 2f
            rel < 2 * L + dayLen -> 2f * (1f - (rel - L - dayLen) / L)
            else -> 0f
        }
        // sol "nasce" no meio da transição do amanhecer e "se põe" no meio da do anoitecer
        val sunUp = L / 2f
        val sunDown = L + dayLen + L / 2f
        val phase = if (rel in sunUp..sunDown) (rel - sunUp) / (sunDown - sunUp) else -1f
        return s to phase
    }

    fun compute(minute: Float, dawn: Int, dusk: Int, weatherId: String): State {
        val w = weather(weatherId)
        val (s, phase) = stage(minute, dawn, dusk)
        val imgs = images(w.id)
        val i0 = min(1, floor(s).toInt())
        val f = s - i0
        val day = if (s <= 1f) .42f * s else .42f + .58f * (s - 1f)
        val golden = 1f - kotlin.math.abs(s - 1f)

        val sun = FloatArray(3)
        val color: FloatArray
        val intensity: Float
        if (phase >= 0f) {
            val e = sin(PI * phase).toFloat()
            val az = -1.75 + phase * 1.25
            val y = max(.05f, e) * .55f
            val h = sqrt(1 - y * y)
            // vetor de onde vem o sol; o Filament quer a direção da luz (oposta)
            sun[0] = -(sin(az) * h).toFloat(); sun[1] = -y; sun[2] = (cos(az) * h).toFloat()
            val g = golden.coerceIn(0f, 1f) * (1 - .8f * w.cover)
            color = mixc(floatArrayOf(1f, .965f, .86f), floatArrayOf(1f, .62f, .31f), g * .92f)
            val key = (.22f + .78f * day) * (1 - .34f * w.cover) * (1 - .18f * w.storm)
            intensity = 1.2f * (.30f + .95f * key)
        } else {
            sun[0] = -.35f; sun[1] = -.62f; sun[2] = .45f   // luar
            color = floatArrayOf(.47f, .58f, .80f)
            intensity = .32f * (1 - .5f * w.cover)
        }
        val n = sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2])
        for (k in 0..2) sun[k] /= n
        val ibl = (.30f + .62f * day) * (1 - .25f * w.cover)
        // disco do sol no céu (como no site: baixo, atravessando de um lado ao outro); à noite, a lua
        val sky: FloatArray
        if (phase >= 0f) {
            val e = sin(PI * phase).toFloat()
            val az = -1.75 + phase * 1.25
            val y = max(.015f, e) * .38f
            val h = sqrt(1 - y * y)
            sky = floatArrayOf((sin(az) * h).toFloat(), y, (-cos(az) * h).toFloat())
        } else {
            val l = sqrt(.45f * .45f + .30f * .30f + .84f * .84f)
            sky = floatArrayOf(.45f / l, .30f / l, -.84f / l)
        }
        return State(imgs[i0], imgs[min(2, i0 + 1)], f, day, golden, sun, color, intensity, ibl, w, sky, phase >= 0f)
    }

    private fun mixc(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }

    fun hhmm(min: Int): String { val m = mod(min); return "%02d:%02d".format(m / 60, m % 60) }
}
