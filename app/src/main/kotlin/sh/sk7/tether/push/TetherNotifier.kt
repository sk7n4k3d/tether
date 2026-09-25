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

    /** Notification ordinaire (fin de tour). */
    private const val TRANSIENT_ID = 1001

    /**
     * ⚠️ ID **distinct** de la notification ordinaire : une alerte persistante ne doit pas être
     * écrasée par la suivante, sinon une décision en attente disparaîtrait dès le tour suivant.
     */
    private const val ONGOING_ID = 1002

    fun show(context: Context, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            // ⚠️ Sur Android 13+, sans `POST_NOTIFICATIONS`, `notify()` **ne lève pas** : il ne se
            // passe rien. On trace, sinon le silence coûte des heures de diagnostic.
            Log.w(TAG, "notifications desactivees : rien ne s'affichera")
            return
        }

        val entry = EntryPointAccessors.fromApplication(context, PushEntryPoint::class.java)
        val foreground = entry.foregroundState().isForeground
        val pending = pendingDecisions(entry)

        val decision = decideNotification(appForeground = foreground, pendingDecisions = pending)
        if (decision == PushDecision.Skip) {
            Log.i(TAG, "notification ignoree : app au premier plan")
            return
        }

        ensureChannel(context)
        // ⚠️ Le `Title` ntfy **ne survit pas** au transport UnifiedPush : le distributeur ne
        // transmet que le corps du message. On ne peut donc pas réafficher « approbation : shell ».
        // On recompose un titre à partir de ce qu'on sait **nous-mêmes** (une décision attend),
        // et on garde le corps reçu tel quel — il reste la seule information du publieur.
        val title = if (decision == PushDecision.Ongoing) {
            "Autorisation requise"
        } else {
            "opencode"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(sh.sk7.tether.R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            // Texte long replié : une notification tronquée perd l'information utile.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent(context, targetFor(decision), ONGOING_ID + 1))
            .setAutoCancel(decision != PushDecision.Ongoing)
            .setOngoing(decision == PushDecision.Ongoing)
            .setPriority(
                if (decision == PushDecision.Ongoing) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .build()

        val id = if (decision == PushDecision.Ongoing) ONGOING_ID else TRANSIENT_ID
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
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
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
}
