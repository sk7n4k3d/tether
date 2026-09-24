package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodeClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val creds = BasicAuthCredentials(password = "s3cret")

    private var lastRequest: io.ktor.client.request.HttpRequestData? = null

    private fun mockClient(body: String): HttpClient {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    }

    @Test
    fun `sessions interroge le bon chemin avec location et auth`() = runBlocking {
        val body = """{"data":[{"id":"ses_1","title":"t"}],"cursor":{"next":"abc"}}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val sessions = client.sessions("/home/utilisateur")
        assertEquals(1, sessions.size)
        assertEquals("ses_1", sessions.first().id)

        val req = lastRequest!!
        assertEquals("http://host:4096/api/session", req.url.toString().substringBefore("?"))
        assertEquals("/home/utilisateur", req.url.parameters["location[directory]"])
        val auth = req.headers[HttpHeaders.Authorization]
        assertEquals("Basic b3BlbmNvZGU6czNjcmV0", auth)
    }

    @Test
    fun `messages interroge le chemin de la session`() = runBlocking {
        val body = """{"data":[{"id":"msg_1","type":"idle","outcome":"succeeded"}],"cursor":null}"""
        val client = OpenCodeClient("http://host:4096/", creds, mockClient(body))
        val msgs = client.messages("ses_1", "/tmp")
        assertEquals(1, msgs.size)
        assertEquals("idle", msgs.first().type)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/session/ses_1/message", req.url.toString().substringBefore("?"))
    }

    @Test
    fun `models decode l enveloppe location data`() = runBlocking {
        val body = """{"location":{"directory":"/tmp"},"data":[{"id":"deepseek-v4.1-flash","providerID":"ollama-cloud"}]}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val models = client.models("/tmp")
        assertEquals(1, models.size)
        assertEquals("ollama-cloud", models.first().providerID)
    }

    @Test
    fun `info decode la version`() = runBlocking {
        val body = """{"version":"2.0.x","pid":42,"urls":[]}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        assertEquals("2.0.x", client.info().version)
    }

    @Test
    fun `createSession poste le corps et lit l objet data`() = runBlocking {
        val body = """{"data":{"id":"ses_new","title":"t"}}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val s = client.createSession("t", ModelRef("m", "p"), "/tmp")
        assertEquals("ses_new", s.id)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/session", req.url.toString())
        val sent = String(req.body.toByteArray())
        assertTrue(sent.contains("\"title\":\"t\""))
        assertTrue(sent.contains("\"directory\":\"/tmp\""))
    }

    @Test
    fun `prompt poste le texte et lit payload text`() = runBlocking {
        val body = """{"data":{"id":"msg_1","sessionID":"ses_1","type":"user","payload":{"text":"PONG"},"delivery":"steer"}}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val acc = client.prompt("ses_1", "PONG", "/tmp")
        assertEquals("PONG", acc.payload?.text)
        assertEquals("steer", acc.delivery)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/session/ses_1/prompt", req.url.toString().substringBefore("?"))
        assertTrue(String(req.body.toByteArray()).contains("\"text\":\"PONG\""))
    }

    @Test
    fun `un HttpClient configure par le compagnon negocie le json`() = runBlocking {
        val body = """{"version":"2.0.x"}"""
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { configureTether() }
        val oc = OpenCodeClient("http://host:4096", creds, client)
        assertEquals("2.0.x", oc.info().version)
    }
}
