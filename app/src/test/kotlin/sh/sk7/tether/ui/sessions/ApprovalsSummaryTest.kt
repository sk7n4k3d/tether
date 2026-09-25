package sh.sk7.tether.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Ce que le point d'entree annonce quand quelque chose bloque un agent.**
 *
 * ⚠️ Un formulaire immobilise la session comme une permission (mesure du projet). Si l'icone de la
 * barre ne comptait que les autorisations, elle dirait « calme » alors qu'un agent attend une
 * reponse — le mensonge par omission que la teinte d'alerte existe pour empecher. Le libelle les
 * distingue : a l'oreille, accorder un droit et remplir un formulaire sont deux gestes differents.
 */
class ApprovalsSummaryTest {

    @Test
    fun `aucune demande ne parle ni de permission ni de formulaire`() {
        val label = approvalsSummary(permissions = 0, forms = 0)
        assertEquals("Approbations en attente", label)
    }

    @Test
    fun `une permission seule est annoncee`() {
        assertTrue(approvalsSummary(permissions = 2, forms = 0).contains("2 autorisation"))
    }

    @Test
    fun `un formulaire seul est annonce`() {
        assertTrue(approvalsSummary(permissions = 0, forms = 1).contains("1 formulaire"))
    }

    /** Les deux files sont dites separement, jamais fondues en un total muet. */
    @Test
    fun `les deux files sont distinguees`() {
        val label = approvalsSummary(permissions = 1, forms = 2)
        assertTrue(label.contains("1 autorisation"))
        assertTrue(label.contains("2 formulaire"))
    }
}
