package sh.sk7.tether.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus

/**
 * **La recherche dans une conversation : ce qu'elle trouve, et ou.**
 *
 * ⚠️ Le point important n'est pas de compter les occurrences, c'est de **dire dans quel champ** la
 * correspondance se trouve. Un match dans le raisonnement replie ne se voit pas a l'ecran : si la
 * recherche ne le signale pas, l'utilisateur clique sur un message et ne comprend pas pourquoi il
 * a ete retenu.
 */
class ChatSearchTest {

    private fun user(id: String, text: String) =
        ChatMessage(id = id, role = Role.User, text = text)

    private fun agent(
        id: String,
        text: String = "",
        reasoning: String = "",
        tools: List<ToolCall> = emptyList(),
    ) = ChatMessage(id = id, role = Role.Assistant, text = text, reasoning = reasoning, tools = tools)

    @Test
    fun `une requete vide ne trouve rien et n est pas une recherche`() {
        // ⚠️ `Result.isEmpty` avec une requete vide signifie « pas de recherche », pas « rien
        // trouve ». L'ecran ne dit pas la meme chose : c'est a l'appelant de regarder `query`.
        val result = ChatSearch.find(listOf(user("m1", "salut")), "")

        assertTrue(result.isEmpty)
        assertEquals("", result.query)
    }

    @Test
    fun `la recherche est insensible aux accents et a la casse`() {
        val messages = listOf(user("m1", "Résumé de la vidéo"))

        // ⚠️ Meme regle que la recherche de sessions : chercher « resume » doit trouver « Résumé ».
        // Deux regles differentes dans la meme app seraient vecues comme un bug.
        assertEquals(1, ChatSearch.find(messages, "resume").total)
        assertEquals(1, ChatSearch.find(messages, "RESUME").total)
        assertEquals(1, ChatSearch.find(messages, "Résumé").total)
    }

    @Test
    fun `le raisonnement est cherche, et signale comme tel`() {
        val messages = listOf(
            agent(id = "m1", text = "voila", reasoning = "j'ai d'abord lu le fichier de config"),
        )

        val result = ChatSearch.find(messages, "config")

        assertEquals(1, result.total)
        assertEquals(1, result.messageCount)
        // ⚠️ LE point de ce test : la correspondance est dans le RAISONNEMENT, qui est replie par
        // defaut. Sans ce signalement, l'utilisateur verrait un message retenu sans comprendre
        // pourquoi — le mot cherche n'apparait nulle part dans ce qui est visible.
        assertEquals(ChatSearch.Match.Field.Reasoning, result.matches.first().field)
    }

    @Test
    fun `les sorties d outils sont cherchees`() {
        val messages = listOf(
            agent(
                id = "m1",
                text = "c'est fait",
                tools = listOf(
                    ToolCall(
                        id = "t1",
                        name = "shell",
                        status = ToolStatus.Succeeded,
                        summary = "uname -a",
                        output = "Linux le serveur 6.8.0",
                    ),
                ),
            ),
        )

        // ⚠️ Cas d'usage le plus frequent : « quelle commande a tourne ? ». Cette information vit
        // dans un bloc replie ; ne pas la chercher rendrait la recherche inutile quand elle sert.
        val result = ChatSearch.find(messages, "uname")
        assertEquals(1, result.total)
        assertEquals(ChatSearch.Match.Field.Tool, result.matches.first().field)

        assertEquals(1, ChatSearch.find(messages, "le serveur").total)
    }

    @Test
    fun `le champ majoritaire est celui qui est annonce`() {
        // ⚠️ Quand le mot apparait partout, on annonce celui ou il est le PLUS present : c'est la
        // que l'utilisateur devra regarder en premier.
        val messages = listOf(
            agent(
                id = "m1",
                text = "timeout",
                reasoning = "le timeout est trop court, il faut augmenter le timeout global",
            ),
        )

        val result = ChatSearch.find(messages, "timeout")
        assertEquals(3, result.total)
        assertEquals(ChatSearch.Match.Field.Reasoning, result.matches.first().field)
    }

    @Test
    fun `plusieurs occurrences dans un message sont comptees`() {
        val messages = listOf(user("m1", "test test test"))

        val result = ChatSearch.find(messages, "test")
        assertEquals(3, result.total)
        // ⚠️ Un seul message, trois occurrences : le compte de messages et le total ne sont pas la
        // meme chose, et les confondre afficherait « 1 resultat » pour trois trouvailles.
        assertEquals(1, result.messageCount)
    }

    @Test
    fun `les identifiants correspondent aux messages trouves`() {
        val messages = listOf(
            user("m1", "premier"),
            user("m2", "deuxieme avec cible"),
            user("m3", "troisieme"),
        )

        val result = ChatSearch.find(messages, "cible")

        assertEquals(setOf("m2"), result.ids)
    }

    @Test
    fun `une recherche sans correspondance ne rend rien, mais garde la requete`() {
        val result = ChatSearch.find(listOf(user("m1", "salut")), "zzz")

        assertTrue(result.isEmpty)
        // ⚠️ La requete est conservee : c'est ce qui permet a l'ecran de dire « aucune session pour
        // « zzz » » en reprenant le terme, ce qui aide a reperer une faute de frappe.
        assertEquals("zzz", result.query)
    }
}
