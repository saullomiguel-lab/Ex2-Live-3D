package br.com.ex2.live3d

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.abs

/** Papel de parede: a mesma cena desenhada direto na superfície do fundo da tela inicial. */
class Live3DService : WallpaperService() {
    override fun onCreateEngine(): Engine = Engine3D()

    companion object {
        /** faixa livre do launcher (medida no print da central 1920×1080): status até ~6%, cartões a partir de ~73% */
        const val SAFE_TOP = .065f
        const val SAFE_BOTTOM = .715f
        /** depois de girar o carro, volta sozinho para a vista de descanso */
        const val HOME_AFTER_MS = 30_000L
        /** cada toque em ◀/▶ anda 4% da largura da tela; no máximo 16% para cada lado (4 toques): mais que isso o carro entra embaixo dos widgets */
        const val SHIFT_STEP = .04f
        const val SHIFT_MAX = .16f
    }

    inner class Engine3D : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val ctx get() = this@Live3DService
        private var world: World? = null
        private var driver: SceneDriver? = null
        private var vp: Viewport3D? = null
        private var spots: WallSpots? = null
        private var destroyed = false
        private var visibleNow = true
        private var screenW = 1f
        private var screenH = 1f
        private val main = Handler(Looper.getMainLooper())

        /* ---------- vistas: padrão, andando e fixada ---------- */

        private fun home(): Orbit.Pose = Orbit.Pose.decode(Prefs.str(ctx, Prefs.WALL_VIEW, "")) ?: Orbit.WALLPAPER
        private fun fixed() = Prefs.bool(ctx, Prefs.WALL_FIXED, false)

        /**
         * Vista de descanso: no Fixo, a que o usuário deixou; no Auto, por trás quando o carro anda
         * (câmera automática) e a padrão quando está parado.
         */
        private fun rest(): Orbit.Pose {
            if (fixed()) Orbit.Pose.decode(Prefs.str(ctx, Prefs.WALL_FIXED_POSE, ""))?.let { return it }
            if (!fixed() && driver?.driving == true && Prefs.bool(ctx, Prefs.AUTO_CAM, true)) return Orbit.WALLPAPER_DRIVE
            return home()
        }

        /** volta para a vista de descanso e esconde os pontos (e o botão Fixo/Auto) */
        private val goHome = Runnable {
            spots?.target = 0f
            vp?.let { it.orbit.set(rest(), animate = true); it.requestFrame() }
        }
        private fun restartHomeTimer() { main.removeCallbacks(goHome); main.postDelayed(goHome, HOME_AFTER_MS) }

        /** Fixo: guarda a vista um instante depois de o dedo soltar (o giro ainda desliza um pouco) */
        private var savePending = false
        private val saveFixed = Runnable {
            savePending = false
            val v = vp
            if (v != null && fixed()) Prefs.get(ctx).edit().putString(Prefs.WALL_FIXED_POSE, v.orbit.pose().encode()).apply()
        }

        /** porta abriu (no carro ou por um ponto): mostra a porta e depois volta à vista de descanso.
            Os pontos NÃO aparecem por isso: só quando o usuário gira o carro com o dedo. */
        private val openListener: (String) -> Unit = { name ->
            val v = vp
            Orbit.forOpening(name)?.let { p ->
                if (savePending) { main.removeCallbacks(saveFixed); saveFixed.run() }   // a vista do usuário, não a da porta
                if (v != null) { v.orbit.set(p, animate = true); v.requestFrame() }
                restartHomeTimer()
            }
        }

        /* ---------- montagem (com novas tentativas se falhar) ---------- */

