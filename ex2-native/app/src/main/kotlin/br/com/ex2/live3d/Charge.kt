package br.com.ex2.live3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** o que o widget de carregamento mostra agora */
class ChargeModel(
    val mode: Int,                 // HIDDEN, CHARGING, DONE
    val dc: Boolean,
    val kw: Float, val volts: Float?, val amps: Float?, val minutesLeft: Float?,
    val soc: Float?, val socFrom: Float?,
    val samples: FloatArray, val peak: Float, val energyKwh: Float, val durationMin: Float,
    val price: Float, val kmSinceLast: Float?,
    /** % da bateria em cada ponto do gráfico (NaN = não informado) */
    val socs: FloatArray = FloatArray(0)
) {
    val cost get() = energyKwh * price
    val avgKw get() = if (durationMin > 1f) energyKwh / (durationMin / 60f) else kw
    companion object { const val HIDDEN = 0; const val CHARGING = 1; const val DONE = 2 }
}

/**
 * Carregamento: percebe quando o carro começa a carregar (estado da carga ou potência entrando),
 * acompanha a sessão (gráfico da potência pela % da bateria, energia, pico, custo pelo valor do kWh)
 * e, ao terminar, mostra o resumo. Depois que aparece, o widget fica na tela até o usuário tocar em
 * Fechar (ou sair dirigindo, se a opção estiver ligada). Pausas no meio da carga (carregador que
 * para e volta) continuam a mesma sessão. Sobrevive ao processo ser fechado (fica nas preferências).
 *
 * A tela é avisada no máximo a cada [NOTIFY_MS]: potência e corrente mudam várias vezes por segundo
 * e cada aviso redesenhava o carro 3D inteiro (pesava na central e levou à tela preta).
 */
class ChargeMonitor private constructor(c: Context) : CarBridge.Listener {
    private val ctx = c.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val listeners = LinkedHashSet<() -> Unit>()
    private var users = 0

    // sessão em andamento
    private var active = false
    private var startMs = 0L
    private var energy = 0.0
    private var peak = 0f
    private var samples = ArrayList<Float>()
    private var socs = ArrayList<Float>()
    private var bucketSum = 0.0; private var bucketN = 0; private var bucketStart = 0L
    private var lastAt = 0L
    private var dc = false
    private var socFrom: Float? = null
    // leitura atual
    private var reading: ChargeReading? = null
    private var chargingSince = 0L
    private var notChargingSince = 0L
    private var speed = 0f
    private var movingSince = 0L
    private var simulating = false

    fun start() {
        if (users++ == 0) {
            restore()
            CarBridge.get(ctx).addListener(this)
            main.post(tick)
        }
    }

    fun stop() {
        if (--users > 0) return
        users = 0
        CarBridge.get(ctx).removeListener(this)
        main.removeCallbacks(tick); main.removeCallbacks(notifyNow)
        save()
    }

    override fun onVehicle(v: Vehicle) {
        speed = v.speedKmh ?: speed
        if (!simulating) v.charge?.let { feed(it) }
    }

    /* ---------- % da bateria com duas casas ---------- */

    // o carro manda a % em passos de 0,1 (ou de 1); entre um passo e outro, a energia que entrou
    // completa as casas (limitado para nunca passar do próximo passo)
    private var stepRaw = Float.NaN
    private var stepEnergy = 0.0
    private var stepsSeen = 0
    private var fine = false
    private var socShown: Float? = null

    private fun socOf(r: ChargeReading?): Float? = r?.socRaw?.takeIf { it > 0f && it <= 100f }

    private fun updateSoc(r: ChargeReading) {
        val raw = socOf(r) ?: return
        if (abs(raw * 10 - (raw * 10).roundToInt()) < .02f && abs(raw - raw.roundToInt()) > .02f) fine = true
        if (raw != stepRaw) {
            if (!stepRaw.isNaN() && raw > stepRaw) stepsSeen++
            stepRaw = raw; stepEnergy = energy
        }
        socShown = if (!active || stepsSeen == 0) raw else {
            val room = if (fine) .099f else .99f
            raw + ((energy - stepEnergy) * EFFICIENCY / CAPACITY_KWH * 100).toFloat().coerceIn(0f, room)
        }
    }

