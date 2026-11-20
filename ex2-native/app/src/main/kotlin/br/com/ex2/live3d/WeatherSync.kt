package br.com.ex2.live3d

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sol e clima automáticos.
 *  - Nascer/pôr do sol pela posição do GPS (sem internet), recalculados a cada hora.
 *  - Clima: Open-Meteo (gratuito, sem chave) a cada 30 min quando há rede; sem rede, mantém o
 *    último resultado por até 3 h. Com "clima automático" desligado, usa o clima escolhido no app.
 */
class WeatherSync private constructor(c: Context) {
    interface Listener { fun onSun(riseMin: Int, setMin: Int); fun onWeather(id: String) }

    private val ctx = c.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    var rise = 360; private set
    var set = 1080; private set
    var weather = "sol"; private set
    private var status = "aguardando"
    private var running = false

    fun status() = String.format(Locale.US, "%s · sol %s–%s · clima %s", status, SkyClock.hhmm(rise), SkyClock.hhmm(set), weather)

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onSun(rise, set); l.onWeather(weather)
        if (!running) { running = true; main.post(sunTask); main.post(wxTask) }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
        if (listeners.isEmpty()) { running = false; main.removeCallbacksAndMessages(null) }
    }

    /**
     * temperatura da internet (reserva quando o carro não informa): a atual se tiver menos de 3 h;
     * senão a da previsão da semana para esta hora; sem nada disso, null
     */
    fun tempC(): Float? {
        val sp = Prefs.get(ctx)
        if (sp.contains(Prefs.WX_TEMP) && System.currentTimeMillis() - sp.getLong(Prefs.WX_AT, 0) <= 3 * 3600 * 1000L) return sp.getFloat(Prefs.WX_TEMP, 0f)
        return hourNow()?.second
    }

    /* ---------- previsão da semana (baixada no clima automático; vale sem internet) ---------- */

    class Day(val start: Long, val code: Int, val min: Float, val max: Float)
    private var weekCache: Pair<String, JSONObject?>? = null

    private fun weekJson(): JSONObject? {
        val raw = Prefs.str(ctx, Prefs.WX_WEEK, "")
        weekCache?.let { if (it.first == raw) return it.second }
        val j = try { if (raw.isEmpty()) null else JSONObject(raw) } catch (_: Exception) { null }
        weekCache = raw to j
        return j
    }

    /** código e temperatura da previsão para a hora atual (null = sem previsão para agora) */
    fun hourNow(): Pair<Int, Float>? = try {
        val h = weekJson()?.getJSONObject("hourly")
        if (h == null) null else {
            val t = h.getJSONArray("time"); val now = System.currentTimeMillis() / 1000
            var hit: Pair<Int, Float>? = null
            for (i in 0 until t.length()) {
                val s = t.getLong(i)
                if (now >= s && now < s + 3600) { hit = h.getJSONArray("weather_code").getInt(i) to h.getJSONArray("temperature_2m").getDouble(i).toFloat(); break }
            }
            hit
        }
    } catch (_: Exception) { null }

    /** dias da previsão guardada, de hoje em diante (vazio = sem previsão) */
    fun week(): List<Day> = try {
        val d = weekJson()?.getJSONObject("daily")
        if (d == null) emptyList() else {
            val t = d.getJSONArray("time"); val today = System.currentTimeMillis() / 1000 - 86400
            (0 until t.length()).mapNotNull { i ->
                val s = t.getLong(i)
                if (s < today) null else Day(s * 1000, d.getJSONArray("weather_code").getInt(i),
                    d.getJSONArray("temperature_2m_min").getDouble(i).toFloat(), d.getJSONArray("temperature_2m_max").getDouble(i).toFloat())
            }
        }
    } catch (_: Exception) { emptyList() }

    /** quando a previsão foi baixada (0 = nunca) */
    fun weekAt(): Long = weekJson()?.optLong("at", 0L) ?: 0L

    /* ---------- cidade (nome embaixo da temperatura) ---------- */

    /** nome da cidade: o último que se conseguiu com internet (null = nunca conseguiu) */
    fun city(): String? = Prefs.str(ctx, Prefs.CITY, "").ifEmpty { null }

    /** opções mudaram no app: reaplica já */
    fun refresh() { main.removeCallbacks(wxTask); main.post(wxTask) }

    private val sunTask = object : Runnable {
        override fun run() {
            val p = position()
            val rs = SunCalc.riseSet(p[0], p[1], Calendar.getInstance())
            rise = rs[0]; set = rs[1]
            listeners.forEach { it.onSun(rise, set) }
            main.postDelayed(this, 60 * 60 * 1000L)
        }
    }

    private val wxTask = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (!Prefs.autoWeather(ctx)) {
                status = "clima manual"
                publish(Prefs.str(ctx, Prefs.MANUAL_WEATHER, "sol"))
                // a cidade embaixo da temperatura vale também com o clima manual
                if (online()) { val p0 = position(); Thread({ fetchCity(if (gpsFix) p0 else (ipLocate() ?: p0)) }, "ex2-cidade").start() }
                main.postDelayed(this, 60 * 60 * 1000L)
                return
            }
            val sp = Prefs.get(ctx)
            val fresh = System.currentTimeMillis() - sp.getLong(Prefs.WX_AT, 0) < 3 * 3600 * 1000L
            val forecast = hourNow()
            when {
                fresh -> publish(map(sp.getInt(Prefs.WX_CODE, 0)))
                // sem clima recente: a previsão da semana guardada para esta hora
                forecast != null -> publish(map(forecast.first))
                // acabaram os dados: volta ao padrão (sol)
                else -> publish("sol")
            }
            if (online()) fetch() else status = if (forecast != null) "sem internet: usando a previsão da semana" else "sem internet"
            // sem internet confere a cada 30 min; a previsão muda de hora em hora
            main.postDelayed(this, if (online()) 30 * 60 * 1000L else 15 * 60 * 1000L)
        }
    }

    private fun publish(id: String) { weather = id; listeners.forEach { it.onWeather(id) } }

    private fun fetch() {
        val p0 = position()
        Thread({
            // sem GPS: posição pela internet antes do clima (senão o clima e a cidade seriam de São Paulo)
            val p = if (gpsFix) p0 else (ipLocate() ?: p0)
            var c: HttpURLConnection? = null
            try {
                // agora + previsão de 7 dias (por hora e por dia): sem internet depois, o céu segue a previsão
                val u = URL(String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=weather_code,temperature_2m&hourly=weather_code,temperature_2m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=7&timeformat=unixtime&timezone=auto", p[0], p[1]))
                c = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
                val body = c.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                val cur = root.getJSONObject("current")
                val code = cur.getInt("weather_code")
                val ed = Prefs.get(ctx).edit().putInt(Prefs.WX_CODE, code).putLong(Prefs.WX_AT, System.currentTimeMillis())
                if (cur.has("temperature_2m")) ed.putFloat(Prefs.WX_TEMP, cur.getDouble("temperature_2m").toFloat())
                if (root.has("hourly") && root.has("daily")) {
                    val week = JSONObject().put("at", System.currentTimeMillis())
                        .put("hourly", root.getJSONObject("hourly")).put("daily", root.getJSONObject("daily"))
                    ed.putString(Prefs.WX_WEEK, week.toString())
                }
                ed.apply()
                main.post { status = "Open-Meteo código $code · previsão de 7 dias guardada"; if (Prefs.autoWeather(ctx)) publish(map(code)) }
            } catch (e: Exception) {
                status = "falha no clima: " + e.javaClass.simpleName
                Diag.log("Clima: $e")
            } finally { c?.disconnect() }
            fetchCity(p)
        }, "ex2-weather").start()
    }

    /**
     * Nome da cidade pela posição (com internet). Primeiro o Geocoder do Android; se a central não
     * tiver, o serviço gratuito BigDataCloud (só a posição arredondada, ~1 km). Só busca de novo se o
     * carro andou mais de 3 km ou passaram 6 h; o último nome fica guardado para quando não houver rede.
     */
    /** cidade achada pela internet (quando o GPS do carro não informa) */
    private var ipCity: String? = null

    /**
     * Sem GPS: posição aproximada pela conexão de internet (o serviço gratuito BigDataCloud, chamado
     * sem coordenadas, devolve a cidade e a posição da conexão). Antes o app ficava em São Paulo.
     * Roda numa linha de execução própria; null = não conseguiu.
     */
    private fun ipLocate(): DoubleArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("https://api.bigdatacloud.net/data/reverse-geocode-client?localityLanguage=pt").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 8000; readTimeout = 8000 }
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val lat = j.optDouble("latitude"); val lon = j.optDouble("longitude")
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) null else {
                ipCity = j.optString("city").ifBlank { j.optString("locality") }
                Prefs.get(ctx).edit().putFloat(Prefs.LAT, lat.toFloat()).putFloat(Prefs.LON, lon.toFloat()).putString(Prefs.LOC_SRC, "internet").apply()
                Diag.log("Posição pela internet: $ipCity")
                doubleArrayOf(lat, lon)
            }
        } catch (e: Exception) { Diag.log("Posição pela internet: $e"); null } finally { c?.disconnect() }
    }

    private fun fetchCity(p: DoubleArray) {
        val sp = Prefs.get(ctx)
        val lat0 = sp.getFloat(Prefs.CITY_LAT, 999f).toDouble(); val lon0 = sp.getFloat(Prefs.CITY_LON, 999f).toDouble()
        val moved = Math.abs(lat0 - p[0]) > .027 || Math.abs(lon0 - p[1]) > .027
        val saved = sp.getString(Prefs.CITY, "") ?: ""
        if (!moved && saved.isNotEmpty() && !saved.startsWith("Região", true) && System.currentTimeMillis() - sp.getLong(Prefs.CITY_AT, 0) < 6 * 3600 * 1000L) return
        // nome de região ("Região Metropolitana de …", "Microrregião …") não serve: tem que ser a cidade
        fun good(s: String?) = !s.isNullOrBlank() && !s.trim().startsWith("Região", true) && !s.trim().startsWith("Microrregião", true) &&
            !s.trim().startsWith("Mesorregião", true)
        var name: String? = if (!gpsFix) ipCity else null
        if (!good(name)) try {
            if (android.location.Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val a = android.location.Geocoder(ctx, Locale("pt", "BR")).getFromLocation(p[0], p[1], 1)?.firstOrNull()
                name = listOf(a?.locality, a?.subAdminArea).firstOrNull { good(it) }
            }
        } catch (_: Exception) { }
        if (!good(name)) {
            var c: HttpURLConnection? = null
            try {
                val u = URL(String.format(Locale.US, "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.2f&longitude=%.2f&localityLanguage=pt", p[0], p[1]))
                c = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                name = listOf(j.optString("city"), j.optString("locality")).firstOrNull { good(it) }
            } catch (e: Exception) { Diag.log("Cidade: $e") } finally { c?.disconnect() }
        }
        if (name == null || !good(name)) return
        sp.edit().putString(Prefs.CITY, name.trim()).putLong(Prefs.CITY_AT, System.currentTimeMillis())
            .putFloat(Prefs.CITY_LAT, p[0].toFloat()).putFloat(Prefs.CITY_LON, p[1].toFloat()).apply()
        Diag.log("Cidade: $name")
    }

    @Suppress("DEPRECATION")
    private fun online() = try {
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
    } catch (e: Exception) { false }

    /** última posição conhecida (GPS/rede); padrão: São Paulo */
    @SuppressLint("MissingPermission")
    private fun position(): DoubleArray {
        val sp = Prefs.get(ctx)
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (prov in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(prov) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (best != null) {
                sp.edit().putFloat(Prefs.LAT, best.latitude.toFloat()).putFloat(Prefs.LON, best.longitude.toFloat())
                    .putString(Prefs.LOC_SRC, "gps").apply()
                gpsFix = true
                return doubleArrayOf(best.latitude, best.longitude)
            }
        } catch (e: SecurityException) { status = "sem permissão de localização" } catch (_: Exception) { }
        gpsFix = false
        // sem GPS: a última posição conhecida (GPS ou pela internet); só no começo de tudo, São Paulo
        return doubleArrayOf(sp.getFloat(Prefs.LAT, -23.55f).toDouble(), sp.getFloat(Prefs.LON, -46.63f).toDouble())
    }

    /** a última posição veio do GPS do carro agora (senão: guardada, pela internet ou o padrão) */
    private var gpsFix = false

    companion object {
        @SuppressLint("StaticFieldLeak") private var instance: WeatherSync? = null
        @Synchronized fun get(c: Context) = instance ?: WeatherSync(c).also { instance = it }

        /** código WMO → clima da cena */
        fun map(code: Int) = when {
            code >= 95 -> "tempestade"
            code in 51..67 || code in 80..82 -> "chuva"
            code >= 2 -> "nublado"
            else -> "sol"
        }
    }
}
