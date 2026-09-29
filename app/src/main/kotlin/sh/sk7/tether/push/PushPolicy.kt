package sh.sk7.tether.push

import sh.sk7.tether.domain.model.PermissionDecision
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **Faut-il afficher une notification, et de quelle nature ?**
 *
 * ### Pourquoi cette décision est une fonction pure
 * Elle combine trois faits (premier plan, décision en attente, contenu) pour produire un choix.
 * Écrite en ligne dans le service, elle serait **intestable** — or c'est précisément la logique
 * qui détermine si l'app est du bruit ou un instrument. Le service, lui, ne fait que l'exécuter.
 */
enum class PushDecision {
    /** Rien à afficher : l'utilisateur regarde déjà l'app. */
    Skip,

    /** Notification ordinaire, qui disparaît au tap. */
    Transient,

    /**
     * Notification **persistante** tant qu'une décision attend.
     *
     * ⚠️ Raison d'être : une notification qui disparaît au premier balayage laisse la session
     * **bloquée des heures** sans que personne ne le sache. Tant que l'agent attend une
     * autorisation, l'alerte doit rester à l'écran — c'est le seul état de l'app qui immobilise
     * du travail.
     */
    Ongoing,

    /**
     * **Avancement d'un tour** : une étape significative vient d'être franchie (appel d'outil,
     * début de réflexion). Ce n'est pas une fin de tour.
     *
     * ⚠️ Pourquoi un cas à part, et pas un `Transient` comme les autres : une fin de tour doit
     * **sonner**, un avancement non. Sans cette distinction, l'agent qui enchaîne dix appels
     * d'outil ferait vibrer le téléphone dix fois — exactement le spam que la demande exclut.
     * Le contenu, lui, vient du flux SSE (`session.tool.called`), donc il dit ce qui se passe
     * vraiment ; c'est seulement l'**alerte** qui est réduite au silence.
     */
    Progress,
}

/**
 * **De quelle nature est le message recu par le distributeur ?**
 *
 * ⚠️ Le publieur (plugin opencode) ajoute une **ligne de routage** au corps du message, parce que
 * les en-tetes ntfy (`Click`, `Priority`) ne survivent pas au transport UnifiedPush. Cette ligne
 * dit si le message annonce une **fin de tour** ou une **etape d'avancement**. Sans elle, l'app
 * traiterait les deux pareil — et ferait sonner le telephone a chaque appel d'outil.
 */
enum class PushKind {
    /** Fin de tour : la notification doit alerter (son, vibration). */
    TurnEnd,

    /** Etape d'avancement : la notification remplace la precedente, en silence. */
    Progress,
}

/**
 * **Un message de push, decode.**
 *
 * @param kind ce que le message annonce.
 * @param sessionID la session concernee, telle qu'annoncee par le publieur — un **indice**,
 *   jamais une autorite (voir [TetherNotifier]).
 * @param text le texte a afficher, **lignes de routage retirees**.
 */
data class PushPayload(
    val kind: PushKind,
    val sessionID: String?,
    val text: String,
)

/** Ligne ajoutee par le plugin pour annoncer une etape d'avancement. */
private val PROGRESS_MARKER = Regex("""tether:progress=(?:\S+)""")

/** Ligne ajoutee par le plugin pour porter la session (voir `ntfy-opencode.ts`). */
private val SESSION_MARKER = Regex("""tether:session=(\S+)""")

/**
 * **Decode le corps d'un push. JSON d'abord, marqueurs en repli.**
 *
 * ### Le defaut que ceci ferme, constate sur un vrai push
 *
 * Le plugin est passe au **JSON v1** (`{"v":1,…}`, voir `protocol.ts`) en annoncant que
 * « l'app lit les deux, JSON d'abord, marqueurs en repli ». Ce repli n'avait jamais ete
 * ecrit ici : l'app ne cherchait que les marqueurs `tether:progress=`.
 *
 * Consequence mesuree sur le premier push reel : le corps JSON ne contenait aucun
 * marqueur, donc `kind` retombait **toujours** sur `TurnEnd`. Chaque etape d'avancement
 * sonnait comme une fin de tour — exactement l'inverse de la notification silencieuse — et
 * le JSON, jamais retire, s'affichait brut a l'ecran.
 *
 * ⚠️ **Un corps qui commence par `{` est du JSON, et rien d'autre.** Si l'analyse echoue,
 * on ne retombe **pas** sur les marqueurs : aucun corps v0 ne commence par `{`, donc le
 * repli afficherait litteralement `{"v":1}` dans la notification. C'est la meme regle que
 * cote serveur — les deux doivent lire le meme format, sinon ils divergent en silence.
 *
 * ⚠️ `text` absent = corps invalide, et on rend le texte brut plutot que rien : mieux vaut
 * une notification etrange qu'une notification absente dont l'utilisateur ignore l'origine.
 */
