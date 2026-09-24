package sh.sk7.tether.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import sh.sk7.tether.data.api.OpenCodeClient
import sh.sk7.tether.data.api.PermissionRequest
import sh.sk7.tether.data.api.Tokens
import sh.sk7.tether.data.event.OcEvent
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.FormRequest
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.SessionStatus
import sh.sk7.tether.domain.model.SessionUiState
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus

/**
 * Reduit le flux d'evenements opencode V2 en [SessionUiState].
 *
 * Regles du contrat :
 * - **fonction pure** : aucun effet de bord, aucune coroutine, aucun log ;
 * - un **type inconnu n'est jamais une erreur** : l'etat revient inchange ;
 * - les champs sont extraits **avec des valeurs par defaut sures** : un champ absent
 *   ne fait pas lever.
 *
 * Les 18 types observes le 24/09 sont traites par domaine (texte, raisonnement, etapes,
 * usage, execution, inbox) dans des fonctions privees. Deux domaines sont traites par
 * **repli** car leur charge n'a jamais ete capturee :
 * - `session.tool.*` : chemin generique, aucun nom de champ suppose obligatoire ;
 * - `session.message.content.updated` : charge conservee telle quelle dans `rawFallback`.
 */
object EventReducer {

    private val json = OpenCodeClient.json

    fun reduce(state: SessionUiState, event: OcEvent): SessionUiState {
        // Le flux est global : un evenement d'une autre session ne doit pas polluer cet etat.
        val sessionID = event.sessionID
        if (sessionID != null && sessionID != state.sessionID) return state

        return when (event.type) {
            // --- Texte ---
            "session.text.started" -> state
            "session.text.delta" -> onTextDelta(state, event.data)
            "session.text.ended" -> onTextEnded(state, event.data)

            // --- Raisonnement ---
            "session.reasoning.started" -> onReasoningStarted(state, event.data)
            "session.reasoning.delta" -> onReasoningDelta(state, event.data)
            "session.reasoning.ended" -> onReasoningEnded(state, event.data)

            // --- Etapes ---
            "session.step.started" -> onStepStarted(state, event.data)
            "session.step.streamed" -> onStepStreamed(state, event.data)
            "session.step.ended" -> onStepEnded(state, event.data)

            // --- Execution (fin de tour) ---
            "session.execution.started" -> onExecutionStarted(state)
            "session.execution.succeeded" -> closeAssistantMessage(state, SessionStatus.Succeeded)
            "session.execution.failed" -> closeAssistantMessage(state, SessionStatus.Failed)
            "session.execution.interrupted" -> closeAssistantMessage(state, SessionStatus.Interrupted)

            // --- Inbox ---
            "session.inbox.enqueued" -> onInboxEnqueued(state, event.data)
            "session.inbox.delivered" -> state

            // --- Instructions ---
            "session.instructions.updated" -> onInstructionsUpdated(state, event.data)

            // --- Usage ---
            "session.usage.updated", "session.usage.recorded" -> onUsageUpdated(state, event.data)

            // --- Outils : forme de charge jamais capturee -> chemin generique ---
            "session.tool.input.started",
            "session.tool.input.delta",
            "session.tool.input.ended",
            "session.tool.called",
            "session.tool.progress" -> onToolEvent(state, event.data, ToolStatus.Running)
            "session.tool.success" -> onToolEvent(state, event.data, ToolStatus.Succeeded)
            "session.tool.failed" -> onToolEvent(state, event.data, ToolStatus.Failed)

            // --- Contenu de forme inconnue : conserve, jamais interprete ---
            "session.message.content.updated" -> onUnknownContent(state, event)

            // --- Attention : formes jamais capturees, par repli prudent ---
            "permission.asked" -> onPermissionAsked(state, event.data)
            "permission.replied" -> state.copy(pendingPermission = null)
            "form.created" -> state.copy(pendingForm = FormRequest(id = event.data.str("id"), raw = event.data))
            "form.replied", "form.cancelled" -> state.copy(pendingForm = null)

            // Tout type non traite (server.connected, session.created, ...) : ignore proprement.
            else -> state
        }
    }

