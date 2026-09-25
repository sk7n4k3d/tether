package sh.sk7.tether.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import sh.sk7.tether.push.NotificationPermissionRequest
import sh.sk7.tether.ui.chat.ChatScreen
import sh.sk7.tether.ui.sessions.SessionListScreen
import sh.sk7.tether.ui.settings.ConnectionScreen

object Routes {
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionID}"
    const val ARG_SESSION_ID = "sessionID"

    fun chat(sessionID: String): String = "chat/$sessionID"
}

/**
 * **Deep link `opencode://session/<sessionID>`** — ouvre directement une conversation.
 *
 * ### Pourquoi c'est indispensable
 * Sans lui, taper une notification ouvre la **liste** : l'utilisateur sait qu'il s'est passe
 * quelque chose mais doit retrouver la session a la main parmi 440. Une notification qui ne
 * mene pas a son sujet est une notification qu'on finit par ignorer.
 *
 * ### Le format est fige par le publieur
 * Le plugin opencode envoie `opencode://session/<id>` (en-tete `Click` de ntfy). C'est ce
 * contrat-la qu'on respecte ici — on ne le redefinit pas.
 *
 * ⚠️ On accepte l'ID **tel quel**, sans le valider contre une liste : un ID inconnu donne une
 * session vide, ce qui est un echec inoffensif. Refuser l'ouverture serait pire (l'utilisateur
 * verrait l'app ne rien faire du tout).
 */
private const val DEEP_LINK_SCHEME = "opencode"
private const val DEEP_LINK_HOST = "session"

/** Extrait l'ID de session d'un intent, ou `null` si ce n'est pas un deep link valide. */
fun sessionIDFromIntent(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    if (uri.scheme != DEEP_LINK_SCHEME || uri.host != DEEP_LINK_HOST) return null
    return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
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

    // Deep link de notification : on ouvre la session des l'arrivee de l'intent.
    //
    // ⚠️ On ecoute AUSSI `onNewIntent` : quand l'app tourne deja, Android ne recree pas
    // l'activite, il livre un nouvel intent. Sans ce deuxieme chemin, taper une notification
    // alors que Tether est en arriere-plan n'ouvrirait **rien** — le cas le plus frequent.
    val context = LocalContext.current
    val activity = remember(context) { context as? androidx.activity.ComponentActivity }
    DisposableEffect(activity, navController) {
        if (activity == null) return@DisposableEffect onDispose { }
        // Intent deja present (lancement depuis la notification).
        sessionIDFromIntent(activity.intent)?.let { sessionID ->
            navController.navigate(Routes.chat(sessionID))
        }
        val listener = androidx.core.util.Consumer<Intent> { intent ->
            sessionIDFromIntent(intent)?.let { sessionID ->
                navController.navigate(Routes.chat(sessionID))
            }
        }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

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
