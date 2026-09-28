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
        assertEquals("/home/user", s.location?.directory)
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
        assertEquals("/home/user", env.location?.directory)
        val m = env.data.first { it.id == "deepseek-v4.1-flash" }
        assertEquals("ollama-cloud", m.providerID)
        assertEquals("DeepSeek V4.1 Flash", m.name)
    }

    @Test
    fun `une enveloppe permission vide se decode`() {
        val env = json.decodeFromString<DataEnvelope<PermissionRequest>>(fixture("permission-request.json"))
        assertTrue(env.data.isEmpty())
        assertEquals("/home/user", env.location?.directory)
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

    // ------------------------------------------------------------------
    // Formulaires : les six types de champ, la condition when, la pseudo-session globale
    // ------------------------------------------------------------------

    /**
     * ⚠️ **Forme mesuree sur le serveur 2.0.x le 2026-09-26**, pas deduite du schema. On decode
     * les six types d'un coup, avec les champs *specifiques* de chacun (`format`, `options`,
     * `minItems`, `url`) pour prouver qu'un seul DTO plat les porte tous.
     */
    @Test
    fun `les six types de champ d un formulaire se decodent`() {
        val raw = """
        {"data":{"id":"frm_1","sessionID":"ses_1","title":"probe","fields":[
          {"key":"s","type":"string","format":"email","minLength":3,"maxLength":50,"pattern":"^.+@.+$",
           "placeholder":"a@b.c","default":"x@y.z","options":[{"value":"a","label":"A"}]},
          {"key":"n","type":"number","minimum":0,"maximum":10,"default":1.5},
          {"key":"i","type":"integer","minimum":1,"maximum":5,"default":2},
          {"key":"b","type":"boolean","default":true},
          {"key":"m","type":"multiselect","options":[{"value":"x","label":"X","description":"dx"}],
           "minItems":1,"maxItems":2,"default":["x"],"custom":true},
          {"key":"e","type":"external","url":"https://example.com"}
        ]}}
        """.trimIndent()
        val detail = json.decodeFromString<FormDetailEnvelope>(raw).data!!
        val byKey = detail.fields.associateBy { it.key }

        assertEquals("email", byKey["s"]!!.format)
        assertEquals(3, byKey["s"]!!.minLength)
        assertEquals("^.+@.+$", byKey["s"]!!.pattern)
        assertEquals("a@b.c", byKey["s"]!!.placeholder)
        assertEquals(1, byKey["m"]!!.minItems)
        assertTrue(byKey["m"]!!.custom)
        assertEquals("dx", byKey["m"]!!.options.first().description)
        assertEquals("https://example.com", byKey["e"]!!.url)
    }

    /**
     * ⚠️ `minimum`/`default` sont des `JsonElement` et **pas** des nombres : le schema autorise les
     * chaines `"Infinity"`/`"NaN"` pour les bornes. Les typer en `Double?` rendrait un formulaire
     * entier indecodable des qu'une de ces formes apparait.
     */
    @Test
    fun `une borne Infinity en chaine ne casse pas le decodage`() {
        val raw = """{"key":"n","type":"number","maximum":"Infinity","minimum":"-Infinity"}"""
        val f = json.decodeFromString<FormFieldDto>(raw)
        assertEquals("Infinity", f.maximum!!.toString().trim('"'))
        assertEquals("-Infinity", f.minimum!!.toString().trim('"'))
    }

    /** Le discriminant de l'union (`type`) est porte a plat dans le DTO. */
    @Test
    fun `le type de champ est porte a plat dans le DTO`() {
        val raw = """{"key":"s","type":"string"}"""
        assertEquals("string", json.decodeFromString<FormFieldDto>(raw).type)
    }

    /** Les conditions `when` (eq/neq sur un champ precedent) se decodent en liste. */
    @Test
    fun `les conditions when se decodent`() {
        val raw = """
        {"key":"d","type":"string","when":[
          {"key":"m","op":"eq","value":"a"},
          {"key":"b","op":"neq","value":true}
        ]}
        """.trimIndent()
        val f = json.decodeFromString<FormFieldDto>(raw)
        assertEquals(2, f.`when`.size)
        assertEquals("m", f.`when`[0].key)
        assertEquals("eq", f.`when`[0].op)
        assertEquals("neq", f.`when`[1].op)
        assertEquals("true", f.`when`[1].value!!.toString())
    }

    /**
     * ⚠️ **`sessionID` peut valoir `"global"`** (elicitation MCP, mesure 2026-09-26) : ce n'est
     * pas une session. [FormInfoDto.isGlobal] le rend explicite pour que le code ne suppose jamais
     * qu'une session se trouve derriere.
     */
    @Test
    fun `un formulaire global est reconnu comme tel`() {
        val raw = """
        {"data":[{"id":"frm_1","sessionID":"global","title":"mcp","fields":[{"key":"k","type":"string"}]}]}
        """.trimIndent()
        val env = json.decodeFromString<ListEnvelope<FormInfoDto>>(raw)
        assertTrue(env.data.first().isGlobal)
        assertEquals(FormInfoDto.GLOBAL_SESSION_ID, env.data.first().sessionID)
    }

    /** L'etat d'un formulaire (`pending` / `answered`) se lit, avec ses reponses. */
    @Test
    fun `l etat d un formulaire se decode`() {
        val raw = """
        {"data":{"id":"frm_1","sessionID":"ses_1","title":"t","fields":[{"key":"q","type":"string"}],
         "state":{"status":"answered","answer":{"q":"hi"}}}}
        """.trimIndent()
        val detail = json.decodeFromString<FormDetailEnvelope>(raw).data!!
        assertFalse(detail.state!!.isPending)
        assertEquals("hi", detail.state.answer!!["q"]!!.toString().trim('"'))
    }

    /** Une reponse de formulaire se decode en `{"answer":{"<cle>":<valeur>}}`. */
    @Test
    fun `une reponse de formulaire se decode`() {
        val raw = """{"answer":{"s":"x","n":3,"b":true,"m":["a","b"]}}"""
        val body = json.decodeFromString<FormReplyBody>(raw)
        assertEquals(4, body.answer.size)
        assertEquals("x", body.answer["s"]!!.toString().trim('"'))
        assertEquals("3", body.answer["n"]!!.toString())
        assertEquals("true", body.answer["b"]!!.toString())
        assertEquals("""["a","b"]""", body.answer["m"]!!.toString())
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
