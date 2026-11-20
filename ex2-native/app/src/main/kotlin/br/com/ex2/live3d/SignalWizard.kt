package br.com.ex2.live3d

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Modo guiado "Encontrar sinais das luzes": pede uma ação por vez (farol, luz alta, setas,
 * pisca-alerta, freio), grava todas as trocas de sinal do carro e, no fim, aponta qual sinal
 * respondeu a cada ação e com qual valor. O resultado vira o mapeamento manual do CarBridge.
 */
class SignalWizard(private val ctx: Context, private val root: FrameLayout) {

    private class Step(val text: String, val field: String?, val secs: Int)

    private val steps = listOf(
        Step("Deixe o botão do farol em DESLIGADO (não AUTO) e não mexa em nada", null, 8),
        Step("Ligue o FAROL (farol baixo)", "farol", 8), Step("Desligue o farol", null, 6),
        Step("Ligue a LUZ ALTA", "alta", 8), Step("Desligue a luz alta", null, 6),
        Step("Ligue a SETA para a ESQUERDA", "seta_esq", 8), Step("Desligue a seta", null, 6),
        Step("Ligue a SETA para a DIREITA", "seta_dir", 8), Step("Desligue a seta", null, 6),
        Step("Ligue o PISCA-ALERTA", "alerta", 8), Step("Desligue o pisca-alerta", null, 6),
        Step("PISE NO FREIO e segure", "freio", 7), Step("Solte o freio", null, 6)
    )
    private val labels = linkedMapOf("farol" to "Farol", "alta" to "Luz alta", "seta_esq" to "Seta esquerda",
        "seta_dir" to "Seta direita", "alerta" to "Pisca-alerta", "freio" to "Freio")

    private val main = Handler(Looper.getMainLooper())
    private val bridge = CarBridge.get(ctx)
    private val overlay = FrameLayout(ctx).apply { setBackgroundColor(Ui.SCRIM); isClickable = true; translationZ = Ui.dp(ctx, 40f) }
    private val box = Ui.panel(ctx).apply { setPadding(Ui.dpi(ctx, 34), Ui.dpi(ctx, 26), Ui.dpi(ctx, 34), Ui.dpi(ctx, 26)) }
    private val starts = LongArray(steps.size + 1)
    private var cancelled = false

