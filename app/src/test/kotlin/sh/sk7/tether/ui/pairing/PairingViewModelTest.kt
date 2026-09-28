package sh.sk7.tether.ui.pairing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sh.sk7.tether.push.PairingLink

/**
 * La regle qui empeche un ecran de consentement de **revenir apres coup**.
 *
 * Android redonne l'`Intent` de lancement a chaque recreation d'activite. Sans cette
 * regle, tourner l'ecran apres avoir appaire rejouait le traitement : la confirmation
 * revenait alors que le jeton etait consomme, sans aucun moyen d'en sortir.
 *
 * La fonction est donc pure et testee isolement — le ViewModel lui-meme a besoin de Hilt,
 * d'un `Context` et d'un serveur, et aucune de ces trois choses n'est ce qu'on veut
 * opposer a une question de `!=`.
 */
class PairingViewModelTest {

    private val demande = PairingLink.Demande("https://exemple.fr:4096", "8z5U-NzHdByXsKPjyIZU1g")

    @Test
    fun `une demande jamais vue se traite`() {
        assertTrue(demandeATraiter(demande, dejaVue = null))
    }

    @Test
    fun `la meme demande rejouee par une recreation d activite est ignoree`() {
        // Le cas de la rotation : Android redonne le meme Intent. Le traiter une seconde
        // fois renverrait l'utilisateur a un ecran de consentement pour un jeton mort.
        assertTrue(demandeATraiter(demande, dejaVue = null))
        assertFalse(demandeATraiter(demande, dejaVue = demande))
    }

    @Test
    fun `un nouveau jeton est traite meme vers le meme serveur`() {
        // Rescanner est un geste volontaire. Le refuser parce que « ca ressemble au
        // precedent » laisserait l'utilisateur sans aucune sortie.
        val rescan = demande.copy(token = "AAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(demandeATraiter(rescan, dejaVue = demande))
    }

    @Test
    fun `un nouveau serveur est traite meme avec le meme jeton`() {
        val ailleurs = demande.copy(server = "https://ailleurs.fr")
        assertTrue(demandeATraiter(ailleurs, dejaVue = demande))
    }
}
