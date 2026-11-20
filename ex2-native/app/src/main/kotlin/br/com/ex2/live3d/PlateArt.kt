package br.com.ex2.live3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Textura da placa Mercosul com o texto do usuário na fonte FE (a mesma do site).
 * Layout do atlas 1024² (igual ao exportado do site): os caracteres ficam deitados na faixa
 * x 28..198 (base → topo do caractere), lidos de baixo para cima e espelhados; o 1º caractere
 * centrado em y = 880, passo 122,4 px, condensados a 72 %.
 */
object PlateArt {
    private const val CAP_BASE = 28f
    private const val CAP_TOP = 198f
    private const val FIRST = 880f
    private const val PITCH = 122.4f
    private const val SQUEEZE = 0.72f

    fun render(ctx: Context, code: String): Bitmap {
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = Startup.take("plate-base.jpg") ?: ctx.assets.open("plate-base.jpg").use { BitmapFactory.decodeStream(it, null, opts)!! }
        if (code.isEmpty()) return bmp
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.createFromAsset(ctx.assets, "FE-FONT.TTF")
            color = Color.rgb(11, 11, 11)
            textSize = 100f
        }
        // calibra pelo "E": tinta inteira (corpo + pé) ocupa a faixa de 170 px
        val r = Rect()
        p.getTextBounds("E", 0, 1, r)
        p.textSize = 100f * (CAP_TOP - CAP_BASE) / r.height()
        p.getTextBounds("E", 0, 1, r)
        val baseX = CAP_BASE + r.bottom          // a linha de base fica acima do pé da tinta
        // (gx, gy) do texto → (x, y) do atlas: para cima vira +x, avanço vira -y, com espelho
        val m = Matrix().apply { setValues(floatArrayOf(0f, -1f, 0f, -SQUEEZE, 0f, 0f, 0f, 0f, 1f)) }
        // placa livre: com menos de 7 caracteres o texto fica centralizado nas 7 casas
        val shift = (7 - code.length.coerceAtMost(7)) / 2f
        code.forEachIndexed { i, ch ->
            val s = ch.toString()
            val w = p.measureText(s)
            c.save()
            c.clipRect(CAP_BASE, 0f, 1024f, 1024f)
            c.translate(baseX, FIRST - PITCH * (i + shift))
            c.concat(m)
            c.drawText(s, -w / 2f, 0f, p)
            c.restore()
        }
        return bmp
    }
}
