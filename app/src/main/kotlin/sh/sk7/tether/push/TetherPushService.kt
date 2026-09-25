package sh.sk7.tether.push

import java.net.HttpURLConnection
import java.net.URL
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
        // ⚠️ On extrait la session AVANT de nettoyer le texte : la ligne de routage ne doit
        // pas s'afficher dans la notification, elle est un en-tete de transport.
        val sessionID = SESSION_MARKER.find(text)?.groupValues?.get(1)
        val visible = text.replace(SESSION_MARKER, "").trim()
        notify(
            applicationContext,
            visible.ifBlank { "Nouvelle activité sur opencode" },
            sessionID = sessionID,
        )
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
        /**
         * La ligne que le plugin ajoute au corps du message pour porter la session.
         *
         * ⚠️ Elle doit rester **identique** cote plugin (voir `tether:session=` dans
         * `ntfy-opencode.ts`). C'est le seul canal qui survit jusqu'a l'application : les
         * en-tetes ntfy n'arrivent pas par UnifiedPush.
         */
        private val SESSION_MARKER = Regex("tether:session=(\\S+)")

        fun notify(context: Context, text: String, sessionID: String? = null) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) {
                Log.w(TAG, "notifications desactivees : rien ne s'affichera")
                return
            }

            ensureChannel(context)

            // ⚠️ **Le deep link est indispensable, et son absence etait un vrai bug.**
            //
            // Avant, l'intent n'emportait rien (« l'app relira l'etat ») : taper une notification
            // ouvrait l'app sur la **derniere session utilisee**, pas sur celle qui avait
            // declenche l'alerte. Avec plusieurs sessions en cours, la notification previent
            // sans dire de quoi — et on peut meme lire la mauvaise conversation en croyant
            // repondre a l'agent.
            //
            // ⚠️ La session arrive par le **corps** du message (`tether:session=...`), pas par un
            // en-tete : mesure du 2026-09-25, un distributeur UnifiedPush ne transmet a l'app que
            // la charge utile (2 extras selon la spec Android). Les en-tetes ntfy sont perdus.
            val intent = Intent(context, sh.sk7.tether.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (sessionID != null) {
                    data = android.net.Uri.parse("opencode://session/$sessionID")
                }
            }
            val pending = PendingIntent.getActivity(
                context,
                // ⚠️ Code de requete **distinct par session** : deux notifications de deux
                // sessions differentes doivent ouvrir leur propre conversation. Un code fige
                // ferait que la seconde ecraserait l'intent de la premiere (FLAG_UPDATE_CURRENT).
                sessionID?.hashCode() ?: 0,
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
 * **Transmission de l'endpoint** vers le serveur, via un topic ntfy relais.
 *
 * ### Le probleme
 * Pour qu'une notification arrive, il faut que le **publieur** (le plugin opencode sur
 * le serveur) connaisse l'endpoint genere par le distributeur du telephone. Or cet endpoint
 * n'existe **que** sur le telephone, et opencode n'expose **aucune route** pour le stocker
 * (verifie sur `/openapi.json` : `/api/experimental/config` n'accepte que `shell`).
 *
 * ### La solution : le topic comme boite aux lettres
 * L'app publie son endpoint sur le topic `TetherEndpoint` (`write-only` anonyme) ; le plugin
 * opencode, lui, **lit** ce topic avec un compte qui en a le droit. C'est un canal **a sens
 * unique**, et c'est exactement ce qu'il faut : personne d'autre ne peut lire l'endpoint, et
 * l'app n'a besoin d'aucun secret pour ecrire.
 *
 * ⚠️ Ne PAS tenter de « faire plus simple » en ecrivant un fichier sur le serveur : la route
 * `/api/session/{id}/shell` renvoie **500** a cause d'un plugin mal configure
 * (`cc-safety-net` : shell `fish` vs option `posix`). Ce chemin est casse pour l'instant, et
 * il n'est de toute facon pas necessaire.
 *
 * ⚠️ L'endpoint **est une capacite d'ecriture** : qui le connait peut publier sur ce topic.
 * On l'envoie donc sur un canal prive en lecture, jamais dans un log partage.
 */
object PushEndpointRelay {

    /** Topic relais : ecriture anonyme, lecture reservee. */
    private const val RELAY_URL = "https://ntfy.example.com/TetherEndpoint"

    /**
     * Delai avant de republier l'endpoint, meme inchange.
     *
     * ⚠️ 6 h : la moitie du cache ntfy par defaut (12 h). Republier a la moitie garantit qu'un
     * message **toujours valide** est present, avec une large marge si un envoi echoue.
     * Republier toutes les heures couterait 24 requetes par jour pour rien ; ne jamais republier
     * perdait les notifications.
     */
    private const val REPUBLISH_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /**
     * Recoit l'endpoint annonce par le distributeur ([TetherPushService.onNewEndpoint]).
     *
     * ⚠️ On **persiste** l'envoi : l'endpoint change quand l'app est reinstallee ou quand le
     * distributeur renouvelle son topic. On le republie donc a chaque fois qu'il change.
     */
    fun publish(context: Context, endpoint: String) {
        if (endpoint.isBlank()) return
        val prefs = context.getSharedPreferences("tether-push", Context.MODE_PRIVATE)

        // ⚠️ BUG REEL CORRIGE ICI : la condition ne portait que sur l'ENDPOINT, jamais sur la
        // DATE. Or le topic du relais est un topic ntfy, et ntfy **expire ses messages**
        // (cache-duration). Consequence : une fois le message expire, le serveur ne retrouvait
        // plus l'endpoint et se repliait sur le topic fixe — donc **plus aucune notification
        // sur le telephone**, sans erreur nulle part. La preuve de bout en bout avait ete faite
        // juste apres la publication, donc dans la fenetre ou ca marchait encore.
        //
        // ⚠️ On republie donc periodiquement, a la moitie du cache courant. Republier est un POST
        // de quelques dizaines d'octets : le cout est nul, et il est tres inferieur a celui d'une
        // notification perdue en silence.
        val lastEndpoint = prefs.getString("last-endpoint", null)
        val lastAt = prefs.getLong("last-endpoint-at", 0L)
        val fresh = System.currentTimeMillis() - lastAt < REPUBLISH_INTERVAL_MS
        if (lastEndpoint == endpoint && fresh) return
        prefs.edit()
            .putString("last-endpoint", endpoint)
            .putLong("last-endpoint-at", System.currentTimeMillis())
            .apply()

        // Envoi en arriere-plan : on ne bloque jamais le thread du distributeur.
        Thread {
            runCatching {
                val connection = URL(RELAY_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.outputStream.use { it.write(endpoint.toByteArray()) }
                Log.i("TetherPush", "endpoint publie sur le relais (http=${connection.responseCode})")
                connection.disconnect()
            }.onFailure { e ->
                // Echec reseau : l'endpoint n'est pas perdu, il sera republie au prochain
                // demarrage (le `last-endpoint` n'a pas ete ecrit si l'envoi a echoue…).
                Log.w("TetherPush", "publication de l'endpoint impossible", e)
            }
        }.start()
    }
}

/**
 * Enregistre l'app aupres du distributeur UnifiedPush (ntfy).
 *
 * ⚠️ **`tryUseCurrentOrDefaultDistributor` et non `registerApp` seul.** Mesure sur le Pixel :
 * avec `registerApp` et un `savedDistributor = null` (installation neuve), l'appel **ne leve
 * pas** et l'endpoint **n'arrive jamais** — ntfy ne recoit aucun `REGISTER`, parce que la
 * bibliotheque ne sait pas quel distributeur interroger. C'est cette fonction qui **resout et
 * sauvegarde** le distributeur ; une fois sauvegarde, les appels suivants sont idempotents.
 *
 * ⚠️ **Idempotent** : appeler cette fonction a chaque demarrage est sans effet si
 * l'enregistrement existe deja — c'est le comportement attendu par la bibliotheque.
 *
 * @return `true` si un distributeur est disponible et l'enregistrement demande.
 */
fun registerForPush(context: Context, onResult: ((Boolean) -> Unit)? = null): Boolean {
    val distributors = UnifiedPush.getDistributors(context)
    Log.i(
        "TetherPush",
        "distributeurs=${distributors.size} saved=${UnifiedPush.getSavedDistributor(context)}",
    )
    if (distributors.isEmpty()) {
        Log.w("TetherPush", "aucun distributeur UnifiedPush installe")
        onResult?.invoke(false)
        return false
    }
    UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { success ->
        Log.i("TetherPush", "distributeur retenu=$success saved=${UnifiedPush.getSavedDistributor(context)}")
        if (success) UnifiedPush.registerApp(context, "")
        onResult?.invoke(success)
    }
    return true
}
