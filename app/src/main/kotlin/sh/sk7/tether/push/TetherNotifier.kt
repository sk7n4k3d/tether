package sh.sk7.tether.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore

/**
 * **Ce qu'il faut d'injection hors d'un point d'entrée Android.**
 *
 * ⚠️ `TetherPushService` n'est **pas** injectable : c'est un service de la bibliothèque
 * UnifiedPush, instancié par le système. On ne peut donc pas le décorer d'`@AndroidEntryPoint`
 * sans casser le contrat de la bibliothèque. Un `@EntryPoint` est **la** façon prévue par Hilt de
 * récupérer un graphe depuis un composant qu'on ne contrôle pas.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PushEntryPoint {
    fun foregroundState(): ForegroundState
    fun connectionStore(): ConnectionStore
    fun gateway(): OpenCodeGateway

    /**
     * Le detenteur d'etat vivant, pour **valider** un identifiant de session recu par push.
     *
     * ⚠️ Il n'est pas utilise pour lire l'activite ici (le push n'a pas a la connaitre), mais
     * parce qu'il est le seul endroit qui sait **quelles sessions existent**. C'est cette liste
     * qui fait d'un indice externe un identifiant acceptable.
     */
    fun activityMonitor(): sh.sk7.tether.data.activity.ActivityMonitor
}

/**
 * **Construit et affiche les notifications de Tether.**
 *
 * ### Ce que cette classe ne fait PAS
 * ⚠️ Elle **ne fait confiance à rien**. La charge reçue par UnifiedPush est du texte non fiable
 * (le topic accepte les publications anonymes) : elle est **affichée** telle quelle, mais jamais
 * interprétée comme un ordre, et n'embarque aucun identifiant de session exploitable pour agir.
 * Une fausse notification peut au pire ouvrir l'app.
 *
 * ### Pourquoi elle lit le serveur
 * Pour savoir si une **décision attend** (tâche 2.4), il faut le demander : la charge du push ne
 * le dit pas. On interroge donc `/api/permission/request`, et un échec réseau se replie sur une
 * notification ordinaire — une notification dégradée vaut mieux qu'aucune notification. Le
 * serveur reste la source de vérité, jamais le texte reçu.
 */
object TetherNotifier {

    private const val TAG = "TetherPush"

    /** Canal de notification. Un seul : les alertes opencode sont de même nature. */
    const val CHANNEL_ID = "opencode"

    /**
     * **Canal dédié à l'avancement**, en importance BASSE.
     *
     * ⚠️ Pourquoi un second canal, et pas le canal `opencode` : ce dernier est en
     * `IMPORTANCE_HIGH` (il porte les autorisations qui immobilisent une session). Y publier
     * l'avancement ferait **sonner le téléphone à chaque appel d'outil** — c'est exactement le
     * spam que la demande exclut. Un canal `LOW` s'affiche dans le tiroir **sans son ni vibration
     * ni heads-up** : l'information est là quand on regarde, elle ne dérange pas quand on ne
     * regarde pas.
     *
     * ⚠️ C'est l'utilisateur qui garde la main : il peut couper le canal `opencode-avancement`
     * dans les réglages Android sans perdre les fins de tour ni les autorisations.
     */
    const val CHANNEL_ID_PROGRESS = "opencode-avancement"

    /** Notification ordinaire (fin de tour). */
    private const val TRANSIENT_ID = 1001

    /**
     * ⚠️ ID **distinct** de la notification ordinaire : une alerte persistante ne doit pas être
     * écrasée par la suivante, sinon une décision en attente disparaîtrait dès le tour suivant.
     */
    private const val ONGOING_ID = 1002

    /**
     * **Les sessions que l'app connait deja**, pour valider un indice de notification.
     *
     * ⚠️ C'est la seule source acceptable : le detenteur d'etat les a lues du serveur. Un
     * identifiant qui n'y figure pas vient forcement d'ailleurs — et on ne l'ouvre pas.
     *
     * ⚠️ On lit la valeur **ponctuellement** (`.value`) plutot que de collecter : ce code
     * s'execute dans un service de push, sans cycle de vie de composition, et n'a besoin que d'un
     * instantane au moment d'afficher.
     */
    private fun knownSessionIDs(entry: PushEntryPoint): Set<String> =
        runCatching { entry.activityMonitor().state.value.bySession.keys }.getOrDefault(emptySet())

