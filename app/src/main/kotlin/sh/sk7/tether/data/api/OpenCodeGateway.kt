package sh.sk7.tether.data.api

import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.domain.model.DailyActivity
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.domain.model.ModelUsage
import sh.sk7.tether.domain.model.UsageStats
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * Point d'acces unique aux operations de l'API V2 utilisees par l'UI.
 *
 * Cette interface existe pour deux raisons :
 * - l'URL de base change avec les reglages : un [OpenCodeClient] est construit **a la
 *   demande** pour la valeur courante plutot que fige a l'injection ;
 * - elle rend les ViewModels testables avec un faux, sans serveur ni moteur HTTP.
 *
 * Les identifiants sont resolus par le [CredentialsProvider] partage, alimente par
 * `ConnectionStore` : le mot de passe n'apparait dans aucune signature.
 */
interface OpenCodeGateway {
    suspend fun info(settings: ConnectionSettings): ServerInfo

    /**
     * Toutes les sessions du repertoire, **pagination suivie**.
     *
     * ⚠️ `GET /api/session` pagine par defaut a 50 et le serveur en compte **428** : s'arreter
     * a la premiere page tronque la liste en silence (defaut connu de la Task 1.5). On boucle
     * sur `cursor.next` en demandant [pageSize] elements par page.
     *
     * @param parent filtre optionnel : enfants directs d'une session, ou racines seulement.
     *   Voir [SessionParent] — la mesure du 2026-09-26 montre que `parentID=null` renvoie
     *   uniquement les racines (159 sur 468), et `parentID=<ses>` uniquement ses enfants.
     */
    suspend fun allSessions(
        settings: ConnectionSettings,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        parent: SessionParent? = null,
    ): List<Session> {
        val all = mutableListOf<Session>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = sessionsPage(settings, pageSize, cursor, parent)
            all += page.data
            cursor = page.next
            pages++
        } while (cursor != null && pages < MAX_SESSION_PAGES)
        return all
    }

    /**
     * Une page de `GET /api/session`.
     *
     * ⚠️ Il n'existe **pas** de `sessions()` non pagine : une telle methode renverrait 50
     * elements par defaut et tronquerait la liste en silence. Toujours passer par
     * [allSessions] ou par cette page explicite.
     *
     * ⚠️ Le filtre `parentID` est celui de l'API : [SessionParent.Of] pour les enfants directs,
     * [SessionParent.Roots] pour les seules racines. Mesure du 2026-09-26 : `parentID=null`
     * (chaine litterale) rend bien **uniquement** les racines, et non « pas de filtre ».
     */
    suspend fun sessionsPage(
        settings: ConnectionSettings,
        limit: Int?,
        cursor: String?,
        parent: SessionParent? = null,
    ): CursorPage<Session>

    suspend fun models(settings: ConnectionSettings): List<Model>

    suspend fun agents(settings: ConnectionSettings): List<Agent>

    /**
     * Cree une session avec **les valeurs par defaut du serveur**.
     *
     * ⚠️ C'est le seul cas normal : le dialogue de creation a ete supprime. `model` et `agent`
     * viennent du **dernier choix memorise** (voir `SessionDefaultsStore`) et sont `null` tant que
     * l'utilisateur n'a rien choisi — le serveur resout alors au premier tour. Aucun `title` :
     * opencode nomme la session lui-meme, et il ne reecrit pas un titre qu'on lui impose.
     */
    suspend fun createSession(
        settings: ConnectionSettings,
        model: ModelRef? = null,
        agent: String? = null,
    ): Session

    /** Une session precise (`GET /api/session/{id}`), pour titrer l'ecran de chat. */
    suspend fun session(settings: ConnectionSettings, sessionID: String): Session

    /** `POST /prompt` : accepte le prompt. La reponse arrive ensuite par le flux. */
    suspend fun prompt(settings: ConnectionSettings, sessionID: String, text: String): PromptAcceptance

    /** Surcharge avec pieces jointes, agents ou skills (`files`, `agents`, `skills` du corps).
     *
     * ⚠️ Les formes acceptees sont **mesurees** (voir [PromptBody]) : un fichier marche en `data:`
     * inline ou en `file://` **absolu**, jamais en chemin relatif. On garde donc les deux
     * surcharges : un texte seul continue de passer par la version sans corps explicite, et cette
     * surcharge n'existe que pour ce qui n'est pas du texte.
     */
    suspend fun prompt(
        settings: ConnectionSettings,
        sessionID: String,
        body: PromptBody,
    ): PromptAcceptance

    /** Un tour complet de messages, **toutes pages confondues**, dans l'ordre chronologique. */
    suspend fun allMessages(
        settings: ConnectionSettings,
        sessionID: String,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): List<MessageDto> {
        val all = mutableListOf<MessageDto>()
        var cursor: String? = null
        var pages = 0
        do {
            // ⚠️ `order = "asc"` sur la PREMIERE page seulement, puis `null` : mesure sur le
            // serveur, l'ordre est **encode dans le curseur** (`{"order":"asc","direction":…}`)
            // et les pages suivantes le conservent. Repasser `order` avec un `cursor` fait
            // repondre **400 InvalidCursorError** (verifie sur le serveur).
            val page = messagesPage(settings, sessionID, pageSize, cursor, order = if (pages == 0) "asc" else null)
            all += page.data
            cursor = page.next
            pages++
        } while (cursor != null && pages < MAX_MESSAGE_PAGES)
        return all
    }

    /**
     * **Une page de l'historique**, du plus recent vers le plus ancien.
     *
     * ⚠️ **Pourquoi cette methode remplace `recentMessages` + `messagesBefore`.**
     *
     * `recentMessages` renvoyait les N derniers messages et **jetait le curseur**. Pour charger
     * la tranche precedente, `messagesBefore` devait alors **repartir du sommet et redescendre**
     * jusqu'a croiser le message de reference. Cout mesure : la 1re tranche demandait 1 requete,
     * la 2e 2, la 3e 3… soit **O(n²)**. Sur une session a 2 040 messages, remonter tout
     * l'historique demandait ~200 requetes au lieu de 21.
     *
     * ⚠️ **`order = "desc"` est indispensable et contre-intuitif** : on veut la FIN de la
     * conversation, donc les messages **les plus recents**. Demander `asc` avec une limite
     * renverrait les **premiers** — l'inverse exact de ce qu'on cherche.
     *
     * ⚠️ On ne peut PAS combiner `order` et `cursor` (**400** verifie) : l'ordre est encode dans
     * le curseur lui-meme (base64 portant `order` + `direction`). On demande donc `order`
     * **uniquement** au premier appel, puis on suit le curseur tel quel.
     */
    suspend fun messagesPageBack(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int,
        cursor: String?,
    ): HistoryPage

    suspend fun messagesPage(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int?,
        cursor: String?,
        order: String? = null,
    ): CursorPage<MessageDto>

    /** `POST /interrupt` : stoppe l'execution en cours. */
    suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean

    /**
     * `PATCH /api/session/{id}` : renomme la session.
     *
     * ⚠️ Le corps n'accepte que `title` et `permissions` (`additionalProperties: false`) et le
     * serveur repond **204 sans corps**. On renvoie donc `true` sur succes, jamais un objet.
     */
    suspend fun renameSession(settings: ConnectionSettings, sessionID: String, title: String): Boolean

    /** `DELETE /api/session/{id}` : supprime definitivement la session. */
    suspend fun deleteSession(settings: ConnectionSettings, sessionID: String)

    /**
     * `POST /api/session/{id}/fork` : duplique la session a partir d'un point.
     *
     * ⚠️ Avec `before = null`, on forke **toute** la session : le corps `{}` est valide.
     */
    suspend fun forkSession(settings: ConnectionSettings, sessionID: String): Session

    /**
     * `POST /api/session/{id}/compact` : resume le contexte pour liberer la fenetre.
     *
     * Renvoie `true` si le serveur a accepte. ⚠️ La compaction est **asynchrone** : le resume
     * n'est pas encore ecrit au retour de l'appel, il arrive par le flux.
     */
    suspend fun compactSession(settings: ConnectionSettings, sessionID: String): Boolean

    // ------------------------------------------------------------------
    // Statistiques et inventaire du serveur
    // ------------------------------------------------------------------

    /**
     * Statistiques d'usage (`/api/experimental/session/stats`).
     *
     * @param fromMillis borne basse de la plage, ou `null` pour tout l'historique du serveur.
     */
    suspend fun stats(settings: ConnectionSettings, fromMillis: Long? = null): UsageStats

    /**
     * Enregistre cet appareil aupres du serveur demande par le QR.
     *
     * ⚠️ [server] vient du QR scanne, **pas** des reglages. C'est le seul cas ou l'app parle
     * a un serveur different de celui auquel elle est configuree — et c'est volontaire : le
     * QR a ete produit par ce serveur, dans un TUI ou l'utilisateur venait de s'authentifier.
     *
     * Les identifiants restent ceux des reglages. Si le serveur scanne en demande un autre,
     * l'appel sort en 401 et l'echec est montre tel quel — l'app ne devine pas, et n'essaie
     * aucun autre mot de passe.
     */
    suspend fun registerDevice(
        settings: ConnectionSettings,
        server: String,
        deviceId: String,
        endpoint: String,
        p256dh: String,
        authSecret: String,
        pairingToken: String,
        distributor: String?,
    ): Boolean

    /** Retire cet appareil. Definitif : il faudra rescaner un QR pour revenir. */
    suspend fun unregisterDevice(
        settings: ConnectionSettings,
        server: String,
        deviceId: String,
    ): Boolean

    /** Les appareils enregistres, en vue publique : ni endpoint ni cles. */
    suspend fun devices(settings: ConnectionSettings, server: String): List<String>

    /** Commandes slash disponibles. */
    suspend fun commands(settings: ConnectionSettings): List<CommandDto>

    /** Skills (competences) disponibles. */
    suspend fun skills(settings: ConnectionSettings): List<SkillDto>

    /** Serveurs MCP et leur etat de connexion. */
    suspend fun mcpServers(settings: ConnectionSettings): List<McpServerDto>

    /** Plugins charges et leur etat. */
    suspend fun plugins(settings: ConnectionSettings): List<PluginDto>

    /** Fournisseurs de modeles configures. */
    suspend fun providers(settings: ConnectionSettings): List<ProviderDto>

    /** Autorisations **memorisees** (revoquables). */
    suspend fun savedPermissions(settings: ConnectionSettings): List<SavedPermissionDto>

    /** Revoque une autorisation memorisee. Action de securite, jamais automatique. */
    suspend fun revokePermission(settings: ConnectionSettings, permissionID: String): Boolean

    /** Projets connus du serveur. */
    suspend fun projects(settings: ConnectionSettings): List<ProjectDto>

    // ------------------------------------------------------------------
    // Etat vivant : activite, file d'attente, shells
    // ------------------------------------------------------------------

    /**
     * Les sessions **en cours d'execution**, telles que le serveur les annonce.
     *
     * ⚠️ C'est la seule source d'etat fiable : `SessionStatus` vient du flux SSE, donc il n'existe
     * que dans l'ecran de chat et seulement si le flux est ouvert. Une session absente de cette
     * carte n'est pas en cours.
     */
    suspend fun activeSessions(settings: ConnectionSettings): Set<String>

    /** Les commandes shell du serveur, terminees comprises. */
    suspend fun shells(settings: ConnectionSettings): List<ShellInfoDto>

    /**
     * Les **terminaux** ouverts (`/api/pty`).
     *
     * ⚠️ Indispensable en plus des shells : `POST /api/session/{id}/shell` rend 500 sur ce serveur
     * (bug de plugin), donc se fier aux seuls shells ferait croire que rien ne tourne.
     */
    suspend fun terminals(settings: ConnectionSettings): List<PtyInfoDto>

    /** La sortie d'un shell, a partir d'un curseur optionnel. */
    suspend fun shellOutput(settings: ConnectionSettings, shellID: String, cursor: Int? = null): ShellOutputDto?

    /**
     * Marque une session comme vue **jusqu'a cet `idle`** (horodatage serveur).
     *
     * ⚠️ Toujours passer l'`idle` du serveur : c'est ce qui rend la comparaison « termine / pas
     * vu » juste, quel que soit le fuseau du telephone.
     */
    suspend fun markViewed(settings: ConnectionSettings, sessionID: String, idle: Long): Boolean

    /** Deplace les outils bloquants d'une session en observation d'arriere-plan. */
    suspend fun backgroundTools(settings: ConnectionSettings, sessionID: String): Boolean

    /** Active une competence dans une session (`POST /experimental/session/{id}/skill`).
     *
     * ⚠️ Chemin **different** de la piece `skills` d'un prompt : ici la competence est attachee a la
     * session et l'execution reprend ; dans le corps du prompt, elle ne vaut que pour ce tour. Les
     * deux existent, on expose les deux plutot que d'en choisir un a la place de l'utilisateur.
     */
    suspend fun activateSkill(settings: ConnectionSettings, sessionID: String, skillID: String): Boolean

    /** Les enfants directs d'un chemin, **relatif au repertoire configure** (`GET /api/fs/list`). */
    suspend fun fsList(settings: ConnectionSettings, path: String? = null): List<FsEntryDto>

    /** Recherche recursive d'entrees (`GET /api/fs/find`). */
    suspend fun fsFind(settings: ConnectionSettings, query: String, type: String? = null): List<FsEntryDto>

    /** Le contenu d'un fichier, ou `null` s'il n'existe pas (`GET /api/fs/read/<chemin>`).
     *
     * ⚠️ Le chemin est **relatif au repertoire configure** : le serveur refuse un chemin absolu
     * (mesure : `404 FileNotFoundError`). On ne « corrige » pas ce chemin cote app — on ne peut pas
     * savoir a quoi un chemin absolu se rapporte sur la machine distante.
     */
    suspend fun fsRead(settings: ConnectionSettings, path: String): ByteArray?

    /** Les references invocables du repertoire (`GET /api/reference`). */
    suspend fun references(settings: ConnectionSettings): List<ReferenceDto>

    /** Les branches du depot du repertoire configure (`GET /api/vcs/branch`).
     *
     * ⚠️ Le depot du repertoire configure, **pas** celui d'une session : une session peut travailler
     * ailleurs. Pour ce cas-la, voir [branchesIn].
     */
    suspend fun branches(settings: ConnectionSettings): List<String>

    /** Les branches d'un **repertoire donne**, distinct de reprendre les branches du cwd. */
    suspend fun branchesIn(settings: ConnectionSettings, directory: String): List<String>


    // ------------------------------------------------------------------
    // Autorisations — le coeur d'un client d'agent
    // ------------------------------------------------------------------

    /**
     * Les demandes d'autorisation **en attente sur tout le serveur**.
     *
     * ⚠️ Route **globale** (`/api/permission/request`) et non par session : c'est ce qui permet
     * de repondre a une demande **sans savoir de quelle session elle vient**. Un utilisateur qui
     * recoit une notification veut approuver, pas naviguer d'abord.
     */
    suspend fun pendingPermissions(settings: ConnectionSettings): List<PermissionRequest>

    /** Demandes en attente **d'une session donnee** (`/api/session/{id}/permission`). */
    suspend fun sessionPermissions(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<PermissionRequest>

    /**
     * Repond a une demande : `once`, `always` ou `reject`.
     *
     * ⚠️ `always` **memorise** le droit : c'est une decision qui survit a la session. La
     * signature le rend explicite ([PermissionDecision]) au lieu d'un booleen ambigu.
     */
    suspend fun replyPermission(
        settings: ConnectionSettings,
        sessionID: String,
        requestID: String,
        decision: PermissionDecision,
        message: String? = null,
    ): Boolean

    // ------------------------------------------------------------------
    // Diffs, contexte, worktrees, revert, commandes
    // ------------------------------------------------------------------

    /** Les fichiers modifies par une session, **patch inclus** (`GET /session/{id}/diff`). */
    suspend fun sessionDiff(settings: ConnectionSettings, sessionID: String): List<FileDiffDto>

    /**
     * Le diff du depot, hors session (`GET /api/vcs/diff`).
     *
     * ⚠️ `mode` est **requis** par le serveur (`working`, `branch`, `committed`) : sans lui, la
     * route rend `400`. Pas de defaut invente cote app.
     */
    suspend fun vcsDiff(settings: ConnectionSettings, mode: String): List<FileDiffDto>

    /**
     * Le diff d'un **repertoire donne** (`GET /api/vcs/diff`).
     *
     * ⚠️ Distinct de [vcsDiff] : une session peut travailler ailleurs que dans le repertoire
     * configure pour l'app, et c'est ce depot-la qui doit etre mesure.
     */
    suspend fun vcsDiffIn(
        settings: ConnectionSettings,
        directory: String,
        mode: String,
    ): List<FileDiffDto>

    /**
     * Le `projectID` du repertoire configure, tel que le serveur le calcule.
     *
     * ⚠️ Necessaire a `/api/worktree`, qui n'accepte qu'un `projectID` et pas un chemin.
     */
    suspend fun projectID(settings: ConnectionSettings): String?

    /** Les fichiers touches, sans patch (`GET /api/vcs/status`). */
    suspend fun vcsStatus(settings: ConnectionSettings): List<VcsFileStatusDto>

    /**
     * Le depot d'un repertoire, ou `null` s'il n'y est pas versionne (`GET /api/vcs`).
     *
     * ⚠️ `directory` est un parametre et non `settings.directory` : une session peut travailler
     * dans un autre repertoire que celui configure pour l'app, et c'est **ce** depot-la qui
     * l'interesse.
     */
    suspend fun vcsInfo(settings: ConnectionSettings, directory: String): VcsInfoDto?

    /**
     * **Ce qui occupe la fenetre de contexte** (`GET /session/{id}/context`).
     *
     * ⚠️ Ce n'est pas l'historique : c'est ce qui sera envoye au prochain tour. La distinction
     * est tout l'interet de la fonction — elle repond a « pourquoi ma session oublie des choses ».
     */
    suspend fun sessionContext(settings: ConnectionSettings, sessionID: String): List<ContextMessageDto>

    /**
     * Prepare un retour en arriere (`POST /session/{id}/revert/stage`).
     *
     * ⚠️ **Ne modifie rien** : renvoie ce qui serait touche. Le changement n'a lieu qu'au
     * [commitRevert] — c'est ce qui rend l'operation confirmable.
     */
    suspend fun stageRevert(
        settings: ConnectionSettings,
        sessionID: String,
        messageID: String,
    ): RevertResultDto?

    /** Applique le retour prepare. */
    suspend fun commitRevert(settings: ConnectionSettings, sessionID: String): Boolean

    /** Abandonne un retour prepare, sans rien modifier. */
    suspend fun discardRevert(settings: ConnectionSettings, sessionID: String): Boolean

    /** Les arbres de travail isoles (`GET /api/worktree`). */
    suspend fun worktrees(settings: ConnectionSettings): List<WorktreeDirDto>

    /**
     * Cree un arbre de travail isole.
     *
     * ⚠️ `name`, jamais un nom de branche : mesure du 2026-09-25, `branch` designe un arbre
     * **existant** a rattacher, pas une branche a creer. Envoyer `"probe-tether"` rend
     * `400 référence invalide`.
     */
    suspend fun createWorktree(settings: ConnectionSettings, name: String?): WorktreeInfoDto

    /**
     * Retire un arbre de travail.
     *
     * ⚠️ `force` est **expose** et non fige a `true` : un arbre avec des modifications non
     * commitees ne se retire pas sans forcer, et cet arbitrage appartient a l'utilisateur — c'est
     * potentiellement du travail perdu.
     */
    suspend fun removeWorktree(
        settings: ConnectionSettings,
        directory: String,
        force: Boolean,
    ): Boolean

    /** Change le modele d'une session. */
    suspend fun setSessionModel(settings: ConnectionSettings, sessionID: String, model: ModelRef): Boolean

    /** Change l'agent d'une session. */
    suspend fun setSessionAgent(settings: ConnectionSettings, sessionID: String, agent: String): Boolean

    /** Lance une commande slash (`POST /session/{id}/command`). */
    suspend fun runCommand(
        settings: ConnectionSettings,
        sessionID: String,
        name: String,
        text: String = "",
    ): Boolean

    /** Les messages **en file** d'attente sur une session. */
    suspend fun sessionInbox(settings: ConnectionSettings, sessionID: String): List<InboxItemDto>

    /** Annule un message en file. */
    suspend fun dismissInbox(settings: ConnectionSettings, sessionID: String, inboxID: String): Boolean

    /**
     * **Change le mode de livraison** d'un message en file (`steer` | `queue`).
     *
     * ⚠️ Mesure du 2026-09-26 : la route rend **204 sans corps** et l'effet est immediat ; une
     * valeur hors enum rend **400**, un id inconnu ou deja livre **409**. La signature prend la
     * chaine brute ([InboxDelivery.STEER] / [InboxDelivery.QUEUE]) pour ne pas figer un enum cote
     * appelant sans que le serveur l'ait exprime autrement.
     *
     * ⚠️ Le serveur est la verite : apres un `true`, l'appelant **relit la file** — un `204` ne
     * prouve pas que le mode voulu est celui qui a ete retenu si l'item a ete livre entre-temps.
     */
    suspend fun updateInboxDelivery(
        settings: ConnectionSettings,
        sessionID: String,
        inboxID: String,
        delivery: String,
    ): Boolean

    // ------------------------------------------------------------------
    // Formulaires — l'agent pose une question et attend, comme une permission
    // ------------------------------------------------------------------

    /**
     * Les formulaires **pendants du repertoire** (`GET /api/form`).
     *
     * ⚠️ C'est la route de **resynchronisation** : le flux SSE est « volatile by contract », un
     * `form.created` emis pendant une coupure est invisible pour toujours. Sans cette lecture,
     * l'agent reste bloque sans que rien ne l'indique.
     */
    suspend fun pendingForms(settings: ConnectionSettings): List<FormInfoDto>

    /**
     * Les formulaires pendants **d'une session** (`GET /api/session/{id}/form`).
     *
     * ⚠️ Le `sessionID` peut valoir `"global"` (elicitation MCP, mesure 2026-09-26) : ce n'est pas
     * une session, mais la route repond pour cette pseudo-session. Un vrai id inconnu rend `404`.
     */
    suspend fun sessionForms(settings: ConnectionSettings, sessionID: String): List<FormInfoDto>

    /** Un formulaire **avec son etat** (`GET /api/session/{id}/form/{formID}`). */
    suspend fun sessionForm(
        settings: ConnectionSettings,
        sessionID: String,
        formID: String,
    ): FormDetailDto?

    /**
     * Repond a un formulaire (`POST /api/session/{id}/form/{formID}/reply`).
     *
     * ⚠️ Un formulaire bloque l'agent **au meme titre qu'une permission**. On n'envoie **jamais**
     * une valeur inventee pour « que ca passe » : le serveur valide finement (type attendu,
     * `pattern`, bornes, options, champs `when` inactifs, externes non acquittes) et rend `400`
     * avec une raison precise. Un formulaire mal repondu debloque l'agent sur une intention qui
     * n'est pas celle de l'utilisateur — c'est pire qu'un blocage visible.
     *
     * @return `true` sur `204` (accepte). Les echecs remontent comme exceptions HTTP (400/404/409)
     *   pour que l'appelant distingue « formulaire deja regle » d'une erreur reseau.
     */
    suspend fun replyForm(
        settings: ConnectionSettings,
        sessionID: String,
        formID: String,
        answer: Map<String, FormAnswerValue>,
    ): Boolean

    // ------------------------------------------------------------------
    // Divers (routes utilisees mais jusqu'ici derivees cote client)
    // ------------------------------------------------------------------

    /**
     * **Le modele par defaut du serveur** (`GET /api/model/default`).
     *
     * ⚠️ L'app derivait jusqu'ici le defaut cote client (premier modele du catalogue, ou celui
     * d'une session recente). Mesure du 2026-09-26 : le serveur repond `glm-5.3-flash`, qui peut
     * differer du premier du catalogue. Demander au serveur est la seule facon de ne pas mentir.
     */
    suspend fun defaultModel(settings: ConnectionSettings): Model?

    /** La **base de revision** deduite par le serveur (`GET /api/vcs/base`), ou `null`. */
    suspend fun vcsBase(settings: ConnectionSettings): VcsBaseDto?

    /**
     * **Attend que la boucle d'agent devienne idle** (`POST /api/experimental/session/{id}/wait`).
     *
     * ⚠️ Mesure du 2026-09-26 : sur une session **idle**, rend **204 immediatement** (8 ms) ; sur
     * une session **en cours**, la requete **bloque** jusqu'a la fin de la boucle (9,1 s mesures).
     * Sur un id inconnu, `404 SessionNotFoundError`.
     *
     * ⚠️ C'est le remplacant naturel du sondage `activeSessions` pour « le travail de fond est-il
     * fini » : au lieu de redemander toutes les 6 s, on **attend la fin** en une requete. La
     * signature prend un `timeoutMillis` : le client ne doit pas rester suspendu indefiniment, et
     * un appelant qui abandonne doit pouvoir le faire — d'ou le `false` (non confirme) plutot
     * qu'une exception, distinct d'un vrai echec.
     */
    suspend fun waitForIdle(
        settings: ConnectionSettings,
        sessionID: String,
        timeoutMillis: Long = DEFAULT_WAIT_TIMEOUT_MS,
    ): Boolean


    companion object {
        /** 200 tient en 4 pages pour 428 sessions (mesure 2026-09-25), sans charger d'un bloc. */
        const val DEFAULT_PAGE_SIZE: Int = 200

        /** Garde-fou : jamais de boucle de pagination non bornee. */
        const val MAX_SESSION_PAGES: Int = 40
        const val MAX_MESSAGE_PAGES: Int = 40

        /**
         * Plafond par defaut d'un [waitForIdle].
         *
         * ⚠️ On ne laisse **jamais** une attente sans borne : un agent bloque (sur une permission
         * ou un formulaire) ne devient jamais idle, et l'appel resterait suspendu pour toujours.
         * 10 minutes couvrent un long tour d'outil sans immobiliser l'app au-dela.
         */
        const val DEFAULT_WAIT_TIMEOUT_MS: Long = 10 * 60 * 1000L
    }
}