    // ------------------------------------------------------------------
    // Texte
    // ------------------------------------------------------------------

    private fun onTextDelta(state: SessionUiState, data: JsonObject): SessionUiState {
        val delta = data.str("delta") ?: return state
        return state.copy(streamingText = (state.streamingText ?: "") + delta)
    }

    private fun onTextEnded(state: SessionUiState, data: JsonObject): SessionUiState {
        var next = state.withAssistantMessageID(data.str("assistantMessageID"))
        // Le texte final ne sert que si aucun delta n'a ete recu (sinon on ecraserait
        // une accumulation deja complete, et un `text` partiel perdrait des morceaux).
        if (next.streamingText == null) {
            data.str("text")?.let { next = next.copy(streamingText = it) }
        }
        return next
    }

    // ------------------------------------------------------------------
    // Raisonnement
    // ------------------------------------------------------------------

    private fun onReasoningStarted(state: SessionUiState, data: JsonObject): SessionUiState =
        state.withAssistantMessageID(data.str("assistantMessageID"))

    private fun onReasoningDelta(state: SessionUiState, data: JsonObject): SessionUiState {
        val delta = data.str("delta") ?: return state
        return state.copy(streamingReasoning = (state.streamingReasoning ?: "") + delta)
    }

    private fun onReasoningEnded(state: SessionUiState, data: JsonObject): SessionUiState {
        var next = state.withAssistantMessageID(data.str("assistantMessageID"))
        if (next.streamingReasoning == null) {
            data.str("text")?.let { next = next.copy(streamingReasoning = it) }
        }
        return next
    }

    // ------------------------------------------------------------------
    // Etapes
    // ------------------------------------------------------------------

    private fun onStepStarted(state: SessionUiState, data: JsonObject): SessionUiState =
        state.copy(
            status = SessionStatus.Running,
            assistantMessageID = data.str("assistantMessageID") ?: state.assistantMessageID,
        )

    private fun onStepStreamed(state: SessionUiState, data: JsonObject): SessionUiState =
        state.withAssistantMessageID(data.str("assistantMessageID"))

    private fun onStepEnded(state: SessionUiState, data: JsonObject): SessionUiState =
        state.withAssistantMessageID(data.str("assistantMessageID")).withUsage(data)

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private fun onExecutionStarted(state: SessionUiState): SessionUiState =
        state.copy(
            streamingText = null,
            streamingReasoning = null,
            streamingTools = emptyList(),
            assistantMessageID = null,
            status = SessionStatus.Running,
        )

    /**
     * Cloture le tour : le contenu transitoire devient un message assistant, puis les
     * champs `streaming*` sont remis a zero (regle : la verite reste le REST).
     *
     * - rien a cloturer si le tour n'a produit aucun contenu -> aucun message vide ;
     * - si un message du meme id existe deja (resync REST), il est **remplace**, pas duplique.
     */
    private fun closeAssistantMessage(state: SessionUiState, status: SessionStatus): SessionUiState {
        val text = state.streamingText
        val reasoning = state.streamingReasoning
        val tools = state.streamingTools
        val hasContent = text != null || reasoning != null || tools.isNotEmpty()

        val messages = if (!hasContent) {
            state.messages
        } else {
            val message = ChatMessage(
                id = state.assistantMessageID ?: "assistant-${state.messages.size}",
                role = Role.Assistant,
                text = text.orEmpty(),
                reasoning = reasoning.orEmpty(),
                tools = tools,
            )
            val index = state.messages.indexOfFirst { it.id == message.id }
            if (index >= 0) state.messages.toMutableList().also { it[index] = message }
            else state.messages + message
        }

        return state.copy(
            messages = messages,
            streamingText = null,
            streamingReasoning = null,
            streamingTools = emptyList(),
            assistantMessageID = null,
            status = status,
        )
    }

    // ------------------------------------------------------------------
    // Inbox
    // ------------------------------------------------------------------

