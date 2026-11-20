package br.com.ex2.live3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Pontos de toque presos ao carro (como na tela original do EX2): bolinha branca com anel
 * translúcido sobre cada parte que abre. Acompanham o giro do carro e somem quando a parte
 * fica do lado de trás. Tocar abre/fecha a peça. (Posições em SpotSet, compartilhadas com o papel de parede.)
 */
class Hotspots(c: Context, private val isOpen: (String) -> Boolean, private val toggle: (String) -> Unit) : View(c) {

    private val set = SpotSet()
    private val d = c.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    /** chamado a cada quadro desenhado: matrizes da câmera (coluna principal) e posição do olho */
    fun update(view: DoubleArray, proj: DoubleArray, eye: DoubleArray) {
        if (width <= 0) return
        set.update(view, proj, eye, width.toFloat(), height.toFloat())
        invalidate()
    }

    override fun onDraw(cv: Canvas) {
        for (s in set.spots) {
            if (s.alpha <= .01f) continue
            val open = isOpen(s.name)
            val a = (s.alpha * 255).toInt()
            // anel translúcido
            ring.style = Paint.Style.FILL
            ring.color = if (open) 0x3D8BFF else 0xFFFFFF
            ring.alpha = (a * (if (open) .45f else .28f)).toInt()
            cv.drawCircle(s.x, s.y, 17 * d, ring)
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 1.5f * d
            ring.color = 0xFFFFFF
            ring.alpha = (a * .75f).toInt()
            cv.drawCircle(s.x, s.y, 17 * d, ring)
            // bolinha central
            dot.color = 0xFFFFFF
            dot.alpha = a
            cv.drawCircle(s.x, s.y, (if (open) 6.5f else 5f) * d, dot)
        }
    }

    private var downSpot: SpotSet.Spot? = null

    /** só "pega" o toque quando ele cai num ponto; o resto passa para girar o carro */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downSpot = set.hit(e.x, e.y, 30 * d)
                return downSpot != null
            }
            MotionEvent.ACTION_UP -> {
                downSpot?.let { toggle(it.name); invalidate() }
                downSpot = null
            }
            MotionEvent.ACTION_CANCEL -> downSpot = null
        }
        return true
    }
}
