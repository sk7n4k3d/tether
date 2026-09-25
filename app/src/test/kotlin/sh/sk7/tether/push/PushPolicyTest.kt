package sh.sk7.tether.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **La règle de notification, et la cible du tap.**
 *
 * ⚠️ Ces tests portent sur ce qui ne se voit **pas** à la compilation : l'ordre des décisions.
 * Une inversion ferait notifier ce que l'utilisateur regarde (du bruit) ou, pire, laisserait une
 * décision qui attend se faire balayer — exactement le scénario où une session reste bloquée des
 * heures.
 */
class PushPolicyTest {

    @Test
    fun `une decision qui attend est persistante meme app au premier plan`() {
        // ⚠️ L'ordre est délibéré : une autorisation immobilise du travail, ce n'est pas du bruit.
        // Si le test de premier plan passait en premier, une décision en attente serait ignorée
        // dès que l'app est ouverte — et la session resterait bloquée sans signal.
        assertEquals(PushDecision.Ongoing, decideNotification(appForeground = true, pendingDecisions = 1))
        assertEquals(PushDecision.Ongoing, decideNotification(appForeground = false, pendingDecisions = 3))
    }

    @Test
    fun `app au premier plan et rien en attente ne notifie pas`() {
        // ⚠️ 2.2 — notifier ce qu'on regarde est du bruit.
        assertEquals(PushDecision.Skip, decideNotification(appForeground = true, pendingDecisions = 0))
    }

    @Test
    fun `app en arriere-plan et rien en attente notifie normalement`() {
        assertEquals(PushDecision.Transient, decideNotification(appForeground = false, pendingDecisions = 0))
    }

    @Test
    fun `une decision en attente mene a l ecran d approbation`() {
        // ⚠️ 2.5 — le tap doit mener là où l'on répond, pas au chat. Le deep link du publieur
        // n'arrive pas par le push : c'est l'app qui choisit, et son choix doit être celui-là.
        assertEquals(PushTarget.Approvals, targetFor(PushDecision.Ongoing))
    }

    @Test
    fun `une notification ordinaire ne detourne pas vers les approbations`() {
        assertEquals(PushTarget.App, targetFor(PushDecision.Transient))
    }

    @Test
    fun `le deep link session mene a une conversation`() {
        assertEquals(
            "ses_abc",
            routeFromUri("opencode", "session", listOf("ses_abc")),
        )
    }

    @Test
    fun `le deep link approve mene a la route des approbations`() {
        assertEquals(APPROVE_ROUTE, routeFromUri("opencode", "approve", emptyList()))
    }

    @Test
    fun `un schema ou un hote inconnu ne produit aucune route`() {
        assertNull(routeFromUri("https", "session", listOf("ses_abc")))
        assertNull(routeFromUri("opencode", "autre", listOf("x")))
        assertNull(routeFromUri("opencode", null, emptyList()))
    }

    @Test
    fun `un id de session vide ne produit aucune route`() {
        assertNull(routeFromUri("opencode", "session", listOf("   ")))
        assertNull(routeFromUri("opencode", "session", emptyList()))
    }
}