    fun show(context: Context, payload: PushPayload) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            // ⚠️ Sur Android 13+, sans `POST_NOTIFICATIONS`, `notify()` **ne lève pas** : il ne se
            // passe rien. On trace, sinon le silence coûte des heures de diagnostic.
            Log.w(TAG, "notifications desactivees : rien ne s'affichera")
            return
        }

        val entry = EntryPointAccessors.fromApplication(context, PushEntryPoint::class.java)

        // ⚠️ Le nettoyage de l'avancement a lieu **ici, avant le test de premier plan**, et c'est
        // le sujet d'un bug mesure : place apres le `return` de `Skip`, il ne s'executerait jamais
        // quand l'app est ouverte. Scenario reel : l'agent travaille, l'etape s'affiche, Bastien
        // ouvre l'app, le tour se termine — la fin de tour est ignoree (il regarde l'ecran) ET
        // l'etape resterait affichee **pour toujours**, a dire « shell : npm install » d'un travail
        // fini. C'est un mensonge, et il est permanent.
        if (shouldClearProgress(payload.kind)) clearProgress(context, payload.sessionID)

        val foreground = entry.foregroundState().isForeground
        val pending = pendingDecisions(entry)

        val decision = decideNotification(
            appForeground = foreground,
            pendingDecisions = pending,
            kind = payload.kind,
        )
        if (decision == PushDecision.Skip) {
            Log.i(TAG, "notification ignoree : app au premier plan")
            return
        }

        ensureChannel(context)
        // ⚠️ Le `Title` ntfy **ne survit pas** au transport UnifiedPush : le distributeur ne
        // transmet que le corps du message. On ne peut donc pas réafficher « approbation : shell ».
        // On recompose un titre à partir de ce qu'on sait **nous-mêmes** (une décision attend),
        // et on garde le corps reçu tel quel — il reste la seule information du publieur.
        val title = when (decision) {
            PushDecision.Ongoing -> "Autorisation requise"
            PushDecision.Progress -> "opencode — en cours"
            else -> "opencode"
        }

        // ⚠️ Seul l'avancement change de canal. Une fin de tour et une autorisation gardent le
        // canal `opencode` : leurs reglages, leur son et leur importance ne bougent pas.
        val channel = if (decision == PushDecision.Progress) CHANNEL_ID_PROGRESS else CHANNEL_ID

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(sh.sk7.tether.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(payload.text)
            // Texte long replié : une notification tronquée perd l'information utile.
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.text))
            .setContentIntent(
                pendingIntent(
                    context,
                    // ⚠️ Une decision en attente prime : c'est l'ecran ou l'on repond. Sinon, on
                    // ouvre la session **si elle est reconnue**, et l'app sinon.
                    targetFor(decision, payload.sessionID?.takeIf { it in knownSessionIDs(entry) }),
                    ONGOING_ID + 1,
                ),
            )
            // ⚠️ Une fin de tour se balaie au tap ; une autorisation reste ; un avancement se
            // laisse balayer (il n'attend rien de l'utilisateur) et **ne vibre pas**.
            .setAutoCancel(decision != PushDecision.Ongoing)
            .setOngoing(decision == PushDecision.Ongoing)
            .setSilent(decision == PushDecision.Progress)
            .setPriority(
                when (decision) {
                    PushDecision.Ongoing -> NotificationCompat.PRIORITY_HIGH
                    PushDecision.Progress -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .build()

        val id = when (decision) {
            PushDecision.Ongoing -> ONGOING_ID
            PushDecision.Progress -> progressNotificationId(payload.sessionID)
            else -> TRANSIENT_ID
        }
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Cas réel : permission refusée entre-temps par l'utilisateur.
            Log.w(TAG, "notification refusee par le systeme", e)
        }
    }

    /**
     * **Retire l'alerte persistante** une fois la décision prise.
     *
     * ⚠️ Sans cet appel, une notification `ongoing` ne peut **pas** être balayée par l'utilisateur
     * et resterait à l'écran alors que plus rien n'attend — l'inverse du service rendu. C'est
     * l'écran d'approbations qui l'appelle quand sa file se vide.
     */
    fun clearOngoing(context: Context) {
        NotificationManagerCompat.from(context).cancel(ONGOING_ID)
    }

    /**
     * **Retire la notification d'avancement** d'une session.
     *
     * ⚠️ Sans cet appel, la derniere etape franchie resterait dans le tiroir a cote de la fin de
     * tour. L'utilisateur lirait « shell : npm install » **et** « termine » en meme temps, deux
     * messages contradictoires dont l'un est perime — exactement ce que la regle « ne jamais
     * mentir » interdit.
     *
     * ⚠️ On cible l'ID de **cette** session (voir [progressNotificationId]) : un `cancel` global
     * effacerait l'avancement d'une autre tache encore vivante.
     */
    fun clearProgress(context: Context, sessionID: String? = null) {
        NotificationManagerCompat.from(context).cancel(progressNotificationId(sessionID))
    }

    /**
     * **Identifiant de la notification de test.**
     *
     * ⚠️ Distinct de [TRANSIENT_ID] et [ONGOING_ID] : un test ne doit **jamais** écraser une vraie
     * alerte opencode, ni être écrasé par elle. Sinon, tester les notifications pourrait faire
     * disparaître une décision qui attend.
     */
    private const val TEST_ID = 1003

    /**
     * **Affiche une notification de test, volontairement, même app au premier plan.**
     *
     * ### Pourquoi une fonction séparée, et pas un paramètre de [show]
     * ⚠️ [show] applique la règle « ne pas notifier ce qu'on regarde » (tâche 2.2) : appelée
     * depuis les Réglages, elle retournerait `Skip` et **rien ne s'afficherait**. Le bouton
     * « Tester la notification » ferait alors semblant de marcher — exactement le « contrôle qui
     * ne peut pas fonctionner » que le projet s'interdit. Ici on **veut** afficher : c'est un test
     * explicite, l'utilisateur regarde l'écran et demande la notification.
     *
     * ⚠️ Ce chemin **ne contourne que** la suppression au premier plan. Il ne saute pas la
     * vérification de permission : sans elle, `notify()` ne lève pas et rien ne s'affiche. On rend
     * donc le fait, pour que l'UI puisse le dire au lieu de rester muette.
     *
     * ⚠️ On réutilise [ensureChannel] et le même canal : tester sur un canal différent ne
     * prouverait rien sur les vraies notifications.
     *
     * @return `true` si une notification a été remise au système, `false` si les notifications
     *   sont désactivées ou refusées — jamais un succès supposé.
     */
    fun showTest(context: Context, text: String): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "test de notification impossible : notifications desactivees")
            return false
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(sh.sk7.tether.R.drawable.ic_launcher_foreground)
            .setContentTitle("Notification de test")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent(context, PushTarget.App, TEST_ID))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            manager.notify(TEST_ID, notification)
            true
        } catch (e: SecurityException) {
            // Cas réel : permission refusée entre-temps par l'utilisateur.
            Log.w(TAG, "test de notification refuse par le systeme", e)
            false
        }
    }

    /**
     * Compte les décisions en attente, ou `0` si on ne peut pas le savoir.
     *
     * ⚠️ Un échec donne **0**, donc une notification ordinaire : on ne bloque pas une alerte sur
     * une lecture réseau. On ne prétend simplement pas qu'une décision attend quand on n'a pas pu
     * le vérifier.
     */
    private fun pendingDecisions(entry: PushEntryPoint): Int = runCatching {
        runBlocking {
            // ⚠️ Borné : on bloque le thread du distributeur, pas l'utilisateur, mais une borne
            // évite qu'un serveur lent retienne le callback système pendant la durée du timeout
            // Ktor (20 s). Au-delà, on considère qu'on ne sait pas — donc notification ordinaire.
            kotlinx.coroutines.withTimeoutOrNull(PENDING_DECISION_TIMEOUT_MS) {
                val settings = entry.connectionStore().current()
                if (!settings.isConfigured) return@withTimeoutOrNull 0
                entry.gateway().pendingPermissions(settings).size
            } ?: 0
        }
    }.getOrDefault(0)

    /** 3 s : bien plus que le temps de réponse d'un serveur local, bien moins qu'une gêne. */
    private const val PENDING_DECISION_TIMEOUT_MS = 3_000L

    /**
     * **La destination du tap, en vrai `PendingIntent`.**
     *
     * ⚠️ On utilise un intent **explicite** vers `MainActivity` portant le deep link en `data` :
     * l'activité est `singleTop` et son `onNewIntent` lit déjà `intent.data` (voir `TetherNavHost`).
     * C'est ce qui permet à `opencode://approve` de mener à l'écran d'approbation sans ajouter de
     * filtre d'intent — un filtre `approve` exposerait aussi l'app à des liens externes.
     *
     * ⚠️ `requestCode` **distinct par cible** : `PendingIntent` identifie ses instances par
     * (requestCode, intent). Même code et même intent = même PendingIntent réutilisé, et la
     * dernière cible écraserait la précédente.
     */
    private fun pendingIntent(context: Context, target: PushTarget, requestCode: Int): PendingIntent {
        val data = when (target) {
            is PushTarget.Session ->
                android.net.Uri.parse("$DEEP_LINK_SCHEME://$DEEP_LINK_HOST_SESSION/${target.sessionID}")
            PushTarget.Approvals -> android.net.Uri.parse(APPROVE_ROUTE)
            PushTarget.App -> null
        }
        val intent = Intent(context, sh.sk7.tether.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data?.let { this.data = it }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "opencode",
                    // ⚠️ IMPORTANCE_HIGH et non DEFAULT : le canal porte désormais **aussi** les
                    // décisions en attente, qui immobilisent une session. Un canal muet laisserait
                    // une autorisation attendre des heures. L'utilisateur peut le baisser, mais le
                    // défaut doit servir le cas qui compte.
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alertes des sessions opencode"
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ID_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_PROGRESS,
                    "opencode — avancement",
                    // ⚠️ IMPORTANCE_LOW et non HIGH, et c'est tout l'anti-spam : l'avancement
                    // s'affiche dans le tiroir **sans son, sans vibration, sans heads-up**. Un
                    // agent qui enchaîne dix appels d'outil reste alors silencieux tout en laissant
                    // une trace lisible — ce que la demande veut exactement.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Étapes intermédiaires d'une session opencode (silencieux)"
                    setShowBadge(false)
                },
            )
        }
    }
}