fun parsePush(raw: String): PushPayload {
    if (raw.trimStart().startsWith("{")) {
        decoderJson(raw)?.let { return it }
    }
    return decoderMarqueurs(raw)
}

/** Le format v1 : `{"v":1,"text":"…","sessionID":"…","progress":true}`. */
private fun decoderJson(raw: String): PushPayload? {
    val objet = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        // Un `{` malforme : ce n'est pas du v1, et ce n'est pas du v0 non plus.
        return null
    }
    val texte = runCatching { objet["text"]?.jsonPrimitive?.content }.getOrNull() ?: return null
    if (texte.isBlank()) return null
    return PushPayload(
        kind = if (objet["progress"]?.jsonPrimitive?.booleanOrNull == true) {
            PushKind.Progress
        } else {
            PushKind.TurnEnd
        },
        sessionID = runCatching { objet["sessionID"]?.jsonPrimitive?.content }.getOrNull(),
        text = texte,
    )
}

/** Le format v0 : le texte, puis `tether:progress=1`, puis `tether:session=<id>`. */
private fun decoderMarqueurs(raw: String): PushPayload {
    val kind = if (PROGRESS_MARKER.containsMatchIn(raw)) PushKind.Progress else PushKind.TurnEnd
    val sessionID = SESSION_MARKER.find(raw)?.groupValues?.get(1)
    val text = raw
        .replace(PROGRESS_MARKER, "")
        .replace(SESSION_MARKER, "")
        .trim()
    return PushPayload(kind = kind, sessionID = sessionID, text = text)
}
/**
 * **Faut-il re-declarer l'abonnement aupres du serveur ?**
 *
 * ⚠️ Fonction **pure**, et pas un `if` en ligne : la regle decide si le telephone peut encore
 * recevoir une notification, et une erreur ici ne se voit **nulle part** — le serveur garde un
 * endpoint perime et publie dans le vide, sans qu'aucune erreur n'apparaisse des deux cotes.
 *
 * ⚠️ On re-declare quand l'endpoint **change** (reinstallation, renouvellement du distributeur)
 * ou quand la precedente declaration est **perimee**. Les deux cas ont la meme consequence s'ils
 * sont rates : le serveur publie sur un point d'acces que le telephone n'ecoute plus.
 *
 * @param lastEndpoint l'endpoint de la derniere declaration reussie, ou `null`.
 * @param current l'endpoint que le distributeur vient d'annoncer.
 * @param lastAtMillis date de la derniere declaration, en millisecondes epoch.
 * @param nowMillis maintenant, en millisecondes epoch.
 * @param intervalMillis duree au-dela de laquelle une declaration est consideree perimee.
 */
fun endpointNeedsRepublish(
    lastEndpoint: String?,
    current: String,
    lastAtMillis: Long,
    nowMillis: Long,
    intervalMillis: Long,
): Boolean {
    if (lastEndpoint != current) return true
    return nowMillis - lastAtMillis >= intervalMillis
}

/**
 * **Une fin de tour doit-elle retirer l'etape d'avancement ?**
 *
 * ⚠️ Fonction pure pour une raison precise : ce nettoyage a lieu **avant** le test de premier
 * plan, et cette position est le sujet. Place apres, il ne s'executerait jamais quand l'app est
 * ouverte — et l'etape resterait affichee alors que le travail est fini.
 */
fun shouldClearProgress(kind: PushKind): Boolean = kind == PushKind.TurnEnd

/**
 * **Une demande d'autorisation sur laquelle la notification peut proposer des boutons.**
 *
 * ⚠️ **Ses champs viennent TOUS du serveur**, jamais de la charge du push.
 *
 * C'est la regle la plus importante de ce fichier. Le topic ntfy accepte l'**ecriture anonyme** :
 * n'importe qui peut y publier. Si l'identifiant de demande venait du push, un tiers pourrait
 * publier une fausse notification et faire **approuver par le pouce de Bastien** une permission
 * arbitraire sur sa machine — execution de commande, ecriture de fichier, le tout depuis un
 * ecran verrouille. On interroge donc `GET /api/permission/request` au moment d'afficher, et le
 * bouton n'agit que sur ce que le serveur a reellement en attente. Une fausse notification peut
 * au pire dire « il y a peut-etre une demande » : elle ne peut rien autoriser.
 *
 * (C'est aussi ce que [TetherNotifier] fait deja pour *compter* les decisions en attente ; on
 * etend cette lecture a leur *contenu*, on ne la duplique pas.)
 */
