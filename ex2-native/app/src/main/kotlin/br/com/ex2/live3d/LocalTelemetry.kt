package br.com.ex2.live3d

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fonte EXTRA de sinais: alguns apps da comunidade EX2 deixam na central um serviço local
 * (http://127.0.0.1:8888/api/energy) com velocidade, marcha, volante, portas e luzes — inclusive
 * farol baixo, lanterna e freio. Se ele existir no carro, o app usa; se não existir, nada muda
 * (consulta leve, de 5 em 5 s, até aparecer). Formato esperado (pelo que esses apps leem):
 *   speed_kmh, gear "P|R|N|D", doors{fl,fr,rl,rr,trunk,frunk}, lights{left,right,dipped,side,brake,lever},
 *   steer{raw,dir}  (raw = volante em décimos de grau, 16 bits com sinal; dir = 1 para um dos lados)
 */
object LocalTelemetry {
    const val URL_ENERGY = "http://127.0.0.1:8888/api/energy"

    class Data(
        val speedKmh: Float?, val gear: String?, val wheelDeg: Float?,
        val doors: Map<String, Boolean>, val left: Boolean?, val right: Boolean?, val dipped: Boolean?,
        val side: Boolean?, val brake: Boolean?
    )

    @Volatile var last: Data? = null; private set
    @Volatile var status = "não consultado"; private set
    @Volatile var found = false; private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Data) -> Unit>()

    fun addListener(l: (Data) -> Unit) { listeners.add(l); start() }
    fun removeListener(l: (Data) -> Unit) { listeners.remove(l); if (listeners.isEmpty()) stop() }

    @Synchronized private fun start() {
        if (thread != null) return
        val t = HandlerThread("ex2-telemetria").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { it.post(tick) }
    }

    @Synchronized private fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely(); thread = null; handler = null
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val d = try { fetch() } catch (t: Throwable) {
                if (found) Diag.log("Serviço 8888: parou de responder ($t)")
                found = false; status = "não encontrado (${t.javaClass.simpleName})"; null
            }
            if (d != null) {
                if (!found) Diag.log("Serviço 8888: encontrado — velocidade, volante, farol baixo e freio disponíveis")
                found = true; status = "encontrado"; last = d
                main.post { listeners.forEach { it(d) } }
            }
            // achou: 8 consultas por segundo (como o app que o usa); não achou: tenta de 5 em 5 s
            handler?.postDelayed(this, if (found) 125L else 5000L)
        }
    }

    private fun fetch(): Data {
        val c = URL(URL_ENERGY).openConnection() as HttpURLConnection
        c.connectTimeout = 300; c.readTimeout = 400; c.useCaches = false
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            fun b(o: org.json.JSONObject?, k: String): Boolean? = o?.opt(k)?.let {
                when (it) { is Boolean -> it; is Number -> it.toInt() != 0; is String -> it == "1" || it.equals("true", true) || it.equals("on", true); else -> null }
            }
            val doors = HashMap<String, Boolean>()
            j.optJSONObject("doors")?.let { o ->
                mapOf("fl" to Openings.FL, "fr" to Openings.FR, "rl" to Openings.RL, "rr" to Openings.RR,
                    "trunk" to Openings.TAILGATE, "frunk" to Openings.FRUNK).forEach { (k, name) -> b(o, k)?.let { doors[name] = it } }
            }
            val l = j.optJSONObject("lights")
            val steer = j.optJSONObject("steer")
            val wheel = steer?.let { s ->
                if (!s.has("raw")) null else {
                    var raw = s.optInt("raw")
                    if (raw > 32767) raw -= 65536                     // 16 bits com sinal
                    var deg = Math.abs(raw) * .1f                      // ângulo do volante
                    if (deg > 720f || deg < 1f) deg = 0f
                    if (s.optInt("dir") == 1) deg = -deg
                    (deg / 15.5f).coerceIn(-35f, 35f)                  // relação de direção → ângulo das rodas
                }
            }
            return Data(
                speedKmh = if (j.has("speed_kmh")) j.optDouble("speed_kmh").toFloat() else null,
                gear = j.optString("gear", "").ifEmpty { null },
                wheelDeg = wheel, doors = doors,
                left = b(l, "left"), right = b(l, "right"), dipped = b(l, "dipped"), side = b(l, "side"), brake = b(l, "brake")
            )
        } finally { c.disconnect() }
    }
}
