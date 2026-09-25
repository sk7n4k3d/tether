package sh.sk7.tether.data.api

import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.domain.model.DailyActivity
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.domain.model.ModelUsage
import sh.sk7.tether.domain.model.UsageStats
import javax.inject.Inject
import javax.inject.Singleton

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
     */
    suspend fun allSessions(
        settings: ConnectionSettings,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): List<Session> {
        val all = mutableListOf<Session>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = sessionsPage(settings, pageSize, cursor)
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
     */
    suspend fun sessionsPage(
        settings: ConnectionSettings,
        limit: Int?,
        cursor: String?,
    ): CursorPage<Session>

    suspend fun models(settings: ConnectionSettings): List<Model>

    suspend fun agents(settings: ConnectionSettings): List<Agent>

    suspend fun createSession(
        settings: ConnectionSettings,
        title: String,
        model: ModelRef,
        agent: String?,
    ): Session

    /** Une session precise (`GET /api/session/{id}`), pour titrer l'ecran de chat. */
    suspend fun session(settings: ConnectionSettings, sessionID: String): Session

    /** `POST /prompt` : accepte le prompt. La reponse arrive ensuite par le flux. */
    suspend fun prompt(settings: ConnectionSettings, sessionID: String, text: String): PromptAcceptance

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
     * **Les N derniers messages**, dans l'ordre chronologique, charges en une requete.
     *
     * ### Pourquoi cette methode existe
     * `allMessages` pagine **tout** l'historique depuis le debut. Mesure : les sessions lourdes
     * font **810 messages en moyenne**, jusqu'a 2 040. Charger tout pour n'en afficher que la fin
     * est un gaspillage pur, a chaque ouverture et a chaque reconnexion.
     *
     * ⚠️ **`order = "desc"` est indispensable ici, et c'est contre-intuitif** : on veut la FIN
     * de la conversation, donc les messages **les plus recents**. Demander `asc` avec une limite
     * renverrait les **premiers** — c'est-a-dire l'inverse exact de ce qu'on cherche. On repasse
     * ensuite la liste a l'endroit pour l'affichage.
     *
     * ⚠️ Une seule page, volontairement : demander `order` **avec** un curseur rend un
     * **400** (verifie sur le serveur). Si la page est pleine, on renvoie ce qu'on a — le reste
     * se chargera en remontant.
     */
    suspend fun recentMessages(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int,
    ): List<MessageDto>

    /**
     * Messages **anterieurs** a un message donne, dans l'ordre chronologique.
     *
     * Sert au scroll vers le haut : on remonte depuis un point connu, pas depuis un index local —
     * celui-ci ne correspond pas a la position serveur, puisque les marqueurs de tour (`idle`,
     * `synthetic`…) ne produisent pas de bulle et sont filtres a l'affichage.
     *
     * ⚠️ On ne peut PAS combiner `order` et `cursor` (**400** verifie) : l'ordre est encode par
     * le curseur lui-meme. On part donc de la tranche recente en `desc`, puis on remonte avec le
     * curseur `previous` qu'elle expose, jusqu'a croiser le message de reference.
     */
    suspend fun messagesBefore(
        settings: ConnectionSettings,
        sessionID: String,
        beforeMessageID: String,
        limit: Int,
    ): List<MessageDto>

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

    companion object {
        /** 200 tient en 4 pages pour 428 sessions (mesure 2026-09-25), sans charger d'un bloc. */
        const val DEFAULT_PAGE_SIZE: Int = 200

        /** Garde-fou : jamais de boucle de pagination non bornee. */
        const val MAX_SESSION_PAGES: Int = 40
        const val MAX_MESSAGE_PAGES: Int = 40
    }
}

