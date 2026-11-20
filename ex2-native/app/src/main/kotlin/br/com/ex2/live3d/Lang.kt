package br.com.ex2.live3d

import android.content.Context
import android.content.res.Resources
import org.json.JSONObject
import java.util.Locale

/**
 * Idioma do app: segue o idioma do carro. A central do EX2 (GeelyAutoSettings, dump do sistema 11/2026)
 * tem 10 idiomas: chinês, inglês, espanhol, francês, português, russo, árabe, tailandês, vietnamita e
 * indonésio. O texto em português é a "chave": [t] devolve a tradução da tabela assets/i18n/<idioma>.json
 * (sem tradução = o próprio português). Ajustes → Sobre permite fixar um idioma (padrão: o do carro).
 */
object L {
    val SUPPORTED = listOf("pt", "en", "es", "fr", "ru", "ar", "th", "vi", "id", "zh")
    /** nome de cada idioma nele mesmo (como na lista da central) */
    val NAMES = mapOf("pt" to "Português", "en" to "English", "es" to "Español", "fr" to "Français", "ru" to "Русский",
        "ar" to "العربية", "th" to "ภาษาไทย", "vi" to "Tiếng Việt", "id" to "Bahasa Indonesia", "zh" to "中文")

    var code = "pt"; private set
    var locale: Locale = Locale("pt", "BR"); private set
    /** idioma e país do sistema do carro (o país ajuda a saber o lado do volante e a moeda) */
    var system: Locale = Locale("pt", "BR"); private set
    private var table: Map<String, String> = emptyMap()
    val rtl get() = code == "ar"
    val listeners = LinkedHashSet<() -> Unit>()

    fun init(ctx: Context) {
        system = try { Resources.getSystem().configuration.locales[0] } catch (_: Throwable) { Locale.getDefault() }
        val forced = Prefs.str(ctx, Prefs.LANG, "auto")
        var c = if (forced != "auto" && forced in SUPPORTED) forced else system.language.lowercase()
        if (c == "in") c = "id"                 // Android antigo chama o indonésio de "in"
        if (c !in SUPPORTED) c = "en"           // idioma que a central não tem: inglês
        if (c == code && table.isNotEmpty() == (c != "pt")) return
        code = c
        locale = if (c == "pt") Locale("pt", "BR") else if (c == system.language || (c == "id" && system.language == "in")) system else Locale(c)
        table = if (c == "pt") emptyMap() else load(ctx, c)
        Diag.log("Idioma: $c (sistema ${system.toLanguageTag()}, ${table.size} textos)")
        for (l in listeners.toList()) try { l() } catch (_: Throwable) { }
    }

    private fun load(ctx: Context, c: String): Map<String, String> = try {
        val j = JSONObject(ctx.assets.open("i18n/$c.json").bufferedReader(Charsets.UTF_8).readText())
        val m = HashMap<String, String>(j.length() * 2)
        for (k in j.keys()) m[k] = j.getString(k)
        m
    } catch (t: Throwable) { Diag.log("Idioma $c: sem tabela ($t)"); emptyMap() }

    /** tradução de um texto em português (linhas e partes "a · b" são traduzidas uma a uma) */
    fun t(s: String): String {
        if (table.isEmpty() || s.isEmpty()) return s
        table[s]?.let { return it }
        if (s.indexOf('\n') < 0 && s.indexOf(" · ") < 0) return s
        return s.split("\n").joinToString("\n") { line -> table[line] ?: line.split(" · ").joinToString(" · ") { table[it] ?: it } }
    }

    /** texto com valores: o modelo em português (%s, %d, %.1f…) é traduzido e preenchido */
    fun f(pattern: String, vararg a: Any?): String = try { String.format(locale, t(pattern), *a) } catch (_: Throwable) { String.format(locale, pattern, *a) }

    /** número com [d] casas, com a vírgula/ponto do idioma */
    fun num(v: Float, d: Int = 1): String = String.format(locale, "%.${d}f", v)

    /** símbolo da moeda: real no português; nos outros, a do país do carro (sem país: nenhum) */
    fun currency(): String {
        if (code == "pt") return "R$"
        return try { if (system.country.isNullOrEmpty()) "" else java.util.Currency.getInstance(system).getSymbol(locale) } catch (_: Throwable) { "" }
    }

    fun money(v: Float, d: Int = 2): String = currency().let { if (it.isEmpty()) num(v, d) else "$it ${num(v, d)}" }

    /** dias da semana curtos (domingo primeiro) e meses curtos no idioma */
    fun weekdays(): List<String> {
        val s = java.text.DateFormatSymbols(locale).shortWeekdays   // índice 1 = domingo
        return (1..7).map { if (code == "pt") listOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")[it - 1] else s[it].trimEnd('.').replaceFirstChar { c -> c.titlecase(locale) } }
    }
}

/**
 * Lado do motorista. Com o volante à direita (Tailândia, Indonésia, Reino Unido…) os painéis do app
 * trocam de lado. Ordem: escolha do usuário (Ajustes) → sinal do carro (INFO_DRIVER_SEAT) → país do sistema.
 */
object DriveSide {
    private val RHD_COUNTRIES = setOf("TH", "ID", "MY", "SG", "BN", "GB", "IE", "AU", "NZ", "JP", "IN", "PK", "LK", "BD", "NP", "HK", "MO",
        "ZA", "KE", "UG", "TZ", "ZM", "ZW", "MZ", "BW", "NA", "MU", "JM", "TT", "BS", "BB", "GY", "SR", "CY", "MT", "FJ", "PG", "BT", "MV", "TL")
    val listeners = LinkedHashSet<() -> Unit>()

    fun rhd(ctx: Context): Boolean = when (Prefs.str(ctx, Prefs.DRIVE_SIDE, "auto")) {
        "left" -> false
        "right" -> true
        else -> {
            val sp = Prefs.get(ctx)
            if (sp.contains(Prefs.CAR_RHD)) sp.getBoolean(Prefs.CAR_RHD, false) else byCountry()
        }
    }

    private fun byCountry(): Boolean {
        val c = L.system.country?.uppercase() ?: ""
        if (c.isNotEmpty()) return c in RHD_COUNTRIES
        return L.system.language in setOf("th", "in", "id")
    }

    /** o carro informou o banco do motorista (guardado: o sinal nem sempre chega) */
    fun fromCar(ctx: Context, right: Boolean) {
        val sp = Prefs.get(ctx)
        if (sp.contains(Prefs.CAR_RHD) && sp.getBoolean(Prefs.CAR_RHD, false) == right) return
        sp.edit().putBoolean(Prefs.CAR_RHD, right).apply()
        Diag.log("Motorista à " + if (right) "direita (painéis espelhados)" else "esquerda")
        for (l in listeners.toList()) try { l() } catch (_: Throwable) { }
    }

    /** a tela do app fica espelhada? (volante à direita, ou árabe com volante à esquerda) */
    fun mirrored(ctx: Context) = rhd(ctx) != L.rtl
}
