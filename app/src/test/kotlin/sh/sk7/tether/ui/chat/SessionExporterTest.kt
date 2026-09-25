package sh.sk7.tether.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.SessionStatus
import sh.sk7.tether.domain.model.SessionUiState
import sh.sk7.tether.data.api.Tokens
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus

/**
 * L'export Markdown : ce qui doit y etre, et ce qui ne doit **jamais** manquer.
 *
 * ⚠️ Ces tests portent sur ce que les utilisateurs reprochent aux exporteurs existants : perdre
 * le raisonnement, perdre les appels d'outils, perdre les blocs de code. Ce sont exactement les
 * elements qu'on verifie ici — pas la mise en forme jolie.
 */
class SessionExporterTest {

    private fun state(vararg messages: ChatMessage) = SessionUiState(
        sessionID = "ses_test",
        messages = messages.toList(),
    )

    @Test
    fun `l export porte le titre, l id de session et la date`() {
        val md = SessionExporter.toMarkdown(
            title = "Refonte du chat",
            sessionID = "ses_abc123",
            state = state(ChatMessage(id = "m1", role = Role.User, text = "salut")),
        )

        assertTrue(md.startsWith("# Refonte du chat"), md.take(60))
        // ⚠️ Sans l'identifiant, un export relu plus tard ne peut plus etre rattache a sa session.
        assertTrue(md.contains("ses_abc123"), "l'id de session doit etre present")
        assertTrue(md.contains("Exporté le"), "la date doit etre presente")
    }

    @Test
    fun `les roles sont distingues`() {
        val md = SessionExporter.toMarkdown(
            title = "t",
            sessionID = "s",
            state = state(
                ChatMessage(id = "m1", role = Role.User, text = "ma question"),
                ChatMessage(id = "m2", role = Role.Assistant, text = "ma reponse"),
            ),
        )

        assertTrue(md.contains("## Toi"), md)
        assertTrue(md.contains("## Agent"), md)
        assertTrue(md.contains("ma question"), md)
        assertTrue(md.contains("ma reponse"), md)
    }

    @Test
    fun `le raisonnement et les outils sont conserves, pas jetes`() {
        val md = SessionExporter.toMarkdown(
            title = "t",
            sessionID = "s",
            state = state(
                ChatMessage(
                    id = "m1",
                    role = Role.Assistant,
                    text = "voila",
                    reasoning = "j'ai reflechi",
                    reasoningDurationLabel = "12 s",
                    tools = listOf(
                        ToolCall(
                            id = "t1",
                            name = "shell",
                            status = ToolStatus.Succeeded,
                            summary = "uname -a",
                            output = "Linux le serveur",
                            durationLabel = "95 ms",
                        ),
                    ),
                ),
            ),
        )

        // ⚠️ Le raisonnement est REPLIE (`details`) mais PRESENT : c'est le point que les
        // exporteurs existants ratent, et c'est justement ce qu'on veut relire.
        assertTrue(md.contains("<details><summary>Raisonnement · 12 s</summary>"), md)
        assertTrue(md.contains("j'ai reflechi"), md)
        // L'outil, avec son entree ET sa sortie.
        assertTrue(md.contains("outil : shell"), md)
        assertTrue(md.contains("uname -a"), md)
        assertTrue(md.contains("Linux le serveur"), md)
        assertTrue(md.contains("95 ms"), md)
    }

    @Test
    fun `le cout et les tokens sont dans l en tete`() {
        val md = SessionExporter.toMarkdown(
            title = "t",
            sessionID = "s",
            state = state(ChatMessage(id = "m1", role = Role.User, text = "x")).copy(
                cost = 1.2345,
                tokens = Tokens(input = 1_500_000, output = 20_000),
            ),
        )

        assertTrue(md.contains("1,23 \$"), md)
        // ⚠️ Locale FRANCAISE : la virgule decimale, pas le point. Le test avait tort au premier
        // passage — le code est correct, et c'est bien la virgule qu'un lecteur francais attend.
        assertTrue(md.contains("1,5 M in"), md)
    }

    @Test
    fun `une session vide produit quand meme un en-tete valide`() {
        // ⚠️ Un export qui plante sur une session vide serait un plantage dans le cas le plus
        // simple a declencher. L'en-tete doit exister meme sans message.
        val md = SessionExporter.toMarkdown("vide", "ses_vide", state())

        assertTrue(md.startsWith("# vide"), md)
        assertTrue(md.contains("ses_vide"), md)
    }
}
