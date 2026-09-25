package sh.sk7.tether.ui.theme

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Le contraste WCAG des couleurs de l'app, en dur et vérifiable.**
 *
 * ### Pourquoi un test, et pas une note dans un commentaire
 * ⚠️ Un contraste ne se voit **pas** dans un diff. Baisser un alpha de 0.85 à 0.80 « pour
 * adoucir » passe en revue sans que personne ne voie que le texte tombe de 5.0 à 4.6 — ou
 * l'inverse, qu'une icône est devenue trop pale. C'est exactement le type de régression que le
 * projet s'interdit : un changement invisible pour qui le commet, lourd de conséquences pour qui
 * en dépend.
 *
 * ⚠️ Les seuils ne sont pas des préférences : WCAG 2.1 AA demande **4.5:1** pour du texte normal
 * (1.4.3) et **3:1** pour un composant d'interface ou un élément graphique (1.4.11). L'European
 * Accessibility Act s'applique aux applications mobiles grand public depuis le 28 juin 2025.
 *
 * ⚠️ On teste **contre les trois fonds réels** de l'app (`#0B0E11`, `#11151A`, `#1F262E`), pas
 * contre un fond théorique : c'est la surface la plus claire — la barre de saisie — qui est le
 * couple contraignant, et un test sur le fond le plus sombre validerait un texte illisible dans
 * la zone où l'on écrit.
 */
class ContrastTest {

    // ------------------------------------------------------------------
    // La formule WCAG, sans dépendance
    // ------------------------------------------------------------------

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Les trois fonds réels, du plus sombre au plus clair. */
    private val backgrounds = listOf(
        "fond principal" to 0xFF0B0E11,
        "surface" to 0xFF11151A,
        "surface de saisie" to 0xFF1F262E,
    )

    private fun assertText(label: String, color: Long, min: Double = 4.5) {
        backgrounds.forEach { (name, bg) ->
            val r = ratio(color, bg)
            assertTrue(
                r >= min,
                "$label sur $name : $r < $min (calculé sur les couleurs réelles)",
            )
        }
    }

    // ------------------------------------------------------------------
    // Texte : seuil 4.5:1
    // ------------------------------------------------------------------

    @Test
    fun `le texte principal passe 4_5 sur les trois fonds`() {
        assertText("texte principal", 0xFFE6EDF3)
    }

    @Test
    fun `le texte secondaire passe 4_5 sur les trois fonds`() {
        assertText("texte secondaire", 0xFF8B98A5)
    }

    /**
     * ⚠️ Le test qui a **motivé** le token.
     *
     * `TetherTextSecondary.copy(alpha = 0.85f)` était la valeur la plus répandue dans l'app avant
     * mesure. Composée sur la surface de saisie (`#1F262E`), elle donne `#7B8793` et un contraste
     * de **4.17** — sous le seuil. C'est précisément ce que l'ancien code faisait, à 29 endroits.
     * Le token `#808D99` (opaque) est le résultat mesuré qui passe.
     */
    @Test
    fun `le texte attenue passe 4_5 la ou l ancien alpha 0_85 echouait`() {
        assertText("texte atténué", 0xFF808D99)
    }

    @Test
    fun `l accent teal passe comme texte`() {
        assertText("accent", 0xFF2DD4BF)
    }

    @Test
    fun `l alerte ambre passe comme texte`() {
        assertText("alerte", 0xFFFFB020)
    }

    // ------------------------------------------------------------------
    // Icônes et éléments graphiques : seuil 3:1
    // ------------------------------------------------------------------

    @Test
    fun `l icone attenuee passe 3_1 sur les trois fonds`() {
        assertText("icône atténuée", 0xFF6B7681, min = 3.0)
    }

    /**
     * ⚠️ La bordure de la barre de saisie est **volontairement** sous 3:1 (mesurée à **1.95**).
     *
     * Elle ne porte pas d'information : elle **délimite** une surface, et sa valeur a été calée sur
     * l'écart réel de ChatGPT (~1.90). La relever au seuil des éléments graphiques changerait la
     * signature visuelle de l'app pour satisfaire un critère qui ne s'applique pas — 1.4.11 vise
     * ce qui est **nécessaire à la compréhension**, et la barre reste identifiable par sa surface
     * tonale (`1.27` d'écart au fond, comme Gemini). Ce test **documente** l'écart et empêche
     * qu'on le « corrige » par erreur, ou qu'on le confonde avec une couleur de texte.
     */
    @Test
    fun `la bordure de la barre de saisie est un repere de surface, pas un signal`() {
        val r = ratio(0xFF49535F, 0xFF1F262E)
        assertTrue(r in 1.5..3.0, "la bordure doit rester un repère discret, mesuré : $r")
    }
}