    private fun kwOf(r: ChargeReading): Float {
        val a = abs(r.kw ?: 0f); val d = abs(r.dcKw ?: 0f)
        val vi = if (r.volts != null && r.amps != null) abs(r.volts * r.amps) / 1000f else 0f
        return maxOf(a, d, vi)
    }

    private fun isCharging(r: ChargeReading): Boolean {
        if (r.state in CHARGING_STATES) return true
        if (r.state in END_STATES) return false
        val plugOk = r.plug == null || r.plug in PLUG_CONNECTED
        return plugOk && kwOf(r) >= .3f
    }

    private fun feed(r: ChargeReading) {
        reading = r
        val now = SystemClock.elapsedRealtime()
        val charging = isCharging(r)
        if (charging) { notChargingSince = 0L; if (chargingSince == 0L) chargingSince = now }
        else { chargingSince = 0L; if (notChargingSince == 0L) notChargingSince = now }
        if (!active && charging && now - chargingSince >= START_MS) begin(r)
        var big = false
        if (active) {
            val kw = kwOf(r)
            if (lastAt != 0L) energy += kw * (minOf(now - lastAt, 30_000L) / 3_600_000.0)
            lastAt = now
            peak = max(peak, kw)
            if (r.dcKw != null && r.dcKw > .5f) dc = true
            if (r.state == STATE_DC) dc = true
            bucketSum += kw; bucketN++
            updateSoc(r)
            if (now - bucketStart >= SAMPLE_MS) { addSample((bucketSum / bucketN).toFloat()); bucketSum = 0.0; bucketN = 0; bucketStart = now }
            val ended = r.state in END_STATES || (!charging && notChargingSince != 0L && now - notChargingSince >= END_MS)
            if (ended) { finish(r); big = true }
        } else updateSoc(r)
        changed(big)
    }

    private fun addSample(kw: Float) {
        samples.add(kw); socs.add(socShown ?: Float.NaN)
        if (samples.size > MAX_SAMPLES) {
            samples = ArrayList(samples.filterIndexed { i, _ -> i % 2 == 0 })
            socs = ArrayList(socs.filterIndexed { i, _ -> i % 2 == 0 })
        }
    }

    private fun begin(r: ChargeReading) {
        val odo = r.odometerKm
        val last = lastJson()
        // carregador que pausou e voltou (sem o carro andar): continua a mesma carga
        val resume = last != null && System.currentTimeMillis() - last.optLong("end") < RESUME_MS &&
            (odo == null || !last.has("odo") || abs(last.optDouble("odo").toFloat() - odo) < .5f)
        active = true; lastAt = 0L
        bucketSum = 0.0; bucketN = 0; bucketStart = SystemClock.elapsedRealtime()
        if (resume && last != null) {
            startMs = last.optLong("start", System.currentTimeMillis() - (last.optDouble("min") * 60000).toLong())
            energy = last.optDouble("kwh"); peak = last.optDouble("peak").toFloat(); dc = last.optBoolean("dc")
            socFrom = last.optDouble("socFrom").takeIf { !it.isNaN() }?.toFloat()
            readArrays(last)
            Diag.log("Carga: voltou depois de uma pausa (mesma sessão)")
        } else {
            startMs = System.currentTimeMillis(); energy = 0.0; peak = 0f
            samples = ArrayList(); socs = ArrayList()
            dc = r.state == STATE_DC || (r.dcKw ?: 0f) > .5f
            socFrom = socOf(r)
            stepsSeen = 0; stepRaw = Float.NaN
            Diag.log("Carga: começou (" + (if (dc) "DC" else "AC") + ")")
        }
        Prefs.get(ctx).edit().putBoolean(Prefs.CHARGE_PIN, true).putBoolean(Prefs.CHARGE_LAST_OPEN, false).apply()
        save()
    }

    private fun readArrays(o: JSONObject) {
        val s = o.optJSONArray("samples") ?: JSONArray()
        val q = o.optJSONArray("socs")
        samples = ArrayList((0 until s.length()).map { s.getDouble(it).toFloat() })
        socs = ArrayList((0 until s.length()).map { if (q != null && it < q.length()) q.optDouble(it).toFloat() else Float.NaN })
    }

