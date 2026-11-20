package br.com.ex2.live3d

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Saudação da tela inicial: frase curta em duas linhas com o nome do motorista do dia, escolhida ao
 * acaso conforme o horário (alternando frases tradicionais e criativas). "####" = nome.
 * Frases-base enviadas pelo usuário (saudações.txt), mais algumas no mesmo estilo.
 */
object Greetings {
    class Driver(val name: String, val days: Set<Int>)    // dias = Calendar.DAY_OF_WEEK (1 dom … 7 sáb)

    private val MORNING = listOf(
        "Bom dia,\n####!", "Olá, ####!\nBom dia!", "Uma ótima\nmanhã, ####!", "Bom dia!\nVamos nessa?",
        "Que bom dia,\n####!", "Acordou?\nBom dia, ####!", "Bom dia, ####!\nBora viajar?", "Uma nova jornada\ncomeça, ####!",
        "Bom dia, ####!\nCafé tomado?", "Manhã boa\npara rodar, ####!")
    private val AFTERNOON = listOf(
        "Boa tarde,\n####!", "Olá, ####!\nBoa tarde!", "Uma ótima tarde,\n####!", "Boa tarde!\nTudo certo?",
        "Que bom te ver,\n####!", "Boa tarde, ####!\nVamos nessa?", "Mais uma jornada,\n####!", "Uma tarde incrível\npara você!",
        "Boa tarde, ####!\nSol na estrada!", "Tarde boa\npara passear!")
    private val NIGHT = listOf(
        "Boa noite,\n####!", "Olá, ####!\nBoa noite!", "Uma ótima noite,\n####!", "Boa noite!\nVamos nessa?",
        "Que bom te ver,\n####!", "Boa noite, ####!\nBoa viagem!", "Noite tranquila,\n####!", "Vamos com calma,\n####!",
        "Boa noite, ####!\nFaróis prontos!", "A noite é sua,\n####!")
    private val CREATIVE = listOf(
        "Olá,\n####!", "E aí,\n####?", "Opa,\n####!", "Bem-vindo,\n####!", "Tudo certo,\n####?", "Vamos nessa,\n####?",
        "Partiu,\n####?", "Que bom ter você\npor aqui!", "Pronto para\nmais uma, ####?", "Bora rodar,\n####?",
        "Seu destino\nte espera!", "A bordo,\n####!", "Novos caminhos,\nnovas histórias!", "Sua jornada\ncomeça aqui!",
        "Preparado,\n####?", "É bom ter você\nde volta, ####!", "Olá novamente,\n####!", "Que bom ter você\na bordo!",
        "Tudo pronto,\n####?", "Vamos acelerar\ncom segurança?", "Bem-vindo de volta,\n####!", "Como vai,\n####?",
        "Pronto para partir,\n####?", "Seu carro está\nà sua espera!", "Mais uma viagem,\nmais histórias!", "Vamos descobrir\nnovos caminhos?",
        "Fala, ####!\nTudo tranquilo?", "Opa, ####!\nChegou a hora!", "Que sua viagem\nseja incrível!", "Tudo em ordem,\n####?",
        "Vamos começar\nmais uma jornada?", "É hora de\npegar a estrada!", "Destino definido,\n####?", "A estrada chama,\nvamos nessa?",
        "Que bom ver você\npor aqui!", "Preparado para\nnovos caminhos?", "Olá, viajante!\nTudo certo?", "Bora aproveitar\no caminho?",
        "Seu próximo destino\nestá esperando!", "Vamos nessa,\nsem pressa!", "Conforto ligado,\njornada iniciada!", "Que sua jornada\nseja tranquila!",
        "Tudo conectado,\n####!", "Aproveite cada\nquilômetro!", "Mais um passeio\npela frente?", "Partiu descobrir\nnovos lugares?",
        "Seu companheiro\nestá pronto!", "Vamos de música\ne estrada?", "Que o caminho\nseja leve!", "Pronto para\nseguir em frente?",
        "É sempre bom\nter você aqui!", "Chegou a hora\nde partir!", "Uma nova rota,\numa nova história!", "Aproveite a viagem,\n####!",
        "Pode chegar,\na casa é sua!", "Boa viagem!\nEstamos juntos!",
        // no mesmo estilo
        "Bateria cheia,\nbora, ####?", "Silêncio elétrico,\n####!", "Cinto afivelado,\n####?", "Rumo ao próximo\ndestino, ####!",
        "Cada viagem\nconta, ####!", "Estrada livre\npara você!")