@Singleton
class KtorOpenCodeGateway @Inject constructor(
    private val http: io.ktor.client.HttpClient,
    private val credentialsProvider: CredentialsProvider,
) : OpenCodeGateway {

    private fun client(settings: ConnectionSettings): OpenCodeClient =
        OpenCodeClient(settings.baseUrl, credentialsProvider, http)

    /**
     * Un client pour une adresse qui ne vient pas des reglages — celle du QR scanne.
     *
     * Meme [credentialsProvider] : le mot de passe de l'utilisateur, pas un autre. Et on ne
     * tente rien d'autre si le serveur le refuse.
     */
    private fun client(baseUrl: String): OpenCodeClient =
        OpenCodeClient(baseUrl, credentialsProvider, http)

    override suspend fun info(settings: ConnectionSettings): ServerInfo =
        client(settings).info()

    override suspend fun sessionsPage(
        settings: ConnectionSettings,
        limit: Int?,
        cursor: String?,
        parent: SessionParent?,
    ): CursorPage<Session> = client(settings).sessionsPage(settings.directory, limit, cursor, parent)

    override suspend fun models(settings: ConnectionSettings): List<Model> =
        client(settings).models(settings.directory)

    override suspend fun agents(settings: ConnectionSettings): List<Agent> =
        client(settings).agents(settings.directory)

    override suspend fun createSession(
        settings: ConnectionSettings,
        model: ModelRef?,
        agent: String?,
    ): Session = client(settings).createSession(
        location = settings.directory,
        model = model,
        agent = agent,
    )

    override suspend fun session(settings: ConnectionSettings, sessionID: String): Session =
        client(settings).session(sessionID)

    override suspend fun prompt(
        settings: ConnectionSettings,
        sessionID: String,
        text: String,
    ): PromptAcceptance = client(settings).prompt(sessionID, text)

    override suspend fun prompt(
        settings: ConnectionSettings,
        sessionID: String,
        body: PromptBody,
    ): PromptAcceptance = client(settings).prompt(sessionID, body)

    override suspend fun messagesPage(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int?,
        cursor: String?,
        order: String?,
    ): CursorPage<MessageDto> = client(settings).messagesPage(sessionID, limit, cursor, order)

    /**
     * **Une page d'historique, en une requete — remontee a l'endroit.**
     *
     * ⚠️ **Ce que cette methode remplace, et pourquoi.** L'ancien couple
     * `recentMessages` + `messagesBefore` etait en **O(n²)** : `recentMessages` jetait le
     * curseur, donc `messagesBefore` devait repartir du sommet et redescendre jusqu'au
     * message de reference a **chaque** tranche. 1 requete pour la 1re, 2 pour la 2e, 3 pour
     * la 3e… soit ~200 requetes pour remonter 2 040 messages, au lieu de 21 en suivant le
     * curseur.
     *
     * ⚠️ **L'ancien code portait un bug mesure** (`first=0`, `hasOlder=true`, liste bloquee) :
     * la reference etant presque toujours dans la premiere page, la boucle s'arretait
     * immediatement et le `subList` rendait une liste vide. Ce detour n'existe plus : on ne
     * cherche plus une reference, on **suit un curseur**.
     *
     * ⚠️ `order` n'est envoye qu'au **premier** appel : le combiner a un curseur rend un
     * **400** (verifie), l'ordre etant encode dans le curseur lui-meme.
     */
    override suspend fun messagesPageBack(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int,
        cursor: String?,
    ): HistoryPage {
        val page = client(settings).messagesPage(
            sessionID = sessionID,
            limit = limit,
            cursor = cursor,
            // ⚠️ `order` seulement au premier appel : avec un curseur, le serveur rend 400.
            order = if (cursor == null) "desc" else null,
        )
        // ⚠️ Le serveur en `desc` annonce un `next` **meme sur la derniere page** ; suivre ce
        // curseur rend une page vide. On le **sonde** donc ici, pour que `cursorBack == null`
        // soit une vraie preuve de fin d'historique — sinon l'UI garderait « charger plus »
        // affiche pour toujours (c'etait le bug B13).
        //
        // La sonde telecharge **1 message**, pas une page : elle tourne a chaque appel tant
        // que la fin n'est pas atteinte (resync comprise), et une page de 40 pesait jusqu'a
        // 3 Mo de JSON dont rien n'etait garde.
        val next = page.next
        val exhausted = next == null ||
            client(settings).messagesPage(sessionID, 1, cursor = next, order = null).data.isEmpty()

        return HistoryPage(
            // ⚠️ On remet a l'endroit : le serveur rend du plus recent au plus ancien, l'ecran
            // lit du plus ancien au plus recent.
            messages = page.data.reversed(),
            cursorBack = next.takeIf { !exhausted },
        )
    }

    override suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean =
        client(settings).interrupt(sessionID)

    override suspend fun renameSession(
        settings: ConnectionSettings,
        sessionID: String,
        title: String,
    ): Boolean = client(settings).renameSession(sessionID, title)

    override suspend fun deleteSession(settings: ConnectionSettings, sessionID: String) =
        client(settings).deleteSession(sessionID)

    override suspend fun forkSession(settings: ConnectionSettings, sessionID: String): Session =
        client(settings).forkSession(sessionID)

    override suspend fun compactSession(settings: ConnectionSettings, sessionID: String) =
        client(settings).compactSession(sessionID)

    // ------------------------------------------------------------------
    // Statistiques et inventaire
    // ------------------------------------------------------------------

    override suspend fun stats(settings: ConnectionSettings, fromMillis: Long?): UsageStats =
        client(settings).stats(settings.directory, fromMillis).toUsageStats()

    override suspend fun commands(settings: ConnectionSettings): List<CommandDto> =
        client(settings).commands(settings.directory)

    override suspend fun skills(settings: ConnectionSettings): List<SkillDto> =
        client(settings).skills(settings.directory)

    override suspend fun mcpServers(settings: ConnectionSettings): List<McpServerDto> =
        client(settings).mcpServers(settings.directory)

    override suspend fun registerDevice(
        settings: ConnectionSettings,
        server: String,
        deviceId: String,
        endpoint: String,
        p256dh: String,
        authSecret: String,
        pairingToken: String,
        distributor: String?,
    ): Boolean = client(server).registerDevice(
        deviceId = deviceId,
        endpoint = endpoint,
        p256dh = p256dh,
        authSecret = authSecret,
        pairingToken = pairingToken,
        label = null,
        distributor = distributor,
        location = settings.directory,
    )

    override suspend fun unregisterDevice(
        settings: ConnectionSettings,
        server: String,
        deviceId: String,
    ): Boolean = client(server).unregisterDevice(deviceId, settings.directory)

    override suspend fun devices(settings: ConnectionSettings, server: String): List<String> =
        client(server).devices(settings.directory)

    override suspend fun plugins(settings: ConnectionSettings): List<PluginDto> =
        client(settings).plugins(settings.directory)

    override suspend fun providers(settings: ConnectionSettings): List<ProviderDto> =
        client(settings).providers(settings.directory)

    override suspend fun savedPermissions(settings: ConnectionSettings): List<SavedPermissionDto> =
        client(settings).savedPermissions(settings.directory)

    override suspend fun revokePermission(settings: ConnectionSettings, permissionID: String): Boolean =
        client(settings).revokePermission(permissionID)

    override suspend fun projects(settings: ConnectionSettings): List<ProjectDto> =
        client(settings).projects()

    // ------------------------------------------------------------------
    // Etat vivant
    // ------------------------------------------------------------------

    override suspend fun activeSessions(settings: ConnectionSettings): Set<String> =
        client(settings).activeSessions(settings.directory).keys

    override suspend fun shells(settings: ConnectionSettings): List<ShellInfoDto> =
        client(settings).shells(settings.directory)

    override suspend fun terminals(settings: ConnectionSettings): List<PtyInfoDto> =
        client(settings).terminals(settings.directory)

    override suspend fun shellOutput(
        settings: ConnectionSettings,
        shellID: String,
        cursor: Int?,
    ): ShellOutputDto? = client(settings).shellOutput(shellID, cursor)

    override suspend fun markViewed(
        settings: ConnectionSettings,
        sessionID: String,
        idle: Long,
    ): Boolean = client(settings).markViewed(sessionID, idle)

    override suspend fun backgroundTools(settings: ConnectionSettings, sessionID: String): Boolean =
        client(settings).backgroundTools(sessionID)

    override suspend fun activateSkill(
        settings: ConnectionSettings,
        sessionID: String,
        skillID: String,
    ): Boolean = client(settings).activateSkill(sessionID, skillID)

    // ------------------------------------------------------------------
    // Explorateur de fichiers, references, branches
    // ------------------------------------------------------------------

    override suspend fun fsList(settings: ConnectionSettings, path: String?): List<FsEntryDto> =
        client(settings).fsList(settings.directory, path)

    override suspend fun fsFind(
        settings: ConnectionSettings,
        query: String,
        type: String?,
    ): List<FsEntryDto> = client(settings).fsFind(settings.directory, query, type)

    override suspend fun fsRead(settings: ConnectionSettings, path: String): ByteArray? =
        client(settings).fsRead(settings.directory, path)

    override suspend fun references(settings: ConnectionSettings): List<ReferenceDto> =
        client(settings).references(settings.directory)

    override suspend fun branches(settings: ConnectionSettings): List<String> =
        client(settings).branches(settings.directory)

    override suspend fun branchesIn(
        settings: ConnectionSettings,
        directory: String,
    ): List<String> = client(settings).branches(directory)


    override suspend fun pendingPermissions(settings: ConnectionSettings): List<PermissionRequest> {
        val http = client(settings)
        return http.permissionRequests(settings.directory).map { it.toAsk() }
    }

    override suspend fun sessionPermissions(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<PermissionRequest> {
        val http = client(settings)
        return http.sessionPermissionRequests(sessionID).map { it.toAsk() }
    }

    override suspend fun replyPermission(
        settings: ConnectionSettings,
        sessionID: String,
        requestID: String,
        decision: PermissionDecision,
        message: String?,
    ): Boolean = client(settings).replyPermission(sessionID, requestID, decision.wire, message)

    // ------------------------------------------------------------------
    // Diffs, contexte, worktrees, revert
    // ------------------------------------------------------------------

    override suspend fun sessionDiff(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<FileDiffDto> = client(settings).sessionDiff(sessionID)

    override suspend fun vcsDiff(
        settings: ConnectionSettings,
        mode: String,
    ): List<FileDiffDto> = client(settings).vcsDiff(settings.directory, mode)

    override suspend fun vcsDiffIn(
        settings: ConnectionSettings,
        directory: String,
        mode: String,
    ): List<FileDiffDto> = client(settings).vcsDiff(directory, mode)

    override suspend fun vcsInfo(
        settings: ConnectionSettings,
        directory: String,
    ): VcsInfoDto? = client(settings).vcsInfo(directory)

    /**
     * ⚠️ On renvoie `null` si le serveur ne donne pas de projet : ce n'est **pas** une erreur
     * (le repertoire peut ne pas etre un depot connu), et fabriquer un identifiant serait pire que
     * l'absence — un projectID invente ferait echouer `/api/worktree` avec un message obscur.
     */
    override suspend fun projectID(settings: ConnectionSettings): String? =
        runCatching { client(settings).location(settings.directory).project?.id }.getOrNull()

    override suspend fun vcsStatus(settings: ConnectionSettings): List<VcsFileStatusDto> =
        client(settings).vcsStatus(settings.directory)

    override suspend fun sessionContext(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<ContextMessageDto> = client(settings).sessionContext(sessionID)

    override suspend fun stageRevert(
        settings: ConnectionSettings,
        sessionID: String,
        messageID: String,
    ): RevertResultDto? = client(settings).revertStage(sessionID, messageID)

    override suspend fun commitRevert(settings: ConnectionSettings, sessionID: String): Boolean =
        client(settings).revertCommit(sessionID)

    override suspend fun discardRevert(settings: ConnectionSettings, sessionID: String): Boolean =
        client(settings).revertDiscard(sessionID)

    override suspend fun worktrees(settings: ConnectionSettings): List<WorktreeDirDto> {
        val id = projectID(settings) ?: return emptyList()
        return client(settings).worktrees(id)
    }

    override suspend fun createWorktree(
        settings: ConnectionSettings,
        name: String?,
    ): WorktreeInfoDto {
        val id = projectID(settings) ?: error(Res.of(R.string.aucun_projet_identifie_47f15f))
        return client(settings).createWorktree(id, name)
    }

    override suspend fun removeWorktree(
        settings: ConnectionSettings,
        directory: String,
        force: Boolean,
    ): Boolean {
        val id = projectID(settings) ?: return false
        return client(settings).removeWorktree(id, directory, force)
    }

    override suspend fun setSessionModel(
        settings: ConnectionSettings,
        sessionID: String,
        model: ModelRef,
    ): Boolean = client(settings).setSessionModel(sessionID, model)

    override suspend fun setSessionAgent(
        settings: ConnectionSettings,
        sessionID: String,
        agent: String,
    ): Boolean = client(settings).setSessionAgent(sessionID, agent)

    override suspend fun runCommand(
        settings: ConnectionSettings,
        sessionID: String,
        name: String,
        text: String,
    ): Boolean = client(settings).runCommand(sessionID, name, text)

    override suspend fun sessionInbox(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<InboxItemDto> = client(settings).sessionInbox(sessionID)

    override suspend fun dismissInbox(
        settings: ConnectionSettings,
        sessionID: String,
        inboxID: String,
    ): Boolean = client(settings).dismissInbox(sessionID, inboxID)

    override suspend fun updateInboxDelivery(
        settings: ConnectionSettings,
        sessionID: String,
        inboxID: String,
        delivery: String,
    ): Boolean = client(settings).updateInboxDelivery(sessionID, inboxID, delivery)

    // ------------------------------------------------------------------
    // Formulaires
    // ------------------------------------------------------------------

    override suspend fun pendingForms(settings: ConnectionSettings): List<FormInfoDto> =
        client(settings).forms(settings.directory)

    override suspend fun sessionForms(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<FormInfoDto> = client(settings).sessionForms(sessionID)

    override suspend fun sessionForm(
        settings: ConnectionSettings,
        sessionID: String,
        formID: String,
    ): FormDetailDto? = client(settings).sessionForm(sessionID, formID)

    /**
     * ⚠️ La traduction `Map<String, FormAnswerValue>` -> `JsonObject` vit sur le **type de
     * valeur** ([FormAnswerValue.toJson]), pas ici : un entier part en entier et une chaine en
     * chaine. C'est exactement le point ou le serveur refuse une forme approchee
     * (`400 Expected integer`, mesure du 2026-09-26).
     */
    override suspend fun replyForm(
        settings: ConnectionSettings,
        sessionID: String,
        formID: String,
        answer: Map<String, FormAnswerValue>,
    ): Boolean = client(settings).replyForm(
        sessionID = sessionID,
        formID = formID,
        answer = JsonObject(answer.mapValues { (_, value) -> value.toJson() }),
    )

    override suspend fun defaultModel(settings: ConnectionSettings): Model? =
        client(settings).defaultModel(settings.directory)

    override suspend fun vcsBase(settings: ConnectionSettings): VcsBaseDto? =
        client(settings).vcsBase(settings.directory)

    /**
     * ⚠️ Le timeout est **a la charge de l'appelant**, pas du client : `waitForIdle` bloque jusqu'a
     * ce que le serveur reponde ou que le moteur HTTP abandonne. On remonte `false` quand la reponse
     * n'est pas un succes (y compris un abandon), et on laisse les erreurs reseau remonter — un
     * serveur injoignable n'est pas « toujours en cours ».
     */
    override suspend fun waitForIdle(
        settings: ConnectionSettings,
        sessionID: String,
        timeoutMillis: Long,
    ): Boolean = withTimeoutOrNull(timeoutMillis) {
        client(settings).waitForIdle(sessionID, timeoutMillis)
    } ?: false


}

/**
 * Traduit le DTO du serveur en modele de domaine.
 *
 * ⚠️ Tous les champs sont **nullable cote DTO** : le serveur peut omettre une section
 * (`tools`, `tokens.cache`, `models`) selon la plage ou la version. On normalise ici, une fois,
 * pour que l'UI n'ait jamais a gerer de `null` — un ecran qui doit verifier 12 champs nullables
 * finit par en oublier un, et affiche « 0 » la ou il n'y a pas de donnee.
 *
 * ⚠️ **`null` n'est pas `0`** : un cout absent et un cout nul sont deux faits differents. Le DTO
 * porte deja des valeurs par defaut a 0 pour les nombres, donc on ne peut plus les distinguer
 * apres coup — mais sur ces champs-la, une absence signifie bien « rien compte », pas « inconnu ».
 */
private fun StatsDto?.toUsageStats(): UsageStats {
    val dto = this ?: return UsageStats()
    return UsageStats(
        fromMillis = dto.range?.from,
        toMillis = dto.range?.to,
        sessions = dto.sessions,
        subagents = dto.subagents,
        prompts = dto.prompts,
        steps = dto.steps,
        inputTokens = dto.tokens?.input ?: 0,
        outputTokens = dto.tokens?.output ?: 0,
        reasoningTokens = dto.tokens?.reasoning ?: 0,
        cacheReadTokens = dto.tokens?.cache?.read ?: 0,
        cacheWriteTokens = dto.tokens?.cache?.write ?: 0,
        cost = dto.cost,
        toolCalls = dto.tools?.totals?.calls ?: 0,
        toolSucceeded = dto.tools?.totals?.succeeded ?: 0,
        toolFailed = dto.tools?.totals?.failed ?: 0,
        toolUnfinished = dto.tools?.totals?.unfinished ?: 0,
        activeDays = dto.activeDays,
        streak = dto.streak,
        activity = dto.activity.map { DailyActivity(date = it.date, steps = it.steps) },
        models = dto.models
            .map { m ->
                ModelUsage(
                    model = m.model?.id.orEmpty(),
                    provider = m.model?.providerID.orEmpty(),
                    steps = m.steps,
                    inputTokens = m.tokens?.input ?: 0,
                    outputTokens = m.tokens?.output ?: 0,
                    cacheReadTokens = m.tokens?.cache?.read ?: 0,
                    cost = m.cost,
                )
            }
            // ⚠️ Tri par COUT, pas par etapes : c'est ce qu'on paie qui doit venir en premier.
            // Un modele tres utilise mais bon marche ne doit pas masquer celui qui coute cher.
            .sortedByDescending { it.cost },
    )
}

/**
 * Traduit la demande du serveur vers le modele d'affichage.
 *
 * ⚠️ Aucun resume : on transporte l'action et les ressources **telles quelles**. Reformuler
 * « bash » en « commande » ferait perdre le mot que l'utilisateur doit reconnaitre pour decider.
 */
private fun PermissionAskDto.toAsk(): PermissionRequest = PermissionRequest(
    id = id,
    sessionID = sessionID,
    action = action,
    resources = resources,
    save = save,
    message = message,
)
