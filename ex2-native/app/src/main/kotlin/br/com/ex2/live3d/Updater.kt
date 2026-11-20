package br.com.ex2.live3d

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizações pelo GitHub (github.com/saullomiguel-lab/Ex2-Live-3D: um release por versão, com o APK).
 *  - Consulta o último release (API pública do GitHub, sem conta) e compara com a versão instalada.
 *  - Baixa o APK para a pasta do app e instala pelo próprio app (PackageInstaller): como o app é
 *    assinado com a chave de plataforma da central, ele tem INSTALL_PACKAGES e instala sozinho, sem
 *    abrir outra tela. Se o sistema pedir confirmação, abre a tela de instalação do Android.
 *  - "Buscar atualizações automaticamente": ao abrir o app (com internet), pergunta se quer baixar.
 */
object Updater {
    private const val API = "https://api.github.com/repos/saullomiguel-lab/Ex2-Live-3D/releases/latest"
    private const val ACTION = "br.com.ex2.live3d.INSTALL_RESULT"
    private val main = Handler(Looper.getMainLooper())

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

    /** estado para a aba Atualizações */
    var status = ""; private set
    var latest: Release? = null; private set
    var progress = -1f; private set          // 0..1 baixando; -1 = parado
    var busy = false; private set
    val listeners = LinkedHashSet<() -> Unit>()
    private fun changed() = main.post { for (l in listeners.toList()) try { l() } catch (_: Throwable) { } }
    private fun setStatus(s: String) { status = s; changed() }

    fun autoOn(ctx: Context) = Prefs.bool(ctx, Prefs.UPD_AUTO, true)

    /** "2.10.1" > "2.9.0" (números por partes) */
    fun newer(a: String, b: String): Boolean {
        val x = a.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    @Suppress("DEPRECATION")
    fun online(ctx: Context) = try {
        (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).activeNetworkInfo?.isConnected == true
    } catch (_: Exception) { false }

    /**
     * consulta o GitHub em segundo plano; [done] recebe a versão nova (null = já está na mais nova ou
     * deu erro: ver [status]). [quiet] = consulta automática (no máximo uma por hora).
     */
    fun check(ctx: Context, quiet: Boolean = false, done: ((Release?) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (busy) return
        if (quiet && System.currentTimeMillis() - Prefs.get(app).getLong(Prefs.UPD_AT, 0L) < 60 * 60_000L) {
            // consultou há pouco: usa o que achou da última vez
            val r = cached(app)?.takeIf { newer(it.version, BuildConfig.VERSION_NAME) }
            main.post { done?.invoke(r) }; return
        }
        if (!online(app)) { setStatus(L.t("Sem internet agora")); main.post { done?.invoke(null) }; return }
        busy = true; setStatus(L.t("Procurando atualização…"))
        Thread({
            var c: HttpURLConnection? = null
            var found: Release? = null
            try {
                c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "EX2Live3D-app")
                }
                if (c.responseCode != 200) throw RuntimeException("GitHub respondeu ${c.responseCode}")
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val r = parse(j) ?: throw RuntimeException("release sem APK")
                Prefs.get(app).edit().putLong(Prefs.UPD_AT, System.currentTimeMillis()).putString(Prefs.UPD_INFO, j.toString().take(20000)).apply()
                latest = r
                found = r.takeIf { newer(it.version, BuildConfig.VERSION_NAME) }
                status = if (found != null) L.f("Versão %s disponível", r.version) else L.f("Você já tem a versão mais nova (%s)", BuildConfig.VERSION_NAME)
                Diag.log("Atualização: GitHub tem ${r.version} (instalada ${BuildConfig.VERSION_NAME})")
            } catch (e: Exception) {
                status = L.t("Não deu para consultar o GitHub") + " (" + e.javaClass.simpleName + ")"
                Diag.log("Atualização: falhou a consulta: $e")
            } finally { c?.disconnect(); busy = false }
            changed()
            main.post { done?.invoke(found) }
        }, "ex2-update").start()
    }