        private var holderRef: SurfaceHolder? = null
        private var tries = 0

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setTouchEventsEnabled(true)
            holderRef = holder
            Diag.log("Papel de parede: criado (preview=$isPreview)")
            // a preparação pesada roda em segundo plano; só depois a cena é montada
            Startup.prepare(ctx) {
                if (destroyed || Startup.error != null) return@prepare
                tryStart()
            }
            main.postDelayed(heartbeat, 20_000)
        }

        private fun tryStart() {
            if (destroyed || vp != null) return
            val holder = holderRef ?: return
            try { start(holder) } catch (t: Throwable) {
                Diag.log("Papel de parede falhou: ${Log.trace(t)}")
                teardown()
                if (++tries < 4) main.postDelayed({ tryStart() }, 3000)
            }
        }

        /**
         * Vigia: de tempos em tempos, com o papel de parede na tela, força um quadro novo (uma imagem
         * "presa" ou preta é trocada) e, se a cena não chegou a ser montada, tenta montar de novo.
         */
        private val heartbeat = object : Runnable {
            override fun run() {
                if (destroyed) return
                if (visibleNow) {
                    val v = vp
                    refreshDate()
                    if (v != null) v.redraw()
                    else if (Startup.error == null && tries < 8) { Diag.log("Papel de parede: sem cena; montando de novo"); tryStart() }
                }
                main.postDelayed(this, 20_000)
            }
        }

        private fun start(holder: SurfaceHolder) {
            if (!isPreview) CrashGuard.begin(ctx)
            val w = World.acquire(ctx)
            world = w
            w.requestCar()   // papel de parede: sem botões, o carro entra assim que carregar
            val d = SceneDriver(ctx, w).also { it.start() }
            driver = d
            val v = Viewport3D(w, ctx)
            vp = v
            v.orbit.set(rest(), animate = false)
            // launcher do EX2: barra de status em cima, cartões e barra de botões embaixo (a partir de
            // ~73% da altura da tela): o carro fica sempre inteiro na faixa livre entre eles
            val real = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(real)
            screenW = real.widthPixels.toFloat(); screenH = real.heightPixels.toFloat()
            applyQuality()
            v.setSafeArea(SAFE_TOP, SAFE_BOTTOM, real.heightPixels)
            v.shiftX = Prefs.get(ctx).getFloat(Prefs.WALL_SHIFT, 0f).coerceIn(-SHIFT_MAX, SHIFT_MAX)
            v.attach(holder)
            // a cor da temperatura segue o fundo: só reavalia quando o fundo troca de claro/escuro
            // (antes era a cada quadro: com a chuva sempre animada isso seria 20 vezes por segundo)
            v.onFrame = { CrashGuard.ok(ctx); val l = w.backdropLight(); if (l != lastBackdrop) { lastBackdrop = l; updateTemp() } }
            v.ambientWindowMs = 30_000   // raios animam 30 s depois de aparecer ou de um toque; a chuva anima sempre
            v.cacheFrames = true         // textos e chuva por cima da imagem guardada do carro (sem redesenhar o 3D)
            v.showLayers(0x1 or WallSpots.LAYER)
            v.extraStep = { dt -> val a = spots?.step(dt) ?: false; val b = greetStep(dt); updatePill(); a || b }
            v.onCamera = { view, proj, eye -> spots?.update(view, proj, eye, screenW, screenH) }
            w.openListeners.add(openListener)
            ChargeMonitor.get(ctx).listeners.add(chargeListener)
            L.listeners.add(langListener)
            // temperatura externa no canto de cima à direita (como o widget da central)
            v.hud = TempHud(w)
            d.onChange = { updateTemp() }
            d.onIgnitionOn = { newGreeting() }          // ligou o carro: saudação nova
            // câmera automática: andando, o carro aparece por trás; 30 s parado, volta (no Fixo não mexe)
            d.onDriving = { if (!fixed() && Prefs.bool(ctx, Prefs.AUTO_CAM, true)) { v.orbit.set(rest(), animate = true); v.requestFrame() } }
            newGreeting()                               // a central ligou: saudação nova
            updateTemp()
            Prefs.get(ctx).registerOnSharedPreferenceChangeListener(this)
            v.setVisible(visibleNow)
            tries = 0
            Diag.log("Papel de parede: cena pronta")
        }

        private var lastBackdrop: Boolean? = null
        /** idioma do carro mudou: textos do papel de parede no idioma novo */
        private val langListener: () -> Unit = { main.post { newGreeting(); updateTemp() } }
        private val chargeListener: () -> Unit = { updateTemp(); vp?.requestFrame() }

        /** toque nos botões do widget de carga: Editar abre o app no valor do kWh; Fechar volta a temperatura */
        private fun chargeHit(x: Float, y: Float): Boolean {
            val hud = vp?.hud ?: return false
            if (!hud.chargeShown) return false
            val sx = x + offX.coerceAtLeast(0f)
            if (hud.chargeHits.close.contains(sx, y)) { ChargeMonitor.get(ctx).close(); return true }
            if (hud.chargeHits.edit.contains(sx, y)) {
                try {
                    startActivity(android.content.Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PRICE, true)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (t: Throwable) { Diag.log("Papel de parede: não abriu o app: $t") }
                return true
            }
            return false
        }

        private fun teardown() {
            try { Prefs.get(ctx).unregisterOnSharedPreferenceChangeListener(this) } catch (_: Throwable) { }
            val w = world
            ChargeMonitor.get(ctx).listeners.remove(chargeListener)
            L.listeners.remove(langListener)
            if (w != null) {
                w.openListeners.remove(openListener)
                try { driver?.stop() } catch (_: Throwable) { }
                try { spots?.destroy() } catch (_: Throwable) { }
                try { vp?.destroy() } catch (_: Throwable) { }
                World.release()
            }
            world = null; driver = null; vp = null; spots = null
        }

        private fun updateTemp() {
            val v = vp ?: return
            val w = world ?: return
            val d = driver ?: return
            val show = Prefs.bool(ctx, Prefs.OUT_TEMP, true)
            // superfície real (a do papel de parede pode ser mais larga que a tela) e o pedaço visível
            val sw = if (surfW > 0) surfW else screenW; val sh = if (surfH > 0) surfH else screenH
            val visW = minOf(screenW, sw)
            val left = offX.coerceIn(0f, sw - visW)
            val light = !w.backdropLight()
            val city = if (Prefs.bool(ctx, Prefs.CITY_ON, true)) WeatherSync.get(ctx).city() else null
            // carregando: o widget de carga entra no lugar da temperatura
            val cm = ChargeMonitor.get(ctx).model()
            var changed = v.hud?.setCharge(cm, !light || d.isNight(), sw, sh, left, visW) == true
            val charging = cm.mode != ChargeModel.HIDDEN
            changed = (v.hud?.set(show && !charging, TempArt.label(d.outsideTemp()), light, sw, sh, left, visW, city) == true) || changed
            changed = (v.hud?.setGreeting(if (greetAlpha > 0f || greetTarget > 0f) greetText else null, light, sw, sh, left, visW) == true) || changed
            // fundo claro (estrada de dia): faixa escura atrás da barra de notificações do carro
            changed = (v.hud?.setTopBand(w.backdropLight()) == true) || changed
            changed = updateRainAsk() || changed
            if (changed) v.redrawHud()
        }

        /* ---------- "Está chovendo aí?" ---------- */

        private fun updateRainAsk(): Boolean {
            val v = vp ?: return false
            val d = driver ?: return false
            val sw = if (surfW > 0) surfW else screenW; val sh = if (surfH > 0) surfH else screenH
            val visW = minOf(screenW, sw)
            return v.hud?.setAsk(RainGate.pending(ctx), d.isNight(), sw, sh, offX.coerceIn(0f, sw - visW), visW) == true
        }

        private fun rainHit(x: Float, y: Float): Boolean {
            val hud = vp?.hud ?: return false
            if (!hud.askShown) return false
            val sx = x + offX.coerceAtLeast(0f)
            val yes = hud.askHits.rain.contains(sx, y); val later = hud.askHits.later.contains(sx, y)
            if (!yes && !later) return false
            RainGate.answer(ctx, yes)
            return true
        }

        /* ---------- botão Fixo | Auto (aparece e some junto com os pontos) ---------- */

        private val pill = FloatArray(4)   // esquerda, topo, largura, altura (pixels da tela)

        private fun updatePill() {
            val v = vp ?: return
            val sw = if (surfW > 0) surfW else screenW; val sh = if (surfH > 0) surfH else screenH
            val k = sh / TempArt.REF_H
            val visW = minOf(screenW, sw)
            val left0 = offX.coerceIn(0f, sw - visW)
            pill[2] = 236f * k; pill[3] = 60f * k
            pill[0] = left0 + visW - 64f * visW / TempArt.REF_W - pill[2]
            pill[1] = SAFE_BOTTOM * sh - 22f * k - pill[3]
            val op = spots?.shown ?: 0f
            var changed = v.hud?.setPill(fixed(), op, pill[0], pill[1], pill[2], pill[3], sw, sh) == true
            // à esquerda do X: chuva (só chovendo) e ◀ ● ▶ (posição do carro na tela)
            val d = pill[3]; val step = d * 1.25f
            var x = pill[0] - step * 2          // (o X fica em pill[0] − 1,25·d)
            val raining = world?.raining == true
            roundRects.clear()
            for (kind in listOf("rain", "right", "center", "left")) {
                val show = kind != "rain" || raining
                if (show) roundRects[kind] = floatArrayOf(x, pill[1], d)
                changed = (v.hud?.setRound(kind, if (show) op else 0f, x, pill[1], d, sw, sh) == true) || changed
                if (show) x -= step
            }
            if (changed) v.redraw()
        }

        /* ---------- botões ◀ ● ▶ (posição do carro, vale no Fixo e no Auto) e suspender chuva ---------- */

        private val roundRects = LinkedHashMap<String, FloatArray>()

        private fun roundHit(x: Float, y: Float): Boolean {
            if ((spots?.shown ?: 0f) < .5f) return false
            for ((kind, r) in roundRects) {
                val m = r[2] * .15f
                if (x < r[0] - m || x > r[0] + r[2] + m || y < r[1] - m || y > r[1] + r[2] + m) continue
                when (kind) {
                    "rain" -> RainGate.suspend(ctx)
                    "center" -> setShift(0f)
                    "left" -> setShift(shiftNow() - SHIFT_STEP)
                    "right" -> setShift(shiftNow() + SHIFT_STEP)
                }
                restartHomeTimer()
                updatePill()
                return true
            }
            return false
        }

        private fun shiftNow() = Prefs.get(ctx).getFloat(Prefs.WALL_SHIFT, 0f)

        /**
         * limite: o carro anda até 16% da tela para cada lado; mais que isso ele encostaria na borda e
         * sairia da faixa livre entre a barra de status e os cartões do launcher
         */
        private fun setShift(f: Float) {
            val s = (Math.round(f.coerceIn(-SHIFT_MAX, SHIFT_MAX) / SHIFT_STEP) * SHIFT_STEP)
            Prefs.get(ctx).edit().putFloat(Prefs.WALL_SHIFT, s).apply()
            vp?.shiftX = s
            Diag.log("Papel de parede: carro em %+.0f%% da tela".format(s * 100))
        }

        /** botão X ao lado do Fixo|Auto: some com os pontos e os botões na hora (voltam ao girar o carro) */
        private fun closeHit(x: Float, y: Float): Boolean {
            if ((spots?.shown ?: 0f) < .5f) return false
            val d = pill[3]; val l = pill[0] - d * 1.25f; val t = pill[1]; val m = d * .2f
            if (x < l - m || x > l + d + m || y < t - m || y > t + d + m) return false
            spots?.hideNow()
            updatePill()
            Diag.log("Papel de parede: pontos escondidos (X)")
            return true
        }

        /** toque no botão: metade esquerda = Fixo, direita = Auto */
        private fun pillHit(x: Float, y: Float): Boolean {
            if ((spots?.shown ?: 0f) < .5f) return false
            val m = pill[3] * .25f
            if (x < pill[0] - m || x > pill[0] + pill[2] + m || y < pill[1] - m || y > pill[1] + pill[3] + m) return false
            val toFixed = x < pill[0] + pill[2] / 2
            val ed = Prefs.get(ctx).edit().putBoolean(Prefs.WALL_FIXED, toFixed)
            // Fixo: a vista de agora passa a ser a de descanso
            if (toFixed) vp?.let { ed.putString(Prefs.WALL_FIXED_POSE, it.orbit.pose().encode()) }
            ed.apply()
            Diag.log("Papel de parede: vista " + if (toFixed) "fixa" else "automática")
            return true
        }

        /* ---------- saudação ---------- */

        private var greetText: String? = null
        private var greetAlpha = 0f
        private var greetTarget = 0f
        /** texto que entra depois de o atual sumir (troca suave); null = nenhuma troca pendente */
        private var pendingText: String? = null
        /** saudação principal da vez (para tirar a data de baixo dela depois) */
        private var firstText: String? = null
        private var dateShown: String? = null
        private val hideGreeting = Runnable { pendingText = null; greetTarget = 0f; vp?.requestFrame() }
        /** 1ª saudação acabou: entra a 2ª (a data) no mesmo lugar */
        private val secondPhase = Runnable {
            val d = Greetings.dateLine(); dateShown = d
            showGreeting(d)
            val s2 = Greetings.seconds2(ctx)
            if (s2 > 0) main.postDelayed(hideGreeting, s2 * 1000L + 700L)
            Diag.log("Saudação 2: $d")
        }
        /** 1ª saudação fixa na tela: a data, que ficou embaixo dela, sai depois do tempo da 2ª */
        private val dropDate = Runnable { dateShown = null; firstText?.let { showGreeting(it) } }

        /** mostra [t]: se já há uma saudação na tela, ela some e a nova entra no lugar */
        private fun showGreeting(t: String) {
            if (greetAlpha <= 0f && greetTarget <= 0f) {
                greetText = t; pendingText = null; greetTarget = 1f; vp?.hud?.greetOpacity = 0f
                updateTemp()
            } else { pendingText = t; greetTarget = 0f }
            vp?.requestFrame()
        }

        /**
         * Saudação nova (ligou o carro / a central / pedido nos ajustes). Com a 2ª ligada:
         *  - 1ª com tempo: some e a data entra no mesmo lugar pelo tempo da 2ª;
         *  - 1ª "sempre": a data aparece embaixo dela (menor) pelo tempo da 2ª e depois sai, a 1ª fica;
         *  - 1ª desligada: só a data.
         */
        private fun newGreeting() {
            Guard.run("saudação") {
                main.removeCallbacks(hideGreeting); main.removeCallbacks(secondPhase); main.removeCallbacks(dropDate)
                val g1 = Greetings.pick(ctx)
                val on2 = Greetings.secondOn(ctx)
                val s1 = Greetings.seconds(ctx); val s2 = Greetings.seconds2(ctx)
                firstText = g1; dateShown = null
                when {
                    g1 == null && !on2 -> { pendingText = null; greetTarget = 0f; vp?.requestFrame() }
                    g1 == null -> secondPhase.run()
                    !on2 -> { showGreeting(g1); if (s1 > 0) main.postDelayed(hideGreeting, s1 * 1000L) }
                    s1 == 0 -> {
                        val d = Greetings.dateLine(); dateShown = d
                        showGreeting(g1 + "\n" + d)
                        if (s2 > 0) main.postDelayed(dropDate, s2 * 1000L)
                    }
                    else -> { showGreeting(g1); main.postDelayed(secondPhase, s1 * 1000L) }
                }
                if (g1 != null) Diag.log("Saudação: " + g1.replace("\n", " / "))
            }
        }

        /** virou o dia com a data na tela: troca para a de hoje */
        private fun refreshDate() {
            val d = dateShown ?: return
            val now = Greetings.dateLine()
            if (d == now) return
            dateShown = now
            greetText = greetText?.replace(d, now); pendingText = pendingText?.replace(d, now)
            updateTemp()
        }

        /** aparece/some em ~0,7 s; true enquanto anima */
        private fun greetStep(dt: Float): Boolean {
            if (greetAlpha == greetTarget) return false
            greetAlpha = if (greetTarget > greetAlpha) minOf(greetTarget, greetAlpha + dt / .7f) else maxOf(greetTarget, greetAlpha - dt / .7f)
            vp?.hud?.greetOpacity = greetAlpha
            if (greetAlpha == 0f) {
                // troca suave: sumiu a anterior, entra a pendente
                pendingText?.let { greetText = it; pendingText = null; greetTarget = 1f }
                updateTemp()
            }
            return true
        }

        private var surfW = 0f; private var surfH = 0f; private var offX = 0f

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width.toFloat() != surfW || height.toFloat() != surfH) Diag.log("Papel de parede: superfície ${width}x$height · tela ${screenW.toInt()}x${screenH.toInt()}")
            surfW = width.toFloat(); surfH = height.toFloat()
            updateTemp()
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            offX = -xPixels.toFloat()
            updateTemp()
        }

        private fun applyQuality() {
            val v = vp ?: return
            // (no Android 9 o papel de parede não pode pedir tamanho próprio: a escala é feita dentro do 3D)
            v.setInnerScale(Prefs.scale(ctx))
            v.setShadows(Prefs.shadows(ctx))
        }

        override fun onVisibilityChanged(visible: Boolean) {
            visibleNow = visible
            vp?.setVisible(visible)
            if (visible) {
                driver?.applyEnv()
                if (vp == null && !destroyed && Startup.error == null) tryStart()
            }
        }

        /* toque: arrastar gira o carro (e mostra os pontos); tocar num ponto abre/fecha a peça */
        private var downX = 0f; private var downY = 0f; private var downAt = 0L; private var dragged = false

        override fun onTouchEvent(event: MotionEvent) {
            if (!Prefs.touch(ctx)) return
            val v = vp ?: return
            val w = world ?: return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; downAt = SystemClock.uptimeMillis(); dragged = false }
                MotionEvent.ACTION_MOVE -> if (!dragged && (abs(event.x - downX) > 14 || abs(event.y - downY) > 14)) {
                    dragged = true
                    spotsReady().target = 1f      // saiu da vista de descanso com o dedo: pontos aparecem
                    v.requestFrame()
                }
                MotionEvent.ACTION_UP -> if (!dragged && SystemClock.uptimeMillis() - downAt < 500) {
                    if (rainHit(event.x, event.y)) { v.requestFrame(); return }
                    if (chargeHit(event.x, event.y)) { v.requestFrame(); return }
                    if (closeHit(event.x, event.y)) { v.requestFrame(); return }
                    if (roundHit(event.x, event.y)) { v.requestFrame(); return }
                    if (pillHit(event.x, event.y)) { restartHomeTimer(); v.requestFrame(); return }
                    spots?.hit(event.x, event.y)?.let { name ->
                        w.setOpening(name, !w.isOpen(name))
                        restartHomeTimer()
                        v.requestFrame()
                        return   // toque no ponto não gira o carro
                    }
                } else if (dragged && fixed()) {
                    // Fixo: a vista em que o usuário deixou passa a ser a de descanso
                    main.removeCallbacks(saveFixed); savePending = true; main.postDelayed(saveFixed, 1500)
                }
            }
            v.onTouch(event, resources.displayMetrics.widthPixels)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_DOWN) restartHomeTimer()
        }

        /** os pontos só são criados na primeira vez que aparecem (nada extra no papel de parede parado) */
        private fun spotsReady(): WallSpots = spots ?: WallSpots(world!!, resources.displayMetrics.density).also { spots = it }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.QUALITY -> applyQuality()
                Prefs.OUT_TEMP, Prefs.WX_TEMP -> updateTemp()
                Prefs.GREET_NOW, Prefs.GREET_ON, Prefs.GREET_DRIVERS, Prefs.GREET_SECS, Prefs.GREET2_ON, Prefs.GREET2_SECS -> newGreeting()
                Prefs.CITY_ON, Prefs.CITY, Prefs.RAIN_ASK -> updateTemp()
                Prefs.LANG -> L.init(ctx)
                Prefs.WALL_VIEW, Prefs.AUTO_CAM -> vp?.let { it.orbit.set(rest(), animate = true); it.requestFrame() }
            }
        }

        override fun onDestroy() {
            destroyed = true
            main.removeCallbacksAndMessages(null)
            teardown()
            Diag.log("Papel de parede: destruído (preview=$isPreview)")
            // a central às vezes troca o papel de parede sozinha: confere em instantes
            if (!isPreview) RecoveryJob.schedule(ctx, 5000, true)
            super.onDestroy()
        }
    }
}
