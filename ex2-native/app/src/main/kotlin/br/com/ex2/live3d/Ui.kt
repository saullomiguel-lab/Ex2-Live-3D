package br.com.ex2.live3d

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Identidade visual da central do EX2 (prints da tela do carro): painéis claros "de vidro fosco",
 * cantos bem arredondados, azul forte da Geely nos itens ligados, texto grafite. No modo escuro do
 * carro, as mesmas formas em grafite. Uma paleta só; a tela é montada de novo quando o tema troca.
 */
object Ui {
    /** tema escuro (segue o carro: modo noturno da central, tema do sistema ou a hora) */
    var dark = false

    val BLUE = 0xFF1557E6.toInt()                                                 // azul da central
    val PANEL get() = if (dark) 0xE61D222A.toInt() else 0xEBF3F5F9.toInt()        // painel fosco
    val CARD get() = if (dark) 0xFF272D36.toInt() else 0xFFFFFFFF.toInt()         // cartão dentro do painel
    val CHIP get() = if (dark) 0x1FFFFFFF else 0xFFE4E8EF.toInt()                 // botão desligado
    val CHIP_ON get() = BLUE
    val TEXT get() = if (dark) 0xFFEEF1F5.toInt() else 0xFF1B1E24.toInt()
    val MUTED get() = if (dark) 0xFF98A1AD.toInt() else 0xFF6E7681.toInt()
    val ICON get() = if (dark) 0xFFE6EAF0.toInt() else 0xFF2B3038.toInt()
    val LINE get() = if (dark) 0x1FFFFFFF else 0x14000000
    val SCRIM get() = if (dark) 0x8C05080C.toInt() else 0x591B2430
    val TRACK_OFF get() = if (dark) 0x40FFFFFF else 0xFFD3D9E1.toInt()
    val AMBER = 0xFFFF9F1A.toInt()

    fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density
    fun dpi(c: Context, v: Int) = (v * c.resources.displayMetrics.density + .5f).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun panel(c: Context, vertical: Boolean = true) = LinearLayout(c).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        background = rounded(PANEL, dp(c, 22f))
        val p = dpi(c, 14)
        setPadding(p, p, p, p)
        elevation = dp(c, if (dark) 6f else 3f)
    }

    /** cartão branco (ou grafite) dentro de um painel: linhas de ajuste, grupos */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(c, 16f))
        val p = dpi(c, 18)
        setPadding(p, dpi(c, 8), p, dpi(c, 8))
    }

    /** texto da interface: já sai no idioma do carro (o português é a chave da tradução; ver L) */
    fun text(c: Context, s: String, sp: Float = 18f, color: Int = TEXT, bold: Boolean = false) = TextView(c).apply {
        text = L.t(s); textSize = sp; setTextColor(color)
        if (bold) typeface = MEDIUM
    }

    fun lp(w: Int = LinearLayout.LayoutParams.WRAP_CONTENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)
}

/**
 * Botão de ícone no estilo da central: bloco arredondado com o ícone e o nome dentro;
 * ligado = azul com ícone e texto brancos. Sem nome: só o ícone, bloco quadrado.
 */
class IconButton(c: Context, icon: Int, label: String?, private val size: Int = 64) : LinearLayout(c) {
    private val img = ImageView(c)
    private val txt: TextView? = label?.let { Ui.text(c, it, 12.5f, Ui.MUTED) }
    private val bg = Ui.rounded(Ui.CHIP, Ui.dp(c, 16f))
    var activeColor = Ui.CHIP_ON
    var on = false
        set(v) { field = v; refresh() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = bg
        val w = Ui.dpi(c, if (label != null) (size * 1.3f).toInt() else size)
        val h = Ui.dpi(c, size)
        minimumWidth = w; minimumHeight = h
        val ic = Ui.dpi(c, if (label != null) 26 else 30)
        img.setImageResource(icon)
        addView(img, LayoutParams(ic, ic))
        txt?.let {
            it.gravity = Gravity.CENTER; it.maxLines = 1
            addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dpi(c, 4) })
        }
        setPadding(Ui.dpi(c, 8), Ui.dpi(c, 6), Ui.dpi(c, 8), Ui.dpi(c, 6))
        isClickable = true
        refresh()
    }

    private fun refresh() {
        bg.setColor(if (on) activeColor else Ui.CHIP)
        img.imageTintList = ColorStateList.valueOf(if (on) Color.WHITE else Ui.ICON)
        txt?.setTextColor(if (on) Color.WHITE else Ui.TEXT)
    }

    override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); alpha = if (pressed) .7f else 1f }
}

