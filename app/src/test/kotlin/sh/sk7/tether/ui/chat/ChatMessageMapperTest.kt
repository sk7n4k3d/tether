package sh.sk7.tether.ui.chat

import sh.sk7.tether.data.api.ContentPart
import sh.sk7.tether.data.api.MessageDto
import sh.sk7.tether.data.api.PromptPayload
import sh.sk7.tether.data.api.TimeInfo
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageMapperTest {

    private fun jsonObject(raw: String): JsonObject =
        Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `un message user a plat devient une bulle utilisateur`() {
        val message = ChatMessageMapper.fromDto(MessageDto(id = "msg_u", type = "user", text = "salut"))

        assertNotNull(message)
        assertEquals(Role.User, message.role)
        assertEquals("salut", message.text)
    }

    @Test
    fun `un prompt passe par POST prompt lit payload text`() {
        val dto = MessageDto(id = "msg_u", type = "user", payload = PromptPayload(text = "bonjour"))

        assertEquals("bonjour", ChatMessageMapper.fromDto(dto)?.text)
    }

    // ------------------------------------------------------------------
    // File d'attente (palier 1.8)
    // ------------------------------------------------------------------

    private fun inboxItem(
        id: String,
        text: String?,
        delivery: String?,
    ) = sh.sk7.tether.data.api.InboxItemDto(
        id = id,
        type = "user",
        payload = text?.let { jsonObject("""{"text":"$it"}""") },
        delivery = delivery,
    )

    @Test
    fun `une entree d inbox porte son texte et son mode`() {
        // ⚠️ Forme reelle capturee le 2026-09-25 : `payload={"text":"…"}`, `delivery` chaine.
        val message = ChatMessageMapper.fromInbox(inboxItem("msg_q", "attends", "queue"))

        assertEquals("attends", message.text)
        assertEquals("queue", message.delivery)
        assertTrue(message.isQueued)
        assertEquals(false, message.isSteering)
    }

    @Test
    fun `steer se distingue de queue`() {
        // ⚠️ C'est la distinction que l'app jetait (issue #32157) : `steer` corrige le tour en
        // cours, `queue` attend son tour. Les confondre perd exactement ce qui explique la suite.
        assertTrue(ChatMessageMapper.fromInbox(inboxItem("msg_s", "corrige", "steer")).isSteering)
        assertEquals(false, ChatMessageMapper.fromInbox(inboxItem("msg_q", "attends", "queue")).isSteering)
    }

    @Test
    fun `une entree d inbox sans texte garde sa charge brute`() {
        // ⚠️ Piece jointe, `move`, `compaction` : on ne jette pas le message, on garde la charge.
        val message = ChatMessageMapper.fromInbox(
            sh.sk7.tether.data.api.InboxItemDto(
                id = "msg_attach",
                type = "user",
                payload = jsonObject("""{"attachment":"photo.png"}"""),
                delivery = "queue",
            ),
        )

        assertNotNull(message.rawFallback)
        assertEquals("", message.text)
        assertTrue(message.isQueued)
    }

    @Test
    fun `mergeInbox rafraichit le mode d un message deja affiche`() {
        // ⚠️ Le serveur peut basculer `queue` -> `steer` par `PATCH`. On suit la verite serveur
        // sans dupliquer la ligne.
        val existing = listOf(ChatMessageMapper.fromInbox(inboxItem("msg_1", "texte", "queue")))
        val inbox = listOf(ChatMessageMapper.fromInbox(inboxItem("msg_1", "texte", "steer")))

        val merged = ChatMessageMapper.mergeInbox(existing, inbox)

        assertEquals(1, merged.size)
        assertEquals("steer", merged.single().delivery)
    }

    @Test
    fun `mergeInbox retire le marqueur d un message livre et le conserve`() {
        // ⚠️ Un message absent de l'inbox n'attend plus : il a ete livre. Le SUPPRIMER ferait
        // disparaitre un message que l'agent a recu — l'inverse de la verite.
        val existing = listOf(ChatMessageMapper.fromInbox(inboxItem("msg_1", "texte", "queue")))

        val merged = ChatMessageMapper.mergeInbox(existing, emptyList())

        assertEquals(1, merged.size)
        assertEquals(null, merged.single().delivery)
        assertEquals("texte", merged.single().text)
    }

    @Test
    fun `mergeInbox ajoute a la fin une entree inconnue`() {
        val existing = listOf(
            sh.sk7.tether.domain.model.ChatMessage(id = "msg_a", role = Role.Assistant, text = "reponse"),
        )
        val inbox = listOf(ChatMessageMapper.fromInbox(inboxItem("msg_q", "en attente", "queue")))

        val merged = ChatMessageMapper.mergeInbox(existing, inbox)

        assertEquals(listOf("msg_a", "msg_q"), merged.map { it.id })
    }

    @Test
    fun `mergeInbox ne touche pas aux messages qui n attendent pas`() {
        val existing = listOf(
            sh.sk7.tether.domain.model.ChatMessage(id = "msg_u", role = Role.User, text = "envoye"),
        )

        val merged = ChatMessageMapper.mergeInbox(existing, emptyList())

        assertEquals(existing, merged)
    }

    @Test
    fun `un message assistant fusionne texte raisonnement et outils`() {
        val dto = MessageDto(
            id = "msg_a",
            type = "assistant",
            content = listOf(
                ContentPart(type = "reasoning", text = "je réfléchis "),
                ContentPart(type = "text", text = "voici la réponse"),
                ContentPart(
                    type = "tool",
                    id = "call_1",
                    name = "shell",
                    executed = false,
                    state = kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"status":"running","input":{"command":"uname -r"}}""",
                    ).let { it as kotlinx.serialization.json.JsonObject },
                ),
            ),
        )

        val message = ChatMessageMapper.fromDto(dto)!!

        assertEquals("je réfléchis ", message.reasoning)
        assertEquals("voici la réponse", message.text)
        assertEquals(1, message.tools.size)
        assertEquals("shell", message.tools.first().name)
        assertEquals(ToolStatus.Running, message.tools.first().status)
        assertTrue(message.tools.first().raw.contains("uname -r"))
    }

    @Test
    fun `un outil expose ce qu il a recu et ce qu il a produit`() {
        // Reproduit la charge REELLE mesuree sur le serveur (ses_f2986f22dffe…, 2026-09-25) :
        // `state.input` porte le quoi, `state.content[].text` porte le resultat, `time` porte
        // la duree reelle. L'app jetait les trois — c'est le reproche « je vois que shell a
        // tourne mais pas ce que tu as ecrit ».
        val state = jsonObject(
            """
            {"status":"completed",
             "input":{"command":"uname -a; echo \"---\"; hostname"},
             "content":[{"type":"text","text":"Linux le serveur 7.2.6 x86_64\n"},{"type":"text","text":"Command exited with code 0."}]}
            """.trimIndent(),
        )
        val dto = MessageDto(
            id = "msg_a",
            type = "assistant",
            content = listOf(
                ContentPart(
                    type = "tool",
                    id = "call_1",
                    name = "shell",
                    state = state,
                    time = TimeInfo(ran = 1_790_304_656_141, completed = 1_790_304_656_236),
                ),
            ),
        )

        val tool = ChatMessageMapper.fromDto(dto)!!.tools.first()

        assertEquals("uname -a; echo \"---\"; hostname", tool.summary)
        assertTrue(tool.output!!.contains("Linux le serveur"), tool.output!!)
        assertTrue(tool.output!!.contains("Command exited with code 0."), tool.output!!)
        // 95 ms : duree reelle mesuree par le serveur (`time.ran` -> `time.completed`).
        assertEquals("95 ms", tool.durationLabel)
    }

    @Test
    fun `un raisonnement expose sa duree reelle`() {
        // Mesure serveur (Markdown check, 2026-09-25) : `time.created` 1790308830868 ->
        // `time.completed` 1790308831563 = 695 ms sur la part `reasoning`.
        val dto = MessageDto(
            id = "msg_a",
            type = "assistant",
            content = listOf(
                ContentPart(
                    type = "reasoning",
                    text = "je reflechis",
                    time = TimeInfo(created = 1_790_308_830_868, completed = 1_790_308_831_563),
                ),
                ContentPart(type = "text", text = "voila"),
            ),
        )

        val message = ChatMessageMapper.fromDto(dto)!!

        // Sans ce champ, la duree disparaissait des qu'on rechargeait l'historique : le direct
        // l'affichait, le REST non. Une information qui depend du chemin de lecture est fausse.
        assertEquals("695 ms", message.reasoningDurationLabel)
    }

    @Test
    fun `un read expose son chemin et un resume long est tronque`() {
        fun tool(input: String) = ContentPart(
            type = "tool",
            id = "call_1",
            name = "read",
            state = jsonObject("""{"status":"completed","input":$input}"""),
        )

        assertEquals(
            "…/memory/MEMORY.md",
            ChatMessageMapper.fromDto(
                MessageDto(
                    id = "m",
                    type = "assistant",
                    content = listOf(tool("""{"path":"/home/utilisateur/.claude/projects/-home-utilisateur/memory/MEMORY.md"}""")),
                ),
            )!!.tools.first().summary,
        )

        // Un resume de 300 caracteres doit tenir sur UNE ligne : tronque, jamais jete.
        val long = ChatMessageMapper.fromDto(
            MessageDto(
                id = "m",
                type = "assistant",
                content = listOf(tool("""{"command":"${"x".repeat(300)}"}""")),
            ),
        )!!.tools.first().summary!!
        assertTrue(long.length <= 121, "tronque a 120 + ellipse, obtenu ${long.length}")
        assertTrue(long.endsWith("…"))
        assertTrue(!long.contains('\n'))
    }

    @Test
    fun `une entree sans cle connue ne fabrique pas de resume`() {
        // Regle : mieux vaut aucune ligne qu'un resume invente.
        val dto = MessageDto(
            id = "m",
            type = "assistant",
            content = listOf(
                ContentPart(
                    type = "tool",
                    id = "call_1",
                    name = "mystere",
                    state = jsonObject("""{"status":"completed","input":{"unknownKey":"v"}}"""),
                ),
            ),
        )

        assertEquals(null, ChatMessageMapper.fromDto(dto)!!.tools.first().summary)
    }

    @Test
    fun `un outil termine est marque succeeded et un echec failed`() {
        fun tool(status: String) = MessageDto(
            id = "msg_a",
            type = "assistant",
            content = listOf(
                ContentPart(
                    type = "tool",
                    id = "call_$status",
                    name = "bash",
                    state = kotlinx.serialization.json.Json.parseToJsonElement("""{"status":"$status"}""")
                        .let { it as kotlinx.serialization.json.JsonObject },
                ),
            ),
        )

        assertEquals(ToolStatus.Succeeded, ChatMessageMapper.fromDto(tool("completed"))!!.tools.first().status)
        assertEquals(ToolStatus.Failed, ChatMessageMapper.fromDto(tool("error"))!!.tools.first().status)
    }

    @Test
    fun `une part de contenu inconnue est conservee en repli`() {
        val dto = MessageDto(
            id = "msg_a",
            type = "assistant",
            content = listOf(ContentPart(type = "type_jamais_vu", text = "charge opaque")),
        )

        val message = ChatMessageMapper.fromDto(dto)!!

        assertTrue(message.rawFallback?.contains("type_jamais_vu") == true)
        assertTrue(message.rawFallback.contains("charge opaque"))
    }

    @Test
    fun `un message synthetic affiche son libelle et conserve le contenu`() {
        val dto = MessageDto(id = "msg_s", type = "synthetic", description = "Loaded AGENTS.md", text = "long contenu")

        val message = ChatMessageMapper.fromDto(dto)!!

        assertEquals("Loaded AGENTS.md", message.text)
        assertEquals("long contenu", message.rawFallback)
    }

    @Test
    fun `les marqueurs de tour ne produisent pas de bulle`() {
        assertNull(ChatMessageMapper.fromDto(MessageDto(id = "msg_i", type = "idle", outcome = "succeeded")))
        assertNull(ChatMessageMapper.fromDto(MessageDto(id = "msg_sy", type = "system")))
        assertNull(ChatMessageMapper.fromDto(MessageDto(id = "msg_sk", type = "skill")))
    }

    @Test
    fun `fromDtos preserve l ordre et filtre les marqueurs`() {
        val dtos = listOf(
            MessageDto(id = "msg_u", type = "user", text = "question"),
            MessageDto(id = "msg_a", type = "assistant", content = listOf(ContentPart(type = "text", text = "réponse"))),
            MessageDto(id = "msg_i", type = "idle", outcome = "succeeded"),
        )

        val messages = ChatMessageMapper.fromDtos(dtos)

        assertEquals(2, messages.size)
        assertEquals(listOf(Role.User, Role.Assistant), messages.map { it.role })
    }
}
