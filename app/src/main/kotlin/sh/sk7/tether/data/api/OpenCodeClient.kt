package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Client REST du serveur opencode V2.
 *
 * Le serveur se protege par HTTP basic (`opencode:<motdepasse>`), pas par Bearer.
 * Les identifiants sont resolus **a chaque requete** via [CredentialsProvider] : c'est ce
 * qui permet a `ConnectionStore` de changer le mot de passe sans reconstruire le client.
 *
 * ⚠️ Noms de parametres query **reels** (verifies sur `/openapi.json`) :
 * - `GET /api/session` : `directory` (PAS `location[directory]`, silencieusement ignore)
 * - `GET /api/session/{id}/message` : `limit`, `order`, `cursor`, `type` (aucun `location`)
 * - `GET /api/model` · `/api/agent` : `location[directory]` (`style: deepObject`)
 *   ⚠️ `?location=/chemin` (valeur simple) est **rejete** par le serveur (`InvalidRequestError`)
 * - `POST /api/session` : le `location` va dans le **corps**
 * - `POST /api/session/{id}/prompt` : **aucun** parametre query
 */
class OpenCodeClient(
    baseUrl: String,
    private val credentialsProvider: CredentialsProvider,
    private val http: HttpClient,
) {
    /**
     * Variante a identifiants fixes (tests, appel ponctuel de diagnostic). Le client de
     * production doit preferer [CredentialsProvider] pour rester reactif aux reglages.
     */
    constructor(baseUrl: String, credentials: BasicAuthCredentials, http: HttpClient) :
        this(baseUrl, FixedCredentialsProvider(credentials), http)

    private val baseUrl: String = baseUrl.trimEnd('/')

    suspend fun info(): ServerInfo {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/info") { auth(credentials) }.body()
    }

    /**
     * Une page de `GET /api/session`. ⚠️ La route pagine par defaut a **50** : sans suivre
     * `cursor.next`, la liste est tronquee en silence (428 sessions reelles le 2026-09-25).
     * `limit` est honore par le serveur (verifie : 200 par page en 4 pages).
     */
    suspend fun sessionsPage(
        directory: String,
        limit: Int? = null,
        cursor: String? = null,
    ): CursorPage<Session> {
        val credentials = credentialsProvider.credentials()
        val envelope = http.get("$baseUrl/api/session") {
            auth(credentials)
            parameter("directory", directory)
            limit?.let { parameter("limit", it) }
            cursor?.let { parameter("cursor", it) }
        }.body<DataEnvelope<Session>>()
        return envelope.toPage()
    }

    suspend fun sessions(directory: String, limit: Int? = null, cursor: String? = null): List<Session> =
        sessionsPage(directory, limit, cursor).data

    /**
     * Une page de `GET /api/session/{id}/message`.
     *
     * ⚠️ `order` ne s'applique qu'a la **premiere** page : l'OpenAPI precise « Do not combine
     * with order », le curseur porte deja le sens. Verifie : `order=asc` puis `cursor.next`
     * pagine vers l'avant **sans recouvrement**.
     */
    suspend fun messagesPage(
        sessionID: String,
        limit: Int? = null,
        cursor: String? = null,
        order: String? = null,
        type: String? = null,
    ): CursorPage<MessageDto> {
        val credentials = credentialsProvider.credentials()
        val envelope = http.get("$baseUrl/api/session/$sessionID/message") {
            auth(credentials)
            limit?.let { parameter("limit", it) }
            cursor?.let { parameter("cursor", it) }
            order?.let { parameter("order", it) }
            type?.let { parameter("type", it) }
        }.body<DataEnvelope<MessageDto>>()
        return envelope.toPage()
    }

    suspend fun messages(
        sessionID: String,
        limit: Int? = null,
        cursor: String? = null,
        order: String? = null,
        type: String? = null,
    ): List<MessageDto> = messagesPage(sessionID, limit, cursor, order, type).data

    /** `POST /api/session/{id}/interrupt` : stoppe l'execution en cours. `{interrupted}`. */
    suspend fun interrupt(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/interrupt") {
            auth(credentials)
        }.body<InterruptResponse>().interrupted
    }

    suspend fun models(location: String): List<Model> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/model") {
            auth(credentials)
            parameter("location[directory]", location)
        }.body<DataEnvelope<Model>>().data
    }

    /**
     * `GET /api/agent` : meme parametre deepObject que `/api/model`
     * (`location[directory]`, style `deepObject` dans l'OpenAPI). Sans lui, `data: []`
     * sans erreur.
     */
    suspend fun agents(location: String): List<Agent> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/agent") {
            auth(credentials)
            parameter("location[directory]", location)
        }.body<DataEnvelope<Agent>>().data
    }

    /** `GET /api/session/{id}` : renvoie `{data: <Session>}` (un objet), pour le titre. */
    suspend fun session(sessionID: String): Session {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID") {
            auth(credentials)
        }.body<SessionEnvelope>().data
    }

    /** `POST /api/session` renvoie `{data: <Session>}` = un OBJET, pas un tableau. */
    suspend fun createSession(
        title: String,
        model: ModelRef,
        location: String,
        agent: String? = null,
    ): Session {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(
                CreateSessionBody(
                    title = title,
                    model = model,
                    location = LocationBody(location),
                    agent = agent?.takeIf { it.isNotBlank() },
                ),
            )
        }.body<SessionEnvelope>().data
    }

    /** `POST /prompt` : le texte est dans `payload.text`, la reponse est `{data: <msg_*>}`. */
    suspend fun prompt(sessionID: String, text: String): PromptAcceptance {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/prompt") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(PromptBody(text = text))
        }.body<PromptEnvelope>().data
    }

    /**
     * `PATCH /api/session/{id}` — seuls `title` et `permissions` sont acceptes.
     *
     * ⚠️ **204 sans corps** (verifie sur le serveur) : ne pas tenter de decoder une reponse.
     * Le faire levait `NoTransformationFoundException` et l'app annoncait « Échec de la
     * connexion » alors que le renommage avait **reussi** — une erreur inventee.
     */
    suspend fun renameSession(sessionID: String, title: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.patch("$baseUrl/api/session/$sessionID") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(RenameSessionBody(title = title))
        }
        return response.status.isSuccess()
    }

    /** `DELETE /api/session/{id}` — 204 sans corps. */
    suspend fun deleteSession(sessionID: String) {
        val credentials = credentialsProvider.credentials()
        http.delete("$baseUrl/api/session/$sessionID") { auth(credentials) }
    }

    /**
     * `POST /api/session/{id}/fork` — `{}` forke la session entiere (`before` optionnel).
     *
     * ⚠️ La reponse est la **nouvelle** session, pas l'ancienne.
     */
    suspend fun forkSession(sessionID: String): Session {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/fork") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(ForkSessionBody())
        }.body<SessionEnvelope>().data
    }

    /** `POST /api/session/{id}/compact` — `{}` suffit (`id`/`delivery` optionnels). */
    suspend fun compactSession(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/compact") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(ForkSessionBody())
        }
        return response.status.isSuccess()
    }

    // ------------------------------------------------------------------
    // Statistiques et inventaire du serveur
    // ------------------------------------------------------------------

    /**
     * `GET /api/experimental/session/stats` : les statistiques d'usage.
     *
     * ⚠️ `from` (millisecondes) restreint la plage — verifie sur le serveur : avec
     * `from=1790000000000`, `sessions` passe de 146 a 73 et l'activite de 13 a 5 jours.
     * Sans borne, le serveur renvoie tout son historique.
     */
    suspend fun stats(location: String, fromMillis: Long? = null): StatsDto? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/experimental/session/stats") {
            auth(credentials)
            parameter("directory", location)
            fromMillis?.let { parameter("from", it) }
        }.body<StatsEnvelope>().data
    }

    /**
     * Les **collections** de l'API, qui partagent toutes l'enveloppe `{location, data}`.
     *
     * ⚠️ Enveloppe **differente** de `{data}` utilisee ailleurs : se tromper donne une liste vide
     * sans erreur, ce qui est le pire des modes d'echec pour un ecran d'inventaire.
     */
    private suspend inline fun <reified T> located(path: String, location: String): List<T> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl$path") {
            auth(credentials)
            parameter("directory", location)
        }.body<LocatedEnvelope<T>>().data
    }

    suspend fun commands(location: String): List<CommandDto> = located("/api/command", location)

    suspend fun skills(location: String): List<SkillDto> = located("/api/skill", location)

    suspend fun mcpServers(location: String): List<McpServerDto> = located("/api/mcp", location)

    suspend fun plugins(location: String): List<PluginDto> = located("/api/plugin", location)

    suspend fun providers(location: String): List<ProviderDto> = located("/api/provider", location)

    /** `GET /api/permission/saved` : les autorisations memorisees, revocables. */
    suspend fun savedPermissions(location: String): List<SavedPermissionDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/permission/saved") {
            auth(credentials)
            parameter("directory", location)
        }.body<ListEnvelope<SavedPermissionDto>>().data
    }

    /**
     * `DELETE /api/permission/saved/{id}` : revoque une autorisation memorisee.
     *
     * ⚠️ C'est une action de **securite** : elle retire un droit qui avait ete accorde. Elle
     * n'est jamais automatique — seul l'utilisateur la declenche.
     */
    suspend fun revokePermission(permissionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.delete("$baseUrl/api/permission/saved/$permissionID") {
            auth(credentials)
        }
        return response.status.isSuccess()
    }

    /** `GET /api/project` : les projets connus du serveur. */
    suspend fun projects(): List<ProjectDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/project") {
            auth(credentials)
        }.body<ListEnvelope<ProjectDto>>().data
    }

    /**
     * `GET /api/permission/request` : les demandes d'autorisation en attente **sur le serveur**.
     *
     * ⚠️ Enveloppe `{location, data}` **et non** `{data}` — comme `/api/command` et `/api/mcp`.
     * Se tromper d'enveloppe donne une liste vide sans erreur, donc une app qui croit qu'il n'y a
     * rien a approuver alors que l'agent est bloque.
     */
    suspend fun permissionRequests(location: String): List<PermissionAskDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/permission/request") {
            auth(credentials)
            parameter("directory", location)
        }.body<LocatedEnvelope<PermissionAskDto>>().data
    }

    /** `GET /api/session/{id}/permission` : les demandes d'une session precise. */
    suspend fun sessionPermissionRequests(sessionID: String): List<PermissionAskDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/permission") {
            auth(credentials)
        }.body<ListEnvelope<PermissionAskDto>>().data
    }

    /**
     * `POST /api/session/{id}/permission/{requestID}/reply`.
     *
     * ⚠️ `decision` est une des trois chaines `once`, `always`, `reject` (enum
     * `Permission.Reply` de l'OpenAPI). Toute autre valeur est refusee par le serveur.
     */
    suspend fun replyPermission(
        sessionID: String,
        requestID: String,
        decision: String,
        message: String?,
    ): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/permission/$requestID/reply") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(PermissionReplyBody(decision = decision, message = message))
        }
        return response.status.isSuccess()
    }

    private fun HttpRequestBuilder.auth(credentials: BasicAuthCredentials?) {
        if (credentials == null) return
        basicAuth(credentials.username, credentials.password)
    }

    private fun <T> DataEnvelope<T>.toPage(): CursorPage<T> =
        CursorPage(data = data, next = cursor?.next, previous = cursor?.previous)

    private class FixedCredentialsProvider(
        private val fixed: BasicAuthCredentials,
    ) : CredentialsProvider {
        override suspend fun credentials(): BasicAuthCredentials = fixed
    }

    companion object {
        /** JSON tolerant : l'API V2 renvoie des champs non documentes. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/** Installe la negociation de contenu kotlinx sur une config HttpClient. */
fun HttpClientConfig<*>.configureTether() {
    install(ContentNegotiation) { json(OpenCodeClient.json) }
}
