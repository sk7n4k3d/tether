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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.sk7.tether.push.APPROVE_ROUTE
import sh.sk7.tether.push.PairingLink
import sh.sk7.tether.push.NotificationPermissionRequest
import sh.sk7.tether.push.routeFromUri
import sh.sk7.tether.ui.chat.ChatScreen
import sh.sk7.tether.ui.pairing.PairingScreen
import sh.sk7.tether.ui.pairing.PairingViewModel
import sh.sk7.tether.ui.permissions.PermissionsScreen
import sh.sk7.tether.ui.scanner.QrScannerScreen
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
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

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
     * Confirmation d'appairage : ce serveur veut enregistrer cet appareil.
     *
     * ⚠️ C'est une route **sans argument**. Le jeton d'appairage et l'adresse du serveur ne
     * voyagent **pas** dans la route : ce sont des donnees d'un tiers (un tiers a forge le
     * QR), et les routes de navigation finissent dans l'historique, les logs de crash et
     * `savedInstanceState`. Ils restent dans le [PairingViewModel], qui lui ne survit qu'a
     * l'activite — ce qui est exactement la duree de vie d'un consentement.
     */
    const val PAIRING = "pairing"

    /**
     * Le scanner du QR d'appairage, ouvert depuis [PAIRING].
     *
     * ⚠️ Une route a part, et pas un etat de [PAIRING] : la camera a un cycle de vie, et
     * ouvrir le scanner doit **arreter** l'analyse en la quittant. Un booleen dans
     * l'ecran d'appairage aurait laisse la camera tourner derriere la confirmation.
     */
    const val SCAN = "scan"

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
 * **Extrait la destination d'un intent**, ou `null` si ce n'est pas un deep link valide.
 *
 * ### Deux cibles
 *  - `opencode://session/<sessionID>` — ouvrir une conversation (format figé par le publieur) ;
 *  - `opencode://approve` — ouvrir les approbations (tâche 2.5), cible des notifications de
 *    décision construites par l'app.
 *
 * ### Pourquoi c'est indispensable
 * Sans deep link, taper une notification ouvre la **liste** : l'utilisateur sait qu'il s'est passé
 * quelque chose mais doit retrouver la session à la main parmi 440. Une notification qui ne mène
 * pas à son sujet est une notification qu'on finit par ignorer.
 *
 * ⚠️ **Correction mesurée (2026-09-25)** : le `Click` du plugin (`opencode://session/<id>`) ne
 * peut **pas** arriver par le push — le distributeur UnifiedPush ne transmet que le message et
 * l'instance, jamais les en-têtes ntfy. Ce format reste correct pour tout ce qui ouvre l'app depuis
 * ailleurs, mais c'est désormais **l'app** qui choisit sa destination (voir
 * [sh.sk7.tether.push.targetFor]).
 *
 * ⚠️ On accepte l'ID **tel quel**, sans le valider contre une liste : un ID inconnu donne une
 * session vide, échec inoffensif. Refuser l'ouverture serait pire — l'utilisateur verrait l'app ne
 * rien faire du tout.
 *
 * ⚠️ On retourne une **route de navigation**, pas seulement un id : c'est ce qui permet au deep
 * link de mener à un écran autre que le chat.
 */
fun routeFromIntent(intent: Intent?): String? {
    val uri = intent?.data ?: return null
    val route = routeFromUri(uri.scheme, uri.host, uri.pathSegments) ?: return null
    return if (route == APPROVE_ROUTE) Routes.PERMISSIONS else Routes.chat(route)
}

/**
 * La demande d'appairage portee par cet intent, ou `null`.
 *
 * ### Pourquoi une fonction separee de [routeFromIntent]
 *
 * [routeFromIntent] ne voit que `pathSegments`, et l'appairage vit dans la **query** :
 * `opencode://pair?s=<serveur>&t=<jeton>`. La faire passer par la meme fonction
 * obligerait a y melanger deux formes de liens sans rapport — et surtout a y encoder la
 * validation d'un contenu tiers dans une fonction qui, elle, ne fait que router.
 *
 * L'**analyse** reste dans [PairingLink], en pur et testee sur la JVM ; il n'y a ici que
 * l'extraction Android (`getQueryParameter`, qui sait decoder le percent-encoding).
 *
 * ⚠️ On renvoie `null` indistinctement pour « pas un lien d'appairage » et « lien
 * d'appairage invalide ». Distinguer les deux offrirait un oracle sur ce que l'app
 * accepte, et ne changerait rien pour l'utilisateur.
 */
