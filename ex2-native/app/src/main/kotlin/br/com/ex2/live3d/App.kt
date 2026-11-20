package br.com.ex2.live3d

import android.app.Application
import android.content.Context
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        CrashGuard.install(this)
        Diag.log("App ${BuildConfig.VERSION_NAME} iniciado")
        L.init(this)
    }

    /** o idioma do carro mudou (o papel de parede continua rodando: refaz os textos) */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        L.init(this)
    }
}

/**
 * Se a abertura do 3D não chegar ao primeiro quadro (o app fechou, travou ou o sistema matou),
 * na próxima abertura junta o registro do app + o registro do sistema (logcat) num relatório
 * que aparece na tela e fica em Android/data/br.com.ex2.live3d/files/relatorio-erro.txt.
 */
object CrashGuard {
    private const val STARTING = "starting3d"

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(Diag.dir(c), "erro.txt").writeText("Thread ${t.name}\n" + android.util.Log.getStackTraceString(e)) } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    /** aberturas seguidas que falharam (o monitor tenta de novo sozinho antes de mostrar o relatório) */
    const val STREAK = "crashStreak"

    fun begin(c: Context) { Prefs.get(c).edit().putBoolean(STARTING, true).commit() }
    fun ok(c: Context) {
        if (Prefs.bool(c, STARTING, false)) {
            Prefs.get(c).edit().putBoolean(STARTING, false).putInt(STREAK, 0).commit()
            Diag.log("Primeiro quadro desenhado")
        }
    }

    /** o relatório da falha anterior vai para o Diagnóstico (não bloqueia a abertura) */
    fun keep(c: Context) {
        val f = reportFile(c)
        if (f.isFile) { f.copyTo(File(Diag.dir(c), "relatorio-erro-anterior.txt"), overwrite = true); f.delete() }
    }

    private fun reportFile(c: Context) = File(Diag.dir(c), "relatorio-erro.txt")

    /** chamado em segundo plano na abertura: monta o relatório se a vez anterior falhou */
    fun collectIfNeeded(c: Context) {
        if (!Prefs.bool(c, STARTING, false)) return
        Prefs.get(c).edit().putBoolean(STARTING, false).commit()
        val dir = Diag.dir(c)
        val b = StringBuilder()
        b.append("EX2 Live 3D ${BuildConfig.VERSION_NAME} — a última abertura não chegou a desenhar o carro.\n\n")
        File(dir, "erro.txt").takeIf { it.isFile }?.let { b.append("== ERRO ==\n").append(it.readText().take(4000)).append("\n\n"); it.delete() }
        File(dir, "diag-anterior.txt").takeIf { it.isFile }?.let { b.append("== ETAPAS ==\n").append(it.readLines().takeLast(40).joinToString("\n")).append("\n\n") }
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "4000"))
            val keep = Regex("(?i)ex2|live3d|filament|gltfio|filamat|AndroidRuntime|FATAL|DEBUG|libc|ANR|am_crash|am_anr|abort|Fatal signal")
            val lines = p.inputStream.bufferedReader().readLines().filter { keep.containsMatchIn(it) }
            p.destroy()
            b.append("== SISTEMA ==\n").append(lines.takeLast(120).joinToString("\n"))
        } catch (t: Throwable) { b.append("logcat indisponível: $t") }
        reportFile(c).writeText(b.toString())
    }

    fun report(c: Context): String? = reportFile(c).takeIf { it.isFile }?.readText()
    fun clear(c: Context) { reportFile(c).delete() }
    fun path(c: Context) = reportFile(c).absolutePath
}
