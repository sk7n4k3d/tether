package sh.sk7.tether.domain.model

/**
 * **Ce qu'une session fait en ce moment, vu du serveur.**
 *
 * ### Pourquoi ce type existe séparément de [SessionStatus]
 * [SessionStatus] est produit par `EventReducer`, donc **uniquement dans l'écran de chat**, et
 * **uniquement si le flux SSE est ouvert sur cette session**. Il répond à « où en est ce tour ? »
 * quand on regarde déjà la conversation.
 *
 * [Activity] répond à une autre question : **« est-ce que quelque chose tourne, quelque part ? »**
 * — posée depuis la liste, sans avoir ouvert quoi que ce soit. Les deux sont nécessaires et ne
 * doivent pas se confondre : le premier est l'état **du tour affiché**, le second est l'état
 * **de l'installation**.
 *
 * ### La source, et ce qu'elle garantit
 * Tout vient de routes serveur mesurées le 2026-09-25 :
 *  - `GET /api/session/active` → `{ses_…: {type: "running"}}` : le serveur ne dit **que**
 *    « tourne ». Tout le reste en est déduit ;
 *  - `session.time.idle` / `session.time.viewed` → « terminé » et « vu » ;
 *  - `session.outcome` → succès / échec / interrompu ;
 *  - `GET /api/session/{id}/inbox` → les messages en file ;
 *  - `GET /api/permission/request` → ce qui attend une décision.
 *
 * ⚠️ **`session.time.viewed` est la clé de « pas vu », et c'est le serveur qui la tient.** C'est
 * une donnée **persistante** : un suivi local repartirait de zéro à chaque réouverture, alors que
 * celui-ci survit à la fermeture de l'app. Comparer `idle > viewed` suffit, et aucune horloge
 * locale n'entre en jeu — donc aucun décalage possible entre le téléphone et la machine.
 */
enum class Activity(val label: String) {
    /**
     * **L'agent attend une décision de toi.** Autorisation, ou question.
     *
     * ⚠️ C'est l'état qui **bloque**, et il prime sur tout le reste : tant qu'une décision n'est
     * pas prise, la session ne peut pas avancer. Afficher « ça tourne » à ce moment-là ferait
     * regarder l'écran sans rien faire, alors que c'est précisément l'instant où l'app sert.
     */
    Waiting("t'attend"),

    /**
     * **Le tour a fini et tu ne l'as pas vu.**
     *
     * ⚠️ Distinct de [Idle] : c'est la différence entre « il s'est passé quelque chose » et
     * « c'est calme ». Sans cette distinction, une app notifierait pour du travail déjà lu.
     */
    Unseen("terminé, pas vu"),

    /** Le serveur travaille. */
    Running("en cours"),

    /** Un message attend son tour (file d'attente, `delivery: queue`). */
    Queued("en file"),

    /** Le tour a échoué ou a été interrompu. */
    Failed("échec"),

    /** Rien. */
    Idle("calme"),
}

/**
 * L'état d'activité d'une session, avec **la source de chaque fait**.
 *
 * ⚠️ Les horodatages sont ceux **du serveur**. On ne les compare jamais à `System.currentTimeMillis()`
 * ailleurs que pour l'affichage relatif : une horloge de téléphone décalée ferait apparaître ou
 * disparaître un « pas vu » selon le fuseau, ce qui est exactement le genre de bug impossible à
 * reproduire.
 */