fun pairingFromIntent(intent: Intent?): PairingLink.Demande? {
    val uri = intent?.data ?: return null
    return PairingLink.depuisUri(uri.scheme, uri.host, uri.query)
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
    val pairingViewModel: PairingViewModel = hiltViewModel()

    // Un lien d'appairage prime sur tout le reste : il porte une demande de consentement
    // posee par un tiers, et elle expire. Si on la laissait perdre derriere un route
    // normale, l'utilisateur devrait rescaner.
    fun router(intent: Intent?) {
        val demande = pairingFromIntent(intent)
        if (demande != null) {
            // `ouvrir` refuse une demande deja traitee : ne pas naviguer alors evite
            // de repousser l'utilisateur sur l'ecran de confirmation a chaque recreation.
            if (pairingViewModel.ouvrir(demande)) {
                navController.navigate(Routes.PAIRING) { launchSingleTop = true }
            }
            return
        }
        routeFromIntent(intent)?.let { route -> navController.navigate(route) }
    }

    /**
     * **Une fois connecte, on atterrit sur les sessions.**
     *
     * Deux chemins y menent : la premiere configuration ([Routes.ONBOARDING]) et un
     * appairage reussi ([Routes.PAIRING]). Aucun ne doit laisser l'utilisateur sur
     * l'ecran qui vient de se terminer.
     *
     * ⚠️ On **remplace** la pile au lieu de revenir en arriere. `popBackStack()` ne faisait
     * rien : l'ecran de connexion est la route de depart au premier lancement, et la seule
     * de la pile apres une deconnexion — il n'y avait donc rien a depiler, et l'app restait
     * bloquee sur un ecran deja valide. Revenir sur une connexion faite n'a de toute facon
     * aucun sens.
     */
    fun repartirSurLesSessions() {
        navController.navigate(Routes.SESSIONS) {
            popUpTo(navController.graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
    }

    DisposableEffect(activity, navController) {
        if (activity == null) return@DisposableEffect onDispose { }
        // Intent deja present (lancement depuis la notification ou le QR).
        router(activity.intent)
        val listener = androidx.core.util.Consumer<Intent> { intent -> router(intent) }
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
            ScreenScaffold(title = stringResource(R.string.modifications_405f45), onBack = { navController.popBackStack() }) {
                DiffScreen()
            }
        }
        composable(Routes.DIFF_NO_SESSION) {
            ScreenScaffold(title = stringResource(R.string.modifications_405f45), onBack = { navController.popBackStack() }) {
                DiffScreen()
            }
        }
        composable(
            route = Routes.CONTEXT,
            arguments = listOf(navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType }),
        ) {
            ScreenScaffold(title = stringResource(R.string.contexte_8112e9), onBack = { navController.popBackStack() }) {
                SessionContextScreen()
            }
        }
        composable(Routes.STATS) {
            ScreenScaffold(title = stringResource(R.string.statistiques_fdce30), onBack = { navController.popBackStack() }) {
                StatsScreen()
            }
        }
        composable(Routes.SERVER) {
            ScreenScaffold(title = stringResource(R.string.serveur_970701), onBack = { navController.popBackStack() }) {
                ServerScreen()
            }
        }
        composable(Routes.PERMISSIONS) {
            ScreenScaffold(title = stringResource(R.string.approbations_77db64), onBack = { navController.popBackStack() }) {
                PermissionsScreen()
            }
        }
        composable(Routes.PAIRING) {
            // ⚠️ Pas de `ScreenScaffold` a retour : l'appairage s'ouvre sur un **scan**, donc
            // depuis n'importe quelle position de la pile. Un « retour » ici ramenerait a une
            // conversation en cours, ce qui donne l'impression d'avoir ete ejecte. Refuser
            // ferme, et c'est la seule sortie.
            val state by pairingViewModel.state.collectAsStateWithLifecycle()

            // L'appairage est enregistre : on quitte l'ecran de confirmation pour les
            // sessions. Le drapeau est consomme d'abord, sinon la recomposition suivante
            // relancerait la navigation.
            LaunchedEffect(state.appaire) {
                if (!state.appaire) return@LaunchedEffect
                pairingViewModel.appairageConsomme()
                repartirSurLesSessions()
            }

            PairingScreen(
                state = state,
                onAuthorize = pairingViewModel::autoriser,
                onRefuse = {
                    pairingViewModel.refuser()
                    navController.popBackStack()
                },
                onScan = { navController.navigate(Routes.SCAN) },
            )
        }
        composable(Routes.SCAN) {
            // Le scanner rend une demande validee, jamais un texte brut : un QR d'un autre
            // format est ignore par l'ecran lui-meme. On revient sur la confirmation, qui
            // affiche l'adresse du serveur avant tout envoi.
            QrScannerScreen(
                onLien = { demande ->
                    pairingViewModel.ouvrir(demande)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            ScreenScaffold(title = stringResource(R.string.propos_5345ad), onBack = { navController.popBackStack() }) {
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
                onOpenPairing = { navController.navigate(Routes.PAIRING) },
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
            ScreenScaffold(title = stringResource(R.string.arbres_travail_e006dc), onBack = { navController.popBackStack() }) {
                WorktreeScreen()
            }
        }
        composable(Routes.FILES) {
            ScreenScaffold(title = stringResource(R.string.fichiers_23a9d9), onBack = { navController.popBackStack() }) {
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
                onConnected = { repartirSurLesSessions() },
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
                            contentDescription = stringResource(R.string.retour_e5befb),
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
