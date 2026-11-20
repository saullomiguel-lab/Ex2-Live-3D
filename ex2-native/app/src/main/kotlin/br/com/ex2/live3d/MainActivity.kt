package br.com.ex2.live3d

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Tela do app: a cena 3D em tela cheia com janelas flutuantes em volta do carro. */
class MainActivity : Activity() {
    private lateinit var world: World
    private lateinit var driver: SceneDriver
    private lateinit var vp: Viewport3D
    private val main = Handler(Looper.getMainLooper())
    private val ctx: Context get() = this

    private lateinit var root: FrameLayout
    private lateinit var carTitle: TextView
    private lateinit var carSummary: TextView
    private lateinit var loading: TextView
    private lateinit var topView: CarTopView
    private lateinit var hourText: TextView
    private lateinit var hourMode: TextView
    private lateinit var dayTrack: DayTrack
    private lateinit var nowBtn: IconButton
    private lateinit var tempView: TempView
    private val viewBtns = LinkedHashMap<String, IconButton>()
    private val lightBtns = LinkedHashMap<String, IconButton>()
    /** tudo o que buildUi() pôs na tela (sai e volta quando o tema claro/escuro troca) */
    private val uiViews = ArrayList<View>()
    private var currentView: String? = "front-34"
    private var frames = 0
    private var fps = 0f
    private var fpsAt = 0L

