package sh.sk7.tether.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Le resume des approbations, verifie **sans texte**.
 *
 * ## Ce que ce test verifie vraiment
 *
 * La propriete utile n'est pas la phrase produite : c'est que **les deux files soient
 * annoncees separement**. Les fusionner en « 3 elements en attente » ferait un ecran
 * muet, ou l'utilisateur ne sait pas s'il doit repondre a une permission ou a un
 * formulaire — et les deux ne se repondent pas de la meme facon.
 *
 * Comparer du francais testerait une chaine, pas cette propriete, et casserait a la
 * premiere reecriture de traduction. Le test fournit donc un resolveur, et regarde
 * **quelles** branches sont choisies et **quels** comptes elles recoivent.
 */
class ApprovalsSummaryTest {

    private class Choix(val id: Int, val args: Array<out Any>)

    private fun choisir(permissions: Int, forms: Int): Choix {
        var choix: Choix? = null
        approvalsSummary(permissions, forms) { id, args ->
            choix = Choix(id, args)
            "texte"
        }
        return choix ?: error("aucune chaine demandee")
    }

    @Test
    fun `aucune demande ne parle ni de permission ni de formulaire`() {
        // Sans rien, un libelle d'attente — mais qui **ne dit pas** « 0 autorisation » :
        // un zero affiche comme un compte invente une demande qui n'existe pas.
        val vide = choisir(0, 0)
        assertTrue(vide.args.isEmpty(), "l'absence ne doit porter aucun compte : ${vide.args}")
    }

    @Test
    fun `une permission seule porte le nombre de permissions`() {
        // ⚠️ Ce test a trouve un bug : la chaine attend `%1$s`, et la branche ne
        // passait aucun argument. L'app affichait « Approbations : %1$s
        // autorisation(s) en attente » — le placeholder, en clair.
        val c = choisir(2, 0)
        assertEquals(1, c.args.size, "une permission seule prend le compte des permissions")
        assertEquals(2, c.args[0], "le compte doit etre celui de la file")
    }

    @Test
    fun `un formulaire seul ne prend aucun compte`() {
        // Le nombre de formulaires n'est pas annonce dans ce libelle. Ce test verrouille
        // ce choix : ajouter un nombre ici changerait l'ecran sans qu'on s'en apercoive.
        val c = choisir(0, 1)
        assertTrue(c.args.isEmpty(), "un formulaire seul ne porte aucun compte : ${c.args}")
    }

    /** Les deux files sont dites separement, jamais fondues en un total muet. */
    @Test
    fun `les deux files sont distinguees`() {
        val une = choisir(1, 0)
        val deux = choisir(0, 1)
        val lesDeux = choisir(1, 2)
        assertEquals(2, lesDeux.args.size, "les deux files annoncees portent deux comptes")
        assertEquals(1, lesDeux.args[0])
        assertEquals(2, lesDeux.args[1])
        // Le cas « les deux » ne doit reutiliser aucune des branches simples.
        assertNotEquals(une.id, lesDeux.id)
        assertNotEquals(deux.id, lesDeux.id)
    }
}