    private fun arrays(o: JSONObject): JSONObject =
        o.put("samples", JSONArray(samples.map { it.toDouble() })).put("socs", JSONArray(socs.map { if (it.isNaN()) JSONObject.NULL else it.toDouble() }))

    private fun finish(r: ChargeReading?) {
        if (!active) return
        if (bucketN > 0) { addSample((bucketSum / bucketN).toFloat()); bucketN = 0; bucketSum = 0.0 }
        active = false
        val dur = (System.currentTimeMillis() - startMs) / 60000f
        val last = arrays(JSONObject().put("start", startMs).put("end", System.currentTimeMillis()).put("min", dur.toDouble()).put("kwh", energy)
            .put("peak", peak.toDouble()).put("dc", dc).put("price", price().toDouble())
            .put("socFrom", socFrom?.toDouble() ?: JSONObject.NULL).put("socTo", (socShown ?: socOf(r))?.toDouble() ?: JSONObject.NULL))
        r?.odometerKm?.let { last.put("odo", it.toDouble()) }
        val show = Prefs.bool(ctx, Prefs.CHARGE_SHOW_END, true)
        val ed = Prefs.get(ctx).edit().putString(Prefs.CHARGE_LAST, last.toString()).remove(Prefs.CHARGE_SESSION)
            .putBoolean(Prefs.CHARGE_LAST_OPEN, show)
        if (!show) ed.putBoolean(Prefs.CHARGE_PIN, false)
        r?.odometerKm?.takeIf { it > 0f }?.let { ed.putFloat(Prefs.CHARGE_ODO, it) }
        ed.apply()
        Diag.log("Carga: terminou · %.1f kWh em %.0f min · pico %.1f kW".format(energy, dur, peak))
    }

    /** botão Fechar (ou saiu dirigindo): volta a temperatura */
    fun close() {
        Prefs.get(ctx).edit().putBoolean(Prefs.CHARGE_LAST_OPEN, false).putBoolean(Prefs.CHARGE_PIN, false).apply()
        changed(true)
    }

    /** mostra de novo o resumo da última carga (ajustes) */
    fun showLast() {
        if (Prefs.str(ctx, Prefs.CHARGE_LAST, "").isNotEmpty()) {
            Prefs.get(ctx).edit().putBoolean(Prefs.CHARGE_LAST_OPEN, true).putBoolean(Prefs.CHARGE_PIN, true).apply(); changed(true)
        }
    }

    fun clearHistory() {
        Prefs.get(ctx).edit().remove(Prefs.CHARGE_LAST).remove(Prefs.CHARGE_LAST_OPEN).remove(Prefs.CHARGE_ODO).remove(Prefs.CHARGE_PIN).apply(); changed(true)
    }

    fun price() = Prefs.get(ctx).getFloat(Prefs.CHARGE_PRICE, .80f)

    private val tick = object : Runnable {
        override fun run() {
            // saiu dirigindo com o resumo aberto: fecha sozinho (opção) — só andando de verdade por 5 s
            val now = SystemClock.elapsedRealtime()
            if (speed > 5f) { if (movingSince == 0L) movingSince = now } else movingSince = 0L
            if (!active && movingSince != 0L && now - movingSince > 5_000L && Prefs.bool(ctx, Prefs.CHARGE_PIN, false) &&
                Prefs.bool(ctx, Prefs.CHARGE_AUTO_CLOSE, true)) { Diag.log("Carga: resumo fechado (saiu dirigindo)"); close() }
            // sem sinal novo há um tempo: reavalia (o carro para de mandar quando a carga acaba)
            if (active && !simulating) reading?.let { feed(it) }
            if (active && SystemClock.elapsedRealtime() % 60_000L < 1_000L) save()
            main.postDelayed(this, 1000)
        }
    }

    /* ---------- o que o widget mostra ---------- */

