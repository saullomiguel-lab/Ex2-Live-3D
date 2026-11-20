package br.com.ex2.live3d

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.Calendar

/**
 * Liga a cena às fontes de estado: preferências (carro, horário, clima), relógio do Android,
 * clima automático e VHAL. Usado pela tela do app e pelo papel de parede.
 */
private const val DRIVE_IDLE_MS = 30_000L

class SceneDriver(context: Context, val world: World) :
    WeatherSync.Listener, CarBridge.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var rise = 360
    private var set = 1080
    private var autoWeatherId = "sol"
    private var lastVehicle: Vehicle? = null
    private var carListening = false
    var onChange: (() -> Unit)? = null
    /** o carro acabou de ser ligado (saudação nova) */
    var onIgnitionOn: (() -> Unit)? = null

    fun start() {
        Prefs.get(ctx).registerOnSharedPreferenceChangeListener(this)
        WeatherSync.get(ctx).addListener(this)
        updateCarListening()
        applyConfig()
        applyReflection()
        applyTests()
        applyScene()
        applyEnv()
        main.post(clock)
        applyDemo()
        ChargeMonitor.get(ctx).start()
    }

    fun stop() {
        main.removeCallbacks(clock)
        main.removeCallbacks(demo); main.removeCallbacks(driveCheck); main.removeCallbacks(idleCheck)
        LocalTelemetry.removeListener(teleListener)
        Prefs.get(ctx).unregisterOnSharedPreferenceChangeListener(this)
        WeatherSync.get(ctx).removeListener(this)
        if (carListening) CarBridge.get(ctx).removeListener(this)
        carListening = false
        ChargeMonitor.get(ctx).stop()
    }

    private val clock = object : Runnable {
        override fun run() { applyEnv(); evalAuto(lastKmh, lastGear); main.postDelayed(this, 20_000) }
    }

    private fun updateCarListening() {
        val want = Prefs.carSync(ctx)
        if (want && !carListening) { CarBridge.get(ctx).addListener(this); LocalTelemetry.addListener(teleListener) }
        if (!want && carListening) { CarBridge.get(ctx).removeListener(this); LocalTelemetry.removeListener(teleListener) }
        carListening = want
    }

    /* ---------- serviço local 8888 (se existir no carro): luzes que faltam e movimento ---------- */

    private var tele: LocalTelemetry.Data? = null
    private val teleListener: (LocalTelemetry.Data) -> Unit = { d ->
        val prev = tele
        tele = d
        var s = world.lightState
        if (d.brake != null && prev?.brake != d.brake) s = s.copy(brake = d.brake)
        if (d.dipped != null && prev?.dipped != d.dipped) s = s.copy(farol = d.dipped)
        if (s != world.lightState) world.setLights(s)
        pushMotion()
        if (prev?.brake != d.brake || prev?.dipped != d.dipped) onChange?.invoke()
    }

    /** velocidade e volante: serviço 8888 → sinais do carro → parado (o modo demonstração tem prioridade) */
    private fun pushMotion() {
        if (Prefs.bool(ctx, Prefs.DEMO, false)) return
        val v = lastVehicle
        val kmh = tele?.speedKmh ?: v?.speedKmh ?: 0f
        val wheel = tele?.wheelDeg ?: v?.wheelDeg ?: 0f
        val gear = tele?.gear ?: v?.gear
        world.setMotion(if (gear == "R") -kmh else kmh, wheel)
        evalAuto(kmh, gear)
    }

    /* ---------- cenário automático: paisagem parado, estrada andando ---------- */

    private var autoRoad = false
    private var stoppedSince = 0L
    private val idleCheck = Runnable { evalAuto(lastKmh, lastGear) }
    private var lastKmh = 0f
    private var lastGear: String? = null
    /** teste no emulador: marcha simulada (adb … --es gear P) */
    var debugGear: String? = null

    /**
     * Andou (> 3 km/h) → estrada. Engatou P → paisagem na hora. Parado fora do P pelo tempo
     * escolhido nos ajustes (1, 3, 5 ou 10 min) → paisagem.
     */
    private fun evalAuto(kmh: Float, gearIn: String?) {
        val gear = debugGear ?: gearIn
        lastKmh = kmh; lastGear = gearIn
        evalDriving(kmh)
        if (Prefs.str(ctx, Prefs.SCENE, "auto") != "auto") return
        val now = android.os.SystemClock.elapsedRealtime()
        when {
            gear == "P" -> { stoppedSince = 0L; setAuto(false) }
            Math.abs(kmh) > 3f -> { stoppedSince = 0L; setAuto(true) }
            else -> {
                if (stoppedSince == 0L) {
                    stoppedSince = now
                    // confere de novo na hora certa (o tempo pode ser de poucos segundos)
                    main.removeCallbacks(idleCheck); main.postDelayed(idleCheck, Prefs.idleSecs(ctx) * 1000L + 300)
                }
                if (now - stoppedSince > Prefs.idleSecs(ctx) * 1000L) setAuto(false)
            }
        }
    }

    /* ---------- câmera automática: "andando" (> 3 km/h) e "parado há 30 s" ---------- */

    /** true = o carro está andando; false = parado há 30 s (a tela usa para trocar a vista) */
    var onDriving: ((Boolean) -> Unit)? = null
    var driving = false
        private set
    private var driveStopAt = 0L
    private val driveCheck = Runnable { evalDriving(lastKmh) }

    private fun evalDriving(kmh: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (Math.abs(kmh) > 3f) {
            driveStopAt = 0L; main.removeCallbacks(driveCheck)
            if (!driving) { driving = true; onDriving?.invoke(true) }
        } else if (driving) {
            if (driveStopAt == 0L) { driveStopAt = now; main.postDelayed(driveCheck, DRIVE_IDLE_MS + 300) }
            else if (now - driveStopAt >= DRIVE_IDLE_MS) { driving = false; driveStopAt = 0L; onDriving?.invoke(false) }
        }
    }

    private fun setAuto(road: Boolean) {
        if (road == autoRoad) return
        autoRoad = road
        world.setRoadMode(road)
    }

    /* ---------- modo demonstração: o carro "anda" sozinho (rodas, esterço, carroceria, faixas) ---------- */

    private var demoT = 0f
    private val demo = object : Runnable {
        override fun run() {
            demoT += .05f
            // acelera até ~60 km/h, freia, para um pouco e repete; curvas suaves no meio
            val cyc = demoT % 24f
            val kmh = when {
                cyc < 9f -> 60f * (cyc / 9f)
                cyc < 15f -> 60f
                cyc < 19f -> 60f * (1f - (cyc - 15f) / 4f)
                else -> 0f
            }
            val wheel = if (cyc in 9f..15f) 14f * kotlin.math.sin((cyc - 9f) / 6f * 2f * Math.PI.toFloat()) else 0f
            world.setMotion(kmh, wheel)
            evalAuto(kmh, null)
            main.postDelayed(this, 50)
        }
    }

    private fun applyDemo() {
        main.removeCallbacks(demo)
        if (Prefs.bool(ctx, Prefs.DEMO, false)) main.post(demo) else pushMotion()
    }

    private fun applyScene() {
        when (Prefs.str(ctx, Prefs.SCENE, "auto")) {
            "estrada" -> world.setRoadMode(true)
            "auto" -> world.setRoadMode(autoRoad)
            else -> world.setRoadMode(false)
        }
        applyRoadTheme()
    }

    /**
     * Estrada clara ou escura, seguindo o carro: 1) modo noturno da central (sinal do carro);
     * 2) tema dia/noite do sistema (se a central o define); 3) a hora do dia (céu do app).
     */
    private fun applyRoadTheme() { world.setRoadTheme(light = !isNight()) }

    /** tema escuro do carro (o mesmo critério da estrada e da interface do app) */
    fun isNight(): Boolean = lastVehicle?.night ?: systemNight() ?: ((world.env?.day ?: 1f) < .5f)

    /** temperatura externa: sensor do carro; sem ele, a da internet (Open-Meteo); null = sem leitura */
    fun outsideTemp(): Float? = lastVehicle?.outsideC ?: WeatherSync.get(ctx).tempC()
    fun outsideFromCar(): Boolean = lastVehicle?.outsideC != null

    private fun systemNight(): Boolean? = try {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        when (ui.nightMode) {
            android.app.UiModeManager.MODE_NIGHT_YES -> true
            android.app.UiModeManager.MODE_NIGHT_NO -> false
            else -> null      // automático/desconhecido: decide pela hora
        }
    } catch (_: Throwable) { null }

    /** reflexo no lago: desligado pelo usuário ou na qualidade Econômica (é a cópia do carro) */
    private fun applyReflection() {
        world.setReflection(Prefs.bool(ctx, Prefs.REFLECT, true) && Prefs.str(ctx, Prefs.QUALITY, "media") != "economica")
    }

    /** chaves do teste de desempenho que mexem na cena */
    private fun applyTests() {
        world.setTestFlags(Prefs.bool(ctx, Prefs.T_MOUNTAINS, true), Prefs.bool(ctx, Prefs.T_MOUNT_MIRROR, true), Prefs.bool(ctx, Prefs.T_CLOUDS, true))
    }

    fun applyConfig() {
        world.setConfig(Prefs.str(ctx, Prefs.TRIM, "pro"), Prefs.str(ctx, Prefs.PAINT, CarSpec.paints[0].id), Prefs.str(ctx, Prefs.PLATE, ""))
    }

    /* ---------- horário ---------- */

    val autoSun get() = Prefs.bool(ctx, Prefs.AUTO_SUN, true)
    /** dia pleno: ~35 min depois do nascer do sol; noite plena: ~45 min depois do pôr */
    fun dawn() = if (autoSun) rise + 35 else Prefs.int(ctx, Prefs.DAWN, 6 * 60 + 30)
    fun dusk() = if (autoSun) set + 45 else Prefs.int(ctx, Prefs.DUSK, 19 * 60)

    fun minute(): Float {
        val fixed = Prefs.int(ctx, Prefs.HOUR, -1)
        if (fixed >= 0) return fixed.toFloat()
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60f + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f
    }

    /** clima da cena: o escolhido à mão, ou o da previsão (chuva só depois de o usuário confirmar) */
    fun weatherId(): String = if (Prefs.autoWeather(ctx)) RainGate.filter(ctx, debugForecast ?: autoWeatherId) else Prefs.str(ctx, Prefs.MANUAL_WEATHER, "sol")
    /** teste no emulador: previsão simulada (adb … --es rainask 1 = "chuva") */
    var debugForecast: String? = null
    /** o que a previsão diz agora (sem a pergunta da chuva) */
    fun forecastId() = autoWeatherId

    fun applyEnv() {
        world.setEnvironment(SkyClock.compute(minute(), dawn(), dusk(), weatherId()))
        applyRoadTheme()
        onChange?.invoke()
    }

    override fun onSun(riseMin: Int, setMin: Int) { rise = riseMin; set = setMin; applyEnv() }
    override fun onWeather(id: String) { autoWeatherId = id; applyEnv() }

    /* ---------- carro ---------- */

    /** só aplica o que mudou no carro: o que o usuário tocou na tela fica até o carro mudar */
    override fun onVehicle(v: Vehicle) {
        val prev = lastVehicle
        lastVehicle = v
        for ((name, open) in v.doors) if (prev?.doors?.get(name) != open) world.setOpening(name, open)
        var s = world.lightState
        if (prev == null || prev.signal != v.signal) s = s.copy(signal = v.signal)
        if (v.farol != null && prev?.farol != v.farol) s = s.copy(farol = v.farol)
        if (v.alta != null && prev?.alta != v.alta) s = s.copy(alta = v.alta)
        if (v.drl != null && prev?.drl != v.drl) s = s.copy(drl = v.drl)
        if (v.brake != null && prev?.brake != v.brake) s = s.copy(brake = v.brake)
        if (s != world.lightState) world.setLights(s)
        pushMotion()
        if (prev?.night != v.night) applyRoadTheme()
        if (prev?.ignitionOn == false && v.ignitionOn == true) onIgnitionOn?.invoke()
        if (prev?.outsideC != v.outsideC) Diag.log("Temperatura externa do carro: ${v.outsideC}")
        // velocidade e volante (~10 por segundo) só mexem no 3D: a interface só é atualizada quando
        // muda outra coisa (antes ela era remontada a cada sinal = engasgos andando)
        // os números da carga (potência, corrente…) também mudam várias vezes por segundo: o widget de carga
        // tem aviso próprio, mais espaçado (antes a tela e o papel de parede eram refeitos a cada leitura)
        if (prev == null || prev.copy(speedKmh = null, wheelDeg = null, charge = null) != v.copy(speedKmh = null, wheelDeg = null, charge = null)) onChange?.invoke()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.TRIM, Prefs.PAINT, Prefs.PLATE -> applyConfig()
            Prefs.CAR_SYNC -> updateCarListening()
            Prefs.REFLECT, Prefs.QUALITY -> applyReflection()
            Prefs.T_MOUNTAINS, Prefs.T_MOUNT_MIRROR, Prefs.T_CLOUDS -> applyTests()
            Prefs.SCENE -> applyScene()
            Prefs.DEMO -> applyDemo()
            Prefs.AUTO_WEATHER, Prefs.MANUAL_WEATHER -> { WeatherSync.get(ctx).refresh(); applyEnv() }
            Prefs.AUTO_SUN, Prefs.DAWN, Prefs.DUSK, Prefs.HOUR -> applyEnv()
            // respondeu "Está chovendo aí?": o céu muda já; a pergunta apareceu/sumiu: as telas se atualizam
            Prefs.RAIN_OK, Prefs.RAIN_SNOOZE -> applyEnv()
            Prefs.RAIN_ASK -> main.post { onChange?.invoke() }
        }
    }
}
