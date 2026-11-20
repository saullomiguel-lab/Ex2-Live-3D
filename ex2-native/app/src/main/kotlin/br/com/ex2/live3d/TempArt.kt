package br.com.ex2.live3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

/**
 * Temperatura externa no canto de cima à direita, como o widget da tela inicial da central.
 * Medidas tiradas da tela do carro (1920×1080): algarismos com 80 px de altura e topo a 123 px;
 * o grau é um anel separado (Ø 28 px, traço fino) alinhado ao topo dos números, com a borda
 * direita a 64 px da lateral. O número cresce para a esquerda; o grau nunca muda de lugar
 * nem de tamanho. Sem leitura: "- -" (como a central faz).
 */
object TempArt {
    const val REF_W = 1920f
    const val REF_H = 1080f
    const val CAP = 80f            // altura dos algarismos
    const val TOP = 123f           // topo dos algarismos (tela inteira)
    const val RIGHT = 64f          // borda direita do grau até a lateral da tela

    // cores como no widget da central: grafite sobre fundo claro; branco levemente translúcido sobre escuro
    const val DARK = 0xFF16181C.toInt()
    const val LIGHT = 0xF2FFFFFF.toInt()

    fun label(c: Float?): String? = c?.let { "${it.roundToInt()}" }

    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    /** altura dos algarismos ÷ tamanho da fonte (medida na própria fonte) */
    private var capRatio = .711f
    private val bounds = android.graphics.Rect()

    /** fonte Falena SemiBold (a mais parecida com a do widget da central), carregada uma vez */
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try { font = Typeface.createFromAsset(ctx.assets, "fonts/falena-semi-bold.ttf"); digits.typeface = font } catch (_: Throwable) { }
        digits.textSize = 100f
        val b = android.graphics.Rect()
        digits.getTextBounds("0123456789", 0, 10, b)
        if (b.height() > 10) capRatio = b.height() / 100f
    }
    private var loaded = false
    /** a mesma fonte para a saudação */
    var font: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        private set

    /** número + o grau DA PRÓPRIA FONTE (anel grosso, do mesmo peso do número, como no carro) */
    private fun full(text: String?) = (text ?: "--") + "°"

    /** largura do desenho (k = escala da tela) */
    fun width(text: String?, k: Float): Float {
        digits.textSize = CAP * k / capRatio; digits.letterSpacing = if (text == null) .2f else 0f
        val s = full(text)
        digits.getTextBounds(s, 0, s.length, bounds)
        return bounds.width().toFloat()
    }

    /**
     * Desenha com a borda direita do grau em [right] e o topo dos números em [top] (o número cresce
     * para a esquerda; o grau não sai do lugar). [shadow] = sombra difusa (só para o texto branco).
     */
    /** o grau tem uma transparência sutil em relação ao número (como no widget da central) */
    const val DEGREE_ALPHA = .62f

    fun draw(cv: Canvas, text: String?, color: Int, k: Float, right: Float, top: Float, shadow: Boolean, xfer: PorterDuff.Mode? = null, shadowColor: Int = 0x48000000,
             degreeColor: Int = (((color ushr 24) * DEGREE_ALPHA).toInt() shl 24) or (color and 0xFFFFFF)) {
        val cap = CAP * k
        digits.textSize = cap / capRatio
        digits.letterSpacing = if (text == null) .2f else 0f
        digits.color = color
        digits.xfermode = xfer?.let { PorterDuffXfermode(it) }
        if (shadow) digits.setShadowLayer(cap * .07f, 0f, cap * .02f, shadowColor) else digits.clearShadowLayer()
        val s = full(text)
        digits.getTextBounds(s, 0, s.length, bounds)
        val x0 = right - bounds.right
        val num = s.dropLast(1)
        cv.drawText(num, x0, top + cap, digits)
        // grau: mesma fonte e mesmo lugar, um pouco transparente
        digits.color = degreeColor
        cv.drawText("°", x0 + digits.measureText(num), top + cap, digits)
        digits.color = color
    }

    /** máscara para o papel de parede: vermelho = desenho, verde = sombra (a cor vem do material) */
    fun drawMask(cv: Canvas, text: String?, k: Float, right: Float, top: Float, city: String? = null) {
        cv.drawColor(0xFF000000.toInt())
        // sombra (verde): o desenho com sombra difusa, tudo em verde
        draw(cv, text, 0xFF00FF00.toInt(), k, right, top, true, null, 0xFF00FF00.toInt())
        // desenho (vermelho) somado por cima: os dois canais ficam separados
        draw(cv, text, 0xFFFF0000.toInt(), k, right, top, false, PorterDuff.Mode.ADD,
            degreeColor = 0xFF000000.toInt() or ((255 * DEGREE_ALPHA).toInt() shl 16))
        if (city != null) {
            drawCity(cv, city, 0xFF00FF00.toInt(), k, right, top, true, null, 0xFF00FF00.toInt())
            drawCity(cv, city, 0xFF000000.toInt() or ((255 * CITY_ALPHA).toInt() shl 16), k, right, top, false, PorterDuff.Mode.ADD)
        }
    }

    /* ---------- nome da cidade embaixo da temperatura (alinhado à direita, como o número) ---------- */

    const val CITY_SIZE = 30f       // px, tela 1920×1080
    const val CITY_GAP = 20f        // da base dos números ao topo do nome
    const val CITY_ALPHA = .86f
    private val cityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    /** largura do nome (para o tamanho da imagem do papel de parede) */
    fun cityWidth(city: String, k: Float): Float { cityPaint.typeface = font; cityPaint.textSize = CITY_SIZE * k; return cityPaint.measureText(city) }
    /** altura extra que o nome ocupa abaixo dos números */
    fun cityHeight(k: Float) = (CITY_GAP + CITY_SIZE * 1.15f) * k

    fun drawCity(cv: Canvas, city: String, color: Int, k: Float, right: Float, top: Float, shadow: Boolean, xfer: PorterDuff.Mode? = null, shadowColor: Int = 0x48000000) {
        cityPaint.typeface = font; cityPaint.textSize = CITY_SIZE * k; cityPaint.color = color
        cityPaint.xfermode = xfer?.let { PorterDuffXfermode(it) }
        if (shadow) cityPaint.setShadowLayer(CITY_SIZE * k * .1f, 0f, CITY_SIZE * k * .03f, shadowColor) else cityPaint.clearShadowLayer()
        val asc = -cityPaint.fontMetrics.ascent * .78f
        cv.drawText(city, right - 4f * k, top + CAP * k + CITY_GAP * k + asc, cityPaint)
    }
}