    fun show() {
        overlay.addView(ScrollView(ctx).apply { addView(box) },
            FrameLayout.LayoutParams(Ui.dpi(ctx, 640), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        intro()
    }

    private fun close() { cancelled = true; main.removeCallbacksAndMessages(null); root.removeView(overlay) }

    private fun page(title: String, body: String, vararg buttons: Pair<String, () -> Unit>) {
        box.removeAllViews()
        box.addView(Ui.text(ctx, title, 26f, Ui.TEXT, true))
        box.addView(Ui.text(ctx, body, 17f, Ui.MUTED), Ui.lp().apply { topMargin = Ui.dpi(ctx, 10) })
        buttons.forEachIndexed { i, (label, action) ->
            box.addView(pill(ctx, label, primary = i == 0) { action() }, Ui.lp(-1).apply { topMargin = Ui.dpi(ctx, if (i == 0) 22 else 10) })
        }
    }

    private fun intro() {
        val ok = bridge.isConnected
        page("Encontrar sinais das luzes",
            "O app vai pedir uma ação por vez (farol, luz alta, setas, pisca-alerta e freio) e anotar qual sinal do carro muda em cada uma. Leva uns 2 minutos.\n\n" +
                "Antes de começar: carro LIGADO (pronto para rodar, não só aceso), PARADO, com o botão do farol em DESLIGADO — não em AUTO (no AUTO o farol já acende sozinho e o teste não vê a mudança). No passo do freio, alguém pisa no pedal.\n\n" +
                if (ok) "Faça cada ação assim que ela aparecer na tela e espere a contagem." else "Atenção: o serviço do carro não está conectado (${bridge.status}). Fora da central o teste roda, mas não encontra nada.",
            "Começar" to { start() }, "Cancelar" to { close() })
    }

    private fun start() {
        bridge.startCapture()
        run(0)
    }

    private fun run(i: Int) {
        if (cancelled) return
        if (i >= steps.size) { starts[i] = SystemClock.elapsedRealtime(); finish(); return }
        val s = steps[i]
        starts[i] = SystemClock.elapsedRealtime()
        var left = s.secs
        fun draw() {
            page(s.text, "Passo ${i + 1} de ${steps.size} · próximo em $left s" + if (s.field != null) "\n\nFaça agora e mantenha até a contagem acabar." else "",
                "Cancelar" to { bridge.stopCapture(); bridge.reloadManualMap(); close() })
        }
        draw()
        val tick = object : Runnable {
            override fun run() {
                if (cancelled) return
                left--
                if (left <= 0) run(i + 1) else { draw(); main.postDelayed(this, 1000) }
            }
        }
        main.postDelayed(tick, 1000)
    }

    /* ---------------- análise ---------------- */

    private class Found(val field: String, val id: Int, val expect: String, val why: String)

    private fun finish() {
        val samples = bridge.stopCapture()
        val byKey = samples.filter { !it.key.contains('/') }.groupBy { it.key }
        fun valueAt(list: List<CarBridge.Sample>, t: Long): Int? = list.lastOrNull { it.at <= t }?.value
        fun changes(list: List<CarBridge.Sample>, a: Long, b: Long): Int {
            var n = 0; var prev = valueAt(list, a)
            for (s in list) if (s.at in (a + 1)..b) { if (prev != null && s.value != prev) n++; prev = s.value }
            return n
        }
        // ruído: sinais que mudaram com tudo parado (velocidade, temperatura, relógio...)
        val noisy = byKey.filter { (_, l) -> changes(l, starts[0], starts[1]) > 0 }.keys
        val found = ArrayList<Found>()
        val ranked = LinkedHashMap<String, List<Pair<Int, Found>>>()   // campo → candidatos (pontos, achado)
        steps.forEachIndexed { i, s ->
            val field = s.field ?: return@forEachIndexed
            val a = starts[i]; val b = starts[i + 1]; val c = starts[i + 2]
            var best: Found? = null; var bestScore = -1
            val cands = ArrayList<Pair<Int, Found>>()
            for ((key, l) in byKey) {
                if (key in noisy) continue
                val id = key.removePrefix("0x").toLong(16).toInt()
                val before = valueAt(l, a); val during = valueAt(l, b - 300); val after = valueAt(l, c - 300)
                val n = changes(l, a, b)
                val blink = n >= 3
                val steady = !blink && during != null && during != before && after == before
                if (!blink && !steady) continue
                // preferências: sinais da documentação; setas/alerta: estado fixo da alavanca/BCM antes do pisca
                var score = if (id in CarBridge.KNOWN) 10 else 0
                if (steady) score += 3
                if (field == "seta_esq" || field == "seta_dir") { if (id == CarBridge.TURN_LEVER) score += 5 }
                if (field == "alerta" && id == CarBridge.BCM_HAZARD) score += 5
                if (field == "farol" && id == CarBridge.HEADLIGHTS) score += 5
                if (field == "alta" && id == CarBridge.HIGH_BEAM) score += 5
                val name = CarBridge.KNOWN[id] ?: "sinal"
                val f = if (blink) Found(field, id, "pisca", "$name piscando ($n trocas); antes=$before depois=$after")
                else Found(field, id, during.toString(), "$name: $before → $during → $after")
                cands.add(score to f)
                if (score > bestScore) { bestScore = score; best = f }
            }
            ranked[field] = cands.sortedByDescending { it.first }.take(5)
            best?.let { found.add(it) }
        }
        // sinais descartados como ruído e o valor de cada um no início (o farol baixo pode estar aqui)
        val noiseInfo = noisy.sorted().map { k ->
            val l = byKey[k].orEmpty()
            "$k · início=${valueAt(l, starts[0])} · trocas no 1º passo=${changes(l, starts[0], starts[1])} · trocas no teste todo=${changes(l, starts[0], starts[steps.size])}"
        }
        // valores dos sinais conhecidos em cada passo (para conferir o farol e o freio à mão)
        val known = CarBridge.KNOWN.keys.map { "0x" + Integer.toHexString(it) }.filter { it in byKey }
        val perStep = known.map { k ->
            val l = byKey[k].orEmpty()
            "$k (${CarBridge.KNOWN[k.removePrefix("0x").toLong(16).toInt()]}): " +
                steps.indices.joinToString(" ") { i -> "${i + 1}=${valueAt(l, starts[i + 1] - 300)}" }
        }
        writeReport(found, ranked, samples.size, noisy.size, noiseInfo, perStep)
        writeRaw(samples, noisy)
        // registro completo para conferência
        Diag.vhal(ctx, "=== modo guiado: ${samples.size} trocas; ruído ignorado: ${noisy.size} sinais")
        steps.forEachIndexed { i, s -> Diag.vhal(ctx, "passo ${i + 1} '${s.text}' começou em ${starts[i]}") }
        for (f in found) Diag.vhal(ctx, "ACHADO ${f.field}=0x${Integer.toHexString(f.id)}:${f.expect} (${f.why})")
        Diag.log("Modo guiado: ${found.size} de ${labels.size} luzes encontradas")
        result(found)
    }

    private fun result(found: List<Found>) {
        val text = buildString {
            for ((field, label) in labels) {
                val f = found.firstOrNull { it.field == field }
                append(label).append(": ")
                if (f == null) append("não encontrado") else append("0x").append(Integer.toHexString(f.id)).append(" = ").append(f.expect).append("  (").append(f.why).append(')')
                append('\n')
            }
            append("\nResultado salvo em Android/data/br.com.ex2.live3d/files/sinais-luzes.txt")
            append("\n(traga esse arquivo e o sinais-bruto.txt, da mesma pasta, para virar o padrão do app).")
        }
        if (found.isEmpty()) {
            page("Nenhum sinal encontrado", text, "Fechar" to { bridge.reloadManualMap(); close() })
            return
        }
        page("Sinais encontrados", text,
            "Usar estes sinais" to { save(found); close() },
            "Descartar" to { bridge.reloadManualMap(); close() })
    }

    /**
     * sinais-luzes.txt (na mesma pasta do relatorio-erro.txt): o mapeamento pronto, para virar o
     * padrão do app, e os melhores candidatos de cada luz, para conferência.
     */
    private fun writeReport(found: List<Found>, ranked: Map<String, List<Pair<Int, Found>>>, samples: Int, noisy: Int,
                            noiseInfo: List<String>, perStep: List<String>) {
        try {
            val txt = buildString {
                append("EX2 Live 3D ${BuildConfig.VERSION_NAME} · sinais das luzes · ")
                append(java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())).append('\n')
                append("Serviço do carro: ${bridge.status}\n")
                append("Trocas gravadas: $samples · sinais ignorados por mudarem sozinhos: $noisy\n\n")
                append("== MAPEAMENTO (campo=sinal:valor aceso | pisca) ==\n")
                for ((field, label) in labels) {
                    val f = found.firstOrNull { it.field == field }
                    if (f == null) append("# $label: não encontrado\n")
                    else append("${f.field}=0x${Integer.toHexString(f.id)}:${f.expect}\n")
                }
                append("\n== CANDIDATOS POR LUZ (pontos · sinal · o que fez) ==\n")
                for ((field, label) in labels) {
                    append(label).append(":\n")
                    val list = ranked[field].orEmpty()
                    if (list.isEmpty()) append("  (nenhum sinal mudou nesse passo)\n")
                    for ((score, f) in list) append("  $score · 0x${Integer.toHexString(f.id)} · ${f.why}\n")
                }
                append("\n== SINAIS CONHECIDOS: VALOR NO FIM DE CADA PASSO ==\n")
                if (perStep.isEmpty()) append("  (nenhum respondeu)\n")
                perStep.forEach { append("  ").append(it).append('\n') }
                append("\n== DESCARTADOS COMO RUÍDO (mudaram com tudo parado) ==\n")
                if (noiseInfo.isEmpty()) append("  (nenhum)\n")
                noiseInfo.forEach { append("  ").append(it).append('\n') }
                append("\n== PASSOS ==\n")
                steps.forEachIndexed { i, s -> append("${i + 1}. ${s.text} (${s.secs} s)\n") }
            }
            java.io.File(Diag.dir(ctx), "sinais-luzes.txt").writeText(txt)
            Diag.log("Modo guiado: sinais-luzes.txt gravado")
        } catch (t: Throwable) { Diag.log("Modo guiado: não gravou sinais-luzes.txt: $t") }
    }

    /**
     * sinais-bruto.txt: TODA troca de sinal capturada, em ordem, com a marca de cada passo.
     * (o vhal-log.txt só recebe isso com a gravação do Diagnóstico ligada; no 1º teste ficou vazio)
     */
    private fun writeRaw(samples: List<CarBridge.Sample>, noisy: Set<String>) {
        try {
            java.io.File(Diag.dir(ctx), "sinais-bruto.txt").bufferedWriter().use { w ->
                w.write("EX2 Live 3D ${BuildConfig.VERSION_NAME} · trocas de sinal do modo guiado\n")
                w.write("tempo em ms desde o início · sinal[/área] = valor · (R = descartado como ruído)\n\n")
                val t0 = starts[0]
                var step = -1
                for (s in samples.sortedBy { it.at }) {
                    while (step + 1 < steps.size && s.at >= starts[step + 1]) {
                        step++
                        w.write("\n--- passo ${step + 1}: ${steps[step].text} (em ${starts[step] - t0} ms)\n")
                    }
                    w.write("${s.at - t0}  ${s.key} = ${s.value}${if (s.key in noisy) "  R" else ""}\n")
                }
            }
            Diag.log("Modo guiado: sinais-bruto.txt gravado (${samples.size} trocas)")
        } catch (t: Throwable) { Diag.log("Modo guiado: não gravou sinais-bruto.txt: $t") }
    }

    /** grava no mapeamento manual (substitui só as luzes encontradas) e reconecta ao carro */
    private fun save(found: List<Found>) {
        val fields = found.map { it.field }.toSet()
        val kept = Prefs.str(ctx, Prefs.MAP, "").split("\n").map { it.trim() }
            .filter { it.isNotEmpty() && it.substringBefore('=').trim().lowercase() !in fields }
        val lines = kept + found.map { "${it.field}=0x${Integer.toHexString(it.id)}:${it.expect}" }
        Prefs.get(ctx).edit().putString(Prefs.MAP, lines.joinToString("\n")).apply()
        Diag.log("Modo guiado: mapeamento salvo (${found.size} luzes)")
        bridge.reloadManualMap()
    }
}