    private fun onInboxEnqueued(state: SessionUiState, data: JsonObject): SessionUiState {
        val inboxID = data.str("inboxID") ?: return state
        if (state.messages.any { it.id == inboxID }) return state
        val text = data["item"]?.jsonObject?.get("payload")?.jsonObject?.str("text") ?: return state
        return state.copy(messages = state.messages + ChatMessage(inboxID, Role.User, text = text))
    }

    // ------------------------------------------------------------------
    // Instructions
    // ------------------------------------------------------------------

    private fun onInstructionsUpdated(state: SessionUiState, data: JsonObject): SessionUiState {
        val delta = data["delta"] as? JsonObject ?: return state
        val updates = delta.mapValues { (_, value) -> value.strValue() }
        return state.copy(instructions = state.instructions + updates)
    }

    // ------------------------------------------------------------------
    // Usage
    // ------------------------------------------------------------------

    private fun onUsageUpdated(state: SessionUiState, data: JsonObject): SessionUiState =
        state.withUsage(data)

    private fun SessionUiState.withUsage(data: JsonObject): SessionUiState {
        val cost = (data["cost"] as? JsonPrimitive)?.doubleOrNull
        val tokens = (data["tokens"] as? JsonObject)?.let(::decodeTokens)
        return copy(cost = cost ?: this.cost, tokens = tokens ?: this.tokens)
    }

    private fun decodeTokens(element: JsonObject): Tokens? =
        runCatching { json.decodeFromJsonElement(Tokens.serializer(), element) }.getOrNull()

    // ------------------------------------------------------------------
    // Outils (chemin generique : aucune forme de charge supposee)
    // ------------------------------------------------------------------

    private fun onToolEvent(state: SessionUiState, data: JsonObject, status: ToolStatus): SessionUiState {
        val id = data.str("toolCallID")
            ?: data.str("callID")
            ?: data.str("toolID")
            ?: data.str("id")
            ?: return state
        val existing = state.streamingTools.firstOrNull { it.id == id }
        val name = data.str("name") ?: data.str("tool") ?: existing?.name.orEmpty()
        // Un statut terminal (succes/echec) ne redescend jamais a "Running" sur un
        // evenement tardif ou desordonne.
        val effective = if (existing != null && existing.status.isTerminal() && status == ToolStatus.Running) {
            existing.status
        } else {
            status
        }
        val updated = ToolCall(id = id, name = name, status = effective, raw = data.toJsonString())
        val tools = if (existing == null) {
            state.streamingTools + updated
        } else {
            state.streamingTools.map { if (it.id == id) updated else it }
        }
        return state.copy(streamingTools = tools)
    }

    private fun ToolStatus.isTerminal(): Boolean =
        this == ToolStatus.Succeeded || this == ToolStatus.Failed

    // ------------------------------------------------------------------
    // Contenu de forme inconnue
    // ------------------------------------------------------------------

    private fun onUnknownContent(state: SessionUiState, event: OcEvent): SessionUiState {
        val id = event.data.str("assistantMessageID") ?: "fallback-${state.messages.size}"
        val message = ChatMessage(id = id, role = Role.Assistant, rawFallback = event.data.toJsonString())
        val index = state.messages.indexOfFirst { it.id == id }
        return if (index >= 0) {
            state.copy(messages = state.messages.toMutableList().also { it[index] = message })
        } else {
            state.copy(messages = state.messages + message)
        }
    }

    // ------------------------------------------------------------------
    // Attention (permissions)
    // ------------------------------------------------------------------

    private fun onPermissionAsked(state: SessionUiState, data: JsonObject): SessionUiState {
        // Forme jamais capturee : on ne retient la demande que si elle est decodable
        // selon le contrat OpenAPI ; sinon l'etat reste inchange (repli sur, pas invention).
        val request = runCatching {
            json.decodeFromJsonElement(PermissionRequest.serializer(), data)
        }.getOrNull() ?: return state
        return state.copy(pendingPermission = request)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun SessionUiState.withAssistantMessageID(id: String?): SessionUiState =
        if (id == null) this else copy(assistantMessageID = id)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.strValue(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toJsonString()

    private fun JsonElement.toJsonString(): String =
        json.encodeToString(JsonElement.serializer(), this)
}
