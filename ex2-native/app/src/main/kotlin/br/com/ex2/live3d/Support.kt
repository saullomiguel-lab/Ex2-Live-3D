package br.com.ex2.live3d

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.sin

/**
 * Registro de diagnóstico: últimas linhas em memória e, quando ligado, arquivo do VHAL.
 * A gravação em arquivo acontece numa linha de execução própria, em lotes (antes cada linha abria e
 * fechava o arquivo na thread principal — com a velocidade chegando ~10 vezes por segundo isso
 * engasgava o 3D). O arquivo é limitado: passou de 1,5 MB, recomeça (o anterior fica como .old).
 */
object Diag {
    private const val TAG = "EX2Live3D"
    private const val MAX_BYTES = 1_500_000L
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var vhalFile: File? = null
    private var logFile: File? = null
    private val queue = java.util.concurrent.LinkedBlockingQueue<Pair<File, String>>()
    private val writer = Thread({
        while (true) {
            try {
                val first = queue.take()
                val batch = ArrayList<Pair<File, String>>()
                batch.add(first)
                // junta o que chegar nos próximos instantes: uma escrita por arquivo
                Thread.sleep(400)
                queue.drainTo(batch)
                for ((f, group) in batch.groupBy({ it.first }, { it.second })) {
                    try {
                        if (f.length() > MAX_BYTES) {
                            val old = File(f.parentFile, f.name + ".old")
                            old.delete(); f.renameTo(old)
                        }
                        java.io.FileOutputStream(f, true).bufferedWriter().use { w -> for (s in group) w.write(s) }
                    } catch (_: Exception) { }
                }
            } catch (_: InterruptedException) { return@Thread } catch (_: Throwable) { }
        }
    }, "ex2-diag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    /** registro também em arquivo (sobrevive a um fechamento inesperado) */
    @Synchronized fun init(c: Context) {
        val dir = c.getExternalFilesDir(null) ?: c.filesDir
        val f = File(dir, "diag.txt")
        if (f.isFile) f.renameTo(File(dir, "diag-anterior.txt"))
        logFile = f
    }

    fun dir(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir

    @Synchronized fun log(msg: String) {
        Log.i(TAG, msg)
        val line = time.format(Date()) + "  " + msg
        lines.addLast(line)
        while (lines.size > 250) lines.removeFirst()
        logFile?.let { queue.offer(it to line + "\n") }
    }

    /** sinais que mudam muitas vezes por segundo: no máximo uma linha a cada [everyMs] por chave */
    private val lastAt = HashMap<String, Long>()
    @Synchronized fun logThrottled(key: String, everyMs: Long, msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (lastAt[key] ?: 0L) < everyMs) return
        lastAt[key] = now
        log(msg)
    }

    @Synchronized fun dump() = lines.joinToString("\n")

    /** memória usada agora (MB): nativa (Filament, imagens) e Java */
    fun mem(): String {
        val nat = android.os.Debug.getNativeHeapAllocatedSize() / 1048576
        val rt = Runtime.getRuntime()
        val java = (rt.totalMemory() - rt.freeMemory()) / 1048576
        return "memória nativa $nat MB, Java $java MB"
    }

    /** Android/data/br.com.ex2.live3d/files/vhal-log.txt (não precisa de permissão) */
    @Synchronized fun vhalFile(c: Context): File =
        vhalFile ?: File(c.getExternalFilesDir(null) ?: c.filesDir, "vhal-log.txt").also { vhalFile = it }

    @Synchronized fun vhal(c: Context, msg: String) {
        queue.offer(vhalFile(c) to time.format(Date()) + "  " + msg + "\n")
    }
}

/**
 * Monitor de erros: cada parte que pode falhar roda protegida. Um erro é registrado (uma vez por
 * tipo, com o caminho do erro) e a parte segue; se a mesma parte falhar muitas vezes seguidas, ela
 * descansa um pouco (não fica derrubando o resto) e volta sozinha depois.
 */
object Guard {
    private class Count(var n: Int, var since: Long, var pausedUntil: Long)
    private val counts = HashMap<String, Count>()
    private val seen = HashSet<String>()
    /** erros vistos nesta sessão (Diagnóstico) */
    var total = 0; private set

