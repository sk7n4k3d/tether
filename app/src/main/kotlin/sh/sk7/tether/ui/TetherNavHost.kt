package sh.sk7.tether.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import sh.sk7.tether.push.NotificationPermissionRequest
import sh.sk7.tether.ui.chat.ChatScreen
import sh.sk7.tether.ui.permissions.PermissionsScreen
import sh.sk7.tether.ui.server.ServerScreen
import sh.sk7.tether.ui.sessions.SessionListScreen
import sh.sk7.tether.ui.settings.AboutScreen
import sh.sk7.tether.ui.settings.ConnectionScreen
import sh.sk7.tether.ui.settings.SettingsScreen
import sh.sk7.tether.ui.connection.OfflineScreen
import sh.sk7.tether.ui.worktree.WorktreeScreen
import sh.sk7.tether.ui.connection.StartRouterViewModel
import sh.sk7.tether.ui.diff.DiffScreen
import sh.sk7.tether.ui.context.SessionContextScreen
import sh.sk7.tether.ui.stats.StatsScreen
import sh.sk7.tether.ui.theme.TetherTextPrimary

object Routes {
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionID}"
    const val ARG_SESSION_ID = "sessionID"

    /** Statistiques d'usage : cout, tokens, activite, repartition par modele. */
    const val STATS = "stats"

    /** Inventaire du serveur : MCP, plugins, skills, commandes, permissions. */
    const val SERVER = "server"

    /** Demandes d'autorisation en attente. */
    const val PERMISSIONS = "permissions"

    /** A propos, diagnostics, licence. */
    const val ABOUT = "about"

    /** Connexion, en mode premiere ouverture (sans retour, avec guidage). */
    const val ONBOARDING = "onboarding"

    /** Arbres de travail isoles : essayer sans risquer le depot. */
    const val WORKTREES = "worktrees"

    /** Explorateur de fichiers : verifier un chemin avant de l'envoyer. */
    const val FILES = "files"

    /**
     * Hors connexion : le serveur ne repond pas, on explique et on propose d'agir.
     *
     * ⚠️ C'est une **route a part entiere**, pas un bandeau : quand le serveur est injoignable, il
     * n'y a rien d'autre a montrer — une liste vide avec un bandeau ferait croire a une absence de
     * sessions, alors que la verite est qu'on n'a pas pu demander.
     */
    const val OFFLINE = "offline"

    /**
     * Diffs d'une session : ce que l'agent a reellement change.
     *
     * ⚠️ La session est un **argument optionnel** : le meme ecran sert aussi a regarder le depot
     * seul (modifications non commitees, ecart de branche, derniers commits). Sans session, la
     * portee « Session » n'est simplement pas proposee.
     */
    const val DIFF = "diff?sessionID={sessionID}"
    const val DIFF_NO_SESSION = "diff"

    /** Contexte d'une session : ce qui occupe la fenetre, et ce que ca coute. */
    const val CONTEXT = "context/{sessionID}"

    fun chat(sessionID: String): String = "chat/$sessionID"

    fun diff(sessionID: String): String = "diff?sessionID=$sessionID"

    fun context(sessionID: String): String = "context/$sessionID"
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
    /**
     * La destination d'ouverture, decidee par [StartRouterViewModel].
     *
     * ⚠️ `null` = la lecture des reglages n'est pas finie. On n'affiche alors **rien** plutot
     * qu'un ecran par defaut : ouvrir sur les sessions avant de savoir si l'app est configuree
     * ferait apparaitre « aucun serveur configure » pendant une fraction de seconde a chaque
     * lancement, puis basculer sur l'ecran de connexion — un clignotement a chaque demarrage.
     */
    startDestination: String?,
    @Suppress("UNUSED_PARAMETER") router: StartRouterViewModel = hiltViewModel(),
) {
    // ⚠️ Tant que la decision n'est pas prise, on ne compose pas de NavHost. Le cout est une frame
    // vide, invisible, contre un clignotement systematique.
    if (startDestination == null) return
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
                onOpenStats = { navController.navigate(Routes.STATS) },
                onOpenServer = { navController.navigate(Routes.SERVER) },
                onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                onOpenOffline = { navController.navigate(Routes.OFFLINE) },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) {
            val sessionID = it.arguments?.getString(Routes.ARG_SESSION_ID).orEmpty()
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenDiff = { navController.navigate(Routes.diff(sessionID)) },
                onOpenContext = { navController.navigate(Routes.context(sessionID)) },
            )
        }
        composable(
            route = Routes.DIFF,
            arguments = listOf(
                navArgument(Routes.ARG_SESSION_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ScreenScaffold(title = "Modifications", onBack = { navController.popBackStack() }) {
                DiffScreen()
            }
        }
        composable(Routes.DIFF_NO_SESSION) {
            ScreenScaffold(title = "Modifications", onBack = { navController.popBackStack() }) {
                DiffScreen()
            }
        }
        composable(
            route = Routes.CONTEXT,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) {
            ScreenScaffold(title = "Contexte", onBack = { navController.popBackStack() }) {
                SessionContextScreen()
            }
        }
        composable(Routes.STATS) {
            ScreenScaffold(title = "Statistiques", onBack = { navController.popBackStack() }) {
                StatsScreen()
            }
        }
        composable(Routes.SERVER) {
            ScreenScaffold(title = "Serveur", onBack = { navController.popBackStack() }) {
                ServerScreen()
            }
        }
        composable(Routes.PERMISSIONS) {
            ScreenScaffold(title = "Approbations", onBack = { navController.popBackStack() }) {
                PermissionsScreen()
            }
        }
        composable(Routes.ABOUT) {
            ScreenScaffold(title = "À propos", onBack = { navController.popBackStack() }) {
                AboutScreen()
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenServer = { navController.navigate(Routes.SERVER) },
                onOpenStats = { navController.navigate(Routes.STATS) },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
                onOpenWorktrees = { navController.navigate(Routes.WORKTREES) },
                onOpenFiles = { navController.navigate(Routes.FILES) },
                onDisconnected = {
                    navController.navigate(Routes.ONBOARDING) {
                        // ⚠️ On vide la pile : apres une deconnexion, revenir en arriere ne doit
                        // pas ramener sur des ecrans qui exigent une connexion.
                        popUpTo(Routes.SESSIONS) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.WORKTREES) {
            ScreenScaffold(title = "Arbres de travail", onBack = { navController.popBackStack() }) {
                WorktreeScreen()
            }
        }
        composable(Routes.FILES) {
            ScreenScaffold(title = "Fichiers", onBack = { navController.popBackStack() }) {
                sh.sk7.tether.ui.files.FilesScreen()
            }
        }
        composable(Routes.OFFLINE) {
            OfflineScreen(
                onOpenSettings = {
                    navController.navigate(Routes.ONBOARDING) {
                        // ⚠️ Depuis l'ecran hors-connexion, les reglages remplacent la pile : y
                        // revenir apres avoir corrige l'adresse n'a pas de sens.
                        popUpTo(Routes.OFFLINE) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ONBOARDING) {
            ConnectionScreen(
                firstRun = true,
                onConnected = { navController.popBackStack() },
            )
        }
    }
}

/**
 * **Le cadre commun des ecrans secondaires** : titre, retour, fond.
 *
 * ⚠️ Il existe pour une raison de coherence, pas de commodite : six ecrans secondaires ecrits
 * separement finiraient par avoir six tailles de titre, deux styles de retour et des fonds
 * legerement differents. Une app dont les ecrans ne se ressemblent pas **parait inachevee**
 * meme quand chacun est correct isolement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Lucide.ArrowLeft,
                            contentDescription = "Retour",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = TetherTextPrimary,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            content()
        }
    }
}