    /** frases no idioma do carro: a tabela de tradução traz "@saudacoes.<grupo>" = frases separadas por "|" */
    private fun pool(group: String, pt: List<String>): List<String> {
        if (L.code == "pt") return pt
        val key = "@saudacoes.$group"
        val s = L.t(key)
        return if (s == key) pt else s.split('|').map { it.replace("\\n", "\n").trim() }.filter { it.isNotEmpty() }.ifEmpty { pt }
    }

    /** motoristas cadastrados (nome e dias da semana) */
    fun drivers(ctx: Context): List<Driver> = try {
        val a = JSONArray(Prefs.str(ctx, Prefs.GREET_DRIVERS, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val d = o.optJSONArray("days") ?: JSONArray()
            Driver(o.optString("name", ""), (0 until d.length()).map { d.getInt(it) }.toSet())
        }
    } catch (_: Throwable) { emptyList() }

    fun save(ctx: Context, list: List<Driver>) {
        val a = JSONArray()
        for (d in list) a.put(JSONObject().put("name", d.name).put("days", JSONArray(d.days.sorted())))
        Prefs.get(ctx).edit().putString(Prefs.GREET_DRIVERS, a.toString()).apply()
    }

    /** nome de quem dirige hoje: o motorista marcado para o dia; senão o primeiro cadastrado */
    fun todaysName(ctx: Context, cal: Calendar = Calendar.getInstance()): String? {
        val list = drivers(ctx).filter { it.name.isNotBlank() }
        val today = cal.get(Calendar.DAY_OF_WEEK)
        return (list.firstOrNull { today in it.days } ?: list.firstOrNull())?.name?.trim()
    }

    /** uma saudação nova (null = saudação desligada) */
    fun pick(ctx: Context): String? {
        if (!Prefs.bool(ctx, Prefs.GREET_ON, true)) return null
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val period = when (h) { in 5..11 -> pool("manha", MORNING); in 12..17 -> pool("tarde", AFTERNOON); else -> pool("noite", NIGHT) }
        val creative = pool("criativas", CREATIVE)
        val name = todaysName(ctx)
        // alterna: uma vez tradicional do horário, outra criativa (a escolha anterior fica guardada)
        val sp = Prefs.get(ctx)
        val useCreative = !sp.getBoolean(Prefs.GREET_LAST_CREATIVE, true)
        var pool = if (useCreative) creative else period
        if (name == null) pool = pool.filter { !it.contains("####") }.ifEmpty { creative.filter { !it.contains("####") } }
        val last = sp.getString(Prefs.GREET_LAST, "")
        val choices = pool.filter { it != last }.ifEmpty { pool }
        val p = choices[(Math.random() * choices.size).toInt().coerceIn(0, choices.size - 1)]
        sp.edit().putString(Prefs.GREET_LAST, p).putBoolean(Prefs.GREET_LAST_CREATIVE, useCreative).apply()
        return if (name != null) p.replace("####", name) else p
    }

    /** segundos na tela (0 = sempre) */
    fun seconds(ctx: Context) = Prefs.int(ctx, Prefs.GREET_SECS, 30)

    /* ---------- 2ª saudação: a data de hoje ---------- */

    private val DAYS = listOf("dom.", "seg.", "ter.", "qua.", "qui.", "sex.", "sáb.")
    private val MONTHS = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")

    /** "Hoje é dom. 15 de nov" */
    fun dateLine(cal: Calendar = Calendar.getInstance()): String {
        if (L.code != "pt") {
            // outros idiomas: o formato de data do próprio idioma ("Today is Wed, Nov 18" …)
            val pattern = L.t("@data").takeIf { it != "@data" } ?: "EEE d MMM"
            return L.f("Hoje é %s", java.text.SimpleDateFormat(pattern, L.locale).format(cal.time))
        }
        return "Hoje é ${DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]} ${cal.get(Calendar.DAY_OF_MONTH)} de ${MONTHS[cal.get(Calendar.MONTH)]}"
    }

    fun secondOn(ctx: Context) = Prefs.bool(ctx, Prefs.GREET2_ON, true)
    /** segundos na tela da 2ª saudação (0 = sempre) */
    fun seconds2(ctx: Context) = Prefs.int(ctx, Prefs.GREET2_SECS, 15)

    /** texto de duração para os ajustes: "30 s", "1 min 30 s", "Sempre" */
    fun durationLabel(secs: Int, zero: String = "Sempre"): String = when {
        secs <= 0 -> L.t(zero)
        secs < 60 -> "$secs s"
        secs % 60 == 0 -> "${secs / 60} min"
        else -> "${secs / 60} min ${secs % 60} s"
    }
}
