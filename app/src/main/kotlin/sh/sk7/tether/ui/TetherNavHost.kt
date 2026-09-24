package sh.sk7.tether.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import sh.sk7.tether.ui.sessions.SessionListScreen
import sh.sk7.tether.ui.settings.ConnectionScreen

object Routes {
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionID}"

    fun chat(sessionID: String): String = "chat/$sessionID"
}

/**
 * Navigation de l'application : la liste des sessions et les reglages de connexion.
 *
 * L'ecran de chat (Task 1.6) est declare ici mais delegue a un ecran encore vide : il est
 * volontairement remplace par un retour, pour ne pas laisser un bouton mort dans la pile.
 */
@Composable
fun TetherNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.SESSIONS,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SESSIONS) {
            SessionListScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSession = { /* Task 1.6 */ },
            )
        }
        composable(Routes.SETTINGS) {
            ConnectionScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