/** Botão de texto: azul (principal) ou cinza-claro, cantos de 14 dp como na central. */
fun pill(c: Context, s: String, primary: Boolean = false, onClick: () -> Unit) =
    Ui.text(c, s, 17f, if (primary) Color.WHITE else Ui.TEXT, true).apply {
        gravity = Gravity.CENTER
        val h = Ui.dpi(c, 22); val v = Ui.dpi(c, 12)
        setPadding(h, v, h, v)
        background = Ui.rounded(if (primary) Ui.CHIP_ON else Ui.CHIP, Ui.dp(c, 14f))
        minHeight = Ui.dpi(c, 52)
        setOnClickListener { onClick() }
    }

/** Escolha entre opções (faixa cinza com o item escolhido em azul), como "关闭 / 高 / 中 / 低" da central. */
class Segmented(c: Context, private val options: List<Pair<String, String>>, selected: String, private val onPick: (String) -> Unit) : LinearLayout(c) {
    private val views = ArrayList<TextView>()
    var selected = selected
        set(v) { field = v; mark() }

    init {
        orientation = HORIZONTAL
        background = Ui.rounded(Ui.CHIP, Ui.dp(c, 14f))
        val p = Ui.dpi(c, 4)
        setPadding(p, p, p, p)
        for ((id, label) in options) {
            val t = Ui.text(c, label, 16f, Ui.TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dpi(c, 18), 0, Ui.dpi(c, 18), 0)
                setOnClickListener { this@Segmented.selected = id; onPick(id) }
            }
            views.add(t)
            addView(t, LayoutParams(0, Ui.dpi(c, 48), 1f))
        }
        mark()
    }

    private fun mark() = views.forEachIndexed { i, t ->
        val on = options[i].first == selected
        t.background = if (on) Ui.rounded(Ui.CHIP_ON, Ui.dp(context, 11f)) else null
        t.setTextColor(if (on) Color.WHITE else Ui.TEXT)
    }
}