data class PendingApproval(
    val requestID: String,
    val sessionID: String,
    /** L'action soumise (`bash`, `edit`…) : le bouton doit dire ce qu'il accorde. */
    val action: String,
)

/**
 * **La demande unique sur laquelle proposer des boutons, ou `null`.**
 *
 * ⚠️ **`singleOrNull`, volontairement.** Avec plusieurs demandes en attente, on ne sait pas
 * laquelle le bouton viserait : en choisir une au hasard, c'est appliquer une decision a un
 * element qu'on n'a pas montre. Sans boutons, la notification renvoie vers l'ecran
 * d'approbations, ou l'utilisateur voit la liste. Une action qui ne peut pas etre faite est
 * pire que pas d'action.
 *
 * - **0** demande : rien a accelerer ;
 * - **1** demande : c'est elle, sans ambiguite ;
 * - **2 et plus** : on renonce aux boutons.
 */
fun approvalFor(pending: List<PendingApproval>): PendingApproval? = pending.singleOrNull()

/**
 * **L'ordre des boutons d'une notification de decision.**
 *
 * ⚠️ `Refuser` d'abord, `Toujours` **en dernier**. L'ordre des actions d'une notification
 * dictate l'ordre de lecture, et le premier est le plus facile a toucher par megarde. Or
 * « Toujours autoriser » est le seul des trois qui ouvre un **droit permanent** : le placer en
 * dernier rend un glissement accidentel beaucoup moins probable. Le droiture reste dans l'app,
 * ou l'on voit la commande avant de l'accorder.
 *
 * L'ordre suit la consequence (voir `PermissionDecision`), pas l'alphabet.
 */
val APPROVAL_ACTIONS: List<PermissionDecision> = listOf(
    PermissionDecision.Reject,
    PermissionDecision.Once,
    PermissionDecision.Always,
)

/** Le libelle d'un bouton, qui dit **ce qu'il accorde** et pas seulement « oui ». */
/**
 * Le libelle du bouton d'une decision.
 *
 * `chaine` est injectable comme dans `RelativeTime` : un test JVM n'a pas de
 * ressources. Le test verifie alors **quelles** chaines sont choisies pour quelles
 * decisions — ce qui est la propriete, et non la phrase affichee.
 */
fun approvalActionLabel(
    decision: PermissionDecision,
    chaine: (Int, Array<out Any>) -> String = { id, args -> Res.of(id, *args) },
): String = when (decision) {
    PermissionDecision.Reject -> chaine(R.string.refuser_628971, emptyArray())
    PermissionDecision.Once -> chaine(R.string.autoriser_fois_35c774, emptyArray())
    PermissionDecision.Always -> chaine(R.string.toujours_ec25a7, emptyArray())
}

/** Plage d'ID reservee a l'avancement, hors des ID fixes du notifier (1001..1004). */
private const val PROGRESS_ID_BASE = 2000
private const val PROGRESS_ID_RANGE = 500

/**
 * **L'ID de la notification d'avancement, pour une session donnee.**
 *
 * ⚠️ **Un ID par session, pas un ID global.** Mesure du projet : deux sessions principales
 * tournent en parallele (un sous-agent delegue est ignore par le publieur, mais deux sessions
 * ouvertes par l'utilisateur, non). Avec un ID unique, l'etape de la session B **ecraserait**
 * celle de la session A : l'ecran afficherait `shell : npm install` a propos d'une tache qui n'a
 * jamais lance cette commande. C'est inoffensif en apparence et faux sur le fond — exactement le
 * mensonge que le projet s'interdit.
 *
 * ⚠️ **Et un seul ID par session** : les etapes successives **remplacent** la meme ligne, ce qui
 * fait l'anti-spam (dix appels d'outil = une notification, pas dix).
 *
 * ⚠️ Fonction **pure** : le calcul est deterministe et testable, sans Android. Un identifiant de
 * session absent retombe sur un ID neutre, jamais sur un ID au hasard.
 */
fun progressNotificationId(sessionID: String?): Int {
    if (sessionID.isNullOrBlank()) return PROGRESS_ID_BASE
    val hash = sessionID.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    return PROGRESS_ID_BASE + 1 + (hash % PROGRESS_ID_RANGE)
}

/** Cible du tap sur une notification. */
sealed interface PushTarget {
    /** Ouvre les approbations en attente : c'est là qu'une décision se prend. */
    data object Approvals : PushTarget

    /** Ouvre une conversation précise. */
    data class Session(val sessionID: String) : PushTarget