    /** roda [block]; erro = registra e devolve false (o chamador segue) */
    inline fun run(area: String, block: () -> Unit): Boolean {
        if (paused(area)) return false
        return try { block(); true } catch (t: Throwable) { failed(area, t); false }
    }

    @Synchronized fun paused(area: String): Boolean {
        val c = counts[area] ?: return false
        return android.os.SystemClock.elapsedRealtime() < c.pausedUntil
    }

    @Synchronized fun failed(area: String, t: Throwable) {
        total++
        val now = android.os.SystemClock.elapsedRealtime()
        val c = counts.getOrPut(area) { Count(0, now, 0L) }
        if (now - c.since > 60_000L) { c.n = 0; c.since = now }
        c.n++
        val sig = area + "|" + t.javaClass.name + "|" + (t.stackTrace.firstOrNull()?.toString() ?: "")
        if (seen.add(sig)) Diag.log("Monitor: erro em '$area' (corrigido, o app segue): ${br.com.ex2.live3d.Log.trace(t).take(600)}")
        if (c.n >= 6) {
            c.pausedUntil = now + 30_000L; c.n = 0; c.since = now
            Diag.log("Monitor: '$area' falhou várias vezes seguidas; pausada por 30 s e volta sozinha")
        }
    }
}

/** Preferências: o app e o papel de parede rodam no mesmo processo e leem daqui. */
object Prefs {
    const val TRIM = "trim"; const val PAINT = "paint"; const val PLATE = "plate"
    const val SETUP_DONE = "setupDone"
    const val ENABLED = "enabled"; const val ORIGINAL = "original"
    const val CAR_SYNC = "carSync"; const val AUTO_WEATHER = "autoWeather"; const val MANUAL_WEATHER = "manualWeather"
    const val AUTO_SUN = "autoSun"; const val DAWN = "dawn"; const val DUSK = "dusk"
    const val HOUR = "hour"                 // minutos fixos; -1 = segue o relógio
    const val TOUCH = "touch"; const val QUALITY = "quality"; const val REFLECT = "reflect"
    const val WALL_VIEW = "wallView"
    const val DIAG_RECORD = "diagRecord"; const val MAP = "map"
    const val LAT = "lat"; const val LON = "lon"; const val WX_CODE = "wxCode"; const val WX_AT = "wxAt"; const val WX_TEMP = "wxTemp"
    const val ATTEMPTS = "recoveryAttempts"
    // teste de desempenho (Ajustes): medidor na tela e partes que podem ser desligadas
    const val PERF_HUD = "perfHud"; const val T_MOUNTAINS = "tMountains"; const val T_MOUNT_MIRROR = "tMountMirror"
    const val T_CLOUDS = "tClouds"; const val T_DYNRES = "tDynRes"; const val T_MSAA = "tMsaa"
    /** cenário: "lago" (padrão) ou "estrada"; modo demonstração (carro andando sozinho) */
    const val SCENE = "scene"; const val DEMO = "demoDrive"
    const val OUT_TEMP = "outTemp"          // widget de temperatura externa (liga/desliga)
    const val AUTO_CAM = "autoCam"          // câmera automática: andando mostra o carro por trás
    const val WALL_FIXED = "wallFixed"; const val WALL_FIXED_POSE = "wallFixedPose"   // papel de parede: vista fixada pelo usuário
    const val IDLE_MIN = "idleMin"          // cenário automático: minutos parado para voltar à paisagem
    // saudação da tela inicial
    const val GREET_ON = "greetOn"; const val GREET_DRIVERS = "greetDrivers"; const val GREET_SECS = "greetSecs"
    const val GREET_NOW = "greetNow"         // muda = mostrar uma saudação nova agora (botão dos ajustes)
    const val GREET_LAST = "greetLast"; const val GREET_LAST_CREATIVE = "greetLastCreative"
    // 2ª saudação ("Hoje é dom. 15 de nov"): liga/desliga e segundos na tela (0 = sempre)
    const val GREET2_ON = "greet2On"; const val GREET2_SECS = "greet2Secs"
    /** cenário automático: segundos parado para voltar à paisagem (substitui IDLE_MIN) */
    const val IDLE_SECS = "idleSecs"
    // cidade embaixo da temperatura (a última conhecida fica guardada para quando não houver internet)
    const val CITY_ON = "cityOn"; const val CITY = "city"; const val CITY_AT = "cityAt"
    const val CITY_LAT = "cityLat"; const val CITY_LON = "cityLon"
    /** de onde veio a última posição: "gps" ou "internet" */
    const val LOC_SRC = "locSrc"
    /** previsão de 7 dias baixada (JSON), usada quando não há internet */
    const val WX_WEEK = "wxWeek"
    // widget de carregamento: liga/desliga, R$/kWh, mostrar o resumo ao terminar, fechar ao sair andando
    const val CHARGE_ON = "chargeOn"; const val CHARGE_PRICE = "chargePrice"
    const val CHARGE_SHOW_END = "chargeShowEnd"; const val CHARGE_AUTO_CLOSE = "chargeAutoClose"
    // sessão em andamento, resumo da última carga (aberto ou não) e hodômetro no fim dela
    const val CHARGE_SESSION = "chargeSession"; const val CHARGE_LAST = "chargeLast"
    const val CHARGE_LAST_OPEN = "chargeLastOpen"; const val CHARGE_ODO = "chargeOdo"
    /** o widget de carga apareceu: fica na tela até o usuário tocar em Fechar */
    const val CHARGE_PIN = "chargePin"
    // idioma ("auto" = o do carro), lado do motorista ("auto" | "left" | "right") e o que o carro informou
    const val LANG = "lang"; const val DRIVE_SIDE = "driveSide"; const val CAR_RHD = "carRhd"
    // atualizações pelo GitHub: buscar sozinho ao abrir, última consulta, versão que o usuário dispensou
    const val UPD_AUTO = "updAuto"; const val UPD_AT = "updAt"; const val UPD_SKIP = "updSkip"; const val UPD_INFO = "updInfo"
    // "Está chovendo aí?": chuva confirmada (enquanto a previsão der chuva) e chuva adiada até (ms)
    const val RAIN_OK = "rainOk"; const val RAIN_SNOOZE = "rainSnooze"; const val RAIN_ASK = "rainAsk"
    /** papel de parede: carro deslocado para o lado (fração da largura da tela; 0 = meio) */
    const val WALL_SHIFT = "wallShift"