data class SessionActivity(
    val sessionID: String,
    val activity: Activity = Activity.Idle,
    /** Nombre de messages en file pour cette session. */
    val queuedCount: Int = 0,
    /** Nombre de demandes de décision en attente (autorisations + formulaires). */
    val waitingCount: Int = 0,
    /** `session.time.idle` : fin du dernier tour. */
    val idleAt: Long? = null,
    /** `session.time.viewed` : jusqu'où l'utilisateur a regardé. */
    val viewedAt: Long? = null,
    /** `session.outcome` brut, pour le détail. */
    val outcome: String? = null,
) {
    /**
     * Le tour est-il terminé mais jamais vu ?
     *
     * ⚠️ On compare **deux horodatages du serveur** : `idle` (fin du tour) et `viewed` (dernier
     * marquage par l'app). `viewed == null` signifie « jamais ouvert », ce qui est le cas le plus
     * fréquent et le plus intéressant — une session terminée qu'on n'a jamais regardée.
     *
     * ⚠️ Un `outcome` nul ou `running` ne doit **pas** déclencher « pas vu » : le tour n'est pas
     * fini. La condition porte donc sur `idle`, pas seulement sur `outcome`.
     */
    val isUnseen: Boolean
        get() = idleAt != null && (viewedAt == null || idleAt > viewedAt) && waitingCount == 0

    /** Vrai si cette session mérite un signal dans la liste. */
    val needsAttention: Boolean
        get() = activity == Activity.Waiting || activity == Activity.Unseen
}

/**
 * **L'état de toute l'installation**, en un seul objet.
 *
 * ### Pourquoi un objet global et pas une map de sessions
 * Parce que la question posée est **globale** : « est-ce que c'est calme ? », « est-ce qu'il y a
 * quelque chose qui m'attend ? ». Une map obligerait chaque appelant à la parcourir pour
 * répondre, et deux appelants répondraient différemment. Ici les compteurs sont calculés **une
 * fois**, au même endroit, donc ils ne peuvent pas se contredire.
 */
data class FleetState(
    /** L'activité par identifiant de session. Absent = jamais interrogé. */
    val bySession: Map<String, SessionActivity> = emptyMap(),
    /** Les commandes shell connues du serveur, y compris terminées. */
    val shells: List<ShellActivity> = emptyList(),
    /** Vrai tant qu'aucune interrogation n'a abouti. */
    val loading: Boolean = false,
    /** Dernière erreur d'interrogation, s'il y en a une. */
    val error: String? = null,
    /** Horodatage **local** de la dernière interrogation réussie (sert à l'affichage « il y a X »). */
    val polledAt: Long? = null,
) {
    /**
     * **Ce qui demande une décision**, toutes sessions confondues.
     *
     * ⚠️ C'est le chiffre qui doit dominer partout : si une seule session attend, l'utilisateur
     * doit le voir avant tout le reste, même si dix autres tournent.
     */
    val waiting: List<SessionActivity> get() = bySession.values.filter { it.activity == Activity.Waiting }

    /** Les sessions terminées et pas vues. */
    val unseen: List<SessionActivity> get() = bySession.values.filter { it.isUnseen }

    /**
     * **Les sessions terminées depuis un instant de reference.**
     *
     * ⚠️ Distinction indispensable a l'affichage, et c'est une mesure qui l'a imposee : sur ce
     * serveur, **110 sessions sur 200** sont « pas vues » — jamais rouvertes depuis des jours. Les
     * compter toutes en tete d'ecran produit un chiffre exact et **inutilisable** : un arriere
     * historique n'est pas une information, c'est du bruit qui noie les deux lignes qui comptent.
     *
     * ⚠️ Ce n'est **pas** un mensonge sur l'etat : la pastille de chaque ligne continue d'utiliser
     * `isUnseen` (la verite du serveur). C'est une **vue** differente de la meme donnee, adaptee a
     * la question « qu'est-ce qui s'est passe pendant que j'etais ailleurs ? ».
     */
    fun unseenSince(reference: Long): List<SessionActivity> =
        bySession.values.filter { it.isUnseen && (it.idleAt ?: 0) > reference }

    /** Les sessions en cours d'exécution. */
    val running: List<SessionActivity> get() = bySession.values.filter { it.activity == Activity.Running }

    /** Les sessions avec des messages en file. */
    val queued: List<SessionActivity> get() = bySession.values.filter { it.queuedCount > 0 }

    /** Les shells encore vivants. */
    val liveShells: List<ShellActivity> get() = shells.filter { it.isLive }

    /**
     * **L'état global, réduit à un seul mot.**
     *
     * ⚠️ L'ordre est **délibéré** et c'est celui validé avec l'utilisateur : ce qui **bloque**
     * passe avant ce qui **travaille**. Une autorisation en attente immobilise une session ;
     * afficher « en cours » à ce moment-là ferait regarder l'écran sans agir.
     *
     * L'ordre : t'attend > terminé pas vu > en cours > en file > échec > calme.
     *
     * ⚠️ [Activity.Failed] est **après** `Running` : un échec ancien ne doit pas masquer du
     * travail en cours. Il reste visible par session, mais ne domine pas l'état global.
     */
    val summary: Activity
        get() = when {
            waiting.isNotEmpty() -> Activity.Waiting
            unseen.isNotEmpty() -> Activity.Unseen
            running.isNotEmpty() -> Activity.Running
            queued.isNotEmpty() -> Activity.Queued
            bySession.values.any { it.activity == Activity.Failed } -> Activity.Failed
            else -> Activity.Idle
        }

    /**
     * L'etat global, **rapporte a la session de l'utilisateur**.
     *
     * ⚠️ Meme logique que [unseenSince] : « 110 pas vu » n'est pas une raison de regarder l'ecran,
     * « 2 termines pendant que tu etais ailleurs » en est une. C'est cette version qui doit
     * s'afficher en tete.
     */
    fun summarySince(reference: Long): Activity = when {
        waiting.isNotEmpty() -> Activity.Waiting
        unseenSince(reference).isNotEmpty() -> Activity.Unseen
        running.isNotEmpty() -> Activity.Running
        queued.isNotEmpty() -> Activity.Queued
        bySession.values.any { it.activity == Activity.Failed } -> Activity.Failed
        else -> Activity.Idle
    }

    /** Y a-t-il **quelque chose** à signaler ? */
    val hasAnything: Boolean
        get() = waiting.isNotEmpty() || unseen.isNotEmpty() || running.isNotEmpty() ||
            queued.isNotEmpty() || liveShells.isNotEmpty()

    /** Nombre de sessions qui attendent une décision — pour le badge. */
    val waitingCount: Int get() = waiting.size
}

