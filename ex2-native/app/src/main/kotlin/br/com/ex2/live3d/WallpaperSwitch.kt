package br.com.ex2.live3d

import android.app.WallpaperManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Troca o papel de parede sem o seletor do sistema: setWallpaperComponent é @hide e exige
 * SET_WALLPAPER_COMPONENT, que a central concede a apps assinados com a chave de plataforma.
 */
object WallpaperSwitch {
    fun ours(c: Context) = ComponentName(c, Live3DService::class.java)
    fun current(c: Context): String = WallpaperManager.getInstance(c).wallpaperInfo?.component?.flattenToString() ?: ""
    fun isActive(c: Context) = ours(c).flattenToString() == current(c)
    fun hasPermission(c: Context) = c.checkSelfPermission("android.permission.SET_WALLPAPER_COMPONENT") == PackageManager.PERMISSION_GRANTED

    private fun set(c: Context, cn: ComponentName) {
        val r = WallpaperManager::class.java.getMethod("setWallpaperComponent", ComponentName::class.java).invoke(WallpaperManager.getInstance(c), cn)
        if (r == false) throw IllegalStateException("o sistema recusou a troca")
    }

    /** @return mensagem; null = falta permissão (quem chama abre o seletor do sistema) */
    fun enable(c: Context): String? {
        val mine = ours(c).flattenToString()
        val cur = current(c)
        val ed = Prefs.get(c).edit()
        if (mine != cur) ed.putString(Prefs.ORIGINAL, cur)
        ed.putBoolean(Prefs.ENABLED, true).apply()
        if (!hasPermission(c)) return null
        return try {
            if (mine != cur) set(c, ours(c))
            Diag.log("Papel de parede ativado (antes: ${cur.ifEmpty { "estático" }})")
            if (isActive(c)) "Papel de parede 3D ativado." else "O sistema não confirmou a troca."
        } catch (e: Exception) {
            Diag.log("Ativação falhou: $e"); "Não foi possível ativar: ${e.javaClass.simpleName}"
        }
    }

    fun restore(c: Context): String {
        Prefs.get(c).edit().putBoolean(Prefs.ENABLED, false).apply()
        RecoveryJob.cancel(c)
        val orig = Prefs.str(c, Prefs.ORIGINAL, "")
        return try {
            val cn = ComponentName.unflattenFromString(orig)
            if (cn != null && hasPermission(c)) set(c, cn) else WallpaperManager.getInstance(c).clear()
            Diag.log("Papel de parede original restaurado: ${orig.ifEmpty { "padrão do sistema" }}")
            "Papel de parede original restaurado."
        } catch (e: Exception) {
            Diag.log("Restauração falhou: $e"); "Não foi possível restaurar: ${e.javaClass.simpleName}"
        }
    }

    fun pickerIntent(c: Context) = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ours(c))

    /** reaplica depois do boot ou quando a central voltou sozinha ao anterior; respeita escolha do usuário */
    fun ensure(c: Context): Boolean {
        if (!Prefs.bool(c, Prefs.ENABLED, false) || !hasPermission(c)) return true
        val cur = current(c)
        if (ours(c).flattenToString() == cur) return true
        if (cur.isNotEmpty() && cur != Prefs.str(c, Prefs.ORIGINAL, "")) {
            Diag.log("Recuperação: usuário escolheu $cur, não reaplica"); return true
        }
        try { set(c, ours(c)); Diag.log("Recuperação: papel de parede 3D reaplicado") } catch (e: Exception) { Diag.log("Recuperação falhou: $e") }
        return isActive(c)
    }
}

/** Depois do boot a central pode voltar ao papel de parede de fábrica: tenta reaplicar algumas vezes. */
class RecoveryJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val n = Prefs.int(this, Prefs.ATTEMPTS, 0) + 1
        Prefs.get(this).edit().putInt(Prefs.ATTEMPTS, n).apply()
        val ok = WallpaperSwitch.ensure(this)
        Diag.log("Recuperação tentativa $n: ${if (ok) "ok" else "ainda não"}")
        if (!ok && n < 8) schedule(this, 10000, false)
        return false
    }

    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val JOB_ID = 3401
        fun schedule(c: Context, delayMs: Long, reset: Boolean) {
            if (reset) Prefs.get(c).edit().putInt(Prefs.ATTEMPTS, 0).apply()
            (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(c, RecoveryJob::class.java))
                    .setMinimumLatency(delayMs).setOverrideDeadline(delayMs + 5000).build()
            )
        }
        fun cancel(c: Context) { (c.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID) }
    }
}

/** BOOT_COMPLETED e atualização do app: agenda a reaplicação do papel de parede, se ativado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // atualização instalada: já deixa os efeitos visuais prontos para a próxima abertura
            val pending = goAsync()
            Thread({ try { Materials.prepare(c.applicationContext) } catch (t: Throwable) { Diag.log("Pré-compilação falhou: $t") } finally { pending.finish() } }, "ex2-prewarm").start()
        }
        if (!Prefs.bool(c, Prefs.ENABLED, false)) return
        Diag.log("Boot/atualização: ${intent.action}")
        RecoveryJob.schedule(c, 4000, true)
    }
}
