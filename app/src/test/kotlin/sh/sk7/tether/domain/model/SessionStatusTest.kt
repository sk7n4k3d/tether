package sh.sk7.tether.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **La fin d'un tour, telle que trois fichiers en dependent.**
 *
 * ⚠️ Cette regle etait dupliquee en prive dans `EventReducer` et absente ailleurs. La nommer sur
 * l'enum, c'est une seule lecture pour tout le monde — et un endroit ou la verifier.
 */
class SessionStatusTest {

    @Test
    fun `un tour fini l'est quelle que soit son issue`() {
        assertTrue(SessionStatus.Succeeded.isTerminal())
        // ⚠️ Un echec est **fini** : le tour ne tourne plus. Le traiter comme « en cours »
        // laisserait l'UI en attente indefiniment sur une session qui ne produira plus rien.
        assertTrue(SessionStatus.Failed.isTerminal())
        // ⚠️ Une interruption est finie aussi : le serveur ne reprendra pas ce tour.
        assertTrue(SessionStatus.Interrupted.isTerminal())
    }

    @Test
    fun `un tour en cours ne l'est pas`() {
        assertFalse(SessionStatus.Running.isTerminal())
    }

    @Test
    fun `Idle n'est PAS terminal, c'est l'absence de tour`() {
        // ⚠️ **Le cas qui a un vrai effet** : le chat marque « vu » des qu'un statut devient
        // terminal (bug B6). Si `Idle` l'etait, ouvrir une conversation ou **rien ne s'est
        // termine** la marquerait comme lue — on marquerait vu ce qu'on n'a jamais vu.
        assertFalse(SessionStatus.Idle.isTerminal())
    }

    @Test
    fun `un message optimiste se reconnait a son identifiant local`() {
        // ⚠️ C'est la marque qui repond au bug B5 : un optimiste est absent du REST, donc un
        // filtre « pas dans la fenetre serveur » le prenait pour de l'historique ancien et le
        // remontait en tete de conversation.
        assertTrue(ChatMessage(id = "local-0", role = Role.User, text = "bonjour").isOptimistic)
        assertFalse(ChatMessage(id = "msg_abc", role = Role.User, text = "bonjour").isOptimistic)
        // ⚠️ Un id d'inbox (`inbox_*`) n'est PAS optimiste : il vient du serveur, il a sa place.
        assertFalse(ChatMessage(id = "inbox_1", role = Role.User, text = "bonjour").isOptimistic)
    }
}