    /** segundos parado para voltar à paisagem (lê o valor antigo em minutos, se houver) */
    fun idleSecs(c: Context) = get(c).getInt(IDLE_SECS, get(c).getInt(IDLE_MIN, 3) * 60)

    fun get(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("ex2live3d", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, def: Boolean) = get(c).getBoolean(k, def)
    fun str(c: Context, k: String, def: String) = get(c).getString(k, def) ?: def
    fun int(c: Context, k: String, def: Int) = get(c).getInt(k, def)

    fun carSync(c: Context) = bool(c, CAR_SYNC, true)
    fun autoWeather(c: Context) = bool(c, AUTO_WEATHER, true)
    fun touch(c: Context) = bool(c, TOUCH, true)

    /** resolução relativa por nível de qualidade */
    fun scale(c: Context) = when (str(c, QUALITY, "media")) { "alta" -> 1f; "economica" -> .62f; else -> .8f }
    fun shadows(c: Context) = str(c, QUALITY, "media") != "economica"
}

/** Nascer e pôr do sol (equação NOAA simplificada) em minutos locais. */
object SunCalc {
    fun riseSet(lat: Double, lon: Double, day: Calendar): IntArray {
        val noon = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val jd = noon.timeInMillis / 86400000.0 + 2440587.5
        val n = ceil(jd - 2451545.0 + 0.0008)
        val jStar = n - lon / 360.0
        val m = norm(357.5291 + 0.98560028 * jStar)
        val mr = Math.toRadians(m)
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = Math.toRadians(norm(m + c + 180 + 102.9372))
        val transit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.44)))
        val phi = Math.toRadians(lat)
        val cosW = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(decl)) / (Math.cos(phi) * Math.cos(decl))
        if (cosW < -1 || cosW > 1) return intArrayOf(360, 1080)
        val w = Math.toDegrees(acos(cosW))
        return intArrayOf(local(transit - w / 360.0, day.timeZone), local(transit + w / 360.0, day.timeZone))
    }

    private fun local(julian: Double, tz: TimeZone): Int {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = round((julian - 2440587.5) * 86400000.0).toLong()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun norm(d: Double): Double { val x = d % 360; return if (x < 0) x + 360 else x }
}