    fun model(): ChargeModel {
        val off = !Prefs.bool(ctx, Prefs.CHARGE_ON, true)
        val r = reading
        val km = kmSinceLast()
        if (!off && active) {
            val dur = (System.currentTimeMillis() - startMs) / 60000f
            val live = if (bucketN > 0) (bucketSum / bucketN).toFloat() else null
            val pts = (samples + listOfNotNull(live)).toFloatArray()
            val ss = (socs + (if (live != null) listOf(socShown ?: Float.NaN) else emptyList())).toFloatArray()
            // (sem leitura ainda, por exemplo logo depois de o app reabrir: mostra o que tinha)
            return ChargeModel(ChargeModel.CHARGING, dc, r?.let { kwOf(it) } ?: 0f, r?.volts, r?.amps, r?.minutesLeft?.takeIf { it > 0f }, socShown ?: socOf(r), socFrom,
                pts, peak, energy.toFloat(), dur, price(), km, ss)
        }
        val last = lastJson()
        val open = Prefs.bool(ctx, Prefs.CHARGE_LAST_OPEN, false) || Prefs.bool(ctx, Prefs.CHARGE_PIN, false)
        if (!off && last != null && open) {
            val s = last.optJSONArray("samples") ?: JSONArray()
            val q = last.optJSONArray("socs")
            return ChargeModel(ChargeModel.DONE, last.optBoolean("dc"), 0f, null, null, null,
                last.optDouble("socTo").takeIf { !it.isNaN() }?.toFloat(), last.optDouble("socFrom").takeIf { !it.isNaN() }?.toFloat(),
                FloatArray(s.length()) { s.getDouble(it).toFloat() }, last.optDouble("peak").toFloat(), last.optDouble("kwh").toFloat(),
                last.optDouble("min").toFloat(), last.optDouble("price", price().toDouble()).toFloat(), km,
                FloatArray(s.length()) { if (q != null && it < q.length()) q.optDouble(it).toFloat() else Float.NaN })
        }
        return ChargeModel(ChargeModel.HIDDEN, false, 0f, null, null, null, null, null, FloatArray(0), 0f, 0f, 0f, price(), km)
    }

    fun lastJson(): JSONObject? = try { Prefs.str(ctx, Prefs.CHARGE_LAST, "").ifEmpty { null }?.let { JSONObject(it) } } catch (_: Exception) { null }

    /** km rodados desde o fim da última carga (null = ainda sem hodômetro guardado) */
    fun kmSinceLast(): Float? {
        val odo = reading?.odometerKm ?: CarBridge.get(ctx).last?.charge?.odometerKm ?: return null
        val at = Prefs.get(ctx).getFloat(Prefs.CHARGE_ODO, -1f)
        return if (at > 0f && odo >= at) odo - at else null
    }

    /** texto dos sinais crus (ajustes, para conferir no carro) */
    fun rawText(): String {
        val r = reading ?: CarBridge.get(ctx).last?.charge ?: return L.t("Sem leitura dos sinais de carga ainda")
        fun f(v: Float?) = v?.let { "%.1f".format(it) } ?: "—"
        return "kW ${f(r.kw)} · DC ${f(r.dcKw)} · ${f(r.volts)} V · ${f(r.amps)} A · ${f(r.minutesLeft)} min · " +
            "0x${r.state?.let { Integer.toHexString(it) } ?: "—"} · 0x${r.plug?.let { Integer.toHexString(it) } ?: "—"} · ${r.socRaw?.let { "%.2f".format(it) } ?: "—"}%"
    }

    /* ---------- aviso à tela, espaçado ---------- */

    private var lastMode = -1
    private var lastNotify = 0L
    private var notifyPending = false
    private val notifyNow = Runnable {
        notifyPending = false; lastNotify = SystemClock.elapsedRealtime()
        for (l in listeners.toList()) Guard.run("widget de carga") { l() }
    }

    /** [big] = começou, terminou, fechou: avisa já; o resto no máximo a cada NOTIFY_MS */
    private fun changed(big: Boolean = false) {
        val mode = if (active) 1 else 0
        val now = SystemClock.elapsedRealtime()
        if (big || mode != lastMode || simulating) { lastMode = mode; main.removeCallbacks(notifyNow); notifyNow.run(); return }
        if (notifyPending) return
        notifyPending = true
        main.postDelayed(notifyNow, (NOTIFY_MS - (now - lastNotify)).coerceIn(0L, NOTIFY_MS))
    }

    /* ---------- guardar e retomar a sessão ---------- */

