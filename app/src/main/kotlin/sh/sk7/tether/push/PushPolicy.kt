package sh.sk7.tether.push

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
 */
fun decideNotification(appForeground: Boolean, pendingDecisions: Int): PushDecision = when {
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
fun targetFor(decision: PushDecision): PushTarget = when (decision) {
    PushDecision.Ongoing -> PushTarget.Approvals
    PushDecision.Transient -> PushTarget.App
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
