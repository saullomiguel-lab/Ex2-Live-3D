package br.com.ex2.live3d

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.android.filament.Camera
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.TextureHelper

/**
 * Temperatura externa no papel de parede: uma segunda vista (só com este texto) desenhada por cima
 * do 3D, sem pós-processamento (as cores ficam exatamente as de tela). Posição/tamanho como no
 * widget da tela inicial do carro (ver TempArt), proporcionais à tela real.
 */
class TempHud(private val world: World) {
    companion object { /** altura da faixa escura do topo (px, tela 1920×1080) */ const val BAND = 170f }

    private val engine = world.engine
    private val scene = engine.createScene()
    val view: View = engine.createView()
    private val camEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(camEntity)
    private val mesh = Geo.quad(engine)
    private val mi = world.mats.label.createInstance()
    private val entity = Geo.renderable(engine, mesh, mi, priority = 7)
    // saudação (precisa existir antes do init)
    private val gMi = world.mats.label.createInstance()
    private val gEntity = Geo.renderable(engine, mesh, gMi, priority = 7)
    private var tex: Texture? = null
    private var key = ""
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    var shown = false
        private set

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.isPostProcessingEnabled = false
        view.isShadowingEnabled = false
        view.antiAliasing = View.AntiAliasing.NONE
        // tela inteira = 0..1 (y para cima)
        camera.setProjection(Camera.Projection.ORTHO, 0.0, 1.0, 0.0, 1.0, -1.0, 1.0)
        camera.setExposure(1f)
        TempArt.load(world.ctx)
        // sem este componente a posição calculada em set() não era aplicada
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        mi.setParameter("opacity", 1f)
        engine.transformManager.create(gEntity)
        scene.addEntity(gEntity)
        gMi.setParameter("opacity", 0f)
        engine.renderableManager.setLayerMask(engine.renderableManager.getInstance(gEntity), 0xff, 0)
    }

    /* ---------- saudação (canto de cima à esquerda) ---------- */

    private var gTex: Texture? = null
    private var gKey = ""
    private var gShown = false

    /** texto da saudação (null = nenhuma); a cor segue o fundo como a temperatura */
    fun setGreeting(text: String?, lightText: Boolean, surfW: Float, surfH: Float, visLeft: Float, visW: Float): Boolean {
        val newKey = "$text|$lightText|${surfW.toInt()}x${surfH.toInt()}|${visLeft.toInt()}|${visW.toInt()}"
        if (newKey == gKey) return false
        gKey = newKey
        gShown = text != null
        if (text == null) { applyGreetLayer(); return true }
        val k = surfH / TempArt.REF_H
        val pad = 14f * k
        val (tw, th) = GreetArt.size(world.ctx, text, k)
        val w = (tw + 2 * pad).toInt() + 2
        val h = (th + 2 * pad).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        GreetArt.drawMask(Canvas(bmp), text, k, pad, pad)
        gTex?.let { engine.destroyTexture(it) }
        val t = Texture.Builder().width(w).height(h).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).build(engine)
        TextureHelper.setBitmap(engine, t, 0, bmp, android.os.Handler(android.os.Looper.getMainLooper()), Runnable { bmp.recycle() })
        gTex = t
        gMi.setParameter("tex", t, sampler)
        val c = if (lightText) floatArrayOf(.95f, .95f, .95f, .5f) else floatArrayOf(.086f, .094f, .11f, 0f)
        gMi.setParameter("color", c[0], c[1], c[2], c[3])
        // mesmo topo da temperatura; margem esquerda de 64 px (em 1920)
        val left = (visLeft + GreetArt.LEFT * visW / TempArt.REF_W - pad) / surfW
        val top = (TempArt.TOP * k - pad) / surfH
        val qw = w / surfW; val qh = h / surfH
        val cx = left + qw / 2; val cy = 1f - top - qh / 2
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(gEntity), floatArrayOf(qw, 0f, 0f, 0f, 0f, qh, 0f, 0f, 0f, 0f, 1f, 0f, cx, cy, 0f, 1f))
        applyGreetLayer()
        return true
    }

    /** aparecer/sumir suave (0..1) */
    var greetOpacity = 0f
        set(v) { field = v.coerceIn(0f, 1f); gMi.setParameter("opacity", field); applyGreetLayer() }

    private fun applyGreetLayer() {
        val rm = engine.renderableManager
        rm.setLayerMask(rm.getInstance(gEntity), 0xff, if (gShown && greetOpacity > .001f && gTex != null) 1 else 0)
    }

    fun resize(w: Int, h: Int) { view.viewport = Viewport(0, 0, w, h) }

    /**
     * [text] null = sem leitura ("- -"); [lightText] = branco (fundo escuro) ou grafite (fundo claro).
     * surfW/H = tamanho da superfície do papel de parede (pode ser mais larga que a tela: o launcher
     * a desloca ao trocar de página); visLeft/visW = pedaço dela que aparece na tela. true = mudou.
     */
    fun set(show: Boolean, text: String?, lightText: Boolean, surfW: Float, surfH: Float, visLeft: Float, visW: Float, city: String? = null): Boolean {
        val screenW = surfW; val screenH = surfH
        val k = screenH / TempArt.REF_H
        val newKey = "$show|$text|$city|$lightText|${surfW.toInt()}x${surfH.toInt()}|${visLeft.toInt()}|${visW.toInt()}"
        if (newKey == key) return false
        key = newKey
        shown = show
        engine.renderableManager.let { it.setLayerMask(it.getInstance(entity), 0xff, if (show) 1 else 0) }
        if (!show) return true
        // imagem só do pedaço com o número (com folga para a sombra) e, embaixo, a cidade
        val pad = 12f * k
        val w = (maxOf(TempArt.width(text, k), city?.let { TempArt.cityWidth(it, k) } ?: 0f) + 2 * pad).toInt() + 2
        val h = (TempArt.CAP * k + 2 * pad + 6 * k + (if (city != null) TempArt.cityHeight(k) else 0f)).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        TempArt.drawMask(Canvas(bmp), text, k, w - pad, pad, city)
        tex?.let { engine.destroyTexture(it) }
        val t = Texture.Builder().width(w).height(h).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).build(engine)
        TextureHelper.setBitmap(engine, t, 0, bmp, android.os.Handler(android.os.Looper.getMainLooper()), Runnable { bmp.recycle() })
        tex = t
        mi.setParameter("tex", t, sampler)
        val c = if (lightText) floatArrayOf(1f, 1f, 1f, .5f) else floatArrayOf(.11f, .118f, .133f, 0f)
        mi.setParameter("color", c[0], c[1], c[2], c[3])
        // canto de cima à direita: borda direita do anel a 64 px (em 1920) e topo dos números a 123 px (em 1080)
        val right = (visLeft + visW - TempArt.RIGHT * visW / TempArt.REF_W + pad) / screenW
        val top = (TempArt.TOP * k - pad) / screenH
        val qw = w / screenW; val qh = h / screenH
        val cx = right - qw / 2; val cy = 1f - top - qh / 2
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(entity), floatArrayOf(qw, 0f, 0f, 0f, 0f, qh, 0f, 0f, 0f, 0f, 1f, 0f, cx, cy, 0f, 1f))
        return true
    }

    /* ---------- faixa escura no topo e botão Fixo/Auto ---------- */

    private val bandMi = world.mats.sprite.createInstance()
    private val bandEntity = Geo.renderable(engine, mesh, bandMi, priority = 6)
    private var bandTex: Texture? = null
    private var bandOn = false
    private val pillMi = world.mats.sprite.createInstance()
    private val pillEntity = Geo.renderable(engine, mesh, pillMi, priority = 7)
    private var pillTex: Texture? = null
    private var pillKey = ""
    private var pillOpacity = 0f

    init {
        val tm = engine.transformManager; val rm = engine.renderableManager
        for (e in intArrayOf(bandEntity, pillEntity)) { tm.create(e); scene.addEntity(e); rm.setLayerMask(rm.getInstance(e), 0xff, 0) }
        // faixa: escura em cima, sumindo para baixo (as letras brancas da barra do carro ficam legíveis
        // sobre a estrada clara de dia)
        val bmp = Bitmap.createBitmap(4, 64, Bitmap.Config.ARGB_8888)
        for (y in 0 until 64) {
            val t = 1f - y / 63f
            val a = (.56f * t * t * (3f - 2f * t) * 255).toInt()
            for (x in 0 until 4) bmp.setPixel(x, y, android.graphics.Color.argb(a, 8, 10, 14))
        }
        val t = upload(bmp)
        bandTex = t
        bandMi.setParameter("tex", t, sampler); bandMi.setParameter("opacity", 1f)
        val h = BAND / TempArt.REF_H
        tm.setTransform(tm.getInstance(bandEntity), floatArrayOf(1f, 0f, 0f, 0f, 0f, h, 0f, 0f, 0f, 0f, 1f, 0f, .5f, 1f - h / 2, 0f, 1f))
        pillMi.setParameter("opacity", 0f)
    }

    private fun upload(bmp: Bitmap): Texture {
        val t = Texture.Builder().width(bmp.width).height(bmp.height).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).build(engine)
        TextureHelper.setBitmap(engine, t, 0, bmp, android.os.Handler(android.os.Looper.getMainLooper()), Runnable { bmp.recycle() })
        return t
    }

    /** faixa escura atrás da barra de notificações (ligada só com o fundo claro) */
    fun setTopBand(on: Boolean): Boolean {
        if (on == bandOn) return false
        bandOn = on
        engine.renderableManager.let { it.setLayerMask(it.getInstance(bandEntity), 0xff, if (on) 1 else 0) }
        return true
    }

    /** botão de duas posições (Fixo | Auto): retângulo em pixels da superfície; true = mudou */
    fun setPill(fixed: Boolean, opacity: Float, left: Float, top: Float, w: Float, h: Float, surfW: Float, surfH: Float): Boolean {
        val k = "$fixed|${w.toInt()}x${h.toInt()}|${left.toInt()},${top.toInt()}"
        var changed = false
        if (k != pillKey && opacity > .01f) {
            pillKey = k; changed = true
            val bw = w.toInt().coerceAtLeast(8); val bh = h.toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = bh / 2f
            p.color = 0x80101418.toInt()
            cv.drawRoundRect(0f, 0f, bw.toFloat(), bh.toFloat(), r, r, p)
            // lado escolhido em azul
            val pad = bh * .1f
            p.color = Ui.BLUE
            if (fixed) cv.drawRoundRect(pad, pad, bw / 2f, bh - pad, r - pad, r - pad, p)
            else cv.drawRoundRect(bw / 2f, pad, bw - pad, bh - pad, r - pad, r - pad, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = bh * .035f; p.color = 0xBFFFFFFF.toInt()
            val s = p.strokeWidth / 2
            cv.drawRoundRect(s, s, bw - s, bh - s, r - s, r - s, p)
            p.style = android.graphics.Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
            p.typeface = Ui.MEDIUM; p.textSize = bh * .38f; p.textAlign = android.graphics.Paint.Align.CENTER
            val base = bh / 2f - (p.ascent() + p.descent()) / 2
            cv.drawText("Fixo", bw * .26f, base, p); cv.drawText("Auto", bw * .74f, base, p)
            pillTex?.let { engine.destroyTexture(it) }
            val t = upload(bmp)
            pillTex = t
            pillMi.setParameter("tex", t, sampler)
            val qw = w / surfW; val qh = h / surfH
            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(pillEntity), floatArrayOf(qw, 0f, 0f, 0f, 0f, qh, 0f, 0f, 0f, 0f, 1f, 0f,
                (left + w / 2) / surfW, 1f - (top + h / 2) / surfH, 0f, 1f))
        }
        if (Math.abs(opacity - pillOpacity) > .001f) {
            pillOpacity = opacity; changed = true
            pillMi.setParameter("opacity", opacity)
            engine.renderableManager.let { it.setLayerMask(it.getInstance(pillEntity), 0xff, if (opacity > .01f && pillTex != null) 1 else 0) }
        }
        // botão X (círculo do mesmo tamanho, à esquerda do Fixo|Auto): esconde pontos e botões na hora
        if (setClose(opacity, left - h * 1.25f, top, h, surfW, surfH)) changed = true
        return changed
    }

    private val closeMi = world.mats.sprite.createInstance()
    private val closeEntity = Geo.renderable(engine, mesh, closeMi, priority = 7)
    private var closeTex: Texture? = null
    private var closeKey = ""
    private var closeOpacity = 0f

    init {
        engine.transformManager.create(closeEntity); scene.addEntity(closeEntity)
        engine.renderableManager.let { it.setLayerMask(it.getInstance(closeEntity), 0xff, 0) }
        closeMi.setParameter("opacity", 0f)
    }

    private fun setClose(opacity: Float, left: Float, top: Float, d: Float, surfW: Float, surfH: Float): Boolean {
        var changed = false
        val k = "${d.toInt()}|${left.toInt()},${top.toInt()}"
        if (k != closeKey && opacity > .01f) {
            closeKey = k; changed = true
            val s = d.toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            val r = s / 2f
            p.color = 0x80101418.toInt(); cv.drawCircle(r, r, r, p)
            p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = s * .035f; p.color = 0xBFFFFFFF.toInt()
            cv.drawCircle(r, r, r - p.strokeWidth / 2, p)
            p.strokeWidth = s * .075f; p.color = 0xFFFFFFFF.toInt(); p.strokeCap = android.graphics.Paint.Cap.ROUND
            val a = s * .3f
            cv.drawLine(a, a, s - a, s - a, p); cv.drawLine(s - a, a, a, s - a, p)
            closeTex?.let { engine.destroyTexture(it) }
            val t = upload(bmp)
            closeTex = t
            closeMi.setParameter("tex", t, sampler)
            val q = d / surfW; val qh = d / surfH
            val tm = engine.transformManager
            tm.setTransform(tm.getInstance(closeEntity), floatArrayOf(q, 0f, 0f, 0f, 0f, qh, 0f, 0f, 0f, 0f, 1f, 0f,
                (left + d / 2) / surfW, 1f - (top + d / 2) / surfH, 0f, 1f))
        }
        if (Math.abs(opacity - closeOpacity) > .001f) {
            closeOpacity = opacity; changed = true
            closeMi.setParameter("opacity", opacity)
            engine.renderableManager.let { it.setLayerMask(it.getInstance(closeEntity), 0xff, if (opacity > .01f && closeTex != null) 1 else 0) }
        }
        return changed
    }

    /* ---------- botões redondos ao lado do Fixo|Auto: ◀ ● ▶ (posição do carro) e chuva ---------- */

    /** um botão redondo desenhado (mesmo estilo do X): [kind] = left, center, right, rain */
    private inner class RoundBtn(val kind: String) {
        val mi = world.mats.sprite.createInstance()
        val entity = Geo.renderable(engine, mesh, mi, priority = 7)
        var tex: Texture? = null
        var key = ""
        var opacity = 0f
        init {
            engine.transformManager.create(entity); scene.addEntity(entity)
            engine.renderableManager.let { it.setLayerMask(it.getInstance(entity), 0xff, 0) }
            mi.setParameter("opacity", 0f)
        }

        fun set(op: Float, left: Float, top: Float, d: Float, surfW: Float, surfH: Float): Boolean {
            var changed = false
            val k = "${d.toInt()}|${left.toInt()},${top.toInt()}"
            if (k != key && op > .01f) {
                key = k; changed = true
                val s = d.toInt().coerceAtLeast(8)
                val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val cv = Canvas(bmp)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val r = s / 2f
                p.color = if (kind == "rain") Ui.BLUE else 0x80101418.toInt(); cv.drawCircle(r, r, r, p)
                p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = s * .035f; p.color = 0xBFFFFFFF.toInt()
                cv.drawCircle(r, r, r - p.strokeWidth / 2, p)
                p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = s * .075f; p.strokeCap = android.graphics.Paint.Cap.ROUND
                p.strokeJoin = android.graphics.Paint.Join.ROUND
                val a = s * .3f
                when (kind) {
                    "left" -> { val path = android.graphics.Path().apply { moveTo(r + s * .07f, a); lineTo(r - s * .13f, r); lineTo(r + s * .07f, s - a) }; cv.drawPath(path, p) }
                    "right" -> { val path = android.graphics.Path().apply { moveTo(r - s * .07f, a); lineTo(r + s * .13f, r); lineTo(r - s * .07f, s - a) }; cv.drawPath(path, p) }
                    "center" -> {   // mira: anel + ponto no meio
                        p.strokeWidth = s * .06f; cv.drawCircle(r, r, s * .17f, p)
                        p.style = android.graphics.Paint.Style.FILL; cv.drawCircle(r, r, s * .055f, p)
                    }
                    else -> {      // nuvem com pingos, riscada (suspender chuva)
                        p.style = android.graphics.Paint.Style.FILL
                        cv.drawCircle(r - s * .08f, r - s * .06f, s * .11f, p); cv.drawCircle(r + s * .06f, r - s * .1f, s * .14f, p)
                        cv.drawRoundRect(r - s * .2f, r - s * .06f, r + s * .2f, r + s * .06f, s * .06f, s * .06f, p)
                        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = s * .05f
                        for (dx in floatArrayOf(-.1f, 0f, .1f)) cv.drawLine(r + s * dx, r + s * .12f, r + s * (dx - .03f), r + s * .2f, p)
                        p.strokeWidth = s * .06f; p.color = 0xFFFFFFFF.toInt()
                        cv.drawLine(s * .26f, s * .26f, s * .74f, s * .74f, p)
                    }
                }
                tex?.let { engine.destroyTexture(it) }
                val t = upload(bmp)
                tex = t
                mi.setParameter("tex", t, sampler)
                val tm = engine.transformManager
                tm.setTransform(tm.getInstance(entity), floatArrayOf(d / surfW, 0f, 0f, 0f, 0f, d / surfH, 0f, 0f, 0f, 0f, 1f, 0f,
                    (left + d / 2) / surfW, 1f - (top + d / 2) / surfH, 0f, 1f))
            }
            if (Math.abs(op - opacity) > .001f) {
                opacity = op; changed = true
                mi.setParameter("opacity", op)
                engine.renderableManager.let { it.setLayerMask(it.getInstance(entity), 0xff, if (op > .01f && tex != null) 1 else 0) }
            }
            return changed
        }

        fun destroy() { scene.remove(entity); engine.destroyEntity(entity); engine.destroyMaterialInstance(mi); tex?.let { engine.destroyTexture(it) } }
    }

    private val roundBtns = linkedMapOf("left" to RoundBtn("left"), "center" to RoundBtn("center"), "right" to RoundBtn("right"), "rain" to RoundBtn("rain"))

    /** posiciona/mostra um botão redondo (op = 0 esconde); true = mudou */
    fun setRound(kind: String, op: Float, left: Float, top: Float, d: Float, surfW: Float, surfH: Float): Boolean =
        roundBtns[kind]?.set(op, left, top, d, surfW, surfH) ?: false

    private val anyRound get() = roundBtns.values.any { it.opacity > .01f }

    /* ---------- widget de carregamento (no lugar da temperatura) ---------- */

    private val chgMi = world.mats.sprite.createInstance()
    private val chgEntity = Geo.renderable(engine, mesh, chgMi, priority = 7)
    private var chgTex: Texture? = null
    private var chgKey = ""
    private var chgW = 0; private var chgH = 0
    var chargeShown = false
        private set
    /** botões do widget em pixels da superfície (Editar / Fechar) */
    val chargeHits = ChargeArt.Hits()

    init {
        engine.transformManager.create(chgEntity); scene.addEntity(chgEntity)
        engine.renderableManager.let { it.setLayerMask(it.getInstance(chgEntity), 0xff, 0) }
        chgMi.setParameter("opacity", 1f)
    }

    /** mostra (ou esconde, com HIDDEN) o widget de carga; true = mudou */
    fun setCharge(m: ChargeModel, dark: Boolean, surfW: Float, surfH: Float, visLeft: Float, visW: Float): Boolean {
        val show = m.mode != ChargeModel.HIDDEN
        val k = surfH / TempArt.REF_H * .9f
        // a chave usa os números como aparecem na tela (o widget só é refeito quando o texto muda)
        val key = if (!show) "off" else "${m.mode}|${m.dc}|${(m.kw * 10).toInt()}|${m.volts?.toInt()}|${((m.amps ?: 0f) * 10).toInt()}|${m.minutesLeft?.toInt()}|" +
            "${m.soc?.let { (it * 100).toInt() }}|${m.samples.size}|${(m.energyKwh * 10).toInt()}|${(m.price * 100).toInt()}|${m.kmSinceLast?.toInt()}|$dark|" +
            "${surfW.toInt()}x${surfH.toInt()}|${visLeft.toInt()}|${L.code}"
        if (key == chgKey) return false
        chgKey = key
        chargeShown = show
        engine.renderableManager.let { it.setLayerMask(it.getInstance(chgEntity), 0xff, if (show) 1 else 0) }
        if (!show) {
            chargeHits.edit.setEmpty(); chargeHits.close.setEmpty()
            chgTex?.let { engine.destroyTexture(it) }; chgTex = null; chgW = 0; chgH = 0
            return true
        }
        val w = (ChargeArt.W * k).toInt() + 2; val h = (ChargeArt.height(m) * k).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val local = ChargeArt.Hits()
        ChargeArt.draw(Canvas(bmp), m, k, 0f, 0f, dark, TempArt.font, local)
        // mesmo tamanho: só troca a imagem da textura (criar e destruir texturas várias vezes por minuto
        // durante horas de carga gastava memória da placa de vídeo)
        val t = chgTex?.takeIf { w == chgW && h == chgH }?.also {
            TextureHelper.setBitmap(engine, it, 0, bmp, android.os.Handler(android.os.Looper.getMainLooper()), Runnable { bmp.recycle() })
        } ?: run {
            chgTex?.let { engine.destroyTexture(it) }
            upload(bmp).also { chgW = w; chgH = h }
        }
        chgTex = t
        chgMi.setParameter("tex", t, sampler)
        // canto de cima à direita: borda direita como a da temperatura; topo logo abaixo da barra do carro
        val left = visLeft + visW - TempArt.RIGHT * visW / TempArt.REF_W - w
        val top = 84f * surfH / TempArt.REF_H
        chargeHits.edit.set(local.edit); chargeHits.edit.offset(left, top)
        chargeHits.close.set(local.close); chargeHits.close.offset(left, top)
        val qw = w / surfW; val qh = h / surfH
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(chgEntity), floatArrayOf(qw, 0f, 0f, 0f, 0f, qh, 0f, 0f, 0f, 0f, 1f, 0f,
            (left + w / 2f) / surfW, 1f - (top + h / 2f) / surfH, 0f, 1f))
        return true
    }

    /* ---------- "Está chovendo aí?" (no alto, no meio) ---------- */

    private val askMi = world.mats.sprite.createInstance()
    private val askEntity = Geo.renderable(engine, mesh, askMi, priority = 7)
    private var askTex: Texture? = null
    private var askKey = ""
    var askShown = false
        private set
    /** botões da pergunta em pixels da superfície */
    val askHits = RainAskArt.Hits()

    init {
        engine.transformManager.create(askEntity); scene.addEntity(askEntity)
        engine.renderableManager.let { it.setLayerMask(it.getInstance(askEntity), 0xff, 0) }
        askMi.setParameter("opacity", 1f)
    }

    fun setAsk(show: Boolean, dark: Boolean, surfW: Float, surfH: Float, visLeft: Float, visW: Float): Boolean {
        val key = if (!show) "off" else "$dark|${surfW.toInt()}x${surfH.toInt()}|${visLeft.toInt()}|${visW.toInt()}|${L.code}"
        if (key == askKey) return false
        askKey = key
        askShown = show
        engine.renderableManager.let { it.setLayerMask(it.getInstance(askEntity), 0xff, if (show) 1 else 0) }
        askTex?.let { engine.destroyTexture(it) }; askTex = null
        if (!show) { askHits.rain.setEmpty(); askHits.later.setEmpty(); return true }
        val k = surfH / TempArt.REF_H
        val w = (RainAskArt.W * k).toInt() + 2; val h = (RainAskArt.H * k).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val local = RainAskArt.Hits()
        RainAskArt.draw(Canvas(bmp), k, dark, TempArt.font, local)
        val t = upload(bmp)
        askTex = t
        askMi.setParameter("tex", t, sampler)
        val left = visLeft + (visW - w) / 2f
        val top = 96f * k
        askHits.rain.set(local.rain); askHits.rain.offset(left, top)
        askHits.later.set(local.later); askHits.later.offset(left, top)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(askEntity), floatArrayOf(w / surfW, 0f, 0f, 0f, 0f, h / surfH, 0f, 0f, 0f, 0f, 1f, 0f,
            (left + w / 2f) / surfW, 1f - (top + h / 2f) / surfH, 0f, 1f))
        return true
    }

    fun render(r: Renderer) {
        if ((shown && tex != null) || (gShown && greetOpacity > .001f) || bandOn || pillOpacity > .01f || chargeShown || askShown || anyRound) r.render(view)
    }

    fun destroy() {
        scene.remove(entity)
        engine.destroyEntity(entity)
        engine.destroyMaterialInstance(mi)
        tex?.let { engine.destroyTexture(it) }
        scene.remove(gEntity)
        engine.destroyEntity(gEntity)
        engine.destroyMaterialInstance(gMi)
        gTex?.let { engine.destroyTexture(it) }
        for (e in intArrayOf(bandEntity, pillEntity)) { scene.remove(e); engine.destroyEntity(e) }
        engine.destroyMaterialInstance(bandMi); engine.destroyMaterialInstance(pillMi)
        bandTex?.let { engine.destroyTexture(it) }; pillTex?.let { engine.destroyTexture(it) }
        scene.remove(closeEntity); engine.destroyEntity(closeEntity); engine.destroyMaterialInstance(closeMi)
        closeTex?.let { engine.destroyTexture(it) }
        scene.remove(chgEntity); engine.destroyEntity(chgEntity); engine.destroyMaterialInstance(chgMi)
        chgTex?.let { engine.destroyTexture(it) }
        roundBtns.values.forEach { it.destroy() }
        scene.remove(askEntity); engine.destroyEntity(askEntity); engine.destroyMaterialInstance(askMi)
        askTex?.let { engine.destroyTexture(it) }
        mesh.destroy(engine)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camEntity)
        EntityManager.get().destroy(camEntity)
    }
}
