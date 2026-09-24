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
 * Le localisation (repertoire de travail) est passee soit en query
 * `?location[directory]=<absolu>` (listings), soit dans le corps de `POST /api/session`.
 */
class OpenCodeClient(
    baseUrl: String,
    private val credentials: BasicAuthCredentials,
    private val http: HttpClient,
) {
    private val baseUrl: String = baseUrl.trimEnd('/')

    suspend fun info(): ServerInfo =
        http.get("$baseUrl/api/info") { auth() }.body()

    suspend fun sessions(location: String): List<Session> =
        http.get("$baseUrl/api/session") {
            auth()
            parameter("location[directory]", location)
        }.body<DataEnvelope<Session>>().data

    suspend fun messages(sessionID: String, location: String): List<MessageDto> =
        http.get("$baseUrl/api/session/$sessionID/message") {
            auth()
            parameter("location[directory]", location)
        }.body<DataEnvelope<MessageDto>>().data

    suspend fun models(location: String): List<Model> =
        http.get("$baseUrl/api/model") {
            auth()
            parameter("location[directory]", location)
        }.body<DataEnvelope<Model>>().data

    /** `POST /api/session` renvoie `{data: <Session>}` = un OBJET, pas un tableau. */
    suspend fun createSession(title: String, model: ModelRef, location: String): Session =
        http.post("$baseUrl/api/session") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(CreateSessionBody(title = title, model = model, location = LocationBody(location)))
        }.body<SessionEnvelope>().data

    /** `POST /prompt` : le texte est dans `payload.text`, la reponse est `{data: <msg_*>}`. */
    suspend fun prompt(sessionID: String, text: String, location: String): PromptAcceptance =
        http.post("$baseUrl/api/session/$sessionID/prompt") {
            auth()
            contentType(ContentType.Application.Json)
            parameter("location[directory]", location)
            setBody(PromptBody(text = text))
        }.body<PromptEnvelope>().data

    private fun HttpRequestBuilder.auth() {
        basicAuth(credentials.username, credentials.password)
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
