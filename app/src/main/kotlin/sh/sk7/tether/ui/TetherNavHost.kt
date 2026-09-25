package sh.sk7.tether.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import sh.sk7.tether.ui.chat.ChatScreen
import sh.sk7.tether.ui.sessions.SessionListScreen
import sh.sk7.tether.ui.settings.ConnectionScreen
import sh.sk7.tether.push.NotificationPermissionRequest

object Routes {
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionID}"
    const val ARG_SESSION_ID = "sessionID"

    fun chat(sessionID: String): String = "chat/$sessionID"
}

/**
 * Navigation de l'application : liste des sessions, reglages de connexion et chat.
 */
@Composable
fun TetherNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.SESSIONS,
) {
    // Demande d'autorisation de notifier : sans elle, la chaine UnifiedPush fonctionne
    // (endpoint recu, message recu) mais rien ne s'affiche, et Android ne le dit pas.
    NotificationPermissionRequest()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.SESSIONS) {
            SessionListScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSession = { sessionID -> navController.navigate(Routes.chat(sessionID)) },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            ConnectionScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
