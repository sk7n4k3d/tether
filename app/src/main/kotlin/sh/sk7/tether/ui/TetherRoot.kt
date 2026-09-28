package sh.sk7.tether.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.sk7.tether.ui.connection.StartDestination
import sh.sk7.tether.ui.connection.StartRouterViewModel
import sh.sk7.tether.ui.theme.TetherBackground

/**
 * **L'entree de l'application : ou ouvrir, et sur quoi.**
 *
 * ### Pourquoi ce composable existe
 * `TetherNavHost` a besoin de connaitre sa destination **avant** de composer le graphe. Ce
 * composable fait la seule chose que le graphe ne peut pas faire : lire les reglages et decider.
 *
 * ⚠️ **Tant que la lecture n'est pas finie, on n'affiche rien** — un `Box` au fond de l'app, donc
 * invisible. Afficher les sessions par defaut ferait clignoter « aucun serveur configure » a chaque
 * lancement d'une app pourtant configuree, puis basculer sur l'ecran de connexion. Le cout d'une
 * frame vide est nul ; celui d'un clignotement a chaque demarrage ne l'est pas.
 *
 * ⚠️ On passe la destination **en chaine resolue**, jamais le `StartDestination` lui-meme : le
 * graphe de navigation ne doit pas dependre du type de la decision, seulement de son resultat.
 */
@Composable
fun TetherRoot() {
    val router: StartRouterViewModel = hiltViewModel()
    val destination by router.destination.collectAsStateWithLifecycle()

    when (val target = destination) {
        // Lecture en cours : fond de l'app, rien de plus.
        null -> Box(Modifier.fillMaxSize())

        StartDestination.Welcome ->
            TetherNavHost(startDestination = Routes.WELCOME, router = router)

        StartDestination.Onboarding ->
            TetherNavHost(startDestination = Routes.ONBOARDING, router = router)

        StartDestination.Sessions ->
            TetherNavHost(startDestination = Routes.SESSIONS, router = router)
    }
}
