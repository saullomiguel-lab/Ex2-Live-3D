package br.com.ex2.live3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Trabalho pesado da abertura feito FORA da thread da tela: compilar os materiais e decodificar
 * as fotos (céu, interior, placa). Se a thread da tela trava mais de ~5 s, a central fecha o app.
 */
object Startup {
    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<() -> Unit>()
    private var running = false
    @Volatile var ready = false
        private set
    @Volatile var error: Throwable? = null
        private set

    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    /** foto já decodificada em segundo plano (some do cache ao ser usada) */
    fun take(name: String): Bitmap? = bitmaps.remove(name)

    fun prepare(ctx: Context, done: () -> Unit) {
        if (ready) { done(); return }
        waiting.add(done)
        if (running) return
        running = true
        val app = ctx.applicationContext
        Thread({
            val t0 = System.currentTimeMillis()
            try { CrashGuard.collectIfNeeded(app) } catch (t: Throwable) { Diag.log("Relatório falhou: $t") }
            try {
                Diag.log("Abertura: preparando materiais")
                Materials.prepare(app)
                Diag.log("Abertura: materiais ok (${System.currentTimeMillis() - t0} ms)")
                Scenery.prepareNoise()
                Diag.log("Abertura: textura do cenário ok (${System.currentTimeMillis() - t0} ms)")
                val names = ArrayList<String>()
                names.add("plate-base.jpg")   // (o céu agora é 3D: as fotos só servem aos reflexos, em tamanho reduzido)
                for (n in names) {
                    val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = n == "plate-base.jpg" }
                    app.assets.open(n).use { s -> BitmapFactory.decodeStream(s, null, o)?.let { bitmaps[n] = it } }
                }
                Diag.log("Abertura: fotos ok (${System.currentTimeMillis() - t0} ms) · ${Diag.mem()}")
            } catch (t: Throwable) {
                error = t
                Diag.log("Abertura falhou: ${Log.trace(t)}")
            }
            main.post {
                ready = true; running = false
                val list = ArrayList(waiting); waiting.clear()
                list.forEach { it() }
            }
        }, "ex2-startup").start()
    }
}

/** utilitário de texto para erros */
object Log {
    fun trace(t: Throwable): String = android.util.Log.getStackTraceString(t).lines().take(25).joinToString("\n")
}
