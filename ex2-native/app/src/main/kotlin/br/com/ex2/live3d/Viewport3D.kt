package br.com.ex2.live3d

import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import kotlin.math.min

/**
 * Uma superfície onde a cena é desenhada (tela do app ou papel de parede), com câmera própria.
 * Só desenha quando algo muda: carro parado e câmera parada = GPU em repouso.
 */
class Viewport3D(private val world: World, private val ctx: android.content.Context) :
    UiHelper.RendererCallback, Choreographer.FrameCallback {

    private val engine = world.engine
    private val renderer: Renderer = engine.createRenderer()
    private val view: View = engine.createView()
    private val camEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(camEntity)
    private val ui = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val display = DisplayHelper(ctx)
    private var swap: SwapChain? = null
    private var width = 1; private var height = 1
    private var scheduled = false
    private var lastNanos = 0L
    private var drawnVersion = -1L
    private var visible = true
    private val model = FloatArray(16)
    private val modelD = DoubleArray(16)
    private val viewD = DoubleArray(16)
    private val projD = DoubleArray(16)
    /** a cada quadro: matrizes de vista/projeção e olho (pontos de toque presos ao carro) */
    var onCamera: ((DoubleArray, DoubleArray, DoubleArray) -> Unit)? = null
    private val listener: () -> Unit = { requestFrame() }

    /** teste: exposição da câmera (1 = normal) */
    private val exposure: (Float) -> Unit = { k -> camera.setExposure(1f, 1.2f * k, 100f); requestFrame() }

    val orbit = Orbit()

    companion object {
        /** quanto o carro pode ocupar da faixa livre (o carro na vista não enche a tela toda) */
        const val FIT = 1.35f
        /** resolução enquanto gira/anima (ao parar, um quadro nítido em 100%) */
        const val LOW_RES = .7f
    }
    /** chamado a cada quadro desenhado (fps no Diagnóstico) */
    var onFrame: ((Long) -> Unit)? = null
    /** chamado quando o usuário toca/arrasta (o papel de parede usa para voltar à vista depois) */
    var onInteract: (() -> Unit)? = null
    /** animação extra desta tela (pontos do papel de parede aparecendo); true = ainda animando */
    var extraStep: ((Float) -> Boolean)? = null

    /** temperatura externa por cima do 3D (só o papel de parede usa) */
    var hud: TempHud? = null
        set(v) { field = v; v?.resize(width, height) }

    /** o texto por cima mudou: desenha de novo */
    fun redraw() { drawnVersion = -1; requestFrame() }

    /**
     * só os textos por cima mudaram (temperatura, widget de carga, saudação): com a imagem do carro
     * guardada, desenha só ela + os textos, sem redesenhar o 3D
     */
    fun redrawHud() { if (cacheFrames && cacheValid) { hudDirty = true; requestFrame() } else redraw() }

    /* ---------------- imagem pronta do carro (chuva e textos sem redesenhar o 3D) ---------------- */

    /** guardar sempre o último quadro do 3D (papel de parede); com chuva, guarda em qualquer tela */
    var cacheFrames = false
    private var cacheValid = false
    private var hudDirty = false
    private var rt: com.google.android.filament.RenderTarget? = null
    private var rtTex: com.google.android.filament.Texture? = null
    private var rtW = 0; private var rtH = 0
    private val compScene = engine.createScene()
    private val compView: View = engine.createView()
    private val compCamEntity = EntityManager.get().create()
    private val compCam: Camera = engine.createCamera(compCamEntity)
    private val quadMesh = Geo.quad(engine)
    private val bgMi = world.mats.backdrop.createInstance()
    private val bgEntity = Geo.renderable(engine, quadMesh, bgMi, priority = 0)
    private val screenMesh = Geo.screen(engine)
    private val rainMi = world.mats.rain.createInstance()
    private val rainEntity = Geo.renderable(engine, screenMesh, rainMi, priority = 7)
    private val rainP = FloatArray(3)
    private val rtSampler = com.google.android.filament.TextureSampler(com.google.android.filament.TextureSampler.MinFilter.NEAREST,
        com.google.android.filament.TextureSampler.MagFilter.NEAREST, com.google.android.filament.TextureSampler.WrapMode.CLAMP_TO_EDGE)

    init {
        compView.scene = compScene
        compView.camera = compCam
        compView.isPostProcessingEnabled = false
        compView.isShadowingEnabled = false
        compView.antiAliasing = View.AntiAliasing.NONE
        compView.dithering = View.Dithering.NONE
        compCam.setProjection(Camera.Projection.ORTHO, 0.0, 1.0, 0.0, 1.0, -1.0, 1.0)
        compCam.setExposure(1f)
        val tm = engine.transformManager; val rm = engine.renderableManager
        tm.create(bgEntity)
        tm.setTransform(tm.getInstance(bgEntity), floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, .5f, .5f, 0f, 1f))
        compScene.addEntity(bgEntity)
        compScene.addEntity(rainEntity)
        rm.setLayerMask(rm.getInstance(rainEntity), 0xff, 0)
    }

    private fun ensureTarget(): Boolean {
        if (rt != null && rtW == width && rtH == height) return true
        dropTarget()
        return try {
            val t = com.google.android.filament.Texture.Builder().width(width).height(height).levels(1)
                .usage(com.google.android.filament.Texture.Usage.COLOR_ATTACHMENT or com.google.android.filament.Texture.Usage.SAMPLEABLE)
                .format(com.google.android.filament.Texture.InternalFormat.RGBA8).build(engine)
            rt = com.google.android.filament.RenderTarget.Builder().texture(com.google.android.filament.RenderTarget.AttachmentPoint.COLOR, t).build(engine)
            rtTex = t; rtW = width; rtH = height
            bgMi.setParameter("tex", t, rtSampler)
            compView.viewport = Viewport(0, 0, width, height)
            true
        } catch (e: Throwable) { Diag.log("Tela 3D: sem imagem guardada ($e)"); dropTarget(); false }
    }

    private fun dropTarget() {
        view.renderTarget = null
        rt?.let { engine.destroyRenderTarget(it) }; rtTex?.let { engine.destroyTexture(it) }
        rt = null; rtTex = null; rtW = 0; rtH = 0; cacheValid = false
    }

    /** camadas extras visíveis só nesta tela (bit 2 = pontos do papel de parede) */
    fun showLayers(mask: Int) { view.setVisibleLayers(mask, mask); drawnVersion = -1; requestFrame() }

    init {
        view.scene = world.scene
        view.camera = camera
        view.antiAliasing = View.AntiAliasing.NONE
        view.setVisibleLayers(0xff, 0x1)   // só a camada normal; o papel de parede liga a dos pontos
        view.colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Filmic()).build(engine)
        view.dithering = View.Dithering.NONE
        camera.setExposure(1f, 1.2f, 100f)   // exposição = 1: intensidades iguais às do site
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(.03, .05, .08, 1.0) }
        ui.renderCallback = this
        world.listeners.add(listener)
        world.exposureListeners.add(exposure)
    }

    /* ---------------- desempenho ---------------- */

    /** suavização de bordas 2x (4x pesava demais na central) e resolução menor enquanto gira */
    private var msaa = true
    private var dynRes = true
    private var lowRes = false
    /** limite de 30 quadros por segundo enquanto anima (a central engasgava tentando 60) */
    var cap30 = true
    private var wasActive = false

    private val prefs = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.T_MSAA || key == Prefs.T_DYNRES) { readPrefs(); drawnVersion = -1; requestFrame() }
    }

    private fun readPrefs() {
        msaa = Prefs.bool(ctx, Prefs.T_MSAA, true)
        dynRes = Prefs.bool(ctx, Prefs.T_DYNRES, true)
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = msaa; sampleCount = 2 }
        if (!dynRes && lowRes) setLowRes(false)
    }

    private fun setLowRes(on: Boolean) {
        lowRes = on
        val base = innerScale * autoScale
        val s = if (on) minOf(LOW_RES, base) else base
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
            enabled = s < .999f; minScale = s; maxScale = s; homogeneousScaling = true
        }
    }

    /**
     * Qualidade do papel de parede: no Android 9 da central o papel de parede NÃO pode pedir uma
     * superfície menor ("Wallpapers currently only support sizing from layout": a montagem parava no
     * meio, sem temperatura, pontos nem troca de vista). Então a resolução menor é feita por dentro
     * do 3D, na mesma superfície do tamanho da tela.
     */
    private var innerScale = 1f
    /** degrau da qualidade automática (ver adapt) */
    var autoScale = 1f
        private set
    fun setInnerScale(s: Float) { innerScale = s.coerceIn(.4f, 1f); setLowRes(lowRes); drawnVersion = -1; requestFrame() }

    init {   // (depois das declarações acima: em Kotlin a ordem importa)
        Prefs.get(ctx).registerOnSharedPreferenceChangeListener(prefs)
        readPrefs()
    }

    /* medidor: quadros por segundo e tempo médio entre quadros enquanto anima */
    var measuredFps = 0f
        private set
    var frameMs = 0f
        private set
    val resolutionNow get() = if (lowRes) LOW_RES else 1f
    private var statFrames = 0
    private var statStart = 0L
    private var statPrev = 0L
    private var statSum = 0L

    private fun countStat(t: Long, active: Boolean) {
        if (!active) { statStart = 0L; statPrev = 0L; return }
        if (statStart == 0L) { statStart = t; statPrev = t; statFrames = 0; statSum = 0L; return }
        statFrames++; statSum += t - statPrev; statPrev = t
        if (t - statStart >= 1_000_000_000L) {
            measuredFps = statFrames * 1e9f / (t - statStart)
            frameMs = statSum / 1e6f / statFrames
            statStart = t; statFrames = 0; statSum = 0L
            adapt(frameMs)
        }
    }

    /**
     * Qualidade automática (monitor de lentidão): se a central ficar lenta (menos de ~18 quadros por
     * segundo por 3 s seguidos), a resolução interna baixa um degrau (até 60%); com folga por 15 s,
     * volta a subir. Assim o carro não fica "arrastado" quando a central está ocupada com outra coisa.
     */
    private var slowSecs = 0
    private var fastSecs = 0
    private fun adapt(ms: Float) {
        if (ms > 55f) { slowSecs++; fastSecs = 0 } else if (ms < 38f) { fastSecs++; slowSecs = 0 } else { slowSecs = 0; fastSecs = 0 }
        if (slowSecs >= 3 && autoScale > .61f) {
            autoScale = (autoScale - .1f).coerceAtLeast(.6f); slowSecs = 0
            Diag.log("Monitor: central lenta (%.0f ms por quadro); resolução automática %d%%".format(ms, (autoScale * 100).toInt()))
            setLowRes(lowRes)
        } else if (fastSecs >= 15 && autoScale < 1f) {
            autoScale = (autoScale + .1f).coerceAtMost(1f); fastSecs = 0
            Diag.log("Monitor: central com folga; resolução automática %d%%".format((autoScale * 100).toInt()))
            setLowRes(lowRes)
        }
    }

    fun attach(sv: SurfaceView) = ui.attachTo(sv)
    fun attach(holder: SurfaceHolder) = ui.attachTo(holder)

    /** fração da resolução da tela (qualidade); o hardware amplia sem custo */
    fun setScale(scale: Float, screenW: Int, screenH: Int) {
        ui.setDesiredSize((screenW * scale).toInt(), (screenH * scale).toInt())
    }

    fun setShadows(on: Boolean) { view.isShadowingEnabled = on; requestFrame() }

    fun setVisible(v: Boolean) {
        visible = v
        if (v) { lastNanos = 0L; drawnVersion = -1; resettle(); wakeAmbient(); requestFrame() }
    }

    fun requestFrame() {
        if (scheduled || !visible) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** monitor de erros: um quadro que falha não derruba o app; tenta de novo logo depois */
    override fun doFrame(frameTimeNanos: Long) {
        try { frame(frameTimeNanos) } catch (t: Throwable) {
            scheduled = false
            Guard.failed("desenhar o 3D", t)
            drawnVersion = -1
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ requestFrame() }, 500)
        }
    }

    private fun frame(frameTimeNanos: Long) {
        scheduled = false
        if (!visible) return
        // 30 quadros por segundo enquanto anima: pula um quadro de tela sim, outro não
        if (cap30 && wasActive && lastDraw != 0L && frameTimeNanos - lastDraw < 30_000_000L) { requestFrame(); return }
        val dt = if (lastNanos == 0L) 1f / 60 else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, .1f)
        lastNanos = frameTimeNanos
        val extra = extraStep?.invoke(dt) ?: false
        // raios: só dentro da janela de animação (papel de parede); cada raio redesenha o carro
        val windowOpen = ambientWindowMs <= 0 || android.os.SystemClock.uptimeMillis() < ambientUntil
        world.flashOk = windowOpen
        val busy = world.tick(frameTimeNanos, dt) || extra
        val moving = orbit.step(dt)
        // chuva: SEMPRE animada, no máximo 20 fps, desenhando só os pingos por cima da imagem pronta do carro
        val raining = world.rainParams(rainP)
        val composite = (cacheFrames || raining) && ensureTarget()
        val stormOn = world.ambient && !raining && windowOpen
        if (world.ambient && !windowOpen) world.calm()
        val rainDue = raining && composite && frameTimeNanos - lastDraw >= 48_000_000L
        val ambientDue = stormOn && frameTimeNanos - lastDraw >= 48_000_000L
        val active = busy || moving || stormOn
        // resolução dinâmica: menor durante o movimento; parou = volta a 100% neste quadro
        val wantLow = dynRes && (moving || busy)
        if (wantLow != lowRes) { setLowRes(wantLow); if (!wantLow) drawnVersion = -1 }
        wasActive = active
        val sc = swap
        var skipped = false
        val full = busy || moving || ambientDue || settle > 0 || world.version != drawnVersion || (composite && !cacheValid)
        val light = !full && composite && (rainDue || hudDirty)
        if (sc != null && light) {
            // só pingos e textos por cima do último quadro do carro (quase nada para a placa de vídeo)
            lastDraw = frameTimeNanos
            val rmg = engine.renderableManager
            rmg.setLayerMask(rmg.getInstance(rainEntity), 0xff, if (raining) 1 else 0)
            if (raining) rainMi.setParameter("params", rainP[0], rainP[1], width.toFloat() / height, rainP[2])
            if (renderer.beginFrame(sc, frameTimeNanos)) {
                renderer.render(compView)
                hud?.render(renderer)
                renderer.endFrame()
                hudDirty = false
            } else skipped = true
        } else if (sc != null && full) {
            lastDraw = frameTimeNanos
            drawnVersion = world.version
            val e = orbit.eye()
            // "para cima" da câmera perpendicular à direção do olhar (vista de cima não gira)
            val sp = Math.sin(orbit.pitch.toDouble()); val cp = Math.cos(orbit.pitch.toDouble())
            val sy = Math.sin(orbit.yaw.toDouble()); val cy = Math.cos(orbit.yaw.toDouble())
            camera.lookAt(e[0], e[1], e[2], orbit.tx.toDouble(), orbit.ty.toDouble(), orbit.tz.toDouble(), -sp * sy, cp, -sp * cy)
            onCamera?.let { cb ->
                camera.getViewMatrix(viewD); camera.getProjectionMatrix(projD)
                cb(viewD, projD, e)
            }
            camera.getModelMatrix(modelD)
            for (i in 0 until 16) model[i] = modelD[i].toFloat()
            world.prepare(model, width.toFloat() / height)
            if (renderer.beginFrame(sc, frameTimeNanos)) {
                if (composite) {
                    // o carro vai para a imagem guardada; a tela recebe ela + pingos + textos
                    view.renderTarget = rt
                    renderer.render(view)
                    val rmg = engine.renderableManager
                    rmg.setLayerMask(rmg.getInstance(rainEntity), 0xff, if (raining) 1 else 0)
                    if (raining) rainMi.setParameter("params", rainP[0], rainP[1], width.toFloat() / height, rainP[2])
                    renderer.render(compView)
                    cacheValid = true
                } else {
                    if (view.renderTarget != null) view.renderTarget = null
                    cacheValid = false
                    renderer.render(view)
                }
                hud?.render(renderer)
                renderer.endFrame()
                hudDirty = false
                refused = 0
                onFrame?.invoke(frameTimeNanos)
                countStat(frameTimeNanos, active)
                if (settle > 0) settle--
            } else {
                // a biblioteca pulou o quadro (placa de vídeo ocupada): tenta de novo no próximo,
                // senão a tela ficava preta/velha até alguém tocar
                drawnVersion = -1
                skipped = true
                // vigia: muitos quadros recusados seguidos = superfície "presa" (tela preta até reativar o
                // papel de parede); refaz a ligação com a superfície
                if (++refused >= 90) { refused = 0; rebuildSwap() }
            }
        }
        if (busy || moving || stormOn || (raining && composite) || skipped || settle > 0) requestFrame() else lastNanos = 0L
    }

    /** quadros extras depois de a superfície surgir/voltar: o primeiro às vezes não chega à tela */
    private var settle = 0
    private fun resettle() { settle = 8 }

    /* ---------------- papel de parede: área livre dos botões do launcher ---------------- */

    /** faixa vertical da tela (frações da altura REAL da tela) onde o carro deve caber; 0..1 = tela toda */
    private var safeTop = 0f
    private var safeBottom = 1f
    private var realHeight = 0

    fun setSafeArea(top: Float, bottom: Float, screenRealHeight: Int) {
        safeTop = top; safeBottom = bottom; realHeight = screenRealHeight
        applyFraming(); drawnVersion = -1; requestFrame()
    }

    /** encolhe e sobe a imagem para o carro ficar inteiro dentro da faixa livre */
    private fun applyFraming() {
        val aspect = width.toDouble() / height
        val near = .05; val far = 400.0
        var hh = near * Math.tan(Math.toRadians(15.0))   // metade da altura do quadro (30° na vertical)
        var yc = 0.0
        if ((safeTop > 0f || safeBottom < 1f) && height > 1) {
            // o papel de parede cobre a tela inteira (o buffer pode ser menor pela qualidade: não importa)
            val top = safeTop
            val bottom = safeBottom
            val free = (bottom - top).coerceIn(.2f, 1f)
            val k = min(1f, free * FIT)               // < 1 = afasta (carro menor)
            val center = (top + bottom) / 2           // fração da altura, de cima
            hh /= k
            // quadro descido = imagem sobe: o centro da cena vai para o meio da faixa livre
            yc = -(1 - 2 * center) * hh
            Diag.log("Papel de parede: faixa livre ${"%.2f".format(top)}..${"%.2f".format(bottom)} · escala ${"%.2f".format(k)}")
        }
        val hw = hh * aspect
        // carro deslocado para o lado (botões ◀ ● ▶ do papel de parede): fração da largura da tela
        val xc = -shiftX * 2 * hw
        camera.setProjection(Camera.Projection.PERSPECTIVE, xc - hw, xc + hw, yc - hh, yc + hh, near, far)
    }

    /** posição do carro na tela: 0 = no meio, + = para a direita (fração da largura da tela) */
    var shiftX = 0f
        set(v) { if (field != v) { field = v; applyFraming(); cacheValid = false; drawnVersion = -1; requestFrame() } }

    private var lastDraw = 0L
    private var ambientUntil = 0L
    /** papel de parede: anima chuva/raios só por esse tempo após aparecer ou ser tocado (0 = sempre) */
    var ambientWindowMs = 0L

    private fun wakeAmbient() { ambientUntil = android.os.SystemClock.uptimeMillis() + ambientWindowMs }

    /* ---------------- superfície ---------------- */

    private var refused = 0
    private var lastSurface: Surface? = null

    private fun rebuildSwap() {
        val s = lastSurface?.takeIf { it.isValid } ?: return
        Diag.log("Tela 3D: superfície presa; refazendo")
        try {
            swap?.let { engine.destroySwapChain(it); engine.flushAndWait() }
            swap = engine.createSwapChain(s, ui.swapChainFlags)
            drawnVersion = -1; resettle()
        } catch (t: Throwable) { Diag.log("Tela 3D: não deu para refazer: $t") }
    }

    override fun onNativeWindowChanged(surface: Surface) {
        lastSurface = surface
        swap?.let { engine.destroySwapChain(it) }
        swap = engine.createSwapChain(surface, ui.swapChainFlags)
        Diag.log("Tela 3D: superfície pronta")
        display.attach(renderer, (ctx.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(0))
        drawnVersion = -1
        resettle()
        requestFrame()
    }

    override fun onDetachedFromSurface() {
        lastSurface = null
        display.detach()
        swap?.let { engine.destroySwapChain(it); engine.flushAndWait() }
        swap = null
    }

    override fun onResized(w: Int, h: Int) {
        width = maxOf(1, w); height = maxOf(1, h)
        view.viewport = Viewport(0, 0, width, height)
        cacheValid = false   // (a imagem guardada é refeita no tamanho novo)
        hud?.resize(width, height)
        applyFraming()   // projeção (30° na vertical), com o enquadramento do papel de parede
        drawnVersion = -1
        resettle()
        requestFrame()
    }

    /* ---------------- toque ---------------- */

    private var vt: VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f
    private var pinching = false
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { orbit.zoom(d.scaleFactor); requestFrame(); return true }
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinching = true; return true }
    })

    fun onTouch(e: MotionEvent, viewWidth: Int): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                lastX = e.x; lastY = e.y; pinching = false
                orbit.stop(); wakeAmbient(); onInteract?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!pinching && e.pointerCount == 1) {
                    orbit.drag(e.x - lastX, e.y - lastY, viewWidth)
                    requestFrame()
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> pinching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.let {
                    it.addMovement(e); it.computeCurrentVelocity(1000)
                    if (!pinching) orbit.fling(it.xVelocity * .35f, it.yVelocity * .2f, viewWidth)
                    it.recycle()
                }
                vt = null
                onInteract?.invoke()
                requestFrame()
            }
        }
        return true
    }

    fun destroy() {
        visible = false
        Prefs.get(ctx).unregisterOnSharedPreferenceChangeListener(prefs)
        world.listeners.remove(listener)
        world.exposureListeners.remove(exposure)
        Choreographer.getInstance().removeFrameCallback(this)
        ui.detach()
        swap?.let { engine.destroySwapChain(it) }
        swap = null
        hud?.destroy(); hud = null
        dropTarget()
        compScene.remove(bgEntity); compScene.remove(rainEntity)
        engine.destroyEntity(bgEntity); engine.destroyEntity(rainEntity)
        engine.destroyMaterialInstance(bgMi); engine.destroyMaterialInstance(rainMi)
        quadMesh.destroy(engine); screenMesh.destroy(engine)
        engine.destroyView(compView); engine.destroyScene(compScene)
        engine.destroyCameraComponent(compCamEntity); EntityManager.get().destroy(compCamEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyCameraComponent(camEntity)
        EntityManager.get().destroy(camEntity)
    }
}
