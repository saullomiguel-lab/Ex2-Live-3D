package br.com.ex2.live3d

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Estado do carro lido do VHAL. null = sinal desconhecido (não mexe na cena). */
data class Vehicle(
    val doors: Map<String, Boolean>,
    val signal: Int,               // 0, 1 esquerda, -1 direita, 2 alerta
    val farol: Boolean?, val drl: Boolean?, val brake: Boolean?,
    val alta: Boolean? = null,     // farol alto
    val speedKmh: Float? = null,   // velocidade (km/h)
    val wheelDeg: Float? = null,   // ângulo das rodas dianteiras (graus, + = esquerda)
    val gear: String? = null,      // P, R, N, D
    val night: Boolean? = null,    // modo noturno da central (tema escuro do carro)
    val outsideC: Float? = null,   // temperatura externa (°C, sensor do carro)
    val ignitionOn: Boolean? = null, // carro ligado (pronto/ligado)
    val charge: ChargeReading? = null // carregamento (sinais do app geelybattery; ver catálogo, seção 8)
)

/** leitura crua dos sinais de carga (null = sinal não informado) */
data class ChargeReading(
    val kw: Float?, val volts: Float?, val amps: Float?, val minutesLeft: Float?, val dcKw: Float?,
    val state: Int?, val plug: Int?, val socRaw: Float?, val odometerKm: Float?
)

/**
 * Lê portas e luzes pelo VHAL (android.car / CarPropertyManager), tudo por reflexão: a biblioteca
 * android.car só existe na central. Sinais (EX Sapo Central, Geely EX2 Tools):
 *   DOOR_POS 0x16400B00 por área — 0x1 FL, 0x4 FR, 0x10 RL, 0x40 RR, 0x10000000 capô, 0x20000000 porta-malas;
 *            1 = aberta, 2 = fechada.  (confirmado no carro)
 * Luzes (documentação da central BR: o BCM manda tudo no quadro CAN 0x1F0, que o MCU repassa ao Android):
 *   alavanca da seta BCM_TURN_LEVER_STATUS 0x2140A205: 0 nada, 1 esquerda, 2 direita (o "sapo" usa: confirmado)
 *   lâmpadas das setas BODY_TURN_LEFT/RIGHT_SIGNAL_STATE 0x2140209C / 0x2140209D (piscam → detectado pelas trocas)
 *   pisca-alerta BCM_HazardSts 0x2140A632 e HAZARD_LIGHTS_STATE 0x11400E03
 *   farol HEADLIGHTS_STATE 0x11400E00, luz alta HIGH_BEAM_LIGHTS_STATE 0x11400E01 (valor de "aceso" a confirmar:
 *   padrão Android 0 desl./1 lig.; ECARX costuma usar 1 desl./2 lig. — decidido pelo que o sinal já mostrou)
 *   modo noturno NIGHT_MODE 0x21408015 (a central liga com o farol): plano B do farol
 *   antigos (sem efeito no EX2 BR, mantidos): setas 0x2140139C/D, combinada 0x21407105.
 * O modo guiado "Encontrar sinais das luzes" confirma tudo no carro e grava o mapeamento manual,
 * que tem prioridade sobre as suposições acima.
 */
class CarBridge private constructor(c: Context) {
    fun interface Listener { fun onVehicle(v: Vehicle) }

    private val ctx = c.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val available = HashSet<Int>()
    private val resolved = LinkedHashMap<String, Int>()
    private val doors = LinkedHashMap<String, Boolean>()
    private val lastValues = LinkedHashMap<String, String>()
    private val manual = HashMap<Int, Pair<String, String?>>()
    private val polled = ArrayList<Int>()
    private val pollFailed = HashSet<Int>()

    private var car: Any? = null
    private var propMgr: Any? = null
    private var callback: Any? = null
    private var connecting = false
    private var connected = false
    private var retries = 0
    var status = "desligado"; private set

    private var headlights: Int? = null; private var highBeam: Int? = null; private var hazard: Int? = null
    private var turnState: Int? = null; private var ignition: Int? = null; private var combined: Int? = null
    private var leftLitAt = 0L; private var rightLitAt = 0L
    private var manualFarol: Boolean? = null; private var manualBrake: Boolean? = null; private var manualDrl: Boolean? = null
    var last: Vehicle? = null; private set

