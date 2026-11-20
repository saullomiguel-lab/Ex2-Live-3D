package br.com.ex2.live3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * "Está chovendo aí?": a previsão do tempo muitas vezes diz que está chovendo quando não está. Com o
 * clima automático, quando a previsão marca chuva (ou tempestade) o céu fica só nublado e o app
 * pergunta. "Chover agora" = chove até a previsão parar de marcar chuva; "Adiar chuva" = 1 hora sem
 * chuva, depois pergunta de novo se a previsão ainda disser chuva. Clima escolhido à mão não pergunta.
 */
object RainGate {
    const val SNOOZE_MS = 60 * 60_000L

    private fun wet(id: String) = id == "chuva" || id == "tempestade"

    /** clima da previsão → clima da cena (pode pedir a pergunta) */
    fun filter(ctx: Context, forecast: String): String {
        val sp = Prefs.get(ctx)
        if (!wet(forecast)) {
            // parou de chover na previsão: a próxima chuva pergunta de novo
            if (sp.getBoolean(Prefs.RAIN_OK, false) || sp.getBoolean(Prefs.RAIN_ASK, false))
                sp.edit().putBoolean(Prefs.RAIN_OK, false).putBoolean(Prefs.RAIN_ASK, false).apply()
            return forecast
        }
        if (sp.getBoolean(Prefs.RAIN_OK, false)) return forecast
        if (System.currentTimeMillis() < sp.getLong(Prefs.RAIN_SNOOZE, 0L)) return "nublado"
        if (!sp.getBoolean(Prefs.RAIN_ASK, false)) {
            sp.edit().putBoolean(Prefs.RAIN_ASK, true).apply()
            Diag.log("Clima: a previsão diz chuva → perguntando \"Está chovendo aí?\"")
        }
        return "nublado"
    }

    fun pending(ctx: Context) = Prefs.bool(ctx, Prefs.RAIN_ASK, false) && Prefs.autoWeather(ctx)

    /**
     * botão "Suspender chuva" (só aparece chovendo): com o clima automático, sem chuva por 1 hora
     * (depois pergunta de novo se a previsão ainda disser chuva); com o clima escolhido à mão, vira nublado
     */
    fun suspend(ctx: Context) {
        val sp = Prefs.get(ctx)
        if (Prefs.autoWeather(ctx)) sp.edit().putBoolean(Prefs.RAIN_OK, false).putBoolean(Prefs.RAIN_ASK, false)
            .putLong(Prefs.RAIN_SNOOZE, System.currentTimeMillis() + SNOOZE_MS).apply()
        else if (wet(Prefs.str(ctx, Prefs.MANUAL_WEATHER, "sol"))) sp.edit().putString(Prefs.MANUAL_WEATHER, "nublado").apply()
        Diag.log("Clima: chuva suspensa")
    }

    fun answer(ctx: Context, rain: Boolean) {
        val ed = Prefs.get(ctx).edit().putBoolean(Prefs.RAIN_ASK, false)
        if (rain) ed.putBoolean(Prefs.RAIN_OK, true) else ed.putLong(Prefs.RAIN_SNOOZE, System.currentTimeMillis() + SNOOZE_MS)
        ed.apply()
        Diag.log("Clima: " + if (rain) "chover agora" else "chuva adiada por 1 h")
    }
}

/** Cartão da pergunta no papel de parede (mesmo desenho em tela de 1920×1080; k = escala). */
object RainAskArt {
    const val W = 600f
    const val H = 236f
    class Hits { val rain = RectF(); val later = RectF() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(cv: Canvas, k: Float, dark: Boolean, font: android.graphics.Typeface?, hits: Hits) {
        val panel = if (dark) 0xF01D222A.toInt() else 0xF2F3F5F9.toInt()
        val text = if (dark) 0xFFEEF1F5.toInt() else 0xFF1B1E24.toInt()
        val muted = if (dark) 0xFF98A1AD.toInt() else 0xFF6E7681.toInt()
        val chip = if (dark) 0x1FFFFFFF else 0xFFE4E8EF.toInt()
        p.style = Paint.Style.FILL; p.color = panel
        cv.drawRoundRect(RectF(0f, 0f, W * k, H * k), 28 * k, 28 * k, p)
        fun txt(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            p.color = color; p.textSize = size * k; p.textAlign = Paint.Align.CENTER
            p.typeface = if (bold) android.graphics.Typeface.create(font, android.graphics.Typeface.BOLD) else font
            cv.drawText(s, x * k, y * k, p)
        }
        // nuvem com pingos
        p.color = Ui.BLUE
        cv.drawCircle(W / 2 * k - 14 * k, 44 * k, 13 * k, p); cv.drawCircle(W / 2 * k + 6 * k, 38 * k, 17 * k, p)
        cv.drawRoundRect(RectF((W / 2 - 30) * k, 40 * k, (W / 2 + 30) * k, 58 * k), 9 * k, 9 * k, p)
        p.strokeWidth = 4 * k; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (dx in intArrayOf(-16, 0, 16)) cv.drawLine((W / 2 + dx) * k, 66 * k, (W / 2 + dx - 5) * k, 78 * k, p)
        p.style = Paint.Style.FILL
        txt(L.t("Está chovendo aí?"), W / 2, 118f, 30f, text, bold = true)
        txt(L.t("A previsão do tempo diz que está chovendo agora"), W / 2, 150f, 19f, muted)
        val bw = 262f; val bh = 56f; val gap = 20f; val y = 166f
        val l = RectF((W / 2 - gap / 2 - bw) * k, y * k, (W / 2 - gap / 2) * k, (y + bh) * k)
        val r = RectF((W / 2 + gap / 2) * k, y * k, (W / 2 + gap / 2 + bw) * k, (y + bh) * k)
        p.color = chip; cv.drawRoundRect(l, 16 * k, 16 * k, p)
        p.color = Ui.BLUE; cv.drawRoundRect(r, 16 * k, 16 * k, p)
        txt(L.t("Adiar chuva"), W / 2 - gap / 2 - bw / 2, y + 36, 21f, text, bold = true)
        txt(L.t("Chover agora"), W / 2 + gap / 2 + bw / 2, y + 36, 21f, 0xFFFFFFFF.toInt(), bold = true)
        hits.later.set(l); hits.rain.set(r)
        p.textAlign = Paint.Align.LEFT
    }
}
