package br.com.ex2.live3d

/** Versões e cores do Geely EX2 (as mesmas do configurador web). */
data class Paint(val id: String, val name: String, val hex: Int, val metal: Float, val rough: Float, val coatRough: Float, val bodyMirrors: Boolean = false)

data class Trim(val id: String, val name: String, val twoTone: Boolean, val wheels: String, val cameras: Boolean, val chrome: Boolean)

object CarSpec {
    val paints = listOf(
        Paint("verde-pistachio", "Verde Pistachio", 0xabbca4, .15f, .40f, .06f, bodyMirrors = true),
        Paint("cinza-street", "Cinza Street", 0x7e8085, .35f, .40f, .05f),
        Paint("branco-horizon", "Branco Horizon", 0xe6e7e7, .05f, .34f, .05f),
        Paint("prata-urban", "Prata Urban", 0xb1b6be, .35f, .38f, .05f)
    )
    val trims = listOf(
        Trim("pro", "Pro", twoTone = false, wheels = "cover15", cameras = false, chrome = false),
        Trim("max", "Max", twoTone = true, wheels = "alloy16", cameras = true, chrome = true)
    )

    fun paint(id: String?) = paints.firstOrNull { it.id == id } ?: paints[0]
    fun trim(id: String?) = trims.firstOrNull { it.id == id } ?: trims[0]

    /** padrão Mercosul (só para mostrar com hífen no cartão; a placa aceita qualquer texto) */
    val PLATE = Regex("^[A-Z]{3}[0-9][A-Z][0-9]{2}$")

    /** texto livre da placa: maiúsculas, até 7 caracteres (menos = centralizado na placa) */
    fun cleanPlate(s: String) = s.uppercase().filter { !it.isISOControl() }.trim().take(7)

    /** como a placa aparece no cartão do carro */
    fun plateLabel(p: String) = when {
        p.isEmpty() -> "sem placa"
        PLATE.matches(p) -> p.substring(0, 3) + "-" + p.substring(3)
        else -> p
    }
}

/** Portas e tampas: nomes das animações do car.glb (já com o lado certo do carro). */
object Openings {
    const val FL = "doorFL"; const val FR = "doorFR"; const val RL = "doorRL"; const val RR = "doorRR"
    const val TAILGATE = "tailgate"; const val FRUNK = "frunk"
    val all = listOf(FL, FR, RL, RR, TAILGATE, FRUNK)
}

/** Luzes do carro (vindas do VHAL ou dos botões). signal: 0 nada, 1 esquerda, -1 direita, 2 alerta */
/** farol = farol baixo; alta = farol alto (cada um com o seu feixe) */
data class LightState(val farol: Boolean = false, val drl: Boolean = false, val brake: Boolean = false, val signal: Int = 0,
                      val alta: Boolean = false)