    /* último valor de cada sinal, quando mudou (2 últimas trocas) e se já mostrou 0 (codificação 0/1) */
    private val values = HashMap<Int, Int>()
    private val floats = HashMap<Int, Float>()
    private val changes = HashMap<Int, LongArray>()
    private val sawZero = HashSet<Int>()

    /** sinal trocando de valor seguido = lâmpada piscando (vale qualquer codificação) */
    private fun blinking(id: Int, now: Long): Boolean {
        val t = changes[id] ?: return false
        return now - t[0] < BLINK_MS && now - t[1] < 2 * BLINK_MS
    }

    /** ligado/desligado estável: com 0 já visto, 0 = desligado; senão (ECARX) 2 = ligado */
    private fun steadyOn(id: Int): Boolean? {
        val v = values[id] ?: return null
        return if (id in sawZero) v != 0 else v == 2
    }

    /* ---------------- captura para o modo guiado ---------------- */

    class Sample(val at: Long, val key: String, val value: Int)
    private var capture: ArrayList<Sample>? = null

    /** grava toda troca de qualquer sinal (assina todos os anunciados pela central) */
    fun startCapture(): Boolean {
        if (!connected) return false
        capture = ArrayList()
        var n = 0
        for (id in available) if (id !in resolved.values && register(id)) n++
        // valores atuais de tudo que dá para ler, como ponto de partida
        val pm = propMgr
        if (pm != null) for (id in available) {
            try {
                val v = pm.javaClass.getMethod("getProperty", Int::class.java, Int::class.java).invoke(pm, id, 0)
                if (v != null) onValue(v)
            } catch (_: Throwable) { }
        }
        Diag.vhal(ctx, "=== modo guiado: captura iniciada ($n extras)")
        return true
    }

    fun stopCapture(): List<Sample> {
        val c = capture ?: return emptyList()
        capture = null
        return c
    }

    val isConnected get() = connected

    fun addListener(l: Listener) { listeners.add(l); last?.let { l.onVehicle(it) }; start() }
    fun removeListener(l: Listener) { listeners.remove(l); if (listeners.isEmpty()) stop() }

    /* ---------------- conexão ---------------- */