    private fun save() {
        if (!active) return
        val o = arrays(JSONObject().put("start", startMs).put("kwh", energy).put("peak", peak.toDouble()).put("dc", dc)
            .put("socFrom", socFrom?.toDouble() ?: JSONObject.NULL).put("saved", System.currentTimeMillis()))
        Prefs.get(ctx).edit().putString(Prefs.CHARGE_SESSION, o.toString()).apply()
    }

    private fun restore() {
        try {
            val o = JSONObject(Prefs.str(ctx, Prefs.CHARGE_SESSION, "").ifEmpty { return })
            if (System.currentTimeMillis() - o.optLong("saved") > 6 * 3600_000L) { Prefs.get(ctx).edit().remove(Prefs.CHARGE_SESSION).apply(); return }
            active = true; startMs = o.optLong("start"); energy = o.optDouble("kwh"); peak = o.optDouble("peak").toFloat()
            dc = o.optBoolean("dc"); socFrom = o.optDouble("socFrom").takeIf { !it.isNaN() }?.toFloat()
            readArrays(o)
            socs.lastOrNull { !it.isNaN() }?.let { socShown = it }
            bucketStart = SystemClock.elapsedRealtime(); notChargingSince = SystemClock.elapsedRealtime()
            Diag.log("Carga: sessão retomada (${samples.size} pontos)")
        } catch (_: Exception) { }
    }

    /* ---------- teste: uma carga simulada em ~45 s (emulador e Ajustes → Testar) ---------- */

    fun simulate() {
        if (simulating) return
        simulating = true
        val t0 = SystemClock.elapsedRealtime()
        val odo = reading?.odometerKm ?: 15151f
        val step = object : Runnable {
            override fun run() {
                val t = (SystemClock.elapsedRealtime() - t0) / 1000f
                // potência cai perto do fim, como numa carga de verdade
                val kw = when { t < 3 -> 0f; t < 6 -> 7.2f * (t - 3) / 3; t < 28 -> 7.0f + .25f * kotlin.math.sin(t * 1.7f); t < 40 -> 7f * (1 - (t - 28) / 12) + .4f; else -> 0f }
                val soc = (32f + t * 1.25f).coerceAtMost(81.3f)
                val raw = Math.floor(soc * 10.0).toFloat() / 10f
                val state = if (t < 40) STATE_AC else 0x24205C04
                feedSim(ChargeReading(kw, 398f + kw, kw * 1000f / 398f, (40 - t).coerceAtLeast(0f) * 2.5f, 0f, state, 0x24130213, raw, odo))
                if (t < 42) main.postDelayed(this, 500) else { simulating = false; changed(true) }
            }
        }
        main.post(step)
    }

    private fun feedSim(r: ChargeReading) {
        // na simulação o tempo anda 60× mais rápido (a energia fica parecida com uma carga de verdade)
        if (!active && r.state == STATE_AC) { Prefs.get(ctx).edit().remove(Prefs.CHARGE_LAST).apply(); begin(r); chargingSince = SystemClock.elapsedRealtime() }
        reading = r
        if (active) {
            val kw = kwOf(r)
            energy += kw * (0.5 * 60 / 3600.0)
            peak = max(peak, kw)
            updateSoc(r)
            addSample(kw)
            startMs -= 30_000L
            if (r.state in END_STATES) finish(r)
        }
        changed()
    }

    companion object {
        const val STATE_AC = 0x24205C02
        const val STATE_DC = 0x24205C15
        val CHARGING_STATES = setOf(STATE_AC, STATE_DC)
        val END_STATES = setOf(0x24205C03, 0x24205C04, 0x24205C26)
        val PLUG_CONNECTED = setOf(0x24130213, 0x24130214, 0x2413021B)
        private const val START_MS = 8_000L          // carregando há 8 s = começou
        private const val END_MS = 90_000L           // 90 s sem carregar = terminou
        private const val RESUME_MS = 30 * 60_000L   // voltou a carregar em até 30 min parado = mesma carga
        private const val SAMPLE_MS = 20_000L        // um ponto do gráfico a cada 20 s
        private const val MAX_SAMPLES = 720
        private const val NOTIFY_MS = 3_000L         // tela atualizada no máximo a cada 3 s
        /** bateria do EX2 (kWh úteis) e quanto da energia de entrada chega nela */
        private const val CAPACITY_KWH = 39.4
        private const val EFFICIENCY = .92

        @android.annotation.SuppressLint("StaticFieldLeak") private var instance: ChargeMonitor? = null
        @Synchronized fun get(c: Context) = instance ?: ChargeMonitor(c).also { instance = it }
    }
}

