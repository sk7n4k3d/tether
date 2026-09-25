package sh.sk7.tether.ui.permissions

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Les deux regles qui decident si une demande sera vue.**
 *
 * ⚠️ Elles sont extraites en fonctions pures parce que chacune corrige un defaut **silencieux** :
 *  - un ecran qui ouvre sur un onglet vide alors que l'autre file un agent bloque oblige a deviner
 *    de quel cote regarder ;
 *  - une icone qui se tait alors qu'une demande attend est un mensonge par omission.
 *
 * Aucune des deux ne se voit a la compilation : d'ou ces tests, sans Compose ni serveur.
 */
class ApprovalRoutingTest {

    @Test
    fun `les autorisations priment a l ouverture`() {
        assertEquals(ApprovalTab.Permissions, initialApprovalTab(permissionsPending = 2, formsPending = 3))
    }

    @Test
    fun `seuls des formulaires ouvrent sur les formulaires`() {
        assertEquals(ApprovalTab.Forms, initialApprovalTab(permissionsPending = 0, formsPending = 1))
    }

    /** Rien en attente : on ouvre sur autorisations, dont l'etat vide explique la situation. */
    @Test
    fun `file vide ouvre sur autorisations`() {
        assertEquals(ApprovalTab.Permissions, initialApprovalTab(permissionsPending = 0, formsPending = 0))
    }

    /**
     * ⚠️ Pendant qu'un formulaire est ouvert, la veille periodique doit se taire : une relecture
     * remplacerait `forms` sous l'index ouvert, c'est-a-dire le formulaire qu'on est en train de
     * remplir.
     */
    @Test
    fun `un formulaire ouvert bloque la relecture de la file`() {
        assertEquals(false, canReloadForms(hasOpenForm = true))
        assertEquals(true, canReloadForms(hasOpenForm = false))
    }
}
