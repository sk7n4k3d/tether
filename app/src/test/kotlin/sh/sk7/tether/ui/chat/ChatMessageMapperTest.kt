package sh.sk7.tether.ui.chat

import sh.sk7.tether.data.api.ContentPart
import sh.sk7.tether.data.api.MessageDto
import sh.sk7.tether.data.api.PromptPayload
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatMessageMapperTest {

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