/**
 * O widget na tela do app: no lugar da temperatura (canto de cima à direita; espelhado, à esquerda).
 * Só recebe toques em cima dos botões (Editar / Fechar); o resto passa para o 3D.
 */
class ChargeView(c: Context, private val onEdit: () -> Unit, private val onClose: () -> Unit) : android.view.View(c) {
    var model: ChargeModel? = null
        set(v) { field = v; invalidate() }
    var dark = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val hits = ChargeArt.Hits()
    /** escala no app: o tamanho dele (o painel de Portas é que se ajusta embaixo) */
    private val scale = .82f
    /** mesma margem dos painéis do app (18 dp): alinhado com o cartão do carro e com o painel de Portas */
    private fun margin() = 18f * resources.displayMetrics.density
    private fun top() = margin()
    /** onde o widget termina na tela (px), para o painel de Portas ficar logo abaixo */
    fun widgetBottom(): Float { val m = model ?: return 0f; return top() + ChargeArt.height(m) * width / TempArt.REF_W * scale }
    /** tela espelhada: o widget vai para o canto de cima à esquerda */
    var mirror = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(cv: Canvas) {
        val m = model ?: return
        if (m.mode == ChargeModel.HIDDEN) return
        val k = width / TempArt.REF_W * scale
        val x = if (mirror) margin() else width - margin() - ChargeArt.W * k
        ChargeArt.draw(cv, m, k, x, top(), dark, TempArt.font, hits)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        val inEdit = hits.edit.contains(e.x, e.y); val inClose = hits.close.contains(e.x, e.y)
        if (!inEdit && !inClose) return false
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) { if (inEdit) onEdit() else onClose() }
        return true
    }
}

/**
 * Desenho do widget de carregamento (o mesmo no app e no papel de parede). Medidas em pixels da
 * tela de referência 1920×1080; k = escala. Painel claro (ou grafite no tema escuro). Gráfico: da
 * esquerda (0% de bateria) à direita (100%), a bolinha anda com a % e a curva verde é a potência
 * que entrou em cada ponto — dá para ver a carga inteira, do começo ao fim.
 */
object ChargeArt {
    const val W = 520f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    class Hits { val edit = RectF(); val close = RectF() }

    fun height(m: ChargeModel) = if (m.mode == ChargeModel.HIDDEN) 0f else 556f

    private fun fmt(v: Float, d: Int = 1) = L.num(v, d)
    private fun dur(min: Float): String { val m = min.roundToInt(); return if (m >= 60) L.f("%dh %dmin", m / 60, m % 60) else L.f("%dmin", m) }

