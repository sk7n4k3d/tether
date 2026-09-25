package sh.sk7.tether.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import sh.sk7.tether.data.api.ContentPart
import sh.sk7.tether.data.api.MessageDto
import sh.sk7.tether.data.api.OpenCodeClient
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus

/**
 * Traduit un message REST en message d'affichage.
 *
 * La verite de l'historique vient du REST (spec §4.2) : a chaque connexion et reconnexion,
 * `messages(...)` est relu et remappe. Le flux SSE ne sert qu'au direct.
 *
 * Regles :
 * - `user` : texte a plat, ou `payload.text` quand le prompt vient de `POST /prompt` ;
 * - `assistant` : parts typees `reasoning`, `text`, `tool` ; un type de part **inconnu** est
 *   conserve dans `rawFallback` (Review Focus n°4 : jamais jeter le message) ;
 * - `synthetic` : note injectee (chargement d'AGENTS.md, rapport de sous-agent) ; libelle
 *   court affiche, contenu complet conserve en repli ;
 * - les types sans contenu (`idle`, `system`, `skill`, `shell`, `compaction`) ne produisent
 *   pas de bulle : ce sont des marqueurs de tour, pas du texte a lire.
 */
object ChatMessageMapper {

    private val json = OpenCodeClient.json

    fun fromDtos(dtos: List<MessageDto>): List<ChatMessage> = dtos.mapNotNull(::fromDto)

    fun fromDto(dto: MessageDto): ChatMessage? = when (dto.type) {
        "user" -> ChatMessage(
            id = dto.id,
            role = Role.User,
            text = dto.payload?.text ?: dto.text.orEmpty(),
        )
        "assistant" -> fromAssistant(dto)
        "synthetic" -> ChatMessage(
            id = dto.id,
            role = Role.Assistant,
            text = dto.description ?: dto.text.orEmpty(),
            rawFallback = dto.text?.takeIf { it.isNotBlank() && dto.description != null },
        )
        else -> null
    }

    private fun fromAssistant(dto: MessageDto): ChatMessage {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val tools = mutableListOf<ToolCall>()
        val unknown = mutableListOf<String>()

        dto.content.forEachIndexed { index, part ->
            when (part.type) {
                "text" -> part.text?.let { text.append(it) }
                "reasoning" -> part.text?.let { reasoning.append(it) }
                "tool" -> tools += part.toToolCall(index)
                else -> unknown += part.rawJson()
            }
        }

        return ChatMessage(
            id = dto.id,
            role = Role.Assistant,
            text = text.toString(),
            reasoning = reasoning.toString(),
            tools = tools,
            rawFallback = unknown.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    private fun ContentPart.toToolCall(index: Int): ToolCall = ToolCall(
        id = id ?: "tool-$index",
        name = name.orEmpty(),
        status = parseStatus(state),
        raw = state?.let { json.encodeToString(JsonObject.serializer(), it) }.orEmpty(),
    )

    /** `state.status` : `running` | `completed` | `error` (formes reelles mesurees). */
    private fun parseStatus(state: JsonObject?): ToolStatus =
        when (state?.get("status")?.jsonPrimitive?.contentOrNull) {
            "completed" -> ToolStatus.Succeeded
            "error", "failed" -> ToolStatus.Failed
            else -> ToolStatus.Running
        }

    private fun ContentPart.rawJson(): String =
        json.encodeToString(
            JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                text?.let { put("text", kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        )
}
