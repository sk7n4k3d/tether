package sh.sk7.tether.data.api

import sh.sk7.tether.data.settings.ConnectionSettings
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

    suspend fun sessions(settings: ConnectionSettings): List<Session>

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
            val page = messagesPage(settings, sessionID, pageSize, cursor, order = if (pages == 0) "asc" else null)
            all += page.data
            cursor = page.next
            pages++
        } while (cursor != null && pages < MAX_MESSAGE_PAGES)
        return all
    }

    suspend fun messagesPage(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int?,
        cursor: String?,
        order: String? = null,
    ): CursorPage<MessageDto>

    /** `POST /interrupt` : stoppe l'execution en cours. */
    suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean

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

    override suspend fun sessions(settings: ConnectionSettings): List<Session> =
        client(settings).sessionsPage(settings.directory).data

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

    override suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean =
        client(settings).interrupt(sessionID)
}
