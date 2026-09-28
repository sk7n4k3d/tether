package sh.sk7.tether.push

import java.net.HttpURLConnection
import java.net.URL
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.data.PushMessage
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

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
     * Nouvel endpoint : on le **memorise en entier**, puis on le re-declare au serveur.
     *
     * ⚠️ La memorisation est nouvelle, et c'est elle qui rend le canal Web Push possible.
     * Le serveur chiffre desormais lui-meme (RFC 8291) : il lui faut l'URL **et** la cle
     * P-256DH **et** le secret d'authentification. Ne garder que l'URL — ce que faisait
     * l'ancien chemin, parce qu'un topic ntfy ne transporte qu'un message — revenait a
     * rendre tout chiffrement impossible, sans lever la moindre erreur.
     *
     * L'ordre compte : on memorise **avant** de re-declarer, pour que l'appel emporte
     * bien les nouvelles valeurs et pas les anciennes.
     *
     * ⚠️ Aucun jeton n'est ici, et c'est voulu. Il est a usage unique, donc mort apres
     * l'appairage ; ici on n'a pas de ceremonie a refaire, seulement un endpoint de
     * remplacement a declarer. Le serveur accepte cette forme pour un appareil deja connu
     * (voir `subscribe` dans `plugin/tether/index.ts`), et refuse tout appareil inconnu.
     */
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "nouvel endpoint (temporaire=${endpoint.temporary})")
        val memorise = PushSubscription.remember(applicationContext, endpoint, instance)
        if (!memorise) {
            // Un endpoint temporaire ne vaut rien : il meurt avec le distributeur. On ne
            // le remplace donc pas par l'ancien, qui reste valide, et on ne dit rien de
            // plus — l'utilisateur n'a rien a faire tant qu'un endpoint definitif n'est pas arrive.
            Log.w(TAG, "endpoint non memorise (temporaire ou incomplet), abonnement conserve")
            return
        }
        redeclarerAupresDuServeur()
        PushEndpointRelay.publish(applicationContext, endpoint.url)
    }

    /**
     * Re-declare l'abonnement courant aupres du serveur appaire, s'il y en a un.
     *
     * ⚠️ Silencieux par conception : c'est un rattrapage, pas une action demandee. Un
     * echec ici n'a rien a afficher — l'endpoint memorise reste bon, et le prochain
     * redemarrage du distributeur retentera. Le logged suffit, et un message visible
     * pour un-channel arriere-plan alarma l'utilisateur sans raison.
     */
    private fun redeclarerAupresDuServeur() {
        val enregistrement = DeviceRegistration.load(applicationContext)
        if (enregistrement == null) {
            // Pas encore appaire : rien a re-declarer. L'ecran de confirmation fera
            // l'appel complet, jeton compris.
            return
        }
        val abonnement = PushSubscription.load(applicationContext) ?: return
        val point = EntryPointAccessors.fromApplication(applicationContext, PushEntryPoint::class.java)
        point.pushScope().coroutines.launch {
            try {
                val ok = point.gateway().registerDevice(
                    settings = point.connectionStore().current(),
                    server = enregistrement.server,
                    deviceId = point.identity().id(),
                    endpoint = abonnement.url,
                    p256dh = abonnement.p256dh,
                    authSecret = abonnement.auth,
                    // Aucun jeton : appareil deja connu. Une chaine vide vaut absence.
                    pairingToken = "",
                    distributor = abonnement.distributor,
                )
                Log.i(TAG, "re-declaration ${if (ok) "acceptee" else "refusee"} par le serveur")
            } catch (e: Exception) {
                Log.w(TAG, "re-declaration impossible : ${e.message}")
            }
        }
    }

    /**
     * Message recu : on construit la notification.
     *
     * Le contenu est du texte **non fiable** : il est affiche tel quel (c'est ce qu'un humain
     * attend d'une notification), mais il n'est jamais interprete comme un ordre.
     */
    override fun onMessage(message: PushMessage, instance: String) {
        val raw = message.content?.toString(Charsets.UTF_8).orEmpty()
        Log.i(TAG, "message recu (${raw.length} octets)")
        // ⚠️ On decode AVANT d'afficher : le corps porte des lignes de routage (`tether:session=`,
        // `tether:progress=`) qui sont un en-tete de transport, pas de l'information pour
        // l'humain. Les afficher serait du bruit, et surtout : c'est `tether:progress=` qui
        // distingue une etape d'avancement d'une fin de tour. La lire ici est ce qui evite de
        // faire sonner le telephone a chaque appel d'outil.
        val payload = parsePush(raw)
        notify(
            applicationContext,
            payload.copy(
                text = payload.text.ifBlank { Res.of(R.string.nouvelle_activite_opencode_3a7f4a) },
            ),
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

        /**
         * Delegue a [TetherNotifier], qui porte la regle de notification.
         *
         * ⚠️ Cette methode n'etait qu'un « affiche et oublie ». Elle ne savait ni si l'app etait
         * au premier plan (tache 2.2), ni si une decision attendait (tache 2.4), ni ou mener le
         * tap (tache 2.5). La logique vit desormais dans [TetherNotifier], et la **decision** dans
         * [decideNotification], testable sans Android.
         *
         * ⚠️ `payload.sessionID` vient du **corps** du message, et n'est qu'un **indice** : le
         * topic relais accepte des publications anonymes, donc rien n'empeche un tiers d'y ecrire
         * `tether:session=<n'importe quoi>`. Il n'est jamais cru sur parole — [TetherNotifier] le
         * confronte aux sessions qu'il connait deja, et ignore un identifiant inconnu.
         *
         * Mesure a l'origine : le `Click:` du plugin n'arrive jamais (UnifiedPush ne transmet que
         * la charge utile). Sans cet indice, taper une notification ouvrait la **derniere session
         * utilisee** au lieu de celle qui avait declenche l'alerte.
         */
        fun notify(context: Context, payload: PushPayload) {
            TetherNotifier.show(context, payload)
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
     * ⚠️ **3 h, et pas 6 h.** Le commentaire d'origine annoncait « la moitie du cache ntfy par
     * defaut (12 h) » — or la configuration **reelle** de ce serveur est `cache-duration: 6h`
     * (mesure : `/mnt/pools/apps/ntfy/config/server.yml` sur le TrueNAS, le 2026-09-26). Republier
     * a 6 h laissait donc une **marge nulle** : le message expirait a l'instant precis ou l'app
     * republiait, et toute publication en retard (app non rouverte, envoi echoue) vidait le relais.
     *
     * ⚠️ Consequence mesuree de ce mode d'echec : le plugin ne retrouve plus l'endpoint et se
     * replie sur le topic fixe — notif recue par le client ntfy, **pas par Tether**, sans aucune
     * erreur nulle part. Le telephone semble muet, l'app paraît en panne.
     *
     * ⚠️ Republier est un POST de quelques dizaines d'octets : le cout est nul, et tres inferieur
     * a celui d'une notification perdue en silence. On garde la moitie du cache comme regle, quelle
     * que soit sa valeur, pour que la marge survive a un changement de configuration du serveur.
     */
    const val REPUBLISH_INTERVAL_MS = 3 * 60 * 60 * 1000L

    /**
     * Recoit l'endpoint annonce par le distributeur ([TetherPushService.onNewEndpoint]).
     *
     * ⚠️ On **persiste** l'envoi : l'endpoint change quand l'app est reinstallee ou quand le
     * distributeur renouvelle son topic. On le republie donc a chaque fois qu'il change.
     */
    fun publish(context: Context, endpoint: String) {
        if (endpoint.isBlank()) return
        val prefs = context.getSharedPreferences("tether-push", Context.MODE_PRIVATE)
        val lastEndpoint = prefs.getString("last-endpoint", null)
        val lastAt = prefs.getLong("last-endpoint-at", 0L)
        val due = endpointNeedsRepublish(
            lastEndpoint = lastEndpoint,
            current = endpoint,
            lastAtMillis = lastAt,
            nowMillis = System.currentTimeMillis(),
            intervalMillis = REPUBLISH_INTERVAL_MS,
        )
        if (!due) return
        // ⚠️ On n'ecrit la date QUE si l'envoi part reellement (voir `send`).
        send(context, prefs, endpoint)
    }

    /**
     * **Republie l'endpoint deja connu**, sans en annoncer un nouveau.
     *
     * ⚠️ Pourquoi cette methode existe — c'est un mode d'echec **mesure**, pas une precaution :
     * `onNewEndpoint` n'est appele par le distributeur qu'**au demarrage du processus**. Or le
     * processus de Tether survit des heures en arriere-plan. Avec une seule publication au boot,
     * le message du relais expirait (cache ntfy) et l'endpoint disparaissait jusqu'a la prochaine
     * ouverture **a froid** — le plugin publiait alors sur un topic que Tether n'ecoute pas.
     *
     * ⚠️ Appelee quand l'app repasse au premier plan : c'est le seul moment ou l'on est sur que
     * le processus tourne sans dependre d'un reveil par le reseau. Le cout d'un appel inutile est
     * nul (la date est verifiee) ; le cout de l'oubli est un telephone muet.
     */
    fun refresh(context: Context) {
        val prefs = context.getSharedPreferences("tether-push", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("last-endpoint", null) ?: return
        val lastAt = prefs.getLong("last-endpoint-at", 0L)
        val due = endpointNeedsRepublish(
            lastEndpoint = endpoint,
            current = endpoint,
            lastAtMillis = lastAt,
            nowMillis = System.currentTimeMillis(),
            intervalMillis = REPUBLISH_INTERVAL_MS,
        )
        if (!due) return
        send(context, prefs, endpoint)
    }

    /**
     * Envoie l'endpoint, et **date l'envoi uniquement s'il a reussi**.
     *
     * ⚠️ C'est le bug que corrige le commentaire d'origine : il annoncait « le `last-endpoint`
     * n'a pas ete ecrit si l'envoi a echoue » alors que l'ecriture avait lieu **avant** l'envoi,
     * inconditionnellement. Un echec reseau laissait donc une date recente, et l'endpoint n'etait
     * pas repreublie avant l'intervalle complet — soit exactement la fenetre ou le relais se vide.
     */
    private fun send(context: Context, prefs: android.content.SharedPreferences, endpoint: String) {
        // Envoi en arriere-plan : on ne bloque jamais le thread du distributeur.
        Thread {
            runCatching {
                val connection = URL(RELAY_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.outputStream.use { it.write(endpoint.toByteArray()) }
                val code = connection.responseCode
                Log.i("TetherPush", "endpoint publie sur le relais (http=$code)")
                connection.disconnect()
                if (code in 200..299) {
                    prefs.edit()
                        .putString("last-endpoint", endpoint)
                        .putLong("last-endpoint-at", System.currentTimeMillis())
                        .apply()
                }
            }.onFailure { e ->
                // Echec reseau : l'endpoint n'est pas perdu, il sera republie au prochain
                // passage au premier plan (la date n'a pas ete ecrite).
                Log.w("TetherPush", "publication de l'endpoint impossible", e)
            }
        }.start()
    }
}

/**
 * **L'etat des notifications, tel que l'UI peut le dire sans mentir.**
 *
 * ### Pourquoi cette lecture est separee de la decision
 * L'ecran Reglages doit afficher trois faits **distincts**, et la tentation est de les fondre en
 * un seul « connecte / pas connecte » — ce qui serait faux dans la plupart des cas :
 *
 *  1. **Un distributeur est-il installe ?** (`null` = aucun). Sans distributeur, aucune
 *     notification ne peut arriver, quelle que soit la configuration : c'est le fait le plus
 *     important, et il doit se dire en clair.
 *  2. **L'app est-elle enregistree aupres de lui ?** Un distributeur installe ne signifie pas
 *     qu'un enregistrement a abouti (il peut avoir echoue, ou l'app peut avoir ete reinstallee).
 *  3. **L'endpoint est-il connu ?** C'est la preuve que la boucle est fermee : sans endpoint,
 *     le serveur opencode n'a rien a publier, meme avec un distributeur et un enregistrement.
 *
 * ⚠️ **Ne PAS confondre `savedDistributor != null` avec « enregistre ».** Mesure a l'origine du
 * projet : avec `registerApp` et un `savedDistributor` a `null` (installation neuve), l'appel ne
 * levait pas et l'endpoint n'arrivait **jamais**. Un distributeur retenu n'est donc qu'une
 * **capacite**, pas une **reussite**.
 *
 * ⚠️ L'endpoint est relu depuis le meme `SharedPreferences` que [PushEndpointRelay] : c'est la
 * valeur **reellement transmise** au serveur, donc l'etat le moins mensonger possible. Sa
 * presence prouve qu'un `onNewEndpoint` a eu lieu au moins une fois.
 */
data class PushStatus(
    /** Identifiant de package du distributeur retenu, ou `null` si aucun n'est installe. */
    val distributor: String?,
    /** Vrai si la permission `POST_NOTIFICATIONS` est accordee (vrai avant Android 13). */
    val notificationsAllowed: Boolean,
    /** L'endpoint enregistre aupres du distributeur, ou `null` si on n'en connait aucun. */
    val endpoint: String?,
) {
    /** Un distributeur est installe : condition **necessaire** a toute notification. */
    val hasDistributor: Boolean get() = distributor != null

    /**
     * La boucle est-elle fermee ?
     *
     * ⚠️ On exige **les trois** : un distributeur, la permission, et un endpoint. Deux sur trois
     * ne suffisent pas a recevoir une notification, et l'annoncer « connecte » serait exactement
     * le mensonge que le projet s'interdit.
     */
    val isReady: Boolean get() = hasDistributor && notificationsAllowed && endpoint != null
}

/** Le nom de fichier partage avec [PushEndpointRelay] : une seule source, pas deux. */
private const val PUSH_PREFS = "tether-push"

/** Duree de vie volontairement courte : l'endpoint est peu expose et change rarement. */
private const val ENDPOINT_DISPLAY_LENGTH = 24

/**
 * **Lit l'etat courant des notifications**, sans rien modifier.
 *
 * ⚠️ Fonction **pure vis-a-vis de l'exterieur** : aucune ecriture, aucun appel reseau. Elle peut
 * donc etre appelee a chaque recomposition d'ecran sans effet de bord.
 *
 * ⚠️ L'endpoint n'est **jamais** affiche en entier par l'UI (voir [PushStatus.endpoint]) : c'est
 * une capacite d'ecriture, on n'en met qu'un fragment a l'ecran (voir l'appelant). On le retourne
 * complet ici pour que l'appelant decide, mais on ne le journalise pas.
 */
fun pushStatus(context: Context): PushStatus {
    val distributor = UnifiedPush.getSavedDistributor(context)
    val allowed = notificationsAllowed(context)
    val endpoint = context
        .getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
        .getString("last-endpoint", null)
    return PushStatus(
        distributor = distributor,
        notificationsAllowed = allowed,
        endpoint = endpoint,
    )
}

/**
 * La permission de notifier est-elle accordee ?
 *
 * ⚠️ On ne demande **rien** en dessous d'Android 13 : la permission n'existe pas, et
 * `checkSelfPermission` renverrait faux a tort — l'ecran afficherait une alerte pour un probleme
 * inexistant.
 */
fun notificationsAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * **Ce qu'un enregistrement a donne.** Trois cas, pas deux.
 *
 * ⚠️ Le troisieme (`NoDistributor`) est indispensable : le confondre avec `Failed` ferait croire a
 * une erreur passagere alors qu'il manque une **application** a installer. Ce ne sont pas les
 * memes gestes, et le message doit le dire.
 */
enum class PushRegistrationResult {
    /** Un distributeur a ete retenu et l'enregistrement demande. */
    Requested,

    /** Aucun distributeur UnifiedPush n'est installe (ntfy manquant). */
    NoDistributor,

    /** Un distributeur existe mais la resolution ou l'enregistrement a echoue. */
    Failed,
}

/**
 * **Le resultat d'un enregistrement, a partir des deux seuls faits disponibles.**
 *
 * ⚠️ Fonction pure, et pas un branchement en ligne : c'est ici que se joue la distinction entre
 * « il manque une application » et « le distributeur a refuse » — deux situations que
 * `registerForPush` confond dans un seul `Boolean`. Un test la verrouille sans Android.
 */
fun registrationOutcome(hasDistributor: Boolean, success: Boolean): PushRegistrationResult = when {
    !hasDistributor -> PushRegistrationResult.NoDistributor
    success -> PushRegistrationResult.Requested
    else -> PushRegistrationResult.Failed
}

/**
 * **Enregistre l'app aupres du distributeur, et rend le resultat a l'UI.**
 *
 * ⚠️ On delegue a [registerForPush], qui porte deja la logique **mesuree** :
 * `tryUseCurrentOrDefaultDistributor` puis `registerApp`, et non `registerApp` seul (voir la
 * justification sur cette fonction). On ne duplique pas la sequence, on la nomme.
 *
 * ⚠️ **On teste le distributeur AVANT d'appeler** : `registerForPush` signale « pas de
 * distributeur » et « echec » par le meme `false`, donc l'appelant ne pourrait pas les distinguer.
 * Or ce ne sont pas les memes situations : l'une demande d'installer ntfy, l'autre de reessayer.
 * C'est [registrationOutcome] qui porte cette relecture.
 *
 * ⚠️ Le callback de la bibliotheque peut etre invoque **de facon asynchrone** ; [onResult] l'est
 * donc aussi. L'appelant doit afficher un etat « en cours » puis le resultat **reel**, jamais un
 * succes suppose.
 *
 * @param onResult appele exactement **une fois**, avec un des trois cas ci-dessus.
 */
fun requestPushRegistration(context: Context, onResult: (PushRegistrationResult) -> Unit) {
    val hasDistributor = UnifiedPush.getDistributors(context).isNotEmpty()
    if (!hasDistributor) {
        // ⚠️ On ne passe pas par `registerForPush` : il rendrait `false` et l'UI afficherait
        // « echec » alors qu'il manque une application a installer. Deux gestes differents.
        Log.w("TetherPush", "aucun distributeur UnifiedPush installe")
        onResult(registrationOutcome(hasDistributor = false, success = false))
        return
    }
    registerForPush(context) { success ->
        onResult(registrationOutcome(hasDistributor = true, success = success))
    }
}

/**
 * L'endpoint, **tronque pour l'affichage**.
 *
 * ⚠️ L'endpoint est une **capacite d'ecriture** : qui le connait peut publier sur le topic. On
 * n'en montre qu'un prefixe a l'ecran (assez pour reconnaitre qu'il existe et a change, pas assez
 * pour le reutiliser), et on ne le journalise jamais.
 */
fun PushStatus.endpointHint(): String? = endpoint?.let { value ->
    if (value.length <= ENDPOINT_DISPLAY_LENGTH) value
    else value.take(ENDPOINT_DISPLAY_LENGTH) + "…"
}

/**
 * **Ce que la section notifications doit dire, en mots — calcul pur et testable.**
 *
 * ### Pourquoi hors du composable
 * La regle « ne jamais annoncer connecte quand ca ne l'est pas » est la seule chose vraiment
 * delicate de cette section. Ecrite dans le `when` du composable, elle ne serait pas testable ;
 * ecrite ici, elle se verifie par un test unitaire sans Android. C'est la meme separation que
 * celle qui a fait sortir `decideNotification` du service (voir `PushPolicy.kt`).
 */
enum class PushTone {
    /** Tout est en place : accent teal. */
    Ready,

    /** Quelque chose manque ou a echoue : ambre. */
    Blocked,

    /** Un etat intermediaire qui n'est ni un succes ni une panne : gris. */
    Pending,
}

/**
 * Le cas structurel, pour que l'UI sache **quelle action** proposer.
 *
 * ⚠️ On ne se sert **pas** du libelle pour decider de l'action : un libelle est fait pour changer,
 * et le jour ou on le reformule, l'action proposee disparaitrait sans que rien ne le signale.
 * C'est le meme piege que de router sur un texte d'erreur.
 */
enum class PushStateKind {
    /** Aucune application distributrice installee. */
    NoDistributor,

    /** Permission `POST_NOTIFICATIONS` refusee : rien ne peut s'afficher. */
    PermissionDenied,

    /** Distributeur et permission presents, mais aucun endpoint connu. */
    AwaitingEndpoint,

    /** Tout est en place. */
    Ready,
}

/**
 * Le verdict, decompose en ce qu'on affiche.
 *
 * @param kind le cas structurel, qui decide de l'action proposee.
 * @param label l'etat, en un mot.
 * @param detail ce qui manque ou ce qui est vrai — jamais une formule vague.
 * @param tone la teinte, qui **ne remplace pas** [label] (un daltonien doit lire l'etat).
 * @param retryable faut-il proposer « Reconnecter » ? Faux quand rien ne peut aboutir.
 */
data class PushVerdict(
    val kind: PushStateKind,
    val label: String,
    val detail: String,
    val tone: PushTone,
    val retryable: Boolean,
)

/**
 * **Traduit [PushStatus] en verdict affichable.**
 *
 * ⚠️ L'ordre des cas **est** la logique : la permission manquante prime sur tout le reste, parce
 * que sans elle aucun `notify()` ne s'affiche (et Android ne dit rien). Un endpoint present ne
 * rend pas l'app « prete » si les notifications systeme sont coupees — c'est le piege que cette
 * fonction existe pour fermer.
 */
fun describePushStatus(status: PushStatus, chaine: (Int) -> String): PushVerdict = when {
    !status.hasDistributor -> PushVerdict(
        kind = PushStateKind.NoDistributor,
        label = chaine(R.string.aucun_distributeur_477e05),
        // ⚠️ On nomme l'application attendue : « aucun distributeur UnifiedPush » seul ne dit pas
        // quoi installer, et c'est **la** question que se pose l'utilisateur devant cet etat.
        detail = chaine(R.string.installe_application_distributrice_109ab1),
        tone = PushTone.Blocked,
        // Rien a reessayer tant qu'aucune application distributrice n'est installee.
        retryable = false,
    )

    !status.notificationsAllowed -> PushVerdict(
        kind = PushStateKind.PermissionDenied,
        label = chaine(R.string.notifications_bloquees_740af1),
        detail = chaine(R.string.android_bloque_notifications_e1b39d),
        tone = PushTone.Blocked,
        retryable = false,
    )

    status.endpoint == null -> PushVerdict(
        kind = PushStateKind.AwaitingEndpoint,
        label = chaine(R.string.attente_endpoint_18be57),
        // ⚠️ Un distributeur retenu et la permission accordee ne suffisent pas : sans endpoint,
        // le serveur opencode n'a rien a publier. On le dit, plutot que d'afficher « connecté ».
        detail = chaine(R.string.distributeur_encore_annonce_1d33a3),
        tone = PushTone.Pending,
        retryable = true,
    )

    else -> PushVerdict(
        kind = PushStateKind.Ready,
        label = chaine(R.string.connecte_75c661),
        detail = chaine(R.string.endpoint_enregistre_alertes_834e66),
        tone = PushTone.Ready,
        retryable = true,
    )
}

/**
 * **Ce qu'on répond à l'utilisateur après un enregistrement.**
 *
 * ⚠️ Une fonction, pas trois chaines en ligne dans le composable : le cas « pas de distributeur »
 * doit **nommer l'application à installer**, et c'est une décision de contenu qu'un test peut
 * verrouiller. Une chaine oubliée dans un `when` ne se voit pas à la relecture.
 */
fun registrationMessage(result: PushRegistrationResult): String = when (result) {
    PushRegistrationResult.Requested ->
        Res.of(R.string.enregistrement_demande_distributeur_5b9834)

    PushRegistrationResult.NoDistributor ->
        Res.of(R.string.aucun_distributeur_unifiedpush_91a0f0)

    PushRegistrationResult.Failed ->
        Res.of(R.string.enregistrement_echoue_reessaie_bcfadb)
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
