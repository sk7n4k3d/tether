package sh.sk7.tether.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Le contraste du theme, mesure plutot que juge.
 *
 * ## Pourquoi ce test existe
 *
 * Un choix de couleur libre peut rendre un ecran illisible — un accent pale sur fond
 * sombre, une teinte proche du fond. Le symptome ne se voit pas chez celui qui a choisi :
 * il voit son theme, et il est content. Ce sont les autres qui ne lisent plus rien.
 *
 * Ce test verrouille donc la propriete : **aucune teinte ne peut etre livree sous le
 * seuil**, quel que soit le fond. Il echoue si une valeur passe sous, ce qui rend
 * impossible d'ajouter un accent illisible sans que quelqu'un le voie.
 */
class ContrasteTest {

    @Test
    fun `chaque teinte accusee tient le seuil des elements d'interface`() {
        for (accent in Accent.entries) {
            val ajuste = accent.teinte.accentue()
            val ratio = contraste(ajuste, FOND_LE_PLUS_SOMBRE)
            assertTrue(
                "${accent.libelle} : $ratio sur le fond le plus sombre, seuil $SEUIL_ELEMENT",
                ratio >= SEUIL_ELEMENT,
            )
        }
    }

    @Test
    fun `chaque teinte accusee tient aussi sur la surface de saisie`() {
        // La surface de saisie est le fond le plus clair : c'est la ou un accent
        // insuffisamment lumineux disparait en premier.
        for (accent in Accent.entries) {
            val ratio = contraste(accent.teinte, FOND_LE_PLUS_CLAIR)
            assertTrue("${accent.libelle} : $ratio sur la surface de saisie", ratio >= SEUIL_ELEMENT)
        }
    }

    @Test
    fun `le texte principal pose sur l'accent reste lisible`() {
        // Un bouton, c'est un accent en fond, et du texte dessus. Si l'accent est trop
        // clair, le texte sombre disparait — l'erreur inverse de la precedente, et elle
        // arrive avec les teintes claires.
        for (accent in Accent.entries) {
            val fond = accent.teinte.accentue()
            val ratio = contraste(FOND_LE_PLUS_SOMBRE, fond)
            assertTrue("${accent.libelle} : texte sur accent a $ratio", ratio >= SEUIL_TEXTE)
        }
    }

    @Test
    fun `un accent deja conforme n est pas modifie`() {
        // Si on ajustait une couleur qui passe deja, on aurait un theme qui bouge au
        // lieu de suivre le choix de l'utilisateur. C'est la difference entre un reglage
        // et une correction forcee.
        for (accent in Accent.entries) {
            val dejaBon = ajusterPourContraste(accent.teinte, FOND_LE_PLUS_SOMBRE, 0.0)
            assertEquals(
                "${accent.libelle} ne doit pas etre touche si la valeur convient deja",
                accent.teinte,
                dejaBon,
            )
        }
    }

    @Test
    fun `une couleur trop sombre est eclaircie jusqu au seuil`() {
        val sombre = androidx.compose.ui.graphics.Color(0xFF102020)
        val ajuste = sombre.accentue()
        assertTrue("devait s'eclaircir", contraste(ajuste, FOND_LE_PLUS_SOMBRE) >= SEUIL_ELEMENT)
        assertNotEquals(sombre, ajuste)
    }

    @Test
    fun `la luminance suit la formule WCAG`() {
        // Deux valeurs verifiees a la main : le blanc pur vaut 1, le noir pur vaut 0. Un
        // calcul faux ici rendrait tous les autres tests faux **et** passants.
        assertEquals(1.0, luminance(androidx.compose.ui.graphics.Color.White), 0.001)
        assertEquals(0.0, luminance(androidx.compose.ui.graphics.Color.Black), 0.001)
    }

    @Test
    fun `le contraste du noir et du blanc vaut 21`() {
        // La borne haute de l'echelle WCAG. Si elle sort de 21, la formule est fausse.
        val ratio = contraste(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.Black)
        assertEquals(21.0, ratio, 0.05)
    }

    @Test
    fun `le contraste est symetrique`() {
        val a = androidx.compose.ui.graphics.Color(0xFF2DD4BF)
        val b = androidx.compose.ui.graphics.Color(0xFF1F262E)
        assertEquals(contraste(a, b), contraste(b, a), 0.0001)
    }

    @Test
    fun `une cle inconnue retombe sur le defaut, sans lever`() {
        // Une cle de reglage ne doit jamais faire planter l'app au demarrage : elle vient
        // d'un fichier de preferences qui peut avoir ete edite a la main.
        assertEquals(Accent.parDefaut, Accent.depuisCle("n-existe-pas"))
        assertEquals(Accent.parDefaut, Accent.depuisCle(null))
        assertEquals(Accent.Azur, Accent.depuisCle("azur"))
    }
}
