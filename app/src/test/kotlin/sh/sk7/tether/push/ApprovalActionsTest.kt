package sh.sk7.tether.push

import sh.sk7.tether.domain.model.PermissionDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Les regles des boutons de decision.
 *
 * \u26a0\ufe0f Le test le plus important est [plusieurs demandes en attente ne donnent aucun bouton] :
 * c'est lui qui interdit d'appliquer une decision a un element qu'on n'a pas montre.
 */
class ApprovalActionsTest {

    private fun pending(id: String = "prq_1", session: String = "ses_1", action: String = "bash") =
        PendingApproval(requestID = id, sessionID = session, action = action)

    @Test
    fun `une seule demande donne cette demande`() {
        val seule = pending(id = "prq_42")
        assertEquals(seule, approvalFor(listOf(seule)))
    }

    @Test
    fun `aucune demande ne donne aucun bouton`() {
        assertNull(approvalFor(emptyList()))
    }

    /**
     * Deux demandes ou plus : **aucun bouton**.
     *
     * \u26a0\ufe0f Avec plusieurs demandes en attente, on ne sait pas laquelle le bouton viserait.
     * En choisir une, meme « la plus recente », revient a appliquer une decision a un element que
     * l'utilisateur n'a pas sous les yeux \u2014 sur une machine ou le bouton peut accorder un
     * acces permanent. Sans boutons, la notification renvoie vers Approbations, ou la liste est
     * visible. C'est aussi notre principe : une action qui ne peut pas aboutir est pire que
     * pas d'action.
     */
    @Test
    fun `plusieurs demandes en attente ne donnent aucun bouton`() {
        assertNull(approvalFor(listOf(pending("prq_1"), pending("prq_2"))))
        assertNull(approvalFor(listOf(pending("prq_1"), pending("prq_2"), pending("prq_3"))))
    }

    /**
     * L'ordre : `Refuser` d'abord, `Toujours` **en dernier**.
     *
     * \u26a0\ufe0f L'ordre de lecture des actions d'une notification dicte l'ordre de touche, et le
     * premier est le plus facile a glisser par megarde. `Toujours` est le seul des trois qui ouvre
     * un **droit permanent** : le mettre en dernier rend l'accident improbable. C'est le
     * compromis accepte pour avoir les trois boutons sur l'ecran verrouille \u2014 le droiture
     * reste dans l'app, ou l'on voit la commande.
     */
    @Test
    fun `Toujours est le dernier bouton`() {
        assertEquals(
            listOf(PermissionDecision.Reject, PermissionDecision.Once, PermissionDecision.Always),
            APPROVAL_ACTIONS,
        )
        assertEquals(PermissionDecision.Always, APPROVAL_ACTIONS.last())
    }

    @Test
    fun `les libelles disent ce qu ils accordent`() {
        // Un resolveur qui note l'identifiant choisi : hors Android, `Res` n'a pas de
        // ressources, et c'est le **choix** de la chaine qu'on veut verifier ici.
        // Chaque decision doit avoir sa **propre** chaine, et trois decisions ne
        // doivent pas se retrouver a la meme : c'est la regle, pas la phrase.
        val refuse = idDe(PermissionDecision.Reject)
        val uneFois = idDe(PermissionDecision.Once)
        val toujours = idDe(PermissionDecision.Always)
        assertEquals(3, setOf(refuse, uneFois, toujours).size, "trois chaines distinctes")
    }

    /**
     * Les trois boutons couvrent les trois valeurs du protocole, sans doublon.
     *
     * \u26a0\ufe0f `wire` est ce que le serveur attend (`{"decision":"once"|"always"|"reject"}`,
     * mesure du 2026-09-25). Un libelle qui ne correspondrait a aucune valeur produirait une
     * notification dont les boutons repondent tous « refuse ».
     */
    @Test
    fun `les trois boutons couvrent les trois valeurs du serveur`() {
        assertEquals(3, APPROVAL_ACTIONS.size)
        assertEquals(3, APPROVAL_ACTIONS.map { it.wire }.toSet().size)
        assertEquals(
            setOf("once", "always", "reject"),
            APPROVAL_ACTIONS.map { it.wire }.toSet(),
        )
    }

    /** L'action demandee voyage avec la demande : le titre doit dire ce qu'on accorde. */
    @Test
    fun `la demande porte l action a accorder`() {
        assertEquals("bash", pending(action = "bash").action)
        assertEquals("edit", pending(action = "edit").action)
    }


    /** L'identifiant de la chaine choisie pour une decision. */
    private fun idDe(decision: PermissionDecision): Int {
        var id = -1
        approvalActionLabel(decision) { choisi, _ ->
            id = choisi
            "texte"
        }
        return id
    }
}
