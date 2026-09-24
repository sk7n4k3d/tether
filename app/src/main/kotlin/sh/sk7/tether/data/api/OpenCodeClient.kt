package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
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

    suspend fun sessions(directory: String, limit: Int? = null, cursor: String? = null): List<Session> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session") {
            auth(credentials)
            parameter("directory", directory)
            limit?.let { parameter("limit", it) }
            cursor?.let { parameter("cursor", it) }
        }.body<DataEnvelope<Session>>().data
    }

    suspend fun messages(
        sessionID: String,
        limit: Int? = null,
        cursor: String? = null,
        order: String? = null,
        type: String? = null,
    ): List<MessageDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/message") {
            auth(credentials)
            limit?.let { parameter("limit", it) }
            cursor?.let { parameter("cursor", it) }
            order?.let { parameter("order", it) }
            type?.let { parameter("type", it) }
        }.body<DataEnvelope<MessageDto>>().data
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

    private fun HttpRequestBuilder.auth(credentials: BasicAuthCredentials?) {
        if (credentials == null) return
        basicAuth(credentials.username, credentials.password)
    }

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
