package sh.sk7.tether.domain.model

import kotlinx.serialization.json.JsonObject
import sh.sk7.tether.data.api.PermissionRequest
import sh.sk7.tether.data.api.Tokens

/**
 * Modele d'affichage d'une conversation.
 *
 * Ces types sont ceux que consomme l'UI : ils sont volontairement **plus pauvres**
 * que les DTO REST (`MessageDto`) et sont le produit du [EventReducer].
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    val tools: List<ToolCall> = emptyList(),
    /** Charge d'un contenu de forme inconnue : affichee, jamais jetee (Review Focus n°4). */
    val rawFallback: String? = null,
)

enum class Role { User, Assistant }

/**
 * Appel d'outil en cours ou termine.
 *
 * ⚠️ **La forme d'un evenement `session.tool.*` n'a jamais ete capturee** (le tour de
 * reference n'appelait pas d'outil). Ce modele est donc volontairement generique :
 * [raw] garde la charge exacte, l'UI ne doit rien supposer d'autre.
 */
data class ToolCall(
    val id: String,
    val name: String = "",
    val status: ToolStatus = ToolStatus.Running,
    val raw: String = "",
)

enum class ToolStatus { Running, Succeeded, Failed }

/**
 * Formulaire en attente. Forme de `form.created` **jamais capturee** : on conserve
 * la charge brute sans l'interpreter.
 */
data class FormRequest(
    val id: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

enum class SessionStatus { Idle, Running, Succeeded, Failed, Interrupted }

/**
 * Etat d'un ecran de session, produit uniquement par [sh.sk7.tether.data.repository.EventReducer].
 *
 * Les champs `streaming*` sont l'etat **transitoire** du tour en cours ; a la cloture du
 * tour ils sont fusionnes dans [messages] puis remis a zero.
 */
data class SessionUiState(
    val sessionID: String,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String? = null,
    val streamingReasoning: String? = null,
    val streamingTools: List<ToolCall> = emptyList(),
    /** Id du message assistant du tour en cours (`assistantMessageID` des evenements). */
    val assistantMessageID: String? = null,
    val status: SessionStatus = SessionStatus.Idle,
    val pendingPermission: PermissionRequest? = null,
    val pendingForm: FormRequest? = null,
    /** Hashes du bloc d'instructions (`session.instructions.updated`, cles `core/...`). */
    val instructions: Map<String, String> = emptyMap(),
    val cost: Double? = null,          // alimente par session.usage.updated
    val tokens: Tokens? = null,        // alimente par session.usage.updated
)
