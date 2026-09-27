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
import kotlin.test.assertFalse
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
    fun `sessions filtre par directory avec le bon nom de parametre`() = runBlocking {
        val body = """{"data":[{"id":"ses_1","title":"t","location":{"directory":"/tmp/opencode"}}],"cursor":{"next":"abc"}}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val sessions = client.sessions("/tmp/opencode")
        assertEquals(1, sessions.size)
        assertEquals("ses_1", sessions.first().id)

        val req = lastRequest!!
        assertEquals("http://host:4096/api/session", req.url.toString().substringBefore("?"))
        assertEquals("/tmp/opencode", req.url.parameters["directory"])
        assertEquals(null, req.url.parameters["location[directory]"])
        val auth = req.headers[HttpHeaders.Authorization]
        assertEquals("Basic b3BlbmNvZGU6czNjcmV0", auth)
    }

    @Test
    fun `messages interroge le chemin de la session sans parametre location`() = runBlocking {
        val body = """{"data":[{"id":"msg_1","type":"idle","outcome":"succeeded"}],"cursor":null}"""
        val client = OpenCodeClient("http://host:4096/", creds, mockClient(body))
        val msgs = client.messages("ses_1", limit = 50)
        assertEquals(1, msgs.size)
        assertEquals("idle", msgs.first().type)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/session/ses_1/message", req.url.toString().substringBefore("?"))
        assertEquals("50", req.url.parameters["limit"])
        assertEquals(null, req.url.parameters["location[directory]"])
        assertEquals(null, req.url.parameters["directory"])
    }

    @Test
    fun `messages signe limit et cursor`() = runBlocking {
        val body = """{"data":[],"cursor":null}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        client.messages("ses_1", limit = 20, cursor = "abc", order = "desc")
        val req = lastRequest!!
        assertEquals("20", req.url.parameters["limit"])
        assertEquals("abc", req.url.parameters["cursor"])
        assertEquals("desc", req.url.parameters["order"])
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
    fun `models envoie location directory en deepObject`() = runBlocking {
        val body = """{"location":{"directory":"/tmp/opencode"},"data":[{"id":"m","providerID":"p"}]}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        assertEquals(1, client.models("/tmp/opencode").size)
        val req = lastRequest!!
        assertEquals("/tmp/opencode", req.url.parameters["location[directory]"])
        assertEquals(null, req.url.parameters["location"])
        assertTrue(req.url.encodedQuery.contains("location%5Bdirectory%5D"), req.url.encodedQuery)
    }

    @Test
    fun `agents interroge api agent avec location directory`() = runBlocking {
        val body = """{"location":{"directory":"/tmp"},"data":[{"id":"general","name":"General","mode":"all"}]}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val agents = client.agents("/tmp")
        assertEquals(1, agents.size)
        assertEquals("general", agents.first().id)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/agent", req.url.toString().substringBefore("?"))
        assertEquals("/tmp", req.url.parameters["location[directory]"])
    }

    /**
     * L'agent n'est signe que si on le fournit.
     *
     * ⚠️ Ecrit sur la forme **reelle** : le corps porte `location` en premier, et `agent` est
     * simplement absent quand on ne le passe pas (et non `null`).
     */
    @Test
    fun `createSession signe l agent optionnel et l omet si absent`() = runBlocking {
        val body = """{"data":{"id":"ses_new"}}"""
        val withAgent = OpenCodeClient("http://host:4096", creds, mockClient(body))
        withAgent.createSession("/tmp", model = ModelRef("m", "p"), agent = "general")
        assertTrue(String(lastRequest!!.body.toByteArray()).contains("\"agent\":\"general\""))

        val withoutAgent = OpenCodeClient("http://host:4096", creds, mockClient(body))
        withoutAgent.createSession("/tmp", model = ModelRef("m", "p"))
        assertFalse(String(lastRequest!!.body.toByteArray()).contains("\"agent\""))
    }

    @Test
    fun `info decode la version`() = runBlocking {
        val body = """{"version":"2.0.x","pid":42,"urls":[]}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        assertEquals("2.0.x", client.info().version)
    }

    /**
     * Le corps minimal : `location` seul suffit, et **aucun titre n'est envoye**.
     *
     * ⚠️ Mesure du 2026-09-26 sur le 2.0.x : `SessionCreate` n'a aucun champ obligatoire, et
     * `{"location":{"directory":"/tmp"}}` seul rend 200 avec `title`/`agent`/`model` a `null`.
     * Le serveur ne reecrit pas un titre qu'on lui impose — envoyer `"Nouvelle session"` le
     * conservait tel quel, ce qui empechait la generation automatique. D'ou l'absence de `title`.
     */
    @Test
    fun `createSession poste le corps minimal et lit l objet data`() = runBlocking {
        val body = """{"data":{"id":"ses_new"}}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val s = client.createSession("/tmp")
        assertEquals("ses_new", s.id)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/session", req.url.toString())
        val sent = String(req.body.toByteArray())
        assertTrue(sent.contains("\"directory\":\"/tmp\""))
        assertFalse(sent.contains("\"title\""), "aucun titre ne doit etre envoye : vu $sent")
    }

    @Test
    fun `prompt poste le texte et lit payload text sans query parameter`() = runBlocking {
        val body = """{"data":{"id":"msg_1","sessionID":"ses_1","type":"user","payload":{"text":"PONG"},"delivery":"steer"}}"""
        val client = OpenCodeClient("http://host:4096", creds, mockClient(body))
        val acc = client.prompt("ses_1", "PONG")
        assertEquals("PONG", acc.payload?.text)
        assertEquals("steer", acc.delivery)
        val req = lastRequest!!
        assertEquals("http://host:4096/api/session/ses_1/prompt", req.url.toString())
        assertEquals(null, req.url.parameters["location[directory]"])
        assertEquals(null, req.url.parameters["directory"])
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