/** Chave liga/desliga desenhada (trilho arredondado, bolinha branca), azul quando ligada. */
class Toggle(c: Context, checked: Boolean, private val onChange: (Boolean) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = c.resources.displayMetrics.density
    private var pos = if (checked) 1f else 0f
    var checked = checked
        private set

    init { isClickable = true; setOnClickListener { set(!this.checked, true) } }

    fun set(v: Boolean, notify: Boolean = false) {
        if (v == checked) return
        checked = v
        ValueAnimator.ofFloat(pos, if (v) 1f else 0f).apply {
            duration = 160; addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        }.start()
        if (notify) onChange(v)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((52 * d).toInt(), (30 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.color = blend(Ui.TRACK_OFF, Ui.CHIP_ON, pos)
        cv.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        val r = h / 2 - 3 * d
        val cx = h / 2 + (w - h) * pos
        p.color = 0x33000000
        cv.drawCircle(cx, h / 2 + .8f * d, r, p)
        p.color = Color.WHITE
        cv.drawCircle(cx, h / 2, r, p)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) + ((((b shr s) and 255) - ((a shr s) and 255)) * t)).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/**
 * Carro visto de cima (frente para cima): toque nas portas, no capô (porta-malas dianteiro)
 * ou na tampa traseira para abrir/fechar. Lado esquerdo do desenho = lado do motorista.
 */
class CarTopView(c: Context, private val isOpen: (String) -> Boolean, private val toggle: (String) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zones = LinkedHashMap<String, RectF>()
    private val d = c.resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((150 * d).toInt(), (250 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bx = w * .22f; val bw = w * .56f; val top = h * .04f; val bot = h * .96f
        val body = RectF(bx, top, bx + bw, bot)
        p.style = Paint.Style.FILL; p.color = Ui.CARD
        cv.drawRoundRect(body, bw * .38f, bw * .38f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = if (Ui.dark) 0x55FFFFFF else 0x33000000
        cv.drawRoundRect(body, bw * .38f, bw * .38f, p)
        // vidros
        p.style = Paint.Style.FILL; p.color = if (Ui.dark) 0x40A8C8FF else 0xFF2B3038.toInt()
        cv.drawRoundRect(RectF(bx + bw * .12f, top + (bot - top) * .27f, bx + bw * .88f, top + (bot - top) * .36f), 8 * d, 8 * d, p)
        cv.drawRoundRect(RectF(bx + bw * .14f, top + (bot - top) * .74f, bx + bw * .86f, top + (bot - top) * .80f), 8 * d, 8 * d, p)
        val L = bot - top
        zones[Openings.FRUNK] = RectF(bx + bw * .1f, top + L * .03f, bx + bw * .9f, top + L * .24f)
        zones[Openings.TAILGATE] = RectF(bx + bw * .1f, top + L * .83f, bx + bw * .9f, top + L * .97f)
        zones[Openings.FL] = RectF(0f, top + L * .37f, bx + bw * .08f, top + L * .55f)
        zones[Openings.FR] = RectF(bx + bw * .92f, top + L * .37f, w, top + L * .55f)
        zones[Openings.RL] = RectF(0f, top + L * .56f, bx + bw * .08f, top + L * .73f)
        zones[Openings.RR] = RectF(bx + bw * .92f, top + L * .56f, w, top + L * .73f)
        for ((name, r) in zones) {
            val open = isOpen(name)
            p.style = Paint.Style.FILL
            p.color = if (open) Ui.CHIP_ON else if (Ui.dark) 0x40FFFFFF else 0x1F000000
            val isDoor = name.startsWith("door")
            if (isDoor) {
                // porta: aberta = desenhada girada para fora
                val left = name.endsWith("L")
                cv.save()
                if (open) cv.rotate(if (left) 28f else -28f, if (left) r.right else r.left, r.top)
                val rr = if (left) RectF(r.right - 12 * d, r.top, r.right, r.bottom) else RectF(r.left, r.top, r.left + 12 * d, r.bottom)
                cv.drawRoundRect(rr, 5 * d, 5 * d, p)
                cv.restore()
            } else {
                cv.drawRoundRect(r, 10 * d, 10 * d, p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            // zona mais próxima do toque (alvos grandes)
            val hit = zones.entries.minByOrNull { (_, r) ->
                val dx = maxOf(r.left - e.x, 0f, e.x - r.right); val dy = maxOf(r.top - e.y, 0f, e.y - r.bottom); dx * dx + dy * dy
            }
            hit?.let { toggle(it.key); invalidate() }
        }
        return true
    }
}

/**
 * Linha do dia para ver as transições do cenário: faixa com as cores do céu ao longo das 24 h
 * (noite, amanhecer, dia, entardecer, noite), marcas do nascer e do pôr do sol e o indicador
 * da hora. Arrastar = hora fixa; o botão "Agora" volta ao relógio.
 */
class DayTrack(c: Context, private val onPick: (Int) -> Unit) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = c.resources.displayMetrics.density
    var dawn = 6 * 60; var dusk = 18 * 60
    var minute = 12 * 60
        set(v) { field = v; invalidate() }
    private val sunrise = c.getDrawable(R.drawable.ic_sunrise)!!.mutate()
    private val sunset = c.getDrawable(R.drawable.ic_sunset)!!.mutate()
    private val sun = c.getDrawable(R.drawable.ic_sun)!!.mutate()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(resolveSize((460 * d).toInt(), w), (58 * d).toInt())

    private fun x(min: Int): Float { val pad = 14 * d; return pad + (width - 2 * pad) * (min / 1440f) }

    override fun onDraw(cv: Canvas) {
        val h = height.toFloat()
        val ty = h * .66f; val th = 8 * d
        // cores do céu ao longo do dia
        val night = 0xFF1E2A4A.toInt(); val gold = 0xFFF0A35E.toInt(); val day = 0xFF6FB3F2.toInt()
        val stops = intArrayOf(night, night, gold, day, day, gold, night, night)
        val pos = floatArrayOf(0f, (dawn - 50) / 1440f, dawn / 1440f, (dawn + 70) / 1440f, (dusk - 70) / 1440f, dusk / 1440f, (dusk + 50) / 1440f, 1f)
            .map { it.coerceIn(0f, 1f) }.toFloatArray()
        for (i in 1 until pos.size) if (pos[i] < pos[i - 1]) pos[i] = pos[i - 1]
        p.shader = android.graphics.LinearGradient(x(0), 0f, x(1440), 0f, stops, pos, android.graphics.Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(x(0), ty - th / 2, x(1440), ty + th / 2), th / 2, th / 2, p)
        p.shader = null
        // marcas: nascer, meio-dia, pôr do sol
        val ic = (20 * d).toInt()
        fun icon(dr: android.graphics.drawable.Drawable, min: Int) {
            val cx = x(min).toInt(); val top = (ty - th / 2 - 6 * d - ic).toInt()
            dr.setTint(Ui.MUTED); dr.setBounds(cx - ic / 2, top, cx + ic / 2, top + ic); dr.draw(cv)
        }
        icon(sunrise, dawn); icon(sun, (dawn + dusk) / 2); icon(sunset, dusk)
        // indicador da hora
        val cx = x(minute); val r = 11 * d
        p.color = 0x33000000; cv.drawCircle(cx, ty + 1.2f * d, r, p)
        p.color = Color.WHITE; cv.drawCircle(cx, ty, r, p)
        p.color = Ui.BLUE; cv.drawCircle(cx, ty, r * .45f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            val pad = 14 * d
            val m = (((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * 1439).toInt()
            minute = m; onPick(m)
        }
        return true
    }
}

/** Coluna que rola (hora ou minuto), no estilo dos seletores da central: o valor do meio em destaque. */
class Wheel(c: Context, private val count: Int, start: Int, private val label: (Int) -> String) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val d = c.resources.displayMetrics.density
    private val row = 64 * d
    private var offset = start * row          // posição contínua (em pixels)
    private var lastY = 0f
    private var anim: ValueAnimator? = null
    private val vt = android.view.VelocityTracker.obtain()
    val value get() = (((Math.round(offset / row) % count) + count) % count)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((150 * d).toInt(), (row * 5).toInt())

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // faixa do valor escolhido
        p.color = Ui.CHIP
        cv.drawRoundRect(RectF(6 * d, h / 2 - row / 2, w - 6 * d, h / 2 + row / 2), 16 * d, 16 * d, p)
        val center = offset / row
        val first = Math.floor(center.toDouble()).toInt() - 3
        for (i in first..first + 6) {
            val dy = (i - center) * row
            val k = (1f - Math.abs(dy) / (row * 2.6f)).coerceIn(0f, 1f)
            if (k <= 0f) continue
            val v = ((i % count) + count) % count
            val sel = Math.abs(dy) < row / 2
            p.textSize = (if (sel) 38 else 28) * d
            p.typeface = if (sel) Ui.MEDIUM else Typeface.DEFAULT
            p.color = if (sel) Ui.TEXT else Ui.MUTED
            p.alpha = (255 * (if (sel) 1f else .25f + .6f * k)).toInt()
            cv.drawText(label(v), w / 2, h / 2 + dy - (p.descent() + p.ascent()) / 2, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        vt.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { anim?.cancel(); lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { offset -= e.y - lastY; lastY = e.y; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt.computeCurrentVelocity(1000)
                // embalo: continua um pouco na direção do dedo e para encaixado num valor
                val target = Math.round((offset - vt.yVelocity * .25f) / row) * row
                anim = ValueAnimator.ofFloat(offset, target).apply {
                    duration = 320; interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { offset = it.animatedValue as Float; invalidate() }
                    start()
                }
                vt.clear()
            }
        }
        return true
    }
}