@Singleton
class KtorOpenCodeGateway @Inject constructor(
    private val http: io.ktor.client.HttpClient,
    private val credentialsProvider: CredentialsProvider,
) : OpenCodeGateway {

    private fun client(settings: ConnectionSettings): OpenCodeClient =
        OpenCodeClient(settings.baseUrl, credentialsProvider, http)

    override suspend fun info(settings: ConnectionSettings): ServerInfo =
        client(settings).info()

    override suspend fun sessionsPage(
        settings: ConnectionSettings,
        limit: Int?,
        cursor: String?,
    ): CursorPage<Session> = client(settings).sessionsPage(settings.directory, limit, cursor)

    override suspend fun models(settings: ConnectionSettings): List<Model> =
        client(settings).models(settings.directory)

    override suspend fun agents(settings: ConnectionSettings): List<Agent> =
        client(settings).agents(settings.directory)

    override suspend fun createSession(
        settings: ConnectionSettings,
        title: String,
        model: ModelRef,
        agent: String?,
    ): Session = client(settings).createSession(
        title = title,
        model = model,
        location = settings.directory,
        agent = agent,
    )

    override suspend fun session(settings: ConnectionSettings, sessionID: String): Session =
        client(settings).session(sessionID)

    override suspend fun prompt(
        settings: ConnectionSettings,
        sessionID: String,
        text: String,
    ): PromptAcceptance = client(settings).prompt(sessionID, text)

    override suspend fun messagesPage(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int?,
        cursor: String?,
        order: String?,
    ): CursorPage<MessageDto> = client(settings).messagesPage(sessionID, limit, cursor, order)

    /** `desc` : on veut la FIN de la conversation, puis on remet la tranche a l'endroit. */
    override suspend fun recentMessages(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int,
    ): List<MessageDto> =
        client(settings)
            .messagesPage(sessionID, limit, cursor = null, order = "desc")
            .data
            .reversed()

    /**
     * Remonte l'historique depuis un message connu.
     *
     * ⚠️ **Le bug qui a mordu ici** : la reference est presque toujours **deja dans la premiere
     * page** (c'est le message le plus ancien de la fenetre courante). La boucle s'arretait donc
     * immediatement, et `subList(index + 1, size)` renvoyait une liste **vide** — le chargement
     * ne ramenait jamais rien. Symptome mesure : `first=0`, `hasOlder=true`, et `msgs` bloque.
     *
     * Le critere d'arret correct n'est pas « la reference est trouvee » mais « la reference est
     * trouvee **et il reste des messages apres elle** » dans ce qu'on a collecte.
     *
     * ⚠️ En ordre `desc`, le curseur **`next`** mene vers les messages **plus anciens**, et
     * `previous` est vide (verifie sur le serveur). On ne peut pas non plus combiner `order` et
     * `cursor` (**400**) : l'ordre est encode dans le curseur.
     */
    override suspend fun messagesBefore(
        settings: ConnectionSettings,
        sessionID: String,
        beforeMessageID: String,
        limit: Int,
    ): List<MessageDto> {
        val http = client(settings)
        val recent = http.messagesPage(sessionID, limit, cursor = null, order = "desc")
        val collected = mutableListOf<MessageDto>()
        collected += recent.data
        var olderCursor = recent.next
        var pages = 0

        while (pages < OpenCodeGateway.MAX_MESSAGE_PAGES) {
            val index = collected.indexOfFirst { it.id == beforeMessageID }
            // On s'arrete des qu'on a la reference ET au moins un message plus ancien qu'elle.
            if (index >= 0 && index < collected.size - 1) break
            // Reference absente du tout : on a epuise l'historique.
            if (olderCursor == null) break
            val older = http.messagesPage(sessionID, limit, cursor = olderCursor, order = null)
            if (older.data.isEmpty()) break
            collected += older.data
            olderCursor = older.next
            pages++
        }

        val index = collected.indexOfFirst { it.id == beforeMessageID }
        // Tout ce qui suit la reference dans l'ordre `desc` = tout ce qui est plus ANCIEN.
        val older = if (index >= 0) collected.subList(index + 1, collected.size) else collected
        return older.reversed()
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