    private fun parse(j: JSONObject): Release? {
        val tag = j.optString("tag_name").ifEmpty { return null }
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            // o APK do carro (o do emulador termina em -emu e não serve na central)
            if (name.endsWith(".apk", true) && !name.contains("-emu", true))
                return Release(tag.trimStart('v', 'V'), j.optString("body"), a.optString("browser_download_url"), a.optLong("size"), j.optString("html_url"))
        }
        return null
    }

    fun cached(ctx: Context): Release? = try { latest ?: parse(JSONObject(Prefs.str(ctx, Prefs.UPD_INFO, "").ifEmpty { return null })).also { latest = it } } catch (_: Exception) { null }

    /** baixa e instala (tudo em segundo plano; a aba mostra o andamento) */
    fun install(ctx: Context, r: Release) {
        val app = ctx.applicationContext
        if (busy) return
        busy = true; progress = 0f; shellTried = false; setStatus(L.f("Baixando a versão %s…", r.version))
        Thread({
            val file = File(app.cacheDir, "update.apk")
            var c: HttpURLConnection? = null
            try {
                // o GitHub redireciona o download para outro endereço
                var url = URL(r.apkUrl)
                for (hop in 0 until 5) {
                    c = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "EX2Live3D-app")
                    }
                    val code = c.responseCode
                    if (code in 300..399) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
                    if (code != 200) throw RuntimeException("download respondeu $code")
                    break
                }
                val total = c!!.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { inp ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int; var got = 0L; var lastPost = 0L
                        while (inp.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); got += n
                            if (total > 0 && System.currentTimeMillis() - lastPost > 300) { progress = got.toFloat() / total; lastPost = System.currentTimeMillis(); changed() }
                        }
                    }
                }
                progress = 1f
                Diag.log("Atualização: baixada ${r.version} (${file.length() / 1024} KB)")
                // confere o APK antes: mesmo pacote e versionCode maior (senão o Android recusa)
                @Suppress("DEPRECATION")
                val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
                if (info == null || info.packageName != app.packageName) throw RuntimeException("APK inválido")
                @Suppress("DEPRECATION")
                if (info.versionCode <= BuildConfig.VERSION_CODE) throw RuntimeException("versão ${info.versionCode} não é mais nova")
                setStatus(L.t("Instalando…"))
                lastFile = file
                commit(app, file)
            } catch (e: Exception) {
                busy = false; progress = -1f
                setStatus(L.t("Falha ao baixar") + " (" + e.javaClass.simpleName + ")")
                Diag.log("Atualização: falhou o download: $e")
            } finally { c?.disconnect() }
        }, "ex2-update-dl").start()
    }

    /** entrega o APK ao instalador do sistema; com a chave de plataforma instala sem perguntar */
    private fun commit(app: Context, file: File) {
        try {
            val pi = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(app.packageName) }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("ex2-live3d.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                app.registerReceiver(resultReceiver, IntentFilter(ACTION))
                val intent = PendingIntent.getBroadcast(app, id, Intent(ACTION).setPackage(app.packageName), PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(intent.intentSender)
            }
            Diag.log("Atualização: enviada ao instalador")
        } catch (e: Exception) {
            Diag.log("Atualização: instalador do sistema falhou: $e")
            shellInstall(app, file)
        }
    }

    /** teste no emulador: instala um APK já na pasta do app pelo mesmo caminho do GitHub (sem baixar) */
    fun installLocal(ctx: Context, path: String) {
        val app = ctx.applicationContext
        if (busy) return
        busy = true; shellTried = false
        Thread({
            val file = File(path)
            @Suppress("DEPRECATION") val info = app.packageManager.getPackageArchiveInfo(file.path, 0)
            @Suppress("DEPRECATION") Diag.log("Atualização (teste): ${file.path} → ${info?.packageName} v${info?.versionCode}")
            if (info == null || info.packageName != app.packageName) { fail("APK inválido"); return@Thread }
            setStatus(L.t("Instalando…")); lastFile = file; commit(app, file)
        }, "ex2-update-test").start()
    }

    private var lastFile: File? = null
    private var shellTried = false

    /**
     * Plano B (do guia de instalação silenciosa em centrais): o comando "pm" por sessão, com o APK
     * enviado pela entrada padrão (pm install <arquivo> falha no SELinux da central para arquivos de
     * outras pastas). O pm roda com a identidade do app, que tem INSTALL_PACKAGES pela chave de plataforma.
     */
    private fun shellInstall(app: Context, file: File) {
        if (shellTried) { fail(L.t("Não deu para instalar")); return }
        shellTried = true
        Thread({
            val script = "SZ=$(wc -c < \"${file.path}\"); SID=$(pm install-create -r -t 2>/dev/null | tr -dc '0-9'); " +
                "if [ -n \"\$SID\" ]; then cat \"${file.path}\" | pm install-write -S \"\$SZ\" \"\$SID\" base - >/dev/null 2>&1; " +
                "OUT=$(pm install-commit \"\$SID\" 2>&1); echo \"\$OUT\"; case \"\$OUT\" in *Success*) exit 0;; esac; pm install-abandon \"\$SID\"; fi; exit 1"
            val ok = try {
                val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                Diag.log("Atualização (pm): ${out.trim().take(300)}")
                p.waitFor() == 0 || "Success" in out
            } catch (e: Exception) { Diag.log("Atualização (pm): $e"); false }
            // com sucesso o sistema troca o app e reinicia o processo já na versão nova
            if (ok) setStatus(L.t("Atualizado! O app vai reiniciar.")) else fail(L.t("Não deu para instalar"))
        }, "ex2-update-pm").start()
    }

    private fun fail(msg: String) { busy = false; progress = -1f; setStatus(msg) }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (st) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // o sistema quer confirmação (sem a chave de plataforma): abre a tela de instalação
                    @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    try { c.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Diag.log("Atualização: sem tela de confirmação: $e") }
                    setStatus(L.t("Confirme a instalação na tela do Android"))
                }
                PackageInstaller.STATUS_SUCCESS -> { setStatus(L.t("Atualizado! O app vai reiniciar.")); Diag.log("Atualização: instalada") }
                else -> {
                    val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    Diag.log("Atualização: instalador recusou: $st $msg")
                    val f = lastFile
                    if (f != null && f.isFile && !shellTried) shellInstall(c.applicationContext, f)
                    else fail(L.t("Não deu para instalar") + " ($st $msg)")
                }
            }
            if (st != PackageInstaller.STATUS_PENDING_USER_ACTION) try { c.applicationContext.unregisterReceiver(this) } catch (_: Exception) { }
        }
    }
}
