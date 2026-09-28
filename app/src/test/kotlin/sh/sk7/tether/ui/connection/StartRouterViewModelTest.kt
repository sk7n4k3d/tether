package sh.sk7.tether.ui.connection

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Ou l'app s'ouvre.**
 *
 * Trois destinations pour deux faits, et c'est cette table qui les separe. Une erreur ici
 * ne se voit pas a la compilation : elle se voit chez quelqu'un qui reinstalle l'app et
 * qui retombe sur une presentation qu'il a deja lue, ou pire — sur un formulaire de
 * connexion alors que ses reglages sont intacts.
 *
 * ⚠️ Ce qui est teste est la **decision**, pas le ViewModel : celui-ci lit deux flux et
 * appelle la fonction ci-dessous. Le tester demanderait Hilt, un `Context` et un serveur,
 * pour verifier une table de quatre lignes.
 */
class StartRouterViewModelTest {

    @Test
    fun `un serveur configure ouvre sur les sessions`() {
        assertEquals(
            StartDestination.Sessions,
            destinationDeDepart(configure = true, accueilVu = true),
        )
    }

    @Test
    fun `un serveur configure n impose pas l accueil, meme jamais vu`() {
        // Le cas de celui qui reinstalle l'app : ses reglages sont conserves, mais le
        // drapeau « accueil vu » vit dans le meme fichier — il peut donc arriver absent
        // si l'utilisateur a vide une partie des donnees. Lui remontrer le carrousel
        // avant ses sessions serait une regression, pas une aide.
        assertEquals(
            StartDestination.Sessions,
            destinationDeDepart(configure = true, accueilVu = false),
        )
    }

    @Test
    fun `rien de configure et jamais vu ouvre sur le carrousel`() {
        // Le vrai premier lancement.
        assertEquals(
            StartDestination.Welcome,
            destinationDeDepart(configure = false, accueilVu = false),
        )
    }

    @Test
    fun `rien de configure mais accueil deja vu va droit a la connexion`() {
        // Deux chemins menent ici, et les deux comptent : quelqu'un qui a passe le
        // carrousel et n'a pas encore saisi ses identifiants, et quelqu'un dont la
        // connexion a echoue puis qui a quitte l'app. Revoir la presentation dans le
        // second cas serait le pire moment — il cherche un formulaire.
        assertEquals(
            StartDestination.Onboarding,
            destinationDeDepart(configure = false, accueilVu = true),
        )
    }
}