/**
 * Saudação (duas linhas) no canto de cima à esquerda, espelhando a temperatura: mesma fonte, mesma
 * altura de topo, margem de 64 px. Letras do tamanho do texto do clima da central (legíveis).
 */
object GreetArt {
    const val LEFT = 64f
    const val SIZE = 46f            // tamanho da fonte (px, tela 1920×1080)
    const val LINE = 1.24f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    /** 3ª linha (a data embaixo de uma saudação fixa): menor */
    const val SMALL = .66f

    private fun lines(text: String) = text.split("\n").take(3)

    /**
     * Para cada linha: distância da linha de base dela até o topo do desenho, e o tamanho da fonte.
     * Linhas 1 e 2 como sempre; a 3ª (menor) logo abaixo da 2ª.
     */
    private fun layout(n: Int, k: Float): List<Pair<Float, Float>> {
        p.typeface = TempArt.font; p.textSize = SIZE * k
        val asc0 = -p.fontMetrics.ascent * .78f
        val out = ArrayList<Pair<Float, Float>>()
        for (i in 0 until n) {
            val size = SIZE * k * (if (i >= 2) SMALL else 1f)
            val base = when (i) {
                0 -> asc0
                1 -> asc0 + SIZE * k * LINE
                else -> asc0 + SIZE * k * LINE + SIZE * k * .34f + size * 1.02f
            }
            out.add(base to size)
        }
        return out
    }

    /** tamanho do desenho (largura, altura) para a escala k */
    fun size(ctx: Context, text: String, k: Float): Pair<Float, Float> {
        TempArt.load(ctx)
        val ls = lines(text); val lay = layout(ls.size, k)
        p.typeface = TempArt.font
        val w = ls.indices.maxOf { i -> p.textSize = lay[i].second; p.measureText(ls[i]) }
        val last = lay.last()
        val h = last.first + last.second * .30f
        return w to h
    }

    fun draw(cv: Canvas, text: String, color: Int, k: Float, left: Float, top: Float, shadow: Boolean, xfer: PorterDuff.Mode? = null, shadowColor: Int = 0x48000000,
             smallColor: Int = color) {
        val ls = lines(text); val lay = layout(ls.size, k)
        p.typeface = TempArt.font
        p.xfermode = xfer?.let { PorterDuffXfermode(it) }
        ls.forEachIndexed { i, l ->
            val (base, size) = lay[i]
            p.textSize = size; p.color = if (i >= 2) smallColor else color
            if (shadow) p.setShadowLayer(size * .08f, 0f, size * .02f, shadowColor) else p.clearShadowLayer()
            cv.drawText(l, left, top + base, p)
        }
    }

    /** máscara para o papel de parede (vermelho = letras, verde = sombra); a data menor sai um pouco mais suave */
    fun drawMask(cv: Canvas, text: String, k: Float, left: Float, top: Float) {
        cv.drawColor(0xFF000000.toInt())
        draw(cv, text, 0xFF00FF00.toInt(), k, left, top, true, null, 0xFF00FF00.toInt())
        draw(cv, text, 0xFFFF0000.toInt(), k, left, top, false, PorterDuff.Mode.ADD, smallColor = 0xFFD0_0000.toInt())
    }
}

/** O mesmo widget na tela do app (por cima do 3D, sem receber toques). */
class TempView(c: Context) : View(c) {
    var text: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    var light = true      // true = texto branco (fundo escuro)
        set(v) { if (field != v) { field = v; invalidate() } }
    /** cidade embaixo do número (null = não mostra) */
    var city: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    /** topo dos números dentro da tela do app (a barra de status do carro fica acima) */
    var topRef = TempArt.TOP - 56f

    init { isClickable = false; isFocusable = false; TempArt.load(c) }

    /** tela espelhada (volante à direita / árabe): a temperatura vai para o canto de cima à esquerda */
    var mirror = false
        set(v) { if (field != v) { field = v; invalidate() } }

    override fun onDraw(cv: Canvas) {
        val k = width / TempArt.REF_W
        val color = if (light) TempArt.LIGHT else TempArt.DARK
        val right = if (mirror) TempArt.RIGHT * k + maxOf(TempArt.width(text, k), city?.let { TempArt.cityWidth(it, k) } ?: 0f) else width - TempArt.RIGHT * k
        TempArt.draw(cv, text, color, k, right, topRef * k, light)
        city?.let { TempArt.drawCity(cv, it, (((color ushr 24) * TempArt.CITY_ALPHA).toInt() shl 24) or (color and 0xFFFFFF), k, right, topRef * k, light) }
    }
}