    /** Ouvre simplement l'app. */
    data object App : PushTarget
}

/**
 * **La règle de notification, en une fonction.**
 *
 * @param appForeground l'app est-elle visible à l'écran ?
 * @param pendingDecisions nombre de demandes d'autorisation en attente sur le serveur.
 * @param kind ce qu'annonce le message (fin de tour, ou etape d'avancement).
 */
fun decideNotification(
    appForeground: Boolean,
    pendingDecisions: Int,
    kind: PushKind = PushKind.TurnEnd,
): PushDecision = when {
    // ⚠️ L'avancement se decide **en premier, et pour lui seul** : c'est une notification
    // silencieuse et remplacee, elle n'a rien a voir avec une demande d'autorisation. La laisser
    // tomber dans la branche des decisions la transformerait en alerte persistante « Autorisation
    // requise » portant le texte d'un appel d'outil — c'est-a-dire un mensonge. La decision en
    // attente a de toute facon **sa propre** notification (ID distinct), emise sur l'evenement
    // `permission.asked`, pas sur un tick d'avancement.
    kind == PushKind.Progress -> if (appForeground) PushDecision.Skip else PushDecision.Progress

    // ⚠️ 2.4 — Une décision qui attend ne se balaie pas, et l'emporter sur le test de premier
    // plan est **délibéré** : une autorisation immobilise du travail, ce n'est pas du bruit. Si
    // l'app est ouverte, l'alerte persistante double le badge de l'app sans gêner (elle ne vibre
    // pas) ; si l'app est fermée, c'est elle qui empêche la session de rester bloquée des heures.
    pendingDecisions > 0 -> PushDecision.Ongoing

    // ⚠️ 2.2 — **Ne pas notifier ce qu'on regarde.** La fin d'un tour, elle, est du bruit quand
    // l'app est au premier plan : l'utilisateur a déjà l'information sous les yeux. Mesure
    // assumée : on renonce à distinguer *quelle* session est regardée, parce que la charge du
    // push ne porte aucun identifiant fiable et qu'on ne veut pas le croire.
    appForeground -> PushDecision.Skip

    else -> PushDecision.Transient
}

/**
 * **Où mène le tap ?**
 *
 * ⚠️ 2.5 — Le deep link du publieur (`Click: opencode://session/<id>`) **n'arrive pas jusqu'à
 * l'app** : le distributeur UnifiedPush ne transmet que deux extras (le message et l'instance),
 * jamais les en-têtes ntfy. C'est donc **l'app** qui construit la notification, et donc **elle**
 * qui choisit la destination. Quand une décision attend, il n'y a qu'un écran utile : celui où
 * l'on répond.
 */
fun targetFor(decision: PushDecision, validSessionID: String? = null): PushTarget = when (decision) {
    // ⚠️ Une decision en attente prime sur tout : c'est le seul ecran ou l'on peut agir, et
    // une session bloquee des heures est plus grave qu'une conversation ouverte au mauvais
    // endroit.
    PushDecision.Ongoing -> PushTarget.Approvals

    // ⚠️ `validSessionID` a DEJA ete confronte aux sessions connues par l'appelant : on ne
    // revalide pas ici, on route. Un identifiant non valide arrive en `null`.
    PushDecision.Transient, PushDecision.Progress ->
        validSessionID?.let { PushTarget.Session(it) } ?: PushTarget.App

    PushDecision.Skip -> PushTarget.App
}

/**
 * **Extrait la destination d'un deep link `opencode://…`.**
 *
 * Deux hôtes :
 *  - `session/<id>` — ouvrir une conversation (format figé par le publieur, conservé) ;
 *  - `approve` — ouvrir les approbations, cible des notifications de décision (voir [targetFor]).
 *
 * ⚠️ L'ID de session est accepté **tel quel**, sans validation contre une liste : un ID inconnu
 * donne une session vide, échec inoffensif. Refuser l'ouverture serait pire — l'utilisateur
 * verrait l'app ne rien faire du tout.
 */
const val DEEP_LINK_SCHEME = "opencode"
const val DEEP_LINK_HOST_SESSION = "session"
const val DEEP_LINK_HOST_APPROVE = "approve"

fun routeFromUri(scheme: String?, host: String?, pathSegments: List<String>): String? {
    if (scheme != DEEP_LINK_SCHEME) return null
    return when (host) {
        DEEP_LINK_HOST_SESSION -> pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        DEEP_LINK_HOST_APPROVE -> APPROVE_ROUTE
        else -> null
    }
}

/** Marqueur interne : la route des approbations, resolus par `TetherNavHost`. */
const val APPROVE_ROUTE = "opencode://approve"