    /** desenha com o canto de cima à esquerda em (x, y); devolve as áreas dos botões */
    fun draw(cv: Canvas, m: ChargeModel, k: Float, x: Float, y: Float, dark: Boolean, font: android.graphics.Typeface?, hits: Hits) {
        val h = height(m)
        val panel = if (dark) 0xF01D222A.toInt() else 0xF2F3F5F9.toInt()
        val text = if (dark) 0xFFEEF1F5.toInt() else 0xFF1B1E24.toInt()
        val muted = if (dark) 0xFF98A1AD.toInt() else 0xFF6E7681.toInt()
        val line = if (dark) 0x33FFFFFF else 0xFFDFE3EA.toInt()
        val green = if (dark) 0xFF34D27A.toInt() else 0xFF15803D.toInt()
        val chart = 0xFF22C55E.toInt()
        fun X(v: Float) = x + v * k
        fun Y(v: Float) = y + v * k
        p.style = Paint.Style.FILL; p.color = panel
        cv.drawRoundRect(RectF(x, y, X(W), Y(h)), 28 * k, 28 * k, p)
        p.typeface = font
        fun txt(s: String, px: Float, py: Float, size: Float, color: Int, right: Boolean = false, bold: Boolean = false, center: Boolean = false) {
            p.style = Paint.Style.FILL; p.color = color; p.textSize = size * k
            p.isFakeBoldText = false
            p.typeface = if (bold) android.graphics.Typeface.create(font, android.graphics.Typeface.BOLD) else font
            p.textAlign = if (center) Paint.Align.CENTER else if (right) Paint.Align.RIGHT else Paint.Align.LEFT
            cv.drawText(s, X(px), Y(py), p)
        }
        val pad = 26f
        val done = m.mode == ChargeModel.DONE
        // cabeçalho
        val title = if (done) L.t("Carga concluída") else L.t("Carregando") + " · " + if (m.dc) "DC" else "AC"
        txt(title, pad + (if (done) 0f else 26f), 52f, 24f, if (done) Ui.BLUE else green, bold = true)
        if (!done) {   // raio
            p.color = green; p.style = Paint.Style.FILL
            val b = Path().apply { moveTo(X(pad + 12), Y(30f)); lineTo(X(pad + 2), Y(46f)); lineTo(X(pad + 10), Y(46f)); lineTo(X(pad + 6), Y(60f)); lineTo(X(pad + 18), Y(42f)); lineTo(X(pad + 10), Y(42f)); close() }
            cv.drawPath(b, p)
        }
        m.soc?.let { s ->
            txt(fmt(s, 2), W - pad - 24, 58f, 40f, text, right = true, bold = true)
            txt("%", W - pad, 58f, 24f, text, right = true)
        }
        // barra da bateria
        val by = 76f
        p.style = Paint.Style.FILL
        p.color = line; cv.drawRoundRect(RectF(X(pad), Y(by), X(W - pad), Y(by + 8)), 4 * k, 4 * k, p)
        p.color = chart
        val frac = ((m.soc ?: 0f) / 100f).coerceIn(0f, 1f)
        if (frac > 0f) cv.drawRoundRect(RectF(X(pad), Y(by), X(pad + (W - 2 * pad) * frac), Y(by + 8)), 4 * k, 4 * k, p)
        // gráfico da potência pela % da bateria
        txt(L.t(if (done) "Potência da carga (kW)" else "Potência de entrada (kW)"), pad, 122f, 19f, muted)
        txt(L.t("pico") + " " + fmt(m.peak), W - pad, 122f, 19f, muted, right = true)
        val gx0 = pad; val gx1 = W - pad; val gy0 = 136f; val gy1 = 252f
        val top = max(1f, kotlin.math.ceil(max(m.peak, m.kw) / 2f) * 2f)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * k; p.color = line
        cv.drawLine(X(gx0), Y(gy1), X(gx1), Y(gy1), p)
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * k, 6 * k), 0f)
        cv.drawLine(X(gx0), Y(gy0), X(gx1), Y(gy0), p)
        // marcas de 25 em 25 %
        for (q in 1..3) { val qx = gx0 + (gx1 - gx0) * q / 4f; cv.drawLine(X(qx), Y(gy0), X(qx), Y(gy1), p) }
        p.pathEffect = null
        val live = m.mode == ChargeModel.CHARGING
        val pts = if (m.samples.isEmpty()) floatArrayOf(m.kw) else m.samples
        val ss = if (m.samples.isEmpty()) floatArrayOf(m.soc ?: Float.NaN) else m.socs
        // pelo eixo da bateria só quando todos os pontos sabem a %; senão, pelo tempo (como antes)
        val bySoc = ss.size == pts.size && ss.isNotEmpty() && ss.all { !it.isNaN() } && m.soc != null
        fun py(v: Float) = gy1 - (gy1 - gy0) * (v / top).coerceIn(0f, 1f)
        if (pts.isNotEmpty()) {
            val n = pts.size
            fun px(i: Int): Float = if (bySoc) gx0 + (gx1 - gx0) * (ss[i] / 100f).coerceIn(0f, 1f)
                else gx0 + (gx1 - gx0) * (if (n == 1) 0f else i / (n - 1f)) * (if (done) 1f else .92f)
            val path = Path(); val fill = Path()
            for (i in 0 until n) {
                val ax = X(px(i)); val ay = Y(py(pts[i]))
                if (i == 0) { path.moveTo(ax, ay); fill.moveTo(ax, Y(gy1)); fill.lineTo(ax, ay) } else { path.lineTo(ax, ay); fill.lineTo(ax, ay) }
            }
            // carregando: a linha vai até a bolinha (a % de agora)
            val endX = if (live && bySoc) X(gx0 + (gx1 - gx0) * (m.soc!! / 100f).coerceIn(0f, 1f)) else X(px(n - 1))
            val endY = Y(py(if (live) m.kw else pts[n - 1]))
            if (live && bySoc) { path.lineTo(endX, endY); fill.lineTo(endX, endY) }
            fill.lineTo(endX, Y(gy1)); fill.close()
            p.style = Paint.Style.FILL; p.color = (chart and 0x00FFFFFF) or 0x38000000; cv.drawPath(fill, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * k; p.color = chart; p.strokeJoin = Paint.Join.ROUND; cv.drawPath(path, p)
            if (live) {   // bolinha: onde a bateria está agora
                p.style = Paint.Style.FILL; p.color = panel; cv.drawCircle(endX, endY, 7 * k, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * k; p.color = chart; cv.drawCircle(endX, endY, 7 * k, p)
            }
        }
        // eixo: 0% · 50% · 100%
        if (bySoc || m.samples.isEmpty()) {
            txt("0%", gx0, gy1 + 22, 16f, muted)
            txt("50%", (gx0 + gx1) / 2, gy1 + 22, 16f, muted, center = true)
            txt("100%", gx1, gy1 + 22, 16f, muted, right = true)
        }
        // quatro números
        val cells = if (done) listOf(L.t("Energia") to fmt(m.energyKwh) + " kWh", L.t("Duração") to dur(m.durationMin),
                L.t("Média") to fmt(m.avgKw) + " kW", L.t("Bateria") to (if (m.socFrom != null && m.soc != null) "${fmt(m.socFrom, 0)}% → ${fmt(m.soc, 0)}%" else "—"))
            else listOf(L.t("Potência") to fmt(m.kw) + " kW", L.t("Tensão") to (m.volts?.let { fmt(it, 0) + " V" } ?: "—"),
                L.t("Corrente") to (m.amps?.let { fmt(it) + " A" } ?: "—"), L.t("Tempo restante") to (m.minutesLeft?.let { dur(it) } ?: "—"))
        cells.forEachIndexed { i, (l, v) ->
            val cx = pad + (i % 2) * (W - 2 * pad) / 2; val cy = 306f + (i / 2) * 64f
            txt(l, cx, cy, 19f, muted); txt(v, cx, cy + 31, 28f, text, bold = true)
        }
        // custo e botão
        val dy = 432f
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * k; p.color = line; cv.drawLine(X(pad), Y(dy), X(W - pad), Y(dy), p)
        txt(if (done) L.t("Última carga") else L.t("Nesta carga") + " · " + fmt(m.energyKwh) + " kWh", pad, dy + 30, 19f, muted)
        txt(L.money(m.cost), pad, dy + 64, 30f, green, bold = true)
        val bw = 120f; val bh = 46f; val bx = W - pad - bw; val byy = dy + 30
        val r = RectF(X(bx), Y(byy), X(bx + bw), Y(byy + bh))
        p.style = Paint.Style.FILL; p.color = if (done) Ui.BLUE else (if (dark) 0x1FFFFFFF else 0xFFE4E8EF.toInt())
        cv.drawRoundRect(r, 16 * k, 16 * k, p)
        txt(L.t(if (done) "Fechar" else "Editar"), bx + bw / 2, byy + 31, 19f, if (done) 0xFFFFFFFF.toInt() else text, bold = true, center = true)
        if (!done) txt(L.money(m.price) + " / kWh", bx - 14, byy + 31, 18f, muted, right = true)
        hits.edit.set(if (done) RectF() else r); hits.close.set(if (done) r else RectF())
        // km
        val km = m.kmSinceLast?.let { L.f("%s km rodados desde a última carga", fmt(it, 0)) } ?: L.t("Km desde a última carga: aparece depois da primeira carga")
        txt(km, pad, h - 20, 18f, muted)
        p.textAlign = Paint.Align.LEFT
    }
}