/**
 * **Une commande shell du serveur, en cours ou terminée.**
 *
 * ⚠️ Le `sessionID` n'est **pas** un champ déclaré par l'API : il vit dans `metadata`, et c'est
 * une mesure qui l'a révélé
 * (`metadata = {"sessionID": "ses_…"}`). Le schéma OpenAPI annonce seulement
 * `metadata: {type: object}` : se fier au schéma laissait croire qu'un shell n'était
 * rattachable à rien, ce qui aurait imposé une section séparée sans raison.
 *
 * ⚠️ `ShellActivity` porte le **statut serveur** (`running`, `exited`, `timeout`, `killed`). Un
 * shell `running` est ce qui permet de répondre à « qu'est-ce qui tourne encore en arrière-plan ».
 */
data class ShellActivity(
    val id: String,
    val command: String,
    val status: String,
    /** Session qui l'a lancé, si le serveur l'a indiquée. */
    val sessionID: String? = null,
    /** Processus système — preuve que ça tourne réellement. */
    val pid: Long? = null,
    /** Code de sortie, quand c'est terminé. */
    val exitCode: Double? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
) {
    /**
     * Le shell tourne-t-il encore ?
     *
     * ⚠️ On se base sur le statut **serveur** et jamais sur l'absence de `completed` : un shell
     * tué par un timeout peut ne pas porter d'horodatage de fin. Le statut est la seule source
     * fiable.
     */
    val isLive: Boolean get() = status == "running"

    /** Première ligne de la commande, pour un affichage compact. */
    val firstLine: String
        get() = command.lineSequence().firstOrNull()?.take(80).orEmpty()

    /** Durée, depuis le début jusqu'à la fin (ou maintenant, mais seulement si ça tourne). */
    fun durationLabel(now: Long): String? {
        val start = startedAt ?: return null
        val end = completedAt ?: if (isLive) now else return null
        val ms = end - start
        return when {
            ms < 1_000 -> "$ms ms"
            ms < 60_000 -> "${ms / 1_000} s"
            ms < 3_600_000 -> "${ms / 60_000} min"
            else -> "${ms / 3_600_000} h"
        }
    }
}
