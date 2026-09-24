package sh.sk7.tether.data.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.readBytes().decodeToString()

    @Test
    fun `decode la liste de sessions reelle`() {
        val env = json.decodeFromString<DataEnvelope<Session>>(fixture("session-list.json"))
        assertEquals(50, env.data.size)
        assertTrue(env.data.first().id.isNotBlank())
        val cursor = env.cursor
        assertNotNull(cursor)
        assertNotNull(cursor.next)
        assertNotNull(cursor.previous)
    }

    @Test
    fun `une session reelle porte agent model tokens outcome time`() {
        val env = json.decodeFromString<DataEnvelope<Session>>(fixture("session-list.json"))
        val s = env.data.first { it.id == "ses_f2b4097ecffe8dMdMYfZlf1eiS" }
        assertEquals("general", s.agent)
        assertEquals("ollama-cloud", s.model?.providerID)
        assertEquals("deepseek-v4.1-flash", s.model?.id)
        assertEquals("default", s.model?.variant)
        assertEquals("succeeded", s.outcome)
        assertEquals(286222, s.tokens?.input)
        assertEquals(90773, s.tokens?.output)
        assertEquals(2775680, s.tokens?.cache?.read)
        assertEquals(0, s.tokens?.cache?.write)
        assertEquals(1790275708982, s.time?.created)
        assertEquals(1790277771722, s.time?.updated)
        assertEquals(1790277398601, s.time?.idle)
        assertEquals(1790277398601, s.time?.viewed)
        assertEquals("/home/utilisateur", s.location?.directory)
    }

    @Test
    fun `une session sans agent ni model se decode quand meme`() {
        val env = json.decodeFromString<DataEnvelope<Session>>(fixture("session-list.json"))
        val s = env.data.first { it.id == "ses_owui_180f0dd24bb97e207e" }
        assertEquals(null, s.agent)
        assertEquals(null, s.model)
    }

    @Test
    fun `decode la liste de messages reelle avec les quatre types`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        assertEquals(50, env.data.size)
        assertTrue(env.data.any { it.type == "assistant" })
        assertTrue(env.data.any { it.type == "user" })
        assertTrue(env.data.any { it.type == "idle" })
        assertTrue(env.data.any { it.type == "synthetic" })
        assertNotNull(env.cursor?.next)
    }

    @Test
    fun `un message assistant porte content reasoning et text`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        val a = env.data.first {
            it.type == "assistant" && it.finish == "stop" &&
                it.content.any { p -> p.type == "text" } &&
                it.content.any { p -> p.type == "reasoning" }
        }
        assertEquals("general", a.agent)
        assertEquals("ollama-cloud", a.model?.providerID)
        assertEquals("stop", a.finish)
        assertTrue(a.content.any { it.type == "text" && !it.text.isNullOrBlank() })
        val reasoning = a.content.first { it.type == "reasoning" }
        assertTrue(reasoning.text!!.isNotBlank())
        assertNotNull(reasoning.state)
        assertEquals("reasoning_content", reasoning.state["reasoningField"]?.toString()?.trim('"'))
        assertNotNull(reasoning.time)
    }

    @Test
    fun `une part tool porte son time avec ran`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        val toolWithTime = env.data.asSequence()
            .flatMap { it.content.asSequence() }
            .first { it.type == "tool" && it.time != null }
        assertNotNull(toolWithTime.time!!.created)
        assertNotNull(toolWithTime.time.ran)
    }

    @Test
    fun `un message assistant porte des parts tool reelles`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        val tool = env.data.asSequence()
            .flatMap { it.content.asSequence() }
            .first { it.type == "tool" }
        assertTrue(tool.name!!.isNotBlank())
        assertNotNull(tool.state)
        assertNotNull(tool.id)
    }

    @Test
    fun `un message user du listing porte le texte a plat`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        val u = env.data.first { it.type == "user" }
        assertNotNull(u.text)
        assertTrue(u.text.isNotBlank())
    }

    @Test
    fun `un message idle porte son outcome`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        val idle = env.data.first { it.type == "idle" }
        assertEquals("succeeded", idle.outcome)
        assertTrue(idle.content.isEmpty())
    }

    @Test
    fun `un message synthetic porte sa description`() {
        val env = json.decodeFromString<DataEnvelope<MessageDto>>(fixture("message-list.json"))
        val s = env.data.first { it.type == "synthetic" }
        assertNotNull(s.description)
        assertTrue(s.description.isNotBlank())
    }

    @Test
    fun `un champ inconnu ne casse pas le decodage`() {
        val raw = """{"data":[{"id":"ses_1","champMystere":42}],"cursor":null}"""
        val env = json.decodeFromString<DataEnvelope<Session>>(raw)
        assertEquals("ses_1", env.data.first().id)
    }

    @Test
    fun `l enveloppe location et data decode le listing modeles reel`() {
        val env = json.decodeFromString<DataEnvelope<Model>>(fixture("model-list.json"))
        assertEquals(37, env.data.size)
        assertEquals("/home/utilisateur", env.location?.directory)
        val m = env.data.first { it.id == "deepseek-v4.1-flash" }
        assertEquals("ollama-cloud", m.providerID)
        assertEquals("DeepSeek V4.1 Flash", m.name)
    }

    @Test
    fun `une enveloppe permission vide se decode`() {
        val env = json.decodeFromString<DataEnvelope<PermissionRequest>>(fixture("permission-request.json"))
        assertTrue(env.data.isEmpty())
        assertEquals("/home/utilisateur", env.location?.directory)
    }

    @Test
    fun `une permission request suit le contrat reecrit par l OpenAPI`() {
        val raw = """{"id":"per_1","sessionID":"ses_1","action":"shell",
            "resources":["cmd"],"save":["always"],"source":{"type":"tool","messageID":"msg_1","id":"call_1"},
            "message":"run?"}"""
        val p = json.decodeFromString<PermissionRequest>(raw)
        assertEquals("per_1", p.id)
        assertEquals("ses_1", p.sessionID)
        assertEquals("shell", p.action)
        assertEquals(listOf("cmd"), p.resources)
        assertEquals(listOf("always"), p.save)
        assertEquals("run?", p.message)
        assertEquals("tool", p.source?.type)
        assertEquals("msg_1", p.source?.messageID)
    }

    @Test
    fun `info json se decode avec version pid urls`() {
        val info = json.decodeFromString<ServerInfo>(fixture("info.json"))
        assertEquals("2.0.x", info.version)
        assertNotNull(info.pid)
        assertTrue(info.urls.isNotEmpty())
    }

    @Test
    fun `Session time supporte archived`() {
        val raw = """{"data":[{"id":"ses_1","time":{"created":1,"updated":2,"archived":3}}]}"""
        val env = json.decodeFromString<DataEnvelope<Session>>(raw)
        assertEquals(3L, env.data.first().time?.archived)
    }

    @Test
    fun `l enveloppe objet de creation de session se decode`() {
        val raw = """{"data":{"id":"ses_new","projectID":"p1","title":"t",
            "model":{"id":"m","providerID":"prov"},"location":{"directory":"/tmp"}}}"""
        val env = json.decodeFromString<SessionEnvelope>(raw)
        assertEquals("ses_new", env.data.id)
        assertEquals("t", env.data.title)
    }

    @Test
    fun `la reponse de prompt met le texte dans payload text`() {
        val raw = """{"data":{"id":"msg_1","sessionID":"ses_1","type":"user",
            "payload":{"text":"PONG"},"delivery":"steer","time":{"created":1}}}"""
        val env = json.decodeFromString<PromptEnvelope>(raw)
        assertEquals("msg_1", env.data.id)
        assertEquals("ses_1", env.data.sessionID)
        assertEquals("PONG", env.data.payload?.text)
        assertEquals("steer", env.data.delivery)
    }

    @Test
    fun `le body de creation de session encode location directory`() {
        val body = CreateSessionBody(
            title = "t",
            model = ModelRef(id = "m", providerID = "p"),
            location = LocationBody("/tmp"),
        )
        val encoded = json.encodeToString(CreateSessionBody.serializer(), body)
        assertTrue(encoded.contains("\"directory\":\"/tmp\""))
        assertFalse(encoded.contains("variant"))
    }
}
