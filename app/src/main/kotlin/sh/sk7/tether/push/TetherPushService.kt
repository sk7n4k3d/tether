package sh.sk7.tether.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * **Reception des notifications UnifiedPush** — le seul chemin possible ici.
 *
 * ### Pourquoi UnifiedPush et pas FCM
 * Le Pixel tourne sous GrapheneOS : **aucun Play Services, donc aucun fallback Firebase**.
 * UnifiedPush est le standard libre ou l'**application distributrice** (ici ntfy) detient la
 * connexion persistante et reveille l'app. C'est le chemin que le spike a valide
 * (`docs/spike-unifiedpush-2026-09-25.md`).
 *
 * ### Ce que ce service ne fait PAS
 * ⚠️ **Il ne fait confiance a rien.** La charge d'une notification ne porte **aucune capacite**
 * (regle S1 du brief) et le topic `up*` accepte les publications anonymes : n'importe qui
 * connaissant l'endpoint peut envoyer un message. Une notification est donc traitee comme une
 * **invitation a aller voir**, jamais comme une instruction :
 *  - elle n'embarque aucun identifiant de session exploitable pour agir ;
 *  - au tap, l'app **relit l'etat reel** depuis opencode avant d'afficher quoi que ce soit.
 *  Une fausse notification peut au pire ouvrir l'app. Rien de plus.
 *
 * ### Contrat d'implementation
 * ⚠️ Il faut **implementer [PushService]** (le service), et **PAS** declarer un
 * `MessagingReceiver` en plus : la bibliotheque fournit deja
 * `internal.MessagingReceiverImpl` et le `RaiseToForegroundService`. **Un doublon casse le
 * routage** (le receiver de la lib et le notre se disputeraient l'intent).
 * Verifie dans l'AAR 3.3.5 : son manifest declare deja WAKE_LOCK, `<queries>`, le receiver
 * et le service foreground.
 */
class TetherPushService : PushService() {

    /**
     * Nouvel endpoint : on le transmet au **pont local**, qui le publiera au serveur opencode.
     *
     * ⚠️ On ne garde pas l'endpoint pour nous : sans transmission, le serveur ne saura pas ou
     * publier et aucune notification n'arrivera. C'est le chainon qui ferme la boucle.
     */
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "nouvel endpoint (temporaire=${endpoint.temporary})")
        PushEndpointRelay.publish(applicationContext, endpoint.url)
    }

    /**
     * Message recu : on construit la notification.
     *
     * Le contenu est du texte **non fiable** : il est affiche tel quel (c'est ce qu'un humain
     * attend d'une notification), mais il n'est jamais interprete comme un ordre.
     */
    override fun onMessage(message: PushMessage, instance: String) {
        val text = message.content?.toString(Charsets.UTF_8).orEmpty()
        Log.i(TAG, "message recu (${text.length} octets)")
        notify(applicationContext, text.ifBlank { "Nouvelle activité sur opencode" })
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // ⚠️ Echec silencieux volontaire cote utilisateur : on trace, on n'interrompt pas.
        // Un push qui ne s'enregistre pas ne doit pas empecher d'utiliser l'app.
        Log.w(TAG, "enregistrement refuse : $reason")
    }

    override fun onUnregistered(instance: String) {
        Log.i(TAG, "desenregistre")
    }

    companion object {
        private const val TAG = "TetherPush"

        /** Canal de notification. Un seul : les alertes opencode sont de meme nature. */
        const val CHANNEL_ID = "opencode"

        /**
         * Cree le canal puis affiche la notification.
         *
         * ⚠️ Sur Android 13+, `POST_NOTIFICATIONS` est obligatoire : sans elle,
         * `NotificationManagerCompat.notify` **ne leve pas**, il ne se passe simplement rien.
         * On verifie donc explicitement, et on trace — un silence inexplique coute des heures.
         */
        fun notify(context: Context, text: String) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) {
                Log.w(TAG, "notifications desactivees : rien ne s'affichera")
                return
            }

            ensureChannel(context)

            // Le tap ouvre l'app. Aucune donnee n'est transportee : l'app relira l'etat.
            val intent = Intent(context, sh.sk7.tether.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(sh.sk7.tether.R.drawable.ic_launcher_foreground)
                .setContentTitle("opencode")
                .setContentText(text)
                // Texte long replie : une notification tronquee perd l'information utile.
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // Cas reel : permission refusee entre-temps par l'utilisateur.
                Log.w(TAG, "notification refusee par le systeme", e)
            }
        }

        private const val NOTIFICATION_ID = 1001

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "opencode",
                    // IMPORTANCE_DEFAULT et non HIGH : une alerte opencode informe, elle
                    // n'exige pas de reaction immediate. Le canal reste modifiable par
                    // l'utilisateur, qui peut le monter s'il le veut.
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Alertes des sessions opencode"
                },
            )
        }
    }
}

/**
 * **Transmission de l'endpoint** vers ce qui sait quoi en faire.
 *
 * ⚠️ Point d'architecture : l'endpoint doit finir **cote serveur** pour que le publieur sache
 * ou envoyer. Ce relais est volontairement une interface : l'implementation vit ailleurs (et
 * peut etre branchee plus tard sans toucher au service). Tant qu'aucun relais n'est installe,
 * on **trace** l'endpoint plutot que de le perdre en silence — c'est ce qui permet de le
 * recuperer a la main pour configurer le serveur.
 *
 * (L'endpoint n'est **pas** un secret au sens d'un mot de passe, mais il **est** une capacite
 * d'ecriture : quiconque le connait peut publier sur ce topic. On ne le journalise donc qu'au
 * niveau `info`, jamais dans un ecran ni dans un partage.)
 */
object PushEndpointRelay {

    @Volatile
    private var sink: ((String) -> Unit)? = null

    /** Installe le destinataire (le serveur, plus tard). */
    fun install(sink: (String) -> Unit) {
        this.sink = sink
    }

    fun publish(context: Context, endpoint: String) {
        sink?.invoke(endpoint) ?: Log.i(
            "TetherPush",
            "endpoint recu, aucun relais installe : $endpoint",
        )
    }
}

/**
 * Enregistre l'app aupres du distributeur UnifiedPush (ntfy).
 *
 * ⚠️ **Idempotent** : appeler cette fonction a chaque demarrage est sans effet si
 * l'enregistrement existe deja — c'est le comportement attendu par la bibliotheque.
 *
 * @return `true` si un distributeur est disponible et l'enregistrement demande.
 */
fun registerForPush(context: Context): Boolean {
    val distributors = UnifiedPush.getDistributors(context)
    if (distributors.isEmpty()) {
        Log.w("TetherPush", "aucun distributeur UnifiedPush installe")
        return false
    }
    // `registerApp` (et non `register`) : c'est la variante qui **resout le distributeur**
    // sauvegarde ou par defaut au lieu d'exiger son identifiant.
    UnifiedPush.registerApp(context, "")
    return true
}