    fun start() {
        if (connected || connecting || !Prefs.carSync(ctx)) return
        connecting = true
        try {
            val carCls = Class.forName("android.car.Car")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        propMgr = carCls.getMethod("getCarManager", String::class.java).invoke(car, "property")
                        connected = true; connecting = false; retries = 0
                        setup()
                    } catch (t: Throwable) { fail("getCarManager(property): $t") }
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    connected = false; status = "serviço do carro caiu, reconectando"
                    Diag.log("Car: desconectado"); retryLater()
                }
            }
            car = carCls.getMethod("createCar", Context::class.java, ServiceConnection::class.java).invoke(null, ctx, conn)
            carCls.getMethod("connect").invoke(car)
            status = "conectando ao serviço do carro"
        } catch (e: ClassNotFoundException) {
            connecting = false; status = "android.car indisponível (não é a central do carro)"; Diag.log("Car: $status")
        } catch (t: Throwable) { fail("createCar/connect: $t") }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        try {
            if (propMgr != null && callback != null) resolved.values.toList().forEach { unregister(it) }
            car?.let { it.javaClass.getMethod("disconnect").invoke(it) }
        } catch (_: Throwable) { }
        car = null; propMgr = null; connected = false; connecting = false; status = "desligado"
    }

    fun reloadManualMap() { if (connected) { stop(); start() } }

    private fun fail(why: String) { connecting = false; status = "erro: $why"; Diag.log("Car: $why"); retryLater() }

    private fun retryLater() {
        if (retries++ > 8 || listeners.isEmpty()) return
        main.postDelayed({ stop(); start() }, minOf(30000L, 2000L * retries))
    }

    /* ---------------- propriedades ---------------- */

    private fun setup() {
        available.clear(); resolved.clear()
        try {
            val list = propMgr!!.javaClass.getMethod("getPropertyList").invoke(propMgr) as List<*>
            for (cfg in list) available.add(cfg!!.javaClass.getMethod("getPropertyId").invoke(cfg) as Int)
        } catch (t: Throwable) { Diag.log("Car: getPropertyList falhou: $t") }
        Diag.log("Car: ${available.size} propriedades no VHAL")
        resolved["DOOR_POS"] = DOOR_POS
        resolved["TURN_LEFT_LAMP"] = TURN_LEFT
        resolved["TURN_RIGHT_LAMP"] = TURN_RIGHT
        resolved["TURN_COMBINED"] = TURN_COMBINED
        putSystem("HEADLIGHTS_STATE", 0x0E00); putSystem("HIGH_BEAM_LIGHTS_STATE", 0x0E01)
        putSystem("HAZARD_LIGHTS_STATE", 0x0E03); putSystem("TURN_SIGNAL_STATE", 0x0408); putSystem("IGNITION_STATE", 0x0409)
        // documentação da central BR: assina mesmo que não apareçam na lista (sem assinatura → consulta)
        resolved.putIfAbsent("HEADLIGHTS_STATE", HEADLIGHTS)
        resolved.putIfAbsent("HIGH_BEAM_LIGHTS_STATE", HIGH_BEAM)
        resolved.putIfAbsent("HAZARD_LIGHTS_STATE", HAZARD_AOSP)
        resolved["TURN_LEVER"] = TURN_LEVER
        resolved["BODY_TURN_LEFT"] = BODY_TURN_LEFT
        resolved["BODY_TURN_RIGHT"] = BODY_TURN_RIGHT
        resolved["BCM_HAZARD"] = BCM_HAZARD
        resolved["NIGHT_MODE"] = NIGHT_MODE
        resolved["HIGH_BEAM_EX2"] = HIGH_BEAM_EX2
        // movimento (padrão Android): velocidade m/s, ângulo de esterço, marcha
        resolved["PERF_VEHICLE_SPEED"] = SPEED
        resolved["PERF_STEERING_ANGLE"] = STEER
        resolved["GEAR_SELECTION"] = GEAR
        // achados no log do carro do usuário (vhal-log.txt, 10/11/2026, 2h45 rodando)
        resolved["FREIO_EX2"] = BRAKE_EX2
        resolved["MARCHA_EX2"] = GEAR_EX2
        resolved["EM_P_EX2"] = PARK_EX2
        resolved["VOLANTE_EX2"] = STEER_EX2
        resolved["FAROL_BAIXO_EX2"] = LOW_BEAM_EX2
        resolved["DRL_EX2"] = DRL_EX2
        resolved["AC_AMBIENT_TEMP"] = AC_AMBIENT
        resolved.putIfAbsent("IGNITION_STATE", IGNITION)
        // carregamento (sinais lidos pelo app geelybattery 0.3.0; ver catálogo, seção 8)
        resolved["CARGA_POTENCIA"] = CHG_POWER
        resolved["CARGA_TENSAO"] = CHG_VOLT
        resolved["CARGA_CORRENTE"] = CHG_CURR
        resolved["CARGA_TEMPO"] = CHG_TIME
        resolved["CARGA_DC"] = CHG_DC
        resolved["CARGA_ESTADO"] = CHG_STATE
        resolved["CARGA_PLUGUE"] = CHG_PLUG
        resolved["BATERIA_PCT"] = SOC_PCT
        resolved["HODOMETRO"] = ODOMETER
        // em que banco fica o motorista (países com volante à direita: o app espelha os painéis)
        resolved["BANCO_MOTORISTA"] = DRIVER_SEAT
        loadManualMap()
        polled.clear()
        for ((name, id) in resolved) {
            val ok = register(id)
            if (!ok && id != DOOR_POS) polled.add(id)
            Diag.log("Car: $name 0x${Integer.toHexString(id)}" + if (ok) " assinado" else " sem assinatura → consulta a cada $POLL_MS ms")
            readInitial(id)
        }
        if (Prefs.bool(ctx, Prefs.DIAG_RECORD, false)) recordAll()
        status = "conectado · ${resolved.size} sinais" + if (polled.isEmpty()) "" else " (${polled.size} por consulta)"
        main.removeCallbacks(poll)
        if (polled.isNotEmpty()) main.postDelayed(poll, POLL_MS)
        emit()
    }

    private val floatPolled = HashSet<Int>()

    private val poll: Runnable = object : Runnable {
        override fun run() {
            val pm = propMgr ?: return
            if (!connected || listeners.isEmpty()) return
            for (id in polled) {
                try {
                    // sinais do tipo número com vírgula (velocidade, temperatura) têm consulta própria
                    val float = (id and 0x00FF0000) == 0x00600000 || id in floatPolled
                    val v = try {
                        pm.javaClass.getMethod(if (float) "getFloatProperty" else "getIntProperty", Int::class.java, Int::class.java).invoke(pm, id, 0)
                    } catch (t: Throwable) {
                        // o volante do EX2 (0x101000) é número com vírgula sem o tipo no código: tenta do outro jeito
                        if (float) throw t
                        pm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java).invoke(pm, id, 0).also { floatPolled.add(id) }
                    }
                    handle(id, 0, v, 0)
                } catch (t: Throwable) {
                    if (pollFailed.add(id)) Diag.log("Car: consulta 0x${Integer.toHexString(id)} falhou: ${cause(t)}")
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun putSystem(name: String, key: Int) {
        available.firstOrNull { (it and 0xF0000000.toInt()) == 0x10000000 && (it and 0xFFFF) == key && (it and 0x0F000000) == 0x01000000 }
            ?.let { resolved[name] = it }
    }

    /**
     * linhas "campo=0xID[:valor]" — campos: farol, alta, freio, drl, seta_esq, seta_dir, alerta.
     * valor = número que significa "aceso", "pisca" (sinal que fica trocando) ou nada (≠ 0 = aceso).
     */
    private fun loadManualMap() {
        manual.clear(); manualFarol = null; manualBrake = null; manualDrl = null
        for (raw in Prefs.str(ctx, Prefs.MAP, "").split("\n")) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq < 1) continue
            val field = line.substring(0, eq).trim().lowercase()
            var rest = line.substring(eq + 1).trim()
            var expect: String? = null
            val colon = rest.indexOf(':')
            if (colon > 0) { expect = rest.substring(colon + 1).trim(); rest = rest.substring(0, colon).trim() }
            try {
                val id = rest.removePrefix("0x").removePrefix("0X").toLong(16).toInt()
                manual[id] = field to expect
                resolved["MANUAL_" + field.uppercase()] = id
            } catch (_: NumberFormatException) { Diag.log("Mapeamento inválido: $line") }
        }
    }

    private fun recordAll() {
        var n = 0
        for (id in available) if (id !in resolved.values && register(id)) n++
        Diag.vhal(ctx, "=== gravação iniciada: $n propriedades extras")
        Diag.log("Diagnóstico: gravando $n propriedades extras em ${Diag.vhalFile(ctx)}")
    }

    private fun register(id: Int): Boolean = try {
        val pm = propMgr!!
        val (cbCls, reg) = try {
            val c = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            c to pm.javaClass.getMethod("registerCallback", c, Int::class.java, Float::class.java)
        } catch (t: Throwable) {
            val c = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener")
            c to pm.javaClass.getMethod("registerListener", c, Int::class.java, Float::class.java)
        }
        if (callback == null || !cbCls.isInstance(callback)) callback = proxy(cbCls)
        val r = reg.invoke(pm, callback, id, 0f)
        r !is Boolean || r
    } catch (t: Throwable) {
        Diag.log("Car: registro 0x${Integer.toHexString(id)} falhou: ${cause(t)}"); false
    }

    private fun unregister(id: Int) {
        try {
            propMgr!!.javaClass.methods.firstOrNull { (it.name == "unregisterCallback" || it.name == "unregisterListener") && it.parameterTypes.size == 2 }
                ?.invoke(propMgr, callback, id)
        } catch (_: Throwable) { }
    }

    private fun proxy(iface: Class<*>): Any = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, m: Method, args ->
        when (m.name) {
            "onChangeEvent" -> { val v = args[0]; main.post { onValue(v) }; null }
            "onErrorEvent" -> { Diag.log("Car: erro prop 0x${Integer.toHexString(args[0] as Int)} área ${args[1]}"); null }
            "hashCode" -> System.identityHashCode(p)
            "equals" -> p === args[0]
            "toString" -> "EX2Live3D.CarCallback"
            else -> null
        }
    }

    private fun readInitial(id: Int) {
        val pm = propMgr ?: return
        val areas = if (id == DOOR_POS) DOOR_AREAS.keys.toIntArray() else intArrayOf(0)
        for (area in areas) {
            val v = try { pm.javaClass.getMethod("getProperty", Int::class.java, Int::class.java).invoke(pm, id, area) } catch (t: Throwable) {
                try { pm.javaClass.getMethod("getProperty", Class::class.java, Int::class.java, Int::class.java).invoke(pm, Any::class.java, id, area) } catch (_: Throwable) { null }
            }
            if (v != null) onValue(v)
        }
    }

    private fun onValue(v: Any) {
        try {
            val c = v.javaClass
            val id = c.getMethod("getPropertyId").invoke(v) as Int
            val area = c.getMethod("getAreaId").invoke(v) as Int
            val value = c.getMethod("getValue").invoke(v)
            val st = try { c.getMethod("getStatus").invoke(v) as Int } catch (_: Throwable) { 0 }
            handle(id, area, value, st)
        } catch (_: Throwable) { }
    }

    private fun handle(id: Int, area: Int, value: Any?, st: Int) {
        val key = "0x${Integer.toHexString(id)}" + if (area != 0) "/0x${Integer.toHexString(area)}" else ""
        val shown = value.toString()
        val withStatus = shown + if (st != 0) " (status $st)" else ""
        if (withStatus != lastValues[key]) {
            // velocidade e volante mudam ~10 vezes por segundo: no registro, no máximo uma linha a cada 5 s
            if (lastValues.containsKey(key) && id in resolved.values) {
                if (id in FAST_IDS) Diag.logThrottled(key, 5000L, "Sinal $key → $withStatus") else Diag.log("Sinal $key → $withStatus")
            }
            lastValues.remove(key); lastValues[key] = withStatus
            if (lastValues.size > 400) lastValues.remove(lastValues.keys.first())
            if (Prefs.bool(ctx, Prefs.DIAG_RECORD, false)) Diag.vhal(ctx, "$key = $shown (status $st)")
        }
        val iv = toInt(value)
        val now = SystemClock.elapsedRealtime()
        capture?.add(Sample(now, key, iv))
        if (value is Float && area == 0) floats[id] = value
        // memória de cada sinal (para pisca e para descobrir a codificação 0/1 ou 1/2)
        if (area == 0) {
            if (values[id] != iv) {
                val t = changes.getOrPut(id) { longArrayOf(0L, 0L) }
                t[1] = t[0]; t[0] = now
            }
            values[id] = iv
            if (iv == 0) sawZero.add(id)
        }
        // a IHU629G entrega quase tudo com status 3 (ECARX); portas e luzes valem com qualquer status
        val always = id == DOOR_POS || id == TURN_LEFT || id == TURN_RIGHT || id == TURN_COMBINED || id in LIGHT_IDS || id in manual
        if (st != 0 && st != 3 && !always) return
        when {
            id == DOOR_POS -> DOOR_AREAS[area]?.let { doors[it] = iv != 0 && iv != 2 }
            id == TURN_LEFT -> if (iv == 2) leftLitAt = now
            id == TURN_RIGHT -> if (iv == 2) rightLitAt = now
            id == TURN_COMBINED -> combined = iv
            id == resolved["HEADLIGHTS_STATE"] -> headlights = iv
            id == resolved["HIGH_BEAM_LIGHTS_STATE"] -> highBeam = iv
            id == resolved["HAZARD_LIGHTS_STATE"] -> hazard = iv
            id == resolved["TURN_SIGNAL_STATE"] -> turnState = iv
            id == resolved["IGNITION_STATE"] -> ignition = iv
        }
        emit()
    }

    private val tick = Runnable { emit() }

    /** mapeamento manual (modo guiado): o campo está aceso? null = campo não mapeado */
    private fun manualOn(field: String, now: Long): Boolean? {
        var any = false; var on = false
        for ((id, fe) in manual) {
            val (f, expect) = fe
            if (f != field) continue
            any = true
            val v = values[id] ?: continue
            on = on || when {
                expect == "pisca" -> blinking(id, now)
                expect == null -> v != 0
                else -> v.toString() == expect
            }
        }
        return if (any) on else null
    }

    private fun emit() {
        val now = SystemClock.elapsedRealtime()
        // setas: alavanca (confirmada no EX2 BR), lâmpadas piscando, e os sinais antigos como reserva
        val lever = values[TURN_LEVER]
        var left = lever == 1 || blinking(BODY_TURN_LEFT, now) || now - leftLitAt < LAMP_HOLD_MS
        var right = lever == 2 || blinking(BODY_TURN_RIGHT, now) || now - rightLitAt < LAMP_HOLD_MS
        if (!left && !right && turnState != null) { left = turnState == 2; right = turnState == 1 }
        if (!left && !right && combined != null && leftLitAt == 0L && rightLitAt == 0L) { left = combined == 1; right = combined == 2 }
        manualOn("seta_esq", now)?.let { left = it }
        manualOn("seta_dir", now)?.let { right = it }
        val hazardOn = manualOn("alerta", now) ?: (steadyOn(BCM_HAZARD) == true || steadyOn(HAZARD_AOSP) == true || hazard == ON ||
            (blinking(BODY_TURN_LEFT, now) && blinking(BODY_TURN_RIGHT, now)))
        val signal = if (hazardOn || (left && right)) 2 else if (left) 1 else if (right) -1 else 0
        // farol baixo e alto separados; sem resposta do baixo, o modo noturno (a central liga com o farol)
        val low = manualOn("farol", now) ?: values[LOW_BEAM_EX2]?.let { it == 1 } ?: steadyOn(HEADLIGHTS)
        // farol alto do EX2 (confirmado); durante o pisca-alerta esse sinal oscila: não conta
        val highEx2 = if (hazardOn && blinking(HIGH_BEAM_EX2, now)) null else values[HIGH_BEAM_EX2]?.let { it == 1 }
        val high = manualOn("alta", now) ?: highEx2 ?: steadyOn(HIGH_BEAM)
        val farol = manualFarol ?: low ?: steadyOn(NIGHT_MODE)
        // (0x21121900 era usado como DRL, mas o app de energia da própria central diz que ele é o cinto do
        // motorista: o DRL segue a ignição)
        val drl = manualOn("drl", now) ?: manualDrl ?: ignition?.let { it >= 4 }
        values[DRIVER_SEAT]?.let { seat -> if (seat == 0x1 || seat == 0x4) DriveSide.fromCar(ctx, seat == 0x4) }
        // freio do EX2: 1 = pedal pisado (confirmado no log: 1 parado no semáforo, 0 antes de acelerar)
        val brake = manualOn("freio", now) ?: manualBrake ?: values[BRAKE_EX2]?.let { it == 1 }
        // velocidade: no EX2 já vem em km/h (máximo do log = 69); antes era multiplicada por 3,6 (rodas 3,6× rápidas)
        val speed = floats[SPEED]?.let { Math.abs(it) }
        // volante do EX2 em graus (±540): roda = volante ÷ 15,5; reserva: esterço padrão Android
        // (2.8.1: sinal invertido — no carro, virando para um lado as rodas do 3D iam para o outro)
        val wheel = floats[STEER_EX2]?.let { (it / STEER_RATIO).coerceIn(-35f, 35f) } ?: floats[STEER]?.let { it.coerceIn(-35f, 35f) }
        val g = values[GEAR_EX2] ?: values[GEAR]
        val gear = if (values[PARK_EX2] == 1) "P" else when (g) { 4 -> "P"; 2 -> "R"; 1 -> "N"; null -> null; else -> if (g >= 8) "D" else null }
        // temperatura externa: só AC_AMBIENT_TEMP (inteiro; °C = (valor − 80) / 2) — o mesmo número da barra do
        // carro. Os dois sinais antigos (0x24120400 / 0x404000) NÃO são a externa: no log de 17/11 marcavam
        // 60–67 °C rodando (temperatura de algum componente). Sem o sinal certo, vale a da internet.
        val outside = values[AC_AMBIENT]?.takeIf { it in 1..250 }?.let { (it - 80) / 2f }
        fun num(id: Int): Float? = floats[id] ?: values[id]?.toFloat()
        val charge = ChargeReading(num(CHG_POWER), num(CHG_VOLT), num(CHG_CURR), num(CHG_TIME), num(CHG_DC),
            values[CHG_STATE], values[CHG_PLUG], num(SOC_PCT)?.takeIf { it > 0f && it <= 100f }, num(ODOMETER))
        val v = Vehicle(LinkedHashMap(doors), signal, farol, drl, brake, high, speed, wheel, gear, steadyOn(NIGHT_MODE),
            outside?.takeIf { it > -60f && it < 80f }, ignition?.let { it >= 4 }, charge)
        if (v != last) { last = v; listeners.forEach { l -> Guard.run("sinais do carro → cena") { l.onVehicle(v) } } }
        main.removeCallbacks(tick)
        // reavalia sozinho enquanto algo pisca (o pisca "acaba" quando o sinal para de trocar)
        if (left || right || hazardOn) main.postDelayed(tick, 250)
    }

    /** texto para a tela Diagnóstico */
    fun report() = buildString {
        append("Status: ").append(status).append('\n')
        append("Propriedades anunciadas: ").append(available.size).append('\n')
        resolved.forEach { (k, v) -> append("  ").append(k).append(" = 0x").append(Integer.toHexString(v)).append('\n') }
        append("Último estado: ").append(last ?: "—").append('\n')
        append("Valores recentes:\n")
        lastValues.entries.toList().takeLast(40).asReversed().forEach { append("  ").append(it.key).append(" = ").append(it.value).append('\n') }
    }

    companion object {
        const val DOOR_POS = 0x16400B00
        const val TURN_LEFT = 0x2140139C
        const val TURN_RIGHT = 0x2140139D
        const val TURN_COMBINED = 0x21407105
        // CONFIRMADO no carro do usuário pelo modo guiado (sinais-luzes.txt, 10/11/2026):
        //   alavanca da seta 0x2140A205 (1 esq / 2 dir), pisca-alerta 0x2140A632 (1 = ligado),
        //   farol alto 0x21121300 (1 = ligado; também troca durante o pisca-alerta → ignorado nele)
        const val HIGH_BEAM_EX2 = 0x21121300
        // movimento, padrão Android (a confirmar no EX2): velocidade (m/s), esterço (graus), marcha
        const val SPEED = 0x11600207
        const val STEER = 0x11600209
        const val GEAR = 0x11400400
        const val OUTSIDE_TEMP = 0x11600703      // no EX2 fica sempre em 25,0 (não é o sensor): não usado
        // achados no log do carro (vhal-log.txt): freio 1 = pisado; marcha 4 P / 2 R / 8 D; "em P" 1;
        // volante em graus; farol baixo e DRL (candidatos fortes); temperatura externa (2 candidatos, 1° de diferença)
        const val BRAKE_EX2 = 0x2140800C
        const val GEAR_EX2 = 0x11400401
        const val PARK_EX2 = 0x20061000
        const val STEER_EX2 = 0x101000
        const val STEER_RATIO = 15.5f
        const val LOW_BEAM_EX2 = 0x21121400
        const val DRL_EX2 = 0x21121900
        const val AC_AMBIENT = 0x2140A377
        const val OUTSIDE_TEMP_EX2 = 0x24120400
        const val OUTSIDE_TEMP_EX2_B = 0x404000
        const val IGNITION = 0x11400409
        // documentação da central BR (lista do amigo do usuário)
        const val TURN_LEVER = 0x2140A205
        const val BODY_TURN_LEFT = 0x2140209C
        const val BODY_TURN_RIGHT = 0x2140209D
        const val BCM_HAZARD = 0x2140A632
        const val NIGHT_MODE = 0x21408015
        const val HEADLIGHTS = 0x11400E00
        const val HIGH_BEAM = 0x11400E01
        const val HAZARD_AOSP = 0x11400E03
        // carregamento (app geelybattery 0.3.0; existem na central do usuário — catálogo, seção 8)
        const val CHG_POWER = 0x24205400   // potência entrando (kW)
        const val CHG_VOLT = 0x24140100    // tensão da carga (V)
        const val CHG_CURR = 0x24140200    // corrente da carga (A)
        const val CHG_TIME = 0x24140300    // tempo restante (min)
        const val CHG_DC = 0x24207100      // potência na carga DC (kW)
        const val CHG_STATE = 0x24205C00   // estado (0x24205C02 AC, C15 DC, C03/C04 fim…)
        const val CHG_PLUG = 0x24130200    // plugue (conectado: 0x24130213/14/1B)
        // % da bateria: IVirtualSensor.TYPE_EV_BATTERY_PERCENTAGE do app de energia da própria central
        // (AutoEnergy, dump do sistema 11/2026). Vem com uma casa (66,9 · 66,5…). O 0x403000 usado
        // antes ficava parado durante a carga (o widget travava em 47%).
        const val SOC_PCT = 0x404000
        const val ODOMETER = 0x11600204    // hodômetro (km)
        const val DRIVER_SEAT = 0x1540010A // INFO_DRIVER_SEAT: 0x1 = motorista à esquerda, 0x4 = à direita

        /** sinais que chegam muitas vezes por segundo (registro resumido) */
        val FAST_IDS = setOf(SPEED, STEER, STEER_EX2, CHG_POWER, CHG_VOLT, CHG_CURR, CHG_TIME, ODOMETER, SOC_PCT)
        val LIGHT_IDS = setOf(TURN_LEVER, BODY_TURN_LEFT, BODY_TURN_RIGHT, BCM_HAZARD, NIGHT_MODE, HEADLIGHTS, HIGH_BEAM, HAZARD_AOSP, HIGH_BEAM_EX2,
            BRAKE_EX2, GEAR_EX2, PARK_EX2, STEER_EX2, LOW_BEAM_EX2, AC_AMBIENT, SOC_PCT, DRIVER_SEAT)
        /** nomes para a tela do modo guiado */
        val KNOWN = mapOf(TURN_LEVER to "alavanca da seta", BODY_TURN_LEFT to "lâmpada seta esq.", BODY_TURN_RIGHT to "lâmpada seta dir.",
            BCM_HAZARD to "pisca-alerta (BCM)", HAZARD_AOSP to "pisca-alerta (padrão)", HEADLIGHTS to "farol (padrão)",
            HIGH_BEAM to "luz alta (padrão)", NIGHT_MODE to "modo noturno", HIGH_BEAM_EX2 to "farol alto (EX2)",
            BRAKE_EX2 to "freio (EX2)", LOW_BEAM_EX2 to "farol baixo (EX2)", DRL_EX2 to "DRL (EX2)", GEAR_EX2 to "marcha", PARK_EX2 to "em P")
        private const val ON = 2
        private const val LAMP_HOLD_MS = 1300L
        /** troca de valor mais espaçada que isso = não está piscando */
        private const val BLINK_MS = 1100L
        private const val POLL_MS = 250L
        val DOOR_AREAS = linkedMapOf(0x1 to Openings.FL, 0x4 to Openings.FR, 0x10 to Openings.RL, 0x40 to Openings.RR,
            0x10000000 to Openings.FRUNK, 0x20000000 to Openings.TAILGATE)

        private fun toInt(v: Any?): Int = when (v) {
            is Int -> v; is Boolean -> if (v) 1 else 0; is Float -> Math.round(v); is Long -> v.toInt()
            is Array<*> -> (v.firstOrNull() as? Int) ?: 0
            else -> 0
        }

        private fun cause(t: Throwable): String { var c = t; while (c.cause != null) c = c.cause!!; return c.toString() }

        @SuppressLint("StaticFieldLeak") private var instance: CarBridge? = null
        @Synchronized fun get(c: Context) = instance ?: CarBridge(c).also { instance = it }
    }
}