    private fun bgColor() = if (Ui.dark) 0xFF0B1118.toInt() else 0xFFE6EAF0.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // até o carro informar, o tema segue o do sistema
        Ui.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        root = FrameLayout(this)
        root.setBackgroundColor(bgColor())
        setContentView(root)
        loading = Ui.text(ctx, "Preparando o EX2 3D…", 18f, Ui.TEXT, true).apply {
            background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 20f))
            setPadding(Ui.dpi(ctx, 22), Ui.dpi(ctx, 12), Ui.dpi(ctx, 22), Ui.dpi(ctx, 12))
        }
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // trabalho pesado em segundo plano; a tela nunca trava
        Startup.prepare(this) {
            if (isFinishing || isDestroyed) return@prepare
            val report = CrashGuard.report(ctx)
            val err = Startup.error
            when {
                err != null -> showReport("Falha ao preparar os efeitos:\n" + Log.trace(err) + "\n\n" + (report ?: Diag.dump()), retry = false)
                report != null -> {
                    // monitor de erros: a abertura anterior falhou. 1ª vez: tenta de novo sozinho; 2ª vez seguida:
                    // tenta com a qualidade Econômica (mais leve); só depois mostra o relatório
                    val streak = Prefs.int(ctx, CrashGuard.STREAK, 0) + 1
                    Prefs.get(ctx).edit().putInt(CrashGuard.STREAK, streak).apply()
                    when (streak) {
                        1 -> { Diag.log("Monitor: a abertura anterior falhou; tentando de novo sozinho"); CrashGuard.keep(ctx); start3D() }
                        2 -> {
                            Diag.log("Monitor: falhou de novo; abrindo com a qualidade Econômica")
                            Prefs.get(ctx).edit().putString(Prefs.QUALITY, "economica").apply()
                            CrashGuard.keep(ctx); start3D()
                        }
                        else -> showReport(report, retry = true)
                    }
                }
                else -> start3D()
            }
        }
    }

    private var started = false

    /** relatório do erro da vez anterior (foto desta tela ajuda a corrigir) */
    private fun showReport(text: String, retry: Boolean) {
        loading.visibility = View.GONE
        val box = Ui.panel(ctx).apply {
            addView(Ui.text(ctx, "O 3D não abriu da última vez", 22f, Ui.TEXT, true))
            addView(Ui.text(ctx, "Tire uma foto desta tela e me envie. Arquivo: " + CrashGuard.path(ctx), 14f, Ui.MUTED))
            val body = Ui.text(ctx, text, 12f, Ui.TEXT).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
            addView(ScrollView(ctx).apply { addView(body) }, Ui.lp(-1, Ui.dpi(ctx, 420)).apply { topMargin = Ui.dpi(ctx, 10) })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            if (retry) row.addView(pill(ctx, "Tentar abrir o 3D", primary = true) {
                CrashGuard.clear(ctx); root.removeView(this@apply.parent as View); loading.visibility = View.VISIBLE; start3D()
            })
            row.addView(pill(ctx, "Fechar") { finish() }, Ui.lp().apply { leftMargin = Ui.dpi(ctx, 12) })
            addView(row, Ui.lp().apply { topMargin = Ui.dpi(ctx, 12) })
        }
        val holder = FrameLayout(ctx)
        holder.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(Ui.dpi(ctx, 60), 0, Ui.dpi(ctx, 60), 0) })
        root.addView(holder, FrameLayout.LayoutParams(-1, -1))
    }

    private lateinit var surface: SurfaceView

    private fun start3D() {
        CrashGuard.begin(ctx)
        // localização do carro (cidade, clima, nascer/pôr do sol): sem pedir, o sistema negava e o app ficava em São Paulo
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), 7) } catch (_: Throwable) { }
        }
        Diag.log("Tela: criando a cena")
        world = World.acquire(this)
        Diag.log("Tela: cena criada")
        driver = SceneDriver(this, world)
        val sv = SurfaceView(this)
        surface = sv
        root.addView(sv, 0, FrameLayout.LayoutParams(-1, -1))
        vp = Viewport3D(world, this)
        vp.orbit.set(Orbit.VIEWS.getValue("front-34"), animate = false)
        vp.attach(sv)
        vp.onFrame = { t ->
            CrashGuard.ok(ctx); countFrame(t)
            // a cor da temperatura acompanha o fundo (muda no meio da transição paisagem ↔ estrada)
            if (::tempView.isInitialized) { val l = !world.backdropLight(); if (tempView.light != l) tempView.light = l }
        }
        vp.onInteract = { selectView(null) }
        sv.setOnTouchListener { v, e -> vp.onTouch(e, v.width) }
        // pontos de toque sobre portas, porta-malas e capô (acima do 3D, abaixo das janelas)
        val spots = Hotspots(this, { world.isOpen(it) }) { name -> world.setOpening(name, !world.isOpen(name)); refresh() }
        root.addView(spots, 1, FrameLayout.LayoutParams(-1, -1))
        vp.onCamera = { v, p, e -> spots.update(v, p, e) }
        world.openListeners.add(openListener)
        ChargeMonitor.get(this).listeners.add(chargeListener)
        registerReceiver(debugReceiver, android.content.IntentFilter("br.com.ex2.live3d.DEBUG"))
        val dm = resources.displayMetrics
        vp.setScale(Prefs.scale(this), dm.widthPixels, dm.heightPixels)
        vp.setShadows(Prefs.shadows(this))

        Ui.dark = driver.isNight()
        root.setBackgroundColor(bgColor())
        buildUi()
        driver.onChange = { refresh() }
        // câmera automática: andando, o carro aparece por trás; 30 s parado, volta para a frente
        driver.onDriving = { moving ->
            if (Prefs.bool(ctx, Prefs.AUTO_CAM, true)) {
                val id = if (moving) "rear-34" else "front-34"
                selectView(id); vp.orbit.set(Orbit.VIEWS.getValue(id), animate = true); vp.requestFrame()
            }
        }
        driver.start()
        selectView("front-34")
        refresh()

        // ordem de aparição (só na 1ª abertura depois de instalar, para aliviar a central):
        // 1) cenário + chão + botões, 2) carro, 3) pontos de toque.
        // Com a cena já aberta (papel de parede rodando) ou com cache, entra tudo junto.
        alreadyOpen = world.sceneryReady
        if (alreadyOpen || world.warmStart) world.requestCar()
        if (!alreadyOpen) {
            panels = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== sv && it !== spots && it !== loading }
            panels.forEach { it.alpha = 0f }
            cover = View(this).apply { setBackgroundColor(bgColor()) }
            root.addView(cover, 1, FrameLayout.LayoutParams(-1, -1))   // tampa o 3D até o cenário estar pronto
        }
        Diag.log("Tela: abertura " + when { alreadyOpen -> "imediata (cena já aberta)"; world.warmStart -> "rápida (cache)"; else -> "em etapas (1ª vez)" })
        spots.alpha = 0f
        hotspots = spots
        world.sceneryListeners.add(sceneryListener)
        world.carListeners.add(carListener)
        loading.bringToFront()
        main.post(loadWatch)
        started = true
        // aberto pelo botão Editar do widget de carga no papel de parede
        if (intent?.getBooleanExtra(EXTRA_PRICE, false) == true) main.postDelayed({ pickPrice() }, 700)
        vp.setVisible(true)
        if (world.sceneryReady) sceneryListener()
        if (world.carShown) carListener()
        updateHud()
        // idioma do carro ou lado do motorista mudou: monta os painéis de novo
        L.listeners.add(uiListener); DriveSide.listeners.add(uiListener)
        // versão nova no GitHub? (pergunta antes de baixar)
        main.postDelayed({ autoUpdateCheck() }, 4000)
    }

    private val uiListener: () -> Unit = { main.post { if (started && !isFinishing) { rebuildUi(); refresh() } } }

    private var panels: List<View> = emptyList()
    private var alreadyOpen = false
    private var cover: View? = null
    private var hotspots: View? = null

    /** 1) cenário pronto: some a tampa, aparecem os botões; o carro entra logo depois */
    private val sceneryListener: () -> Unit = {
        cover?.animate()?.alpha(0f)?.setDuration(450)?.withEndAction { cover?.visibility = View.GONE }?.start()
        panels.forEach { it.animate().alpha(1f).setDuration(450).start() }
        if (!world.warmStart) main.postDelayed({ world.requestCar() }, 350)
        // (confere de novo na hora de abrir: o aviso de "cenário pronto" pode chegar duas vezes)
        if (!Prefs.bool(this, Prefs.SETUP_DONE, false)) main.postDelayed({
            if (!Prefs.bool(this, Prefs.SETUP_DONE, false)) showSetup(first = true)
        }, 500)
    }

    /** 2) carro na tela: 3) os pontos de toque aparecem por último */
    private val carListener: () -> Unit = {
        loading.visibility = View.GONE
        // o carro leva alguns quadros para surgir depois de entrar na cena: os pontos vêm depois
        // (com a cena já aberta o carro já está desenhado: os pontos vêm na hora)
        hotspots?.animate()?.alpha(1f)?.setStartDelay(if (alreadyOpen) 0 else 1400)?.setDuration(if (alreadyOpen) 200 else 450)?.start()
    }

    /** ajustes ao vivo para testes no emulador (adb shell am broadcast -a br.com.ex2.live3d.DEBUG --es sun 3) */
    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            i.extras?.keySet()?.forEach { k -> world.debug(k, i.getStringExtra(k) ?: "") }
            if (i.hasExtra("view")) Orbit.VIEWS[i.getStringExtra("view")]?.let { vp.orbit.set(it, false); vp.requestFrame() }
            if (i.hasExtra("settings")) showSettings(i.getStringExtra("settings") ?: "carro")
            // câmera num ponto exato (teste): --es pos "x,y,z" (olha para o centro do carro)
            i.getStringExtra("pos")?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.let { p ->
                vp.orbit.set(Orbit.Pose.from(p.toFloatArray(), floatArrayOf(0f, .72f, .05f)), false); selectView(null); vp.requestFrame()
            }
            if (i.hasExtra("gear")) driver.debugGear = i.getStringExtra("gear")?.takeIf { it.isNotEmpty() && it != "-" }
            if (i.hasExtra("time")) pickTime(Prefs.DUSK, driver.dusk())
            if (i.hasExtra("charge")) ChargeMonitor.get(ctx).simulate()   // teste: carga simulada
            // teste: idioma (--es lang en|ar|th|auto…), lado do motorista (--es side right|left|auto), pergunta da chuva (--es rainask 1)
            i.getStringExtra("lang")?.let { Prefs.get(ctx).edit().putString(Prefs.LANG, it).apply(); L.init(ctx) }
            i.getStringExtra("side")?.let { Prefs.get(ctx).edit().putString(Prefs.DRIVE_SIDE, it).apply(); rebuildUi(); refresh() }
            if (i.hasExtra("rainask")) {
                Prefs.get(ctx).edit().putBoolean(Prefs.AUTO_WEATHER, true).putBoolean(Prefs.RAIN_OK, false).remove(Prefs.RAIN_SNOOZE).apply()
                driver.debugForecast = if (i.getStringExtra("rainask") == "0") null else "chuva"; driver.applyEnv()
            }
            if (i.hasExtra("update")) autoUpdateCheckNow()
            i.getStringExtra("installfile")?.let { Updater.installLocal(ctx, it); showSettings("atualizacoes") }
        }
    }

    /** porta/tampa abriu: a câmera vai até ela */
    private val openListener: (String) -> Unit = { name ->
        Orbit.forOpening(name)?.let { vp.orbit.set(it, animate = true); selectView(null); vp.requestFrame() }
        main.post { refresh() }
    }

    private val loadWatch = object : Runnable {
        override fun run() {
            if (world.carShown) { loading.visibility = View.GONE; refresh(); return }
            loading.text = L.f("Carregando o EX2 3D… %d%%", (world.loadProgress * 100).toInt())
            main.postDelayed(this, 200)
        }
    }

    override fun onNewIntent(i: android.content.Intent?) {
        super.onNewIntent(i)
        if (started && i?.getBooleanExtra(EXTRA_PRICE, false) == true) pickPrice()
    }

    companion object { const val EXTRA_PRICE = "editChargePrice" }

    /** permitiu a localização: busca clima e cidade de novo já com a posição certa */
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 7 && res.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            Diag.log("Localização permitida")
            Prefs.get(ctx).edit().remove(Prefs.CITY_AT).apply()
            WeatherSync.get(ctx).refresh()
        }
    }

    override fun onResume() { super.onResume(); if (started) { vp.setVisible(true); driver.applyEnv(); refreshWallChip() } }
    override fun onPause() { if (started) vp.setVisible(false); super.onPause() }

    /** o sistema trocou claro/escuro (a tela não é recriada: o 3D continua) */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (started) driver.applyEnv()
    }

    /** voltar fecha primeiro a janela aberta (ajustes), depois o app */
    @Deprecated("Android 9 da central")
    override fun onBackPressed() {
        pickerClose?.let { it(); return }
        if (sheet != null) { closeSheet(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (started) {
            world.openListeners.remove(openListener)
            ChargeMonitor.get(this).listeners.remove(chargeListener)
            L.listeners.remove(uiListener); DriveSide.listeners.remove(uiListener)
            world.sceneryListeners.remove(sceneryListener)
            world.carListeners.remove(carListener)
            try { unregisterReceiver(debugReceiver) } catch (_: Exception) { }
            driver.stop(); vp.destroy(); World.release()
        }
        super.onDestroy()
    }

    private fun countFrame(t: Long) {
        frames++
        if (fpsAt == 0L) fpsAt = t
        if (t - fpsAt > 1_000_000_000L) { fps = frames * 1e9f / (t - fpsAt); frames = 0; fpsAt = t }
    }

    /* ======================= montagem ======================= */

    /**
     * põe um painel na tela. Volante à direita: os painéis trocam de lado (o que fica à esquerda no Brasil
     * vai para a direita). Em árabe a tela toda já é espelhada pelo Android (direita para a esquerda).
     */
    private fun place(v: View, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
        val swap = DriveSide.rhd(ctx)
        var g = gravity
        if (swap) {
            val h = g and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK
            g = (g and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv()) or when (h) { Gravity.START -> Gravity.END; Gravity.END -> Gravity.START; else -> h }
        }
        root.addView(v, FrameLayout.LayoutParams(-2, -2, g).apply {
            topMargin = Ui.dpi(ctx, t); bottomMargin = Ui.dpi(ctx, b)
            marginStart = Ui.dpi(ctx, if (swap) r else l); marginEnd = Ui.dpi(ctx, if (swap) l else r)
        })
        uiViews.add(v)
    }

    /** a temperatura e o widget de carga vão para o canto oposto ao do cartão do carro */
    private fun widgetsMirrored() = DriveSide.mirrored(ctx)

    /** botão pequeno de ícone + texto em linha (cartão do carro) */
    private fun chip(icon: Int, label: String, primary: Boolean = false, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(if (primary) Ui.CHIP_ON else Ui.CHIP, Ui.dp(ctx, 14f))
        setPadding(Ui.dpi(ctx, 14), 0, Ui.dpi(ctx, 16), 0)
        minimumHeight = Ui.dpi(ctx, 48)
        val fg = if (primary) Color.WHITE else Ui.ICON
        addView(ImageView(ctx).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(fg) }, Ui.lp(Ui.dpi(ctx, 22), Ui.dpi(ctx, 22)))
        addView(Ui.text(ctx, label, 15.5f, if (primary) Color.WHITE else Ui.TEXT, true), Ui.lp().apply { leftMargin = Ui.dpi(ctx, 8) })
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val m = 18
        // árabe: a tela inteira da direita para a esquerda (textos, ajustes, ordem dos botões)
        root.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        // temperatura externa: canto de cima à direita, como na tela inicial do carro (não recebe toques)
        tempView = TempView(ctx).apply { mirror = widgetsMirrored() }
        root.addView(tempView, FrameLayout.LayoutParams(-1, -1))
        uiViews.add(tempView)
        // carregando: o widget de carga entra no lugar da temperatura
        chargeView = ChargeView(ctx, onEdit = { pickPrice() }, onClose = { ChargeMonitor.get(ctx).close() }).apply { mirror = widgetsMirrored() }
        root.addView(chargeView, FrameLayout.LayoutParams(-1, -1))
        uiViews.add(chargeView)

        // --- cartão do carro (topo esquerdo): nome, cor/placa e ações
        val card = Ui.panel(ctx).apply {
            setPadding(Ui.dpi(ctx, 20), Ui.dpi(ctx, 16), Ui.dpi(ctx, 16), Ui.dpi(ctx, 16))
            carTitle = Ui.text(ctx, "Geely EX2", 24f, Ui.TEXT, true)
            carSummary = Ui.text(ctx, "", 15f, Ui.MUTED)
            addView(carTitle); addView(carSummary, Ui.lp().apply { topMargin = Ui.dpi(ctx, 2) })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(chip(R.drawable.ic_edit, "Editar carro", primary = true) { showSetup(first = false) })
            // um toque: ativa o papel de parede 3D; ativo, o mesmo botão desativa (volta ao da central)
            wallChip = chip(R.drawable.ic_wallpaper, "Ativar papel de parede") { toggleWallpaper() }
            row.addView(wallChip, Ui.lp().apply { leftMargin = Ui.dpi(ctx, 8) })
            row.addView(chip(R.drawable.ic_gear, "Ajustes") { showSettings("carro") }, Ui.lp().apply { leftMargin = Ui.dpi(ctx, 8) })
            addView(row, Ui.lp().apply { topMargin = Ui.dpi(ctx, 14) })
        }
        place(card, Gravity.TOP or Gravity.START, m, m)

        // --- vistas (coluna esquerda)
        val views = Ui.panel(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dpi(ctx, 10), Ui.dpi(ctx, 10), Ui.dpi(ctx, 10), Ui.dpi(ctx, 2))
            listOf("front-34" to (R.drawable.ic_view_34 to "3/4 frente"), "front" to (R.drawable.ic_view_front to "Frente"),
                "side" to (R.drawable.ic_view_side to "Lateral"), "rear-34" to (R.drawable.ic_view_rear to "Traseira"),
                "top" to (R.drawable.ic_view_top to "Topo")).forEach { (id, v) ->
                val b = IconButton(ctx, v.first, v.second, 62)
                b.setOnClickListener { selectView(id); vp.orbit.set(Orbit.VIEWS.getValue(id), animate = true); vp.requestFrame() }
                viewBtns[id] = b
                addView(b, Ui.lp(Ui.dpi(ctx, 84)).apply { bottomMargin = Ui.dpi(ctx, 8) })
            }
        }
        place(views, Gravity.BOTTOM or Gravity.START, m, 0, 0, m)

        // --- portas e tampas (direita, meio)
        val doors = Ui.panel(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dpi(ctx, 16), Ui.dpi(ctx, 14), Ui.dpi(ctx, 16), Ui.dpi(ctx, 16))
            addView(Ui.text(ctx, "Portas", 16f, Ui.TEXT, true))
            topView = CarTopView(ctx, { world.isOpen(it) }) { name -> world.setOpening(name, !world.isOpen(name)); refresh() }
            addView(topView, Ui.lp().apply { topMargin = Ui.dpi(ctx, 6) })
            // fecha portas, porta-malas e capô de uma vez (só aparece com 2 ou mais abertas)
            closeAll = pill(ctx, "Fechar tudo") {
                Openings.all.forEach { world.setOpening(it, false) }
                topView.invalidate(); refresh()
            }.apply {
                textSize = 15f; minHeight = Ui.dpi(ctx, 44); setPadding(Ui.dpi(ctx, 16), Ui.dpi(ctx, 8), Ui.dpi(ctx, 16), Ui.dpi(ctx, 8))
                visibility = View.GONE
            }
            addView(closeAll, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 10) })
        }
        place(doors, Gravity.CENTER_VERTICAL or Gravity.END, 0, 40, m)
        doorsPanel = doors

        // --- luzes (direita, embaixo)
        val lights = Ui.panel(ctx, vertical = false).apply {
            setPadding(Ui.dpi(ctx, 10), Ui.dpi(ctx, 10), Ui.dpi(ctx, 10), Ui.dpi(ctx, 10))
            fun add(id: String, icon: Int, label: String, amber: Boolean = false) {
                val b = IconButton(ctx, icon, label, 62)
                if (amber) b.activeColor = Ui.AMBER
                b.setOnClickListener { toggleLight(id) }
                lightBtns[id] = b
                addView(b, Ui.lp().apply { leftMargin = if (childCount > 0) Ui.dpi(ctx, 8) else 0 })
            }
            add("left", R.drawable.ic_left, "Seta esq.", true)
            add("hazard", R.drawable.ic_hazard, "Alerta", true)
            add("right", R.drawable.ic_right, "Seta dir.", true)
            add("farol", R.drawable.ic_headlight, "Farol")
            add("alta", R.drawable.ic_headlight, "Alto")
            add("drl", R.drawable.ic_drl, "DRL")
            add("brake", R.drawable.ic_brake, "Freio")
        }
        place(lights, Gravity.BOTTOM or Gravity.END, 0, 0, m, m)
        lightsPanel = lights

        // --- linha do dia (embaixo, ao lado das vistas): ver as transições do cenário
        //     (amanhecer, anoitecer, clima e os automáticos ficam em Ajustes → Horário e clima)
        val bar = Ui.panel(ctx, vertical = false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dpi(ctx, 20), Ui.dpi(ctx, 8), Ui.dpi(ctx, 10), Ui.dpi(ctx, 8))
            val hourBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                hourText = Ui.text(ctx, "", 26f, Ui.TEXT, true)
                hourMode = Ui.text(ctx, "", 13f, Ui.MUTED)
                addView(hourText); addView(hourMode)
            }
            addView(hourBox, Ui.lp(Ui.dpi(ctx, 96)))
            dayTrack = DayTrack(ctx) { m -> Prefs.get(ctx).edit().putInt(Prefs.HOUR, m).apply() }
            addView(dayTrack, Ui.lp(Ui.dpi(ctx, 420), -2).apply { leftMargin = Ui.dpi(ctx, 6) })
            nowBtn = IconButton(ctx, R.drawable.ic_clock, "Agora", 62).apply {
                setOnClickListener { Prefs.get(ctx).edit().putInt(Prefs.HOUR, -1).apply() }
            }
            addView(nowBtn, Ui.lp().apply { leftMargin = Ui.dpi(ctx, 12) })
            // só chovendo: tira a chuva (1 h no clima automático; nublado no clima escolhido à mão)
            rainBtn = IconButton(ctx, R.drawable.ic_rain, "Suspender chuva", 62).apply {
                activeColor = Ui.BLUE; on = true
                setOnClickListener { val auto = Prefs.autoWeather(ctx); RainGate.suspend(ctx); toast(if (auto) "Chuva suspensa por 1 hora" else "Chuva suspensa") }
                visibility = if (world.raining) View.VISIBLE else View.GONE
            }
            addView(rainBtn, Ui.lp().apply { leftMargin = Ui.dpi(ctx, 8) })
        }
        place(bar, Gravity.BOTTOM or Gravity.START, m + 104 + 12, 0, 0, m)
    }

    /** tema claro/escuro trocou: monta os painéis de novo com a outra paleta (o 3D continua) */
    private fun rebuildUi() {
        uiViews.forEach { root.removeView(it) }
        uiViews.clear(); viewBtns.clear(); lightBtns.clear(); wallChipActive = null
        rainAsk?.let { root.removeView(it); rainAsk = null }
        doorsFitCharging = null
        loading.text = L.t("Preparando o EX2 3D…")
        root.setBackgroundColor(bgColor())
        val sheetOpen = sheetSection
        closeSheet()
        buildUi()
        selectView(currentView)
        hud?.let { root.removeView(it); hud = null; updateHud() }
        sheetOpen?.let { showSettings(it) }
        Diag.log("Tela: tema " + if (Ui.dark) "escuro" else "claro")
    }


    /* ======================= estado → tela ======================= */

    /** troca o texto só se mudou (trocar pelo mesmo texto refazia o layout da tela inteira) */
    private fun TextView.put(s: CharSequence) { if (text.toString() != s.toString()) text = s }

    private fun refresh() { Guard.run("atualizar a tela") { refreshNow() } }

    private fun refreshNow() {
        // segue o carro: modo noturno da central → interface escura
        val night = driver.isNight()
        if (night != Ui.dark) { Ui.dark = night; rebuildUi() }
        val trim = CarSpec.trim(Prefs.str(ctx, Prefs.TRIM, "pro"))
        val paint = CarSpec.paint(Prefs.str(ctx, Prefs.PAINT, ""))
        val plate = Prefs.str(ctx, Prefs.PLATE, "")
        carTitle.put("Geely EX2 ${trim.name}")
        carSummary.put(L.t(paint.name) + " · " + L.t(CarSpec.plateLabel(plate)))
        refreshWallChip()
        val minute = driver.minute().toInt()
        val fixed = Prefs.int(ctx, Prefs.HOUR, -1) >= 0
        hourText.put(SkyClock.hhmm(minute))
        hourMode.put(L.t(if (fixed) "hora de teste" else "relógio"))
        dayTrack.dawn = driver.dawn(); dayTrack.dusk = driver.dusk(); dayTrack.minute = minute
        nowBtn.on = !fixed
        sheetRefresh?.invoke()
        val ls = world.lightState
        lightBtns["left"]?.on = ls.signal == 1
        lightBtns["right"]?.on = ls.signal == -1
        lightBtns["hazard"]?.on = ls.signal == 2
        lightBtns["farol"]?.on = ls.farol
        lightBtns["alta"]?.on = ls.alta
        lightBtns["drl"]?.on = ls.drl
        lightBtns["brake"]?.on = ls.brake
        topView.invalidate()
        closeAll.visibility = if (Openings.all.count { world.isOpen(it) } >= 2) View.VISIBLE else View.GONE
        // carregamento (no lugar da temperatura) e temperatura externa
        val cm = ChargeMonitor.get(ctx).model()
        val charging = cm.mode != ChargeModel.HIDDEN
        chargeView.model = cm; chargeView.dark = Ui.dark
        chargeView.visibility = if (charging) View.VISIBLE else View.GONE
        fitDoors(charging)
        rainBtn?.let { val v = if (world.raining) View.VISIBLE else View.GONE; if (it.visibility != v) it.visibility = v }
        tempView.visibility = if (Prefs.bool(ctx, Prefs.OUT_TEMP, true) && !charging) View.VISIBLE else View.GONE
        tempView.text = TempArt.label(driver.outsideTemp())
        tempView.city = if (Prefs.bool(ctx, Prefs.CITY_ON, true)) WeatherSync.get(ctx).city() else null
        tempView.light = !world.backdropLight()
        tempStatus?.put(tempSourceText())
        // a previsão diz chuva: pergunta antes de chover
        if (RainGate.pending(ctx)) showRainAsk() else rainAsk?.let { root.removeView(it); rainAsk = null }
    }

    /* ======================= "Está chovendo aí?" ======================= */

    private var rainAsk: View? = null

    private fun showRainAsk() {
        if (rainAsk != null) return
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 55f) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            setPadding(Ui.dpi(ctx, 30), Ui.dpi(ctx, 24), Ui.dpi(ctx, 30), Ui.dpi(ctx, 24)); isClickable = true
        }
        box.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_rain); imageTintList = ColorStateList.valueOf(Ui.BLUE) }, Ui.lp(Ui.dpi(ctx, 52), Ui.dpi(ctx, 52)))
        box.addView(Ui.text(ctx, "Está chovendo aí?", 24f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 8) })
        box.addView(Ui.text(ctx, "A previsão do tempo diz que está chovendo agora", 16f, Ui.MUTED).apply { gravity = Gravity.CENTER },
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 4); bottomMargin = Ui.dpi(ctx, 20) })
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun answer(rain: Boolean) { RainGate.answer(ctx, rain); root.removeView(overlay); rainAsk = null }
        buttons.addView(pill(ctx, "Adiar chuva") { answer(false) }, Ui.lp(0, -2, 1f).apply { marginEnd = Ui.dpi(ctx, 12) })
        buttons.addView(pill(ctx, "Chover agora", primary = true) { answer(true) }, Ui.lp(0, -2, 1f))
        box.addView(buttons, Ui.lp(-1))
        box.addView(Ui.text(ctx, "Adiar: sem chuva por 1 hora; depois, se a previsão ainda disser chuva, pergunto de novo", 13.5f, Ui.MUTED).apply { gravity = Gravity.CENTER },
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 14) })
        overlay.addView(box, FrameLayout.LayoutParams(Ui.dpi(ctx, 540), -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        rainAsk = overlay
    }

    /* ======================= atualizações ======================= */

    /** ao abrir: com a busca automática ligada e internet, pergunta se quer baixar a versão nova */
    private fun autoUpdateCheck() {
        if (!Updater.autoOn(ctx) || !Updater.online(ctx)) return
        Updater.check(ctx, quiet = true) { r ->
            if (r == null || isFinishing || isDestroyed) return@check
            if (Prefs.str(ctx, Prefs.UPD_SKIP, "") == r.version) return@check
            askUpdate(r)
        }
    }

    /** teste: consulta já e mostra a pergunta mesmo que a versão seja a mesma */
    private fun autoUpdateCheckNow() {
        Updater.check(ctx) { r -> (r ?: Updater.latest)?.let { askUpdate(it) } }
    }

    private fun askUpdate(r: Updater.Release) {
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 58f) }
        fun close() { root.removeView(overlay); pickerClose = null }
        pickerClose = { close() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            setPadding(Ui.dpi(ctx, 30), Ui.dpi(ctx, 24), Ui.dpi(ctx, 30), Ui.dpi(ctx, 24)); isClickable = true
        }
        box.addView(Ui.text(ctx, "Nova versão disponível", 24f, Ui.TEXT, true))
        box.addView(Ui.text(ctx, L.f("EX2 Live 3D %s (você tem a %s). Quer baixar e instalar agora?", r.version, BuildConfig.VERSION_NAME), 16f, Ui.MUTED),
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 6) })
        if (r.notes.isNotBlank()) {
            val notes = Ui.text(ctx, r.notes.take(1500), 14.5f, Ui.TEXT).apply { setLineSpacing(Ui.dp(ctx, 2f), 1f) }
            box.addView(ScrollView(ctx).apply { addView(notes) }, Ui.lp(-1, Ui.dpi(ctx, 220)).apply { topMargin = Ui.dpi(ctx, 12) })
        }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill(ctx, "Agora não") { Prefs.get(ctx).edit().putString(Prefs.UPD_SKIP, r.version).apply(); close() },
            Ui.lp(0, -2, 1f).apply { marginEnd = Ui.dpi(ctx, 12) })
        buttons.addView(pill(ctx, "Baixar e instalar", primary = true) { close(); Updater.install(ctx, r); showSettings("atualizacoes") }, Ui.lp(0, -2, 1f))
        box.addView(buttons, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 18) })
        overlay.addView(box, FrameLayout.LayoutParams(Ui.dpi(ctx, 640), -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun fillUpdates(c: LinearLayout) {
        val status = Ui.text(ctx, "", 14f, Ui.MUTED)
        val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        lateinit var action: TextView
        val notes = Ui.text(ctx, "", 14.5f, Ui.TEXT).apply { setLineSpacing(Ui.dp(ctx, 2f), 1f) }
        fun refreshTab() {
            val r = Updater.latest ?: Updater.cached(ctx)
            val newer = r != null && Updater.newer(r.version, BuildConfig.VERSION_NAME)
            status.text = Updater.status.ifEmpty { L.f("Versão instalada: %s", BuildConfig.VERSION_NAME) }
            bar.visibility = if (Updater.progress >= 0f) View.VISIBLE else View.GONE
            bar.progress = (Updater.progress.coerceIn(0f, 1f) * 1000).toInt()
            action.text = L.t(if (newer) "Baixar e instalar" else "Verificar agora")
            action.alpha = if (Updater.busy) .5f else 1f
            notes.text = if (r != null) L.f("Versão %s no GitHub", r.version) + (if (r.notes.isNotBlank()) "\n\n" + r.notes.take(2500) else "") else ""
        }
        val row = actionRow(L.f("Versão %s", BuildConfig.VERSION_NAME), null, "Verificar agora", primary = true) {
            if (Updater.busy) return@actionRow
            val r = Updater.latest
            if (r != null && Updater.newer(r.version, BuildConfig.VERSION_NAME)) Updater.install(ctx, r) else Updater.check(ctx)
        }
        action = row.getChildAt(1) as TextView
        (row.getChildAt(0) as LinearLayout).addView(status, Ui.lp().apply { topMargin = Ui.dpi(ctx, 2) })
        (row.getChildAt(0) as LinearLayout).addView(bar, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 8) })
        group(c, null,
            toggleRow("Buscar atualizações automaticamente", "Ao abrir o app com internet, avisa se houver versão nova e pergunta se quer baixar", Prefs.UPD_AUTO, true),
            row)
        group(c, "Novidades", LinearLayout(ctx).apply { setPadding(0, Ui.dpi(ctx, 12), 0, Ui.dpi(ctx, 12)); addView(notes) })
        c.addView(Ui.text(ctx, "As versões vêm de github.com/saullomiguel-lab/Ex2-Live-3D. O app baixa e instala sozinho, sem precisar de outro programa.", 13.5f, Ui.MUTED),
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 12); leftMargin = Ui.dpi(ctx, 6) })
        val l: () -> Unit = { refreshTab() }
        Updater.listeners.add(l)
        sheetRefresh = { refreshTab() }
        sheetClose = { Updater.listeners.remove(l) }
        refreshTab()
        if (Updater.latest == null && !Updater.busy) Updater.check(ctx, quiet = true) { refreshTab() }
    }

    private var rainBtn: IconButton? = null
    private var doorsPanel: View? = null
    private var lightsPanel: View? = null
    private var doorsFitCharging: Boolean? = null

    /**
     * Widget de carga no tamanho dele (não mais encolhido) e o painel de Portas se ajusta: desce para
     * logo abaixo do widget e, se não couber até as luzes, diminui por igual (como uma foto). Sem carga,
     * volta ao lugar e tamanho normais.
     */
    private fun fitDoors(charging: Boolean) {
        val d = doorsPanel ?: return
        val l = lightsPanel ?: return
        if (d.height == 0 || l.height == 0 || root.height == 0) { if (doorsFitCharging != charging) d.post { fitDoors(charging) }; return }
        doorsFitCharging = charging
        if (!charging) { d.translationY = 0f; d.scaleX = 1f; d.scaleY = 1f; return }
        val gap = Ui.dp(ctx, 12f)
        val widgetBottom = chargeView.widgetBottom()
        val avail = l.top - gap - (widgetBottom + gap)
        val s = (avail / d.height).coerceIn(.55f, 1f)
        // encolhe a partir do canto de cima do lado da tela (direita; espelhado, esquerda)
        d.pivotX = if (widgetsMirrored()) 0f else d.width.toFloat(); d.pivotY = 0f
        d.scaleX = s; d.scaleY = s
        d.translationY = widgetBottom + gap - d.top
    }

    private lateinit var closeAll: TextView
    private lateinit var chargeView: ChargeView
    private val chargeListener: () -> Unit = { main.post { refresh(); sheetRefresh?.invoke() } }

    /** valor do kWh: colunas de reais e centavos que rolam (como o horário), de R$ 0,00 a R$ 9,99 */
    private fun pickPrice(after: (() -> Unit)? = null) {
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 60f) }
        fun close() { root.removeView(overlay); pickerClose = null }
        pickerClose = { close() }
        overlay.setOnClickListener { close() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            setPadding(Ui.dpi(ctx, 28), Ui.dpi(ctx, 22), Ui.dpi(ctx, 28), Ui.dpi(ctx, 24)); isClickable = true
        }
        box.addView(Ui.text(ctx, "Valor do kWh", 22f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(-1))
        box.addView(Ui.text(ctx, "Base para calcular quanto custa cada carga", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER },
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 2); bottomMargin = Ui.dpi(ctx, 14) })
        val cents = Math.round(ChargeMonitor.get(ctx).price() * 100).coerceIn(0, 999)
        val reais = Wheel(ctx, 10, cents / 100) { "$it" }
        val cent = Wheel(ctx, 100, cents % 100) { "%02d".format(it) }
        fun col(w: Wheel, unit: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(w); addView(Ui.text(ctx, unit, 15f, Ui.MUTED, true).apply { gravity = Gravity.CENTER }, Ui.lp(-1))
        }
        val wheels = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(Ui.text(ctx, L.currency(), 30f, Ui.TEXT, true), Ui.lp().apply { rightMargin = Ui.dpi(ctx, 10); bottomMargin = Ui.dpi(ctx, 24) })
            addView(col(reais, "reais"))
            addView(Ui.text(ctx, ",", 36f, Ui.TEXT, true), Ui.lp().apply { leftMargin = Ui.dpi(ctx, 6); rightMargin = Ui.dpi(ctx, 6); bottomMargin = Ui.dpi(ctx, 24) })
            addView(col(cent, "centavos"))
            addView(Ui.text(ctx, "/ kWh", 20f, Ui.MUTED, true), Ui.lp().apply { leftMargin = Ui.dpi(ctx, 10); bottomMargin = Ui.dpi(ctx, 24) })
        }
        box.addView(wheels, Ui.lp(-1))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill(ctx, "Cancelar") { close() }, Ui.lp(0, -2, 1f).apply { rightMargin = Ui.dpi(ctx, 12) })
        buttons.addView(pill(ctx, "Salvar", primary = true) {
            Prefs.get(ctx).edit().putFloat(Prefs.CHARGE_PRICE, (reais.value * 100 + cent.value) / 100f).apply(); close(); refresh(); after?.invoke()
        }, Ui.lp(0, -2, 1f))
        box.addView(buttons, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 20) })
        overlay.addView(box, FrameLayout.LayoutParams(Ui.dpi(ctx, 560), -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    private fun priceLabel() = L.money(ChargeMonitor.get(ctx).price()) + " / kWh"

    private fun selectView(id: String?) { currentView = id; viewBtns.forEach { (k, b) -> b.on = k == id } }

    private fun toggleLight(id: String) {
        val s = world.lightState
        world.setLights(when (id) {
            "left" -> s.copy(signal = if (s.signal == 1) 0 else 1)
            "right" -> s.copy(signal = if (s.signal == -1) 0 else -1)
            "hazard" -> s.copy(signal = if (s.signal == 2) 0 else 2)
            "farol" -> s.copy(farol = !s.farol)
            "alta" -> s.copy(alta = !s.alta)
            "drl" -> s.copy(drl = !s.drl)
            else -> s.copy(brake = !s.brake)
        })
        refresh()
    }

    private val dialogTheme get() = if (Ui.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private var pickerClose: (() -> Unit)? = null

    /** escolher horário: janela no estilo da central, com colunas de hora e minuto que rolam */
    private fun pickTime(key: String, current: Int) {
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 60f) }
        fun close() { root.removeView(overlay); pickerClose = null }
        pickerClose = { close() }
        overlay.setOnClickListener { close() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            setPadding(Ui.dpi(ctx, 28), Ui.dpi(ctx, 22), Ui.dpi(ctx, 28), Ui.dpi(ctx, 24))
            isClickable = true
        }
        box.addView(Ui.text(ctx, if (key == Prefs.DAWN) "Amanhecer" else "Anoitecer", 22f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(-1))
        box.addView(Ui.text(ctx, if (key == Prefs.DAWN) "Horário em que já é dia" else "Horário em que já é noite", 15f, Ui.MUTED).apply { gravity = Gravity.CENTER },
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 2); bottomMargin = Ui.dpi(ctx, 14) })
        val hours = Wheel(ctx, 24, current / 60 % 24) { "%02d".format(it) }
        val mins = Wheel(ctx, 60, current % 60) { "%02d".format(it) }
        val wheels = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(hours)
            addView(Ui.text(ctx, ":", 36f, Ui.TEXT, true), Ui.lp().apply { leftMargin = Ui.dpi(ctx, 6); rightMargin = Ui.dpi(ctx, 6) })
            addView(mins)
        }
        box.addView(wheels)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill(ctx, "Cancelar") { close() }, Ui.lp(0, -2, 1f).apply { rightMargin = Ui.dpi(ctx, 12) })
        buttons.addView(pill(ctx, "Salvar", primary = true) {
            Prefs.get(ctx).edit().putBoolean(Prefs.AUTO_SUN, false)
                .putInt(Prefs.DAWN, driver.dawn()).putInt(Prefs.DUSK, driver.dusk())
                .putInt(key, hours.value * 60 + mins.value).apply()
            close()
        }, Ui.lp(0, -2, 1f))
        box.addView(buttons, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 20) })
        overlay.addView(box, FrameLayout.LayoutParams(Ui.dpi(ctx, 420), -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * Escolher um tempo arrastando as colunas de minutos e segundos (como o horário).
     * [always] = mostra o botão "Sempre" (grava 0). [minSecs] = menor tempo aceito.
     */
    private fun pickDuration(title: String, sub: String, current: Int, always: Boolean, minSecs: Int = 1, onSave: (Int) -> Unit) {
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 60f) }
        fun close() { root.removeView(overlay); pickerClose = null }
        pickerClose = { close() }
        overlay.setOnClickListener { close() }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            setPadding(Ui.dpi(ctx, 28), Ui.dpi(ctx, 22), Ui.dpi(ctx, 28), Ui.dpi(ctx, 24))
            isClickable = true
        }
        box.addView(Ui.text(ctx, title, 22f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }, Ui.lp(-1))
        box.addView(Ui.text(ctx, sub, 15f, Ui.MUTED).apply { gravity = Gravity.CENTER },
            Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 2); bottomMargin = Ui.dpi(ctx, 14) })
        val cur = if (current > 0) current else 30
        val mins = Wheel(ctx, 60, (cur / 60).coerceAtMost(59)) { "%02d".format(it) }
        val secs = Wheel(ctx, 60, cur % 60) { "%02d".format(it) }
        fun col(w: Wheel, unit: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(w); addView(Ui.text(ctx, unit, 15f, Ui.MUTED, true).apply { gravity = Gravity.CENTER }, Ui.lp(-1))
        }
        val wheels = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(col(mins, "minutos"))
            addView(Ui.text(ctx, ":", 36f, Ui.TEXT, true), Ui.lp().apply { leftMargin = Ui.dpi(ctx, 6); rightMargin = Ui.dpi(ctx, 6); bottomMargin = Ui.dpi(ctx, 24) })
            addView(col(secs, "segundos"))
        }
        box.addView(wheels, Ui.lp(-1))
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(pill(ctx, "Cancelar") { close() }, Ui.lp(0, -2, 1f).apply { rightMargin = Ui.dpi(ctx, 12) })
        if (always) buttons.addView(pill(ctx, "Sempre") { onSave(0); close() }, Ui.lp(0, -2, 1f).apply { rightMargin = Ui.dpi(ctx, 12) })
        buttons.addView(pill(ctx, "Salvar", primary = true) {
            onSave((mins.value * 60 + secs.value).coerceAtLeast(minSecs)); close()
        }, Ui.lp(0, -2, 1f))
        box.addView(buttons, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 20) })
        overlay.addView(box, FrameLayout.LayoutParams(Ui.dpi(ctx, if (always) 520 else 440), -2, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
    }

    /** linha de ajuste de tempo: mostra o valor atual e abre as colunas de minutos e segundos */
    private fun durationRow(title: String, hint: String, key: String, def: Int, always: Boolean, minSecs: Int = 1, read: () -> Int = { Prefs.int(ctx, key, def) }): LinearLayout {
        lateinit var row: LinearLayout
        fun label() = Greetings.durationLabel(read()) + " · " + hint
        row = actionRow(title, label(), "Alterar") {
            pickDuration(title, hint, read(), always, minSecs) { v ->
                Prefs.get(ctx).edit().putInt(key, v).apply()
                ((row.getChildAt(0) as LinearLayout).getChildAt(1) as TextView).text = label()
            }
        }
        return row
    }

    private fun toast(s: String) = Toast.makeText(ctx, L.t(s), Toast.LENGTH_LONG).show()

    /* ======================= papel de parede ======================= */

    private fun enableWallpaper() {
        val msg = WallpaperSwitch.enable(ctx)
        if (msg == null) {
            try { startActivity(WallpaperSwitch.pickerIntent(ctx)) } catch (e: Exception) { toast("Seletor de papel de parede indisponível") }
        } else toast(msg)
        refreshWallChip()
    }

    /** botão do cartão do carro: "Ativar papel de parede" ↔ "Desativar papel de parede" */
    private var wallChip: LinearLayout? = null
    private var wallChipActive: Boolean? = null

    private fun toggleWallpaper() {
        if (WallpaperSwitch.isActive(ctx)) toast(WallpaperSwitch.restore(ctx).replace("original restaurado", "da central de volta"))
        else enableWallpaper()
        // o sistema confirma a troca um instante depois
        refreshWallChip(); main.postDelayed({ refreshWallChip() }, 800)
    }

    private fun refreshWallChip() {
        val chip = wallChip ?: return
        val active = WallpaperSwitch.isActive(ctx)
        if (active == wallChipActive) return
        wallChipActive = active
        (chip.getChildAt(1) as? TextView)?.text = L.t(if (active) "Desativar papel de parede" else "Ativar papel de parede")
    }

    private fun useViewForWallpaper() {
        Prefs.get(ctx).edit().putString(Prefs.WALL_VIEW, vp.orbit.pose().encode()).apply()
        toast("Esta vista agora é a do papel de parede.")
    }

    /* ======================= ajustes (no estilo da tela de ajustes da central) ======================= */

    private var sheet: View? = null
    private var sheetSection: String? = null
    private var tempStatus: TextView? = null

    private fun closeSheet() {
        sheet?.let { root.removeView(it) }
        sheetClose?.invoke()
        sheet = null; sheetSection = null; tempStatus = null; sheetRefresh = null; sheetClose = null
    }

    /** a seção aberta parou de aparecer (troca de seção ou fechou os ajustes) */
    private var sheetClose: (() -> Unit)? = null

    /** a seção aberta dos ajustes se atualiza junto com a tela (horários, clima) */
    private var sheetRefresh: (() -> Unit)? = null

    private fun tempSourceText(): String {
        val t = driver.outsideTemp()
        return when {
            t == null -> L.t("Sem leitura agora: aparece \"- -\" até o carro (ou a internet) informar")
            driver.outsideFromCar() -> L.f("Sensor do carro: %s°", TempArt.label(t))
            else -> L.f("O carro ainda não informou; usando a internet: %s°", TempArt.label(t))
        }
    }

    private val sections = listOf("carro" to "Carro", "papel" to "Papel de parede", "saudacao" to "Saudações", "carga" to "Carregamento", "cena" to "Cenário",
        "horario" to "Horário e clima", "qualidade" to "Qualidade", "idioma" to "Idioma e região", "atualizacoes" to "Atualizações", "sobre" to "Sobre")

    private fun showSettings(section: String) {
        closeSheet()
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; setOnClickListener { closeSheet() }; translationZ = Ui.dp(ctx, 40f) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            setPadding(Ui.dpi(ctx, 22), Ui.dpi(ctx, 18), Ui.dpi(ctx, 22), Ui.dpi(ctx, 22))
            isClickable = true   // toque dentro não fecha
            elevation = Ui.dp(ctx, 12f)
        }
        // cabeçalho: fechar (círculo) à esquerda, título no meio
        val head = FrameLayout(ctx)
        head.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); imageTintList = ColorStateList.valueOf(Ui.ICON)
            background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 24f))
            val p = Ui.dpi(ctx, 12); setPadding(p, p, p, p)
            setOnClickListener { closeSheet() }
        }, FrameLayout.LayoutParams(Ui.dpi(ctx, 48), Ui.dpi(ctx, 48), Gravity.START or Gravity.CENTER_VERTICAL))
        head.addView(Ui.text(ctx, "Ajustes", 22f, Ui.TEXT, true), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        box.addView(head, Ui.lp(-1, Ui.dpi(ctx, 52)))
        // corpo: menu à esquerda, conteúdo à direita
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val nav = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(content); isVerticalScrollBarEnabled = false }
        val navItems = ArrayList<TextView>()
        fun open(id: String) {
            sheetSection = id
            navItems.forEachIndexed { i, t ->
                val on = sections[i].first == id
                t.background = if (on) Ui.rounded(Ui.CARD, Ui.dp(ctx, 14f)) else null
                t.setTextColor(if (on) Ui.TEXT else Ui.MUTED)
            }
            content.removeAllViews(); tempStatus = null
            sheetClose?.invoke(); sheetClose = null; sheetRefresh = null
            fillSection(id, content)
            scroll.scrollTo(0, 0)
        }
        sections.forEach { (id, label) ->
            val t = Ui.text(ctx, label, 17f, Ui.MUTED, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dpi(ctx, 18), 0, Ui.dpi(ctx, 12), 0)
                setOnClickListener { open(id) }
            }
            navItems.add(t)
            nav.addView(t, Ui.lp(-1, Ui.dpi(ctx, 56)).apply { bottomMargin = Ui.dpi(ctx, 4) })
        }
        body.addView(nav, Ui.lp(Ui.dpi(ctx, 230), -1))
        body.addView(scroll, Ui.lp(0, -1, 1f).apply { leftMargin = Ui.dpi(ctx, 20) })
        box.addView(body, Ui.lp(-1, 0, 1f).apply { topMargin = Ui.dpi(ctx, 12) })
        overlay.addView(box, FrameLayout.LayoutParams(Ui.dpi(ctx, 1180), Ui.dpi(ctx, 780), Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        sheet = overlay
        open(section)
    }

    /* peças das telas de ajuste */

    private fun group(parent: LinearLayout, title: String?, vararg rows: View) {
        if (title != null) parent.addView(Ui.text(ctx, title, 15f, Ui.MUTED).apply { setPadding(Ui.dpi(ctx, 6), Ui.dpi(ctx, 14), 0, Ui.dpi(ctx, 8)) })
        val card = Ui.card(ctx)
        rows.forEachIndexed { i, r ->
            if (i > 0) card.addView(View(ctx).apply { setBackgroundColor(Ui.LINE) }, Ui.lp(-1, Ui.dpi(ctx, 1)))
            card.addView(r, Ui.lp(-1))
        }
        parent.addView(card, Ui.lp(-1))
    }

    private fun titleCol(title: String, sub: String?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.text(ctx, title, 17.5f, Ui.TEXT, true))
        if (sub != null) addView(Ui.text(ctx, sub, 14f, Ui.MUTED), Ui.lp().apply { topMargin = Ui.dpi(ctx, 2) })
    }

    /** linha com chave (à esquerda, como na central) e texto */
    private fun toggleRow(title: String, sub: String?, key: String, def: Boolean, after: ((Boolean) -> Unit)? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dpi(ctx, 74)
        val tg = Toggle(ctx, Prefs.bool(ctx, key, def)) { v -> Prefs.get(ctx).edit().putBoolean(key, v).apply(); after?.invoke(v) }
        addView(tg)
        addView(titleCol(title, sub), Ui.lp(0, -2, 1f).apply { leftMargin = Ui.dpi(ctx, 16) })
        setOnClickListener { tg.set(!tg.checked, true) }
    }

    /** linha com texto e um botão à direita */
    private fun actionRow(title: String, sub: String?, button: String, primary: Boolean = false, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dpi(ctx, 78)
        addView(titleCol(title, sub), Ui.lp(0, -2, 1f))
        addView(pill(ctx, button, primary) { onClick() }.apply { minHeight = Ui.dpi(ctx, 46); setPadding(Ui.dpi(ctx, 20), 0, Ui.dpi(ctx, 20), 0) },
            Ui.lp(-2, Ui.dpi(ctx, 46)).apply { leftMargin = Ui.dpi(ctx, 16) })
    }

    /** linha com escolha entre opções */
    private fun chooserRow(title: String, sub: String?, seg: Segmented) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, Ui.dpi(ctx, 14), 0, Ui.dpi(ctx, 16))
        addView(titleCol(title, sub))
        addView(seg, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 12) })
    }

    private fun fillSection(id: String, c: LinearLayout) {
        when (id) {
            "carro" -> {
                group(c, "Sincronização",
                    toggleRow("Sincronizar com o carro", "Portas, setas e luzes do 3D seguem o carro de verdade", Prefs.CAR_SYNC, true))
                val temp = toggleRow("Temperatura externa", tempSourceText(), Prefs.OUT_TEMP, true) { refresh() }
                tempStatus = ((temp.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
                group(c, "Widget (canto de cima à direita, no app e no papel de parede)", temp, cityRow())
            }
            "papel" -> {
                group(c, null,
                    actionRow("Usar como papel de parede", "Coloca o EX2 3D na tela inicial da central", "Ativar", true) { enableWallpaper() },
                    actionRow("Papel de parede anterior", "Volta ao papel de parede de antes do EX2 3D", "Restaurar") { toast(WallpaperSwitch.restore(ctx)) })
                group(c, "Vista",
                    actionRow("Usar esta vista", "O papel de parede passa a mostrar o carro do ângulo que está na tela agora", "Usar esta vista") { useViewForWallpaper() },
                    actionRow("Vista padrão", "Volta ao ângulo original do papel de parede", "Voltar ao padrão") {
                        Prefs.get(ctx).edit().remove(Prefs.WALL_VIEW).apply(); toast("O papel de parede voltou à vista padrão.")
                    },
                    toggleRow("Girar o carro com toque", "Arraste no papel de parede para girar; volta sozinho depois de 30 s", Prefs.TOUCH, true))
                val temp = toggleRow("Temperatura externa", tempSourceText(), Prefs.OUT_TEMP, true) { refresh() }
                tempStatus = ((temp.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
                group(c, "Widget", temp, cityRow())
            }
            "saudacao" -> fillGreetings(c)
            "carga" -> fillCharge(c)
            "horario" -> {
                val dawnRow = actionRow("Amanhecer", null, "Alterar") { pickTime(Prefs.DAWN, driver.dawn()) }
                val duskRow = actionRow("Anoitecer", null, "Alterar") { pickTime(Prefs.DUSK, driver.dusk()) }
                fun sub(row: LinearLayout): TextView = (row.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
                val autoSun = toggleRow("Nascer e pôr do sol automáticos", "Calculados pela localização do carro, todo dia", Prefs.AUTO_SUN, true) { auto ->
                    // ao desligar, parte dos horários que estavam valendo
                    if (!auto) Prefs.get(ctx).edit().putInt(Prefs.DAWN, driver.dawn()).putInt(Prefs.DUSK, driver.dusk()).apply()
                }
                group(c, "Sol", autoSun, dawnRow, duskRow)
                val weatherSeg = Segmented(ctx, listOf("sol" to "Sol", "nublado" to "Nublado", "chuva" to "Chuva", "tempestade" to "Tempestade"),
                    Prefs.str(ctx, Prefs.MANUAL_WEATHER, "sol")) {
                    Prefs.get(ctx).edit().putBoolean(Prefs.AUTO_WEATHER, false).putString(Prefs.MANUAL_WEATHER, it).apply()
                }
                val autoWx = toggleRow("Clima automático", "O céu segue a previsão do tempo de onde o carro está (internet)", Prefs.AUTO_WEATHER, true)
                group(c, "Clima", autoWx, chooserRow("Clima escolhido", "Tocar num clima desliga o automático", weatherSeg))
                group(c, "Previsão da semana (clima automático)", *forecastRows())
                sheetRefresh = {
                    sub(dawnRow).text = L.t("Amanhecer") + " · " + SkyClock.hhmm(driver.dawn())
                    sub(duskRow).text = L.t("Anoitecer") + " · " + SkyClock.hhmm(driver.dusk())
                    (autoSun.getChildAt(0) as Toggle).set(driver.autoSun)
                    (autoWx.getChildAt(0) as Toggle).set(Prefs.autoWeather(ctx))
                    weatherSeg.selected = if (Prefs.autoWeather(ctx)) "" else driver.weatherId()
                }
                sheetRefresh?.invoke()
            }
            "cena" -> {
                group(c, null,
                    chooserRow("Cenário", "Automático: paisagem com o carro parado, estrada quando ele anda",
                        Segmented(ctx, listOf("auto" to "Automático", "lago" to "Lago e montanhas", "estrada" to "Estrada"), Prefs.str(ctx, Prefs.SCENE, "auto")) {
                            Prefs.get(ctx).edit().putString(Prefs.SCENE, it).apply()
                        }),
                    durationRow("Voltar à paisagem parado há", "no automático; volta também na hora em que o carro vai para P",
                        Prefs.IDLE_SECS, 180, always = false, minSecs = 5) { Prefs.idleSecs(ctx) },
                    toggleRow("Câmera automática", "Andando, mostra o carro por trás; depois de 30 s parado, volta para a frente", Prefs.AUTO_CAM, true),
                    toggleRow("Reflexo das montanhas no lago", "O carro tem uma sombra suave no lago (sem desenhar o carro duas vezes)", Prefs.REFLECT, true),
                    toggleRow("Modo demonstração", "O carro anda sozinho: rodas girando, curvas e faixas passando", Prefs.DEMO, false))
            }
            "qualidade" -> {
                group(c, null,
                    chooserRow("Qualidade da imagem", "Econômica alivia a central (sem reflexo e sem sombras)",
                        Segmented(ctx, listOf("alta" to "Alta", "media" to "Média", "economica" to "Econômica"), Prefs.str(ctx, Prefs.QUALITY, "media")) {
                            Prefs.get(ctx).edit().putString(Prefs.QUALITY, it).apply()
                            val dm = resources.displayMetrics
                            vp.setScale(Prefs.scale(ctx), dm.widthPixels, dm.heightPixels)
                            vp.setShadows(Prefs.shadows(ctx))
                        }))
                group(c, "Teste de desempenho (liga e desliga cada parte para medir no carro)",
                    toggleRow("Medidor de quadros por segundo", null, Prefs.PERF_HUD, false) { updateHud() },
                    toggleRow("Montanhas", null, Prefs.T_MOUNTAINS, true),
                    toggleRow("Reflexo das montanhas no lago", null, Prefs.T_MOUNT_MIRROR, true),
                    toggleRow("Nuvens", null, Prefs.T_CLOUDS, true),
                    toggleRow("Resolução menor enquanto gira", null, Prefs.T_DYNRES, true),
                    toggleRow("Suavização de bordas", null, Prefs.T_MSAA, true))
            }
            "sobre" -> fillAbout(c)
            "atualizacoes" -> fillUpdates(c)
            "idioma" -> {
                val langs = listOf("auto" to L.t("Do carro")) + L.SUPPORTED.map { it to (L.NAMES[it] ?: it) }
                // 11 opções não cabem numa faixa: duas linhas
                val cur = Prefs.str(ctx, Prefs.LANG, "auto")
                lateinit var segA: Segmented; lateinit var segB: Segmented
                fun pick(id: String) {
                    segA.selected = id; segB.selected = id
                    Prefs.get(ctx).edit().putString(Prefs.LANG, id).apply()
                    L.init(ctx)
                }
                segA = Segmented(ctx, langs.take(6), cur) { pick(it) }
                segB = Segmented(ctx, langs.drop(6), cur) { pick(it) }
                val langRow = chooserRow("Idioma", L.f("Do carro: segue o idioma da central (agora %s)", L.NAMES[L.code] ?: L.code), segA)
                langRow.addView(segB, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 8) })
                val sideNow = if (DriveSide.rhd(ctx)) L.t("direita") else L.t("esquerda")
                group(c, null, langRow,
                    chooserRow("Lado do motorista", L.f("Com o volante à direita, os painéis do app trocam de lado (agora: %s)", sideNow),
                        Segmented(ctx, listOf("auto" to "Automático", "left" to "Volante à esquerda", "right" to "Volante à direita"), Prefs.str(ctx, Prefs.DRIVE_SIDE, "auto")) {
                            Prefs.get(ctx).edit().putString(Prefs.DRIVE_SIDE, it).apply()
                            main.post { rebuildUi() }
                        }))
            }
            "diag" -> {   // escondido: 3 toques na versão, na aba Sobre
                group(c, "Diagnóstico",
                    actionRow("Encontrar sinais das luzes", "Modo guiado (só se algum sinal do carro mudar)", "Começar") { closeSheet(); SignalWizard(ctx, root).show() },
                    toggleRow("Gravar todos os sinais do carro", "Arquivo vhal-log.txt para descobrir sinais novos", Prefs.DIAG_RECORD, false) { CarBridge.get(ctx).reloadManualMap() },
                    actionRow("Mapeamento manual de sinais", "Para quem já sabe o número do sinal", "Editar") { showMap() })
                val text = Ui.text(ctx, "", 12.5f, Ui.TEXT).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
                fun fill() { text.text = diagText() }
                fill()
                val card = Ui.card(ctx).apply { setPadding(Ui.dpi(ctx, 18), Ui.dpi(ctx, 14), Ui.dpi(ctx, 18), Ui.dpi(ctx, 14)); addView(text) }
                c.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.text(ctx, "Relatório", 15f, Ui.MUTED), Ui.lp(0, -2, 1f).apply { leftMargin = Ui.dpi(ctx, 6) })
                    addView(pill(ctx, "Atualizar") { fill() }.apply { minHeight = Ui.dpi(ctx, 40); setPadding(Ui.dpi(ctx, 18), 0, Ui.dpi(ctx, 18), 0) }, Ui.lp(-2, Ui.dpi(ctx, 40)))
                }, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 14); bottomMargin = Ui.dpi(ctx, 8) })
                c.addView(card, Ui.lp(-1))
            }
        }
    }

    /* aba Carregamento: liga/desliga, valor do kWh (slider), comportamento ao terminar, última carga, teste */
    private fun fillCharge(c: LinearLayout) {
        val cm = ChargeMonitor.get(ctx)
        group(c, null,
            toggleRow("Widget de carregamento", "Ao começar a carregar, aparece no lugar da temperatura (no app e no papel de parede)", Prefs.CHARGE_ON, true) { refresh() },
            toggleRow("Mostrar a última carga ao terminar", "Resumo com energia, custo e duração até você tocar em Fechar", Prefs.CHARGE_SHOW_END, true),
            toggleRow("Fechar o resumo ao sair dirigindo", "Volta a temperatura sozinho quando o carro anda", Prefs.CHARGE_AUTO_CLOSE, true))
        lateinit var priceRow: LinearLayout
        priceRow = actionRow("Valor do kWh · " + priceLabel(), "Base do custo de cada carga (tarifa da sua casa ou do carregador)", "Alterar") {
            pickPrice { ((priceRow.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).text = L.t("Valor do kWh") + " · " + priceLabel() }
        }
        group(c, "Custo", priceRow)
        // última carga
        val last = cm.lastJson()
        val lastText = if (last == null) "Nenhuma carga registrada ainda" else {
            val fmtd = java.text.SimpleDateFormat(L.t("dd/MM 'às' HH:mm"), L.locale).format(java.util.Date(last.optLong("end")))
            val kwh = last.optDouble("kwh"); val price = last.optDouble("price", cm.price().toDouble())
            fmtd + " · " + L.num(kwh.toFloat()) + " kWh · " + L.money((kwh * price).toFloat()) + " · " + L.f("%d min", last.optDouble("min").toInt()) + " · " + L.t("pico") + " " + L.num(last.optDouble("peak").toFloat()) + " kW"
        }
        val km = cm.kmSinceLast()?.let { L.f("%s km rodados desde então", L.num(it, 0)) }
        group(c, "Última carga",
            actionRow("Resumo", lastText + (km?.let { "\n$it" } ?: ""), "Mostrar") { cm.showLast(); closeSheet() },
            actionRow("Apagar histórico", "Esquece a última carga e a contagem de km", "Apagar") { cm.clearHistory(); showSettings("carga") })
        val raw = Ui.text(ctx, cm.rawText(), 13f, Ui.MUTED)
        group(c, "Teste e diagnóstico",
            actionRow("Testar o widget", "Simula uma carga completa em uns 45 segundos (não mexe no carro)", "Testar") { cm.simulate(); closeSheet() },
            LinearLayout(ctx).apply { setPadding(0, Ui.dpi(ctx, 12), 0, Ui.dpi(ctx, 12)); addView(titleCol("Sinais de carga agora", null)); addView(raw) }.also { it.orientation = LinearLayout.VERTICAL })
        sheetRefresh = { raw.text = cm.rawText() }
    }

    private fun cityRow() = toggleRow("Nome da cidade embaixo da temperatura",
        WeatherSync.get(ctx).city()?.let { L.f("Agora: %s (sem internet, fica a última cidade conhecida)", it) } ?: "Aparece quando houver internet; depois fica a última conhecida",
        Prefs.CITY_ON, true) { refresh() }

    /** prévia da previsão da semana guardada (Ajustes → Horário e clima) */
    private fun forecastRows(): Array<View> {
        val wx = WeatherSync.get(ctx)
        val days = wx.week()
        if (days.isEmpty()) return arrayOf(titleCol("Ainda sem previsão guardada",
            "Com o clima automático e internet, a previsão de 7 dias é baixada e o céu segue ela mesmo sem conexão. Sem dados, o céu volta ao padrão (sol)."
        ).apply { setPadding(0, Ui.dpi(ctx, 14), 0, Ui.dpi(ctx, 14)) })
        val names = L.weekdays()
        fun label(code: Int) = when (WeatherSync.map(code)) { "tempestade" -> "Tempestade"; "chuva" -> "Chuva"; "nublado" -> "Nublado"; else -> "Sol" }
        fun icon(code: Int) = when (WeatherSync.map(code)) { "tempestade" -> R.drawable.ic_storm; "chuva" -> R.drawable.ic_rain; "nublado" -> R.drawable.ic_cloud; else -> R.drawable.ic_sun }
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val rows = days.map { d ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = d.start }
            val name = if (cal.get(java.util.Calendar.DAY_OF_YEAR) == today) "Hoje" else names[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(java.util.Calendar.DAY_OF_MONTH)
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dpi(ctx, 52)
                addView(Ui.text(ctx, name, 17f, Ui.TEXT, true), Ui.lp(Ui.dpi(ctx, 110)))
                addView(ImageView(ctx).apply { setImageResource(icon(d.code)); imageTintList = ColorStateList.valueOf(Ui.ICON) }, Ui.lp(Ui.dpi(ctx, 26), Ui.dpi(ctx, 26)))
                addView(Ui.text(ctx, label(d.code), 16f, Ui.TEXT), Ui.lp(0, -2, 1f).apply { leftMargin = Ui.dpi(ctx, 12) })
                addView(Ui.text(ctx, "%d° / %d°".format(Math.round(d.min), Math.round(d.max)), 16f, Ui.MUTED, true))
            }
        }
        val at = wx.weekAt()
        val head = titleCol("Previsão baixada", if (at > 0) L.t("Atualizada em") + " " + java.text.SimpleDateFormat(L.t("dd/MM 'às' HH:mm"), L.locale).format(java.util.Date(at)) +
            " · sem internet, o céu segue esta previsão" else null).apply { setPadding(0, Ui.dpi(ctx, 12), 0, Ui.dpi(ctx, 8)) }
        return (listOf<View>(head) + rows).toTypedArray()
    }

    /* saudações: liga/desliga, tempo na tela e motoristas (nome + dias da semana) */
    private fun fillGreetings(c: LinearLayout) {
        val example = Ui.text(ctx, "", 15f, Ui.MUTED)
        fun refreshExample() {
            val n = Greetings.todaysName(ctx)
            example.text = if (n == null) L.t("Sem nome cadastrado: as frases aparecem sem nome") else L.f("Hoje o motorista é %s", n)
        }
        group(c, null,
            toggleRow("Saudação na tela inicial", "Frase curta com o nome do motorista no canto de cima à esquerda; uma nova cada vez que a central liga", Prefs.GREET_ON, true),
            durationRow("Tempo na tela", "depois disso ela some devagar", Prefs.GREET_SECS, 30, always = true),
            actionRow("Ver uma saudação agora", "Mostra uma frase nova no papel de parede", "Mostrar") {
                Prefs.get(ctx).edit().putLong(Prefs.GREET_NOW, System.currentTimeMillis()).apply()
                toast("Saudação nova no papel de parede.")
            })
        group(c, "Segunda saudação (a data de hoje, logo depois da primeira)",
            toggleRow("Mostrar a data", L.f("Ex.: \"%s\". Com a primeira fixa na tela, a data aparece embaixo dela", Greetings.dateLine()), Prefs.GREET2_ON, true),
            durationRow("Tempo na tela da data", "conta a partir de quando ela aparece", Prefs.GREET2_SECS, 15, always = true))
        // motoristas
        val list = Greetings.drivers(ctx).toMutableList()
        if (list.isEmpty()) list.add(Greetings.Driver("", (1..7).toSet()))
        val rows = ArrayList<View>()
        val dayIds = listOf(2, 3, 4, 5, 6, 7, 1)
        val dayNames = L.weekdays().let { it.drop(1) + it.first() }
        fun save() { Greetings.save(ctx, list); refreshExample() }
        list.forEachIndexed { i, d ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dpi(ctx, 12), 0, Ui.dpi(ctx, 14)) }
            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = EditText(ctx).apply {
                setText(d.name); textSize = 18f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); hint = L.t("Nome do motorista")
                typeface = Ui.MEDIUM; maxLines = 1; isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(16))
                background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 14f))
                setPadding(Ui.dpi(ctx, 16), Ui.dpi(ctx, 8), Ui.dpi(ctx, 16), Ui.dpi(ctx, 8))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) { list[i] = Greetings.Driver(s.toString(), list[i].days); save() }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
                })
            }
            top.addView(name, Ui.lp(0, Ui.dpi(ctx, 50), 1f))
            if (list.size > 1) top.addView(pill(ctx, "Remover") { list.removeAt(i); save(); showSettings("saudacao") }.apply {
                minHeight = Ui.dpi(ctx, 46); setPadding(Ui.dpi(ctx, 18), 0, Ui.dpi(ctx, 18), 0)
            }, Ui.lp(-2, Ui.dpi(ctx, 46)).apply { leftMargin = Ui.dpi(ctx, 12) })
            row.addView(top, Ui.lp(-1))
            // dias da semana em que este motorista dirige
            val days = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            dayIds.forEachIndexed { j, id ->
                val chip = Ui.text(ctx, dayNames[j], 15f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
                fun mark() {
                    val on = id in list[i].days
                    chip.background = Ui.rounded(if (on) Ui.CHIP_ON else Ui.CHIP, Ui.dp(ctx, 12f))
                    chip.setTextColor(if (on) android.graphics.Color.WHITE else Ui.TEXT)
                }
                chip.setOnClickListener {
                    val cur = list[i].days.toMutableSet()
                    if (id in cur) cur.remove(id) else cur.add(id)
                    list[i] = Greetings.Driver(list[i].name, cur); save(); mark()
                }
                mark()
                days.addView(chip, Ui.lp(0, Ui.dpi(ctx, 42), 1f).apply { if (j > 0) leftMargin = Ui.dpi(ctx, 6) })
            }
            row.addView(days, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 10) })
            rows.add(row)
        }
        rows.add(actionRow("Outro motorista", "Para os dias em que outra pessoa dirige o carro", "Adicionar") {
            list.add(Greetings.Driver("", emptySet())); save(); showSettings("saudacao")
        })
        group(c, "Motoristas (o nome entra nas frases; cada um nos seus dias da semana)", *rows.toTypedArray())
        refreshExample()
        c.addView(example, Ui.lp().apply { topMargin = Ui.dpi(ctx, 10); leftMargin = Ui.dpi(ctx, 6) })
    }

    /* aba Sobre (3 toques na versão abrem o diagnóstico, que saiu do menu) */
    private fun fillAbout(c: LinearLayout) {
        fun para(s: String, sp: Float = 16f, color: Int = Ui.TEXT, bold: Boolean = false, top: Int = 0) =
            Ui.text(ctx, s, sp, color, bold).apply { setPadding(0, Ui.dpi(ctx, top), 0, 0); setLineSpacing(Ui.dp(ctx, 3f), 1f) }
        fun head(s: String) = para(s, 14f, Ui.MUTED, true, 18)
        val card = Ui.card(ctx).apply { setPadding(Ui.dpi(ctx, 22), Ui.dpi(ctx, 18), Ui.dpi(ctx, 22), Ui.dpi(ctx, 20)) }
        card.addView(para("EX2 Live 3D", 24f, Ui.TEXT, true))
        var taps = 0
        card.addView(para(L.f("versão %s", BuildConfig.VERSION_NAME), 14f, Ui.MUTED, false, 2).apply {
            setOnClickListener { if (++taps >= 3) { taps = 0; showSettings("diag") } }
        })
        card.addView(para("Desenvolvido por Saullo Miguel.", 17f, Ui.TEXT, true, 14))
        card.addView(para("Aplicativo de distribuição gratuita, feito para os donos do Geely EX2. Se você pagou por ele, foi enganado.", top = 6))
        card.addView(head("CONTATO"))
        card.addView(para("Encontrou um bug ou tem alguma pergunta? Escreva para saullomigueldev@gmail.com.", top = 4).apply { setTextIsSelectable(true) })
        card.addView(head("AGRADECIMENTOS"))
        card.addView(para("Aos desenvolvedores Bruno, Ravi e Pedro, pela ajuda no caminho.", top = 4))
        card.addView(head("COMO FOI FEITO"))
        card.addView(para("Aplicativo desenvolvido com o Claude, com apoio de prompts do Google AI Studio e do ChatGPT.", top = 4))
        card.addView(para("Lembre-se: IA é uma ferramenta, como uma furadeira. Sem você, ela não fura parede nenhuma.", top = 6))
        card.addView(para("Este é um projeto independente, sem vínculo com a Geely.", 13.5f, Ui.MUTED, false, 18))
        c.addView(card, Ui.lp(-1))
    }

    private fun diagText() = buildString {
        append("Versão ${BuildConfig.VERSION_NAME} · ${"%.1f".format(fps)} fps na última animação\n")
        append("Papel de parede ativo: ${WallpaperSwitch.isActive(ctx)} · permissão de plataforma: ${WallpaperSwitch.hasPermission(ctx)}\n")
        append("Clima/sol: ${WeatherSync.get(ctx).status()}\n")
        append("Temperatura externa: ${tempSourceText()}\n")
        append("Cidade: ${WeatherSync.get(ctx).city() ?: "—"} · previsão guardada: ${WeatherSync.get(ctx).week().size} dias\n")
        append("Monitor de erros: ${Guard.total} erro(s) corrigido(s) nesta sessão · qualidade automática ${"%.0f".format(vp.autoScale * 100)}%\n")
        // serviço local de outros apps EX2 (velocidade, volante, farol baixo, freio)
        append("Serviço 8888: ${LocalTelemetry.status}")
        LocalTelemetry.last?.let { d -> append(" · ${d.speedKmh ?: "-"} km/h · rodas ${d.wheelDeg ?: "-"}° · marcha ${d.gear ?: "-"} · freio ${d.brake ?: "-"} · farol baixo ${d.dipped ?: "-"}") }
        append("\n\n")
        append(CarBridge.get(ctx).report()).append('\n')
        append("Arquivo VHAL: ${Diag.vhalFile(ctx)}\n\n")
        append(Diag.dump().lines().takeLast(80).joinToString("\n"))
    }

    /* medidor de desempenho (canto de cima, no meio) */
    private var hud: TextView? = null
    private val hudTick = object : Runnable {
        override fun run() {
            val h = hud ?: return
            val idle = vp.measuredFps <= 0f
            h.text = if (idle) L.t("medidor: gire o carro para medir")
                else "%.0f quadros/s · %.0f ms por quadro · resolução %d%%".format(vp.measuredFps, vp.frameMs, (vp.resolutionNow * 100).toInt())
            main.postDelayed(this, 500)
        }
    }

    private fun updateHud() {
        val on = Prefs.bool(ctx, Prefs.PERF_HUD, false)
        if (on && hud == null) {
            hud = Ui.text(ctx, "", 15f, Ui.TEXT, true).apply {
                background = Ui.rounded(Ui.PANEL, Ui.dp(ctx, 12f))
                setPadding(Ui.dpi(ctx, 14), Ui.dpi(ctx, 6), Ui.dpi(ctx, 14), Ui.dpi(ctx, 6))
            }
            root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = Ui.dpi(ctx, 18) })
            main.post(hudTick)
        } else if (!on && hud != null) {
            main.removeCallbacks(hudTick)
            root.removeView(hud); hud = null
        }
    }

    private fun showMap() {
        val edit = EditText(ctx).apply {
            setText(Prefs.str(ctx, Prefs.MAP, "")); setTextColor(Ui.TEXT); minLines = 4
            hint = "farol=0x2140abcd\nfreio=0x2140abcd:1\ndrl=0x2140abcd"
        }
        AlertDialog.Builder(ctx, dialogTheme)
            .setTitle(L.t("Mapeamento manual")).setView(edit)
            .setPositiveButton(L.t("Salvar")) { _, _ -> Prefs.get(ctx).edit().putString(Prefs.MAP, edit.text.toString()).apply(); CarBridge.get(ctx).reloadManualMap() }
            .setNegativeButton(L.t("Cancelar"), null).show()
    }

    /* ======================= monte o seu EX2 ======================= */

    /** tela "Monte o seu EX2" aberta (nunca abre uma segunda por cima) */
    private var setupOverlay: View? = null

    private fun showSetup(first: Boolean) {
        if (setupOverlay != null) return
        var trim = Prefs.str(ctx, Prefs.TRIM, "pro")
        var paint = Prefs.str(ctx, Prefs.PAINT, CarSpec.paints[0].id)
        val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 40f) }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(if (Ui.dark) 0xFF1B2028.toInt() else 0xFFF2F4F8.toInt(), Ui.dp(ctx, 28f))
            elevation = Ui.dp(ctx, 12f)
            setPadding(Ui.dpi(ctx, 32), Ui.dpi(ctx, 26), Ui.dpi(ctx, 32), Ui.dpi(ctx, 26))
            addView(Ui.text(ctx, "Monte o seu EX2", 26f, Ui.TEXT, true))
            addView(Ui.text(ctx, "Escolha a versão, a cor e a placa. Dá para mudar depois em Editar carro.", 15f, Ui.MUTED), Ui.lp().apply { topMargin = Ui.dpi(ctx, 4); bottomMargin = Ui.dpi(ctx, 18) })
        }
        fun label(s: String) = Ui.text(ctx, s, 15f, Ui.MUTED).apply { setPadding(Ui.dpi(ctx, 4), 0, 0, Ui.dpi(ctx, 8)) }
        // versão
        box.addView(label("Versão"))
        box.addView(Segmented(ctx, CarSpec.trims.map { it.id to "EX2 " + it.name }, trim) { trim = it; preview(trim, paint) },
            Ui.lp(-1).apply { bottomMargin = Ui.dpi(ctx, 18) })
        // cor
        val colors = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val colorName = Ui.text(ctx, "", 15f, Ui.TEXT, true)
        val swatches = ArrayList<View>()
        fun markColors() {
            swatches.forEachIndexed { i, s ->
                val p = CarSpec.paints[i]
                val sel = p.id == paint
                s.background = Ui.rounded(0xFF000000.toInt() or p.hex, Ui.dp(ctx, 30f), Ui.dpi(ctx, if (sel) 4 else 1), if (sel) Ui.CHIP_ON else Ui.LINE or 0x22000000)
            }
            colorName.text = L.t(CarSpec.paint(paint).name)
        }
        CarSpec.paints.forEach { p ->
            val s = View(ctx).apply { setOnClickListener { paint = p.id; markColors(); preview(trim, paint) } }
            swatches.add(s); colors.addView(s, Ui.lp(Ui.dpi(ctx, 56), Ui.dpi(ctx, 56)).apply { rightMargin = Ui.dpi(ctx, 14) })
        }
        markColors()
        box.addView(label("Cor")); box.addView(colors)
        box.addView(colorName, Ui.lp().apply { topMargin = Ui.dpi(ctx, 8); bottomMargin = Ui.dpi(ctx, 18); leftMargin = Ui.dpi(ctx, 4) })
        // placa
        val plate = EditText(ctx).apply {
            setText(Prefs.str(ctx, Prefs.PLATE, "")); textSize = 22f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); hint = "ABC1D23"
            typeface = Ui.MEDIUM; letterSpacing = .08f
            background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 14f))
            setPadding(Ui.dpi(ctx, 18), Ui.dpi(ctx, 10), Ui.dpi(ctx, 18), Ui.dpi(ctx, 10))
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        box.addView(label("Placa")); box.addView(plate, Ui.lp(Ui.dpi(ctx, 280), Ui.dpi(ctx, 56)))
        box.addView(Ui.text(ctx, "Até 7 caracteres: letras, números ou o que quiser.", 14f, Ui.MUTED), Ui.lp().apply { topMargin = Ui.dpi(ctx, 6); leftMargin = Ui.dpi(ctx, 4) })
        // nome do motorista: as saudações já começam prontas (mais motoristas em Ajustes → Saudações)
        val drivers = Greetings.drivers(ctx)
        val who = EditText(ctx).apply {
            setText(drivers.firstOrNull()?.name ?: ""); textSize = 20f; setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); hint = L.t("Como quer ser chamado")
            typeface = Ui.MEDIUM; maxLines = 1; isSingleLine = true
            background = Ui.rounded(Ui.CHIP, Ui.dp(ctx, 14f))
            setPadding(Ui.dpi(ctx, 18), Ui.dpi(ctx, 10), Ui.dpi(ctx, 18), Ui.dpi(ctx, 10))
            filters = arrayOf(InputFilter.LengthFilter(16))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        box.addView(label("Seu nome (para as saudações)").apply { setPadding(Ui.dpi(ctx, 4), Ui.dpi(ctx, 18), 0, Ui.dpi(ctx, 8)) })
        box.addView(who, Ui.lp(Ui.dpi(ctx, 280), Ui.dpi(ctx, 56)))
        fun close() { root.removeView(overlay); setupOverlay = null }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        if (!first) buttons.addView(pill(ctx, "Cancelar") { close(); driver.applyConfig() }, Ui.lp(0, -2, 1f).apply { rightMargin = Ui.dpi(ctx, 12) })
        buttons.addView(pill(ctx, "Ver meu EX2", primary = true) {
            val code = CarSpec.cleanPlate(plate.text.toString())
            // placa livre: qualquer texto de até 7 caracteres (sem exigir o padrão Mercosul)
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(plate.windowToken, 0)
            Prefs.get(ctx).edit().putString(Prefs.TRIM, trim).putString(Prefs.PAINT, paint).putString(Prefs.PLATE, code)
                .putBoolean(Prefs.SETUP_DONE, true).apply()
            val name = who.text.toString().trim()
            if (name.isNotEmpty() || drivers.isNotEmpty()) {
                val list = drivers.toMutableList()
                if (list.isEmpty()) list.add(Greetings.Driver(name, (1..7).toSet())) else list[0] = Greetings.Driver(name, list[0].days)
                Greetings.save(ctx, list)
            }
            close()
            refresh()
        }, Ui.lp(0, -2, 1f))
        box.addView(buttons, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, 24) })
        // sempre no meio da tela (na abertura e no botão "Editar carro")
        overlay.addView(ScrollView(ctx).apply { addView(box) },
            FrameLayout.LayoutParams(Ui.dpi(ctx, 560), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setupOverlay = overlay
    }

    /** mostra a escolha no carro na hora (antes de salvar) */
    private fun preview(trim: String, paint: String) = world.setConfig(trim, paint, Prefs.str(ctx, Prefs.PLATE, ""))
}