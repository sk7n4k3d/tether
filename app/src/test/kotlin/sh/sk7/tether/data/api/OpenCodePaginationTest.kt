package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import sh.sk7.tether.data.settings.ConnectionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La pagination est un **defaut connu de la Task 1.5** : `GET /api/session` renvoie 50
 * elements par defaut et 428 sessions existent. Ces tests prouvent que le curseur est suivi,
 * et que le client n'invente pas de page quand `next` est nul.
 */
class OpenCodePaginationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val creds = BasicAuthCredentials(password = "x")

    private var requests = mutableListOf<String>()

    private fun http(handler: (String) -> String): HttpClient {
        val engine = MockEngine { request ->
            requests += request.url.toString()
            respond(
                content = handler(request.url.toString()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    }

    private fun client(handler: (String) -> String): OpenCodeClient =
        OpenCodeClient("http://host:4096", creds, http(handler))

    private fun gateway(handler: (String) -> String): KtorOpenCodeGateway =
        KtorOpenCodeGateway(http(handler), InMemoryCredentialsProvider(creds))

    private fun session(id: String) = """{"id":"$id","title":"t"}"""

    @Test
    fun `allSessions suit le curseur jusqu a epuisement`() = runBlocking<Unit> {
        val gateway = gateway { url ->
            when {
                url.contains("cursor=p2") -> """{"data":[${session("ses_3")}],"cursor":{}}"""
                url.contains("cursor=p1") -> """{"data":[${session("ses_2")}],"cursor":{"next":"p2"}}"""
                else -> """{"data":[${session("ses_1")}],"cursor":{"next":"p1"}}"""
            }
        }

        val all = gateway.allSessions(ConnectionSettings(password = "x", directory = "/d"), pageSize = 1)

        assertEquals(listOf("ses_1", "ses_2", "ses_3"), all.map { it.id })
        assertEquals(3, requests.size, "une requete par page, pas de page en trop")
        assertEquals("1", io.ktor.http.Url(requests.first()).parameters["limit"])
    }

    @Test
    fun `sessionsPage expose le curseur next de l enveloppe`() = runBlocking<Unit> {
        val client = client { """{"data":[${session("ses_1")}],"cursor":{"previous":"p0","next":"p1"}}""" }

        val page = client.sessionsPage("/d", limit = 50)

        assertEquals(1, page.data.size)
        assertEquals("p1", page.next)
        assertEquals("p0", page.previous)
    }

    @Test
    fun `allMessages ne demande order que sur la premiere page`() = runBlocking<Unit> {
        val gateway = gateway { url ->
            when {
                url.contains("cursor=p1") -> """{"data":[{"id":"msg_b","type":"assistant"}],"cursor":{}}"""
                else -> """{"data":[{"id":"msg_a","type":"user"}],"cursor":{"next":"p1"}}"""
            }
        }

        val all = gateway.allMessages(ConnectionSettings(password = "x", directory = "/d"), "ses_1", pageSize = 1)

        assertEquals(listOf("msg_a", "msg_b"), all.map { it.id })
        // ⚠️ L'OpenAPI interdit de combiner `order` et `cursor` : la 2e page ne doit pas
        // renvoyer `order`, sinon le serveur repond InvalidCursorError.
        assertTrue(requests.first().contains("order=asc"), requests.first())
        assertTrue(!requests[1].contains("order="), requests[1])
        assertTrue(requests[1].contains("cursor=p1"), requests[1])
    }

    @Test
    fun `sessionsPage signe directory et pas location`() = runBlocking<Unit> {
        val client = client { """{"data":[],"cursor":null}""" }

        client.sessionsPage("/home/utilisateur", limit = 200)

        val url = requests.first()
        assertTrue(url.contains("directory=%2Fhome%2Futilisateur") || url.contains("directory=/home/utilisateur"), url)
        assertTrue(!url.contains("location"), url)
        assertTrue(url.contains("limit=200"), url)
    }

    @Test
    fun `interrupt poste sur la route dediee et lit interrupted`() = runBlocking<Unit> {
        val engine = MockEngine { request ->
            assertEquals("http://host:4096/api/session/ses_1/interrupt", request.url.toString())
            respond(
                content = """{"interrupted":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val client = OpenCodeClient("http://host:4096", creds, http)

        assertTrue(client.interrupt("ses_1"))
    }
}
