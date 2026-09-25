package sh.sk7.tether.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * DTOs du contrat API opencode V2, formes verifiees contre les fixtures reelles
 * capturees le 2026-09-24 (fixtures, docs/spike-api-v2-2026-09-24.md).
 */

/**
 * Enveloppe unique des listings. Deux formes coexistent cote serveur :
 * - `{data, cursor}` pour `/api/session` et `/api/session/{id}/message`
 * - `{location, data}` pour `/api/model`, `/api/agent`, `/api/provider`,
 *   `/api/permission/request`, `/api/form`
 * Les trois champs sont optionnels : un seul type decode les deux formes.
 * [T] est par defaut une liste ; pour `POST /api/session` (objet) voir [SessionEnvelope].
 */
@Serializable
data class DataEnvelope<T>(
    val data: List<T> = emptyList(),
    val cursor: Cursor? = null,
    val location: LocationInfo? = null,
)

/** `POST /api/session` renvoie `{data: <Session>}` = un OBJET, pas un tableau (spike §4). */
@Serializable
data class SessionEnvelope(val data: Session)

@Serializable
data class Cursor(val previous: String? = null, val next: String? = null)

/**
 * Une page d'un listing pagine, avec le curseur pour continuer.
 *
 * Les routes `GET /api/session` et `GET /api/session/{id}/message` paginent par defaut a
 * **50** elements : sans suivre [next], une liste est tronquee en silence (428 sessions
 * reelles le 2026-09-25). [next] est nul quand il n'y a plus rien a charger.
 */
data class CursorPage<T>(
    val data: List<T>,
    val next: String? = null,
    val previous: String? = null,
)

/** Reponse de `POST /api/session/{id}/interrupt` : `{interrupted}`. */
@Serializable
data class InterruptResponse(val interrupted: Boolean = false)

@Serializable
data class LocationInfo(val directory: String, val project: ProjectInfo? = null)

@Serializable
data class ProjectInfo(val id: String, val directory: String, val canonical: String? = null)

@Serializable
data class ModelRef(
    val id: String,
    val providerID: String,
    val variant: String? = null,
)

@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: CacheTokens = CacheTokens(),
)

@Serializable
data class CacheTokens(val read: Long = 0, val write: Long = 0)

@Serializable
data class TimeInfo(
    val created: Long? = null,
    val updated: Long? = null,
    val completed: Long? = null,
    val streamed: Long? = null,
    val idle: Long? = null,
    val viewed: Long? = null,
    /** `Session.time` : horodatage d'archivage. */
    val archived: Long? = null,
    /** `ContentPart.time` des parts `tool` : duree d'execution. */
    val ran: Long? = null,
)

@Serializable
data class Session(
    val id: String,
    val projectID: String? = null,
    val parentID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val outcome: String? = null,
    val time: TimeInfo? = null,
    val location: LocationInfo? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class Model(
    val id: String,
    val modelID: String? = null,
    val providerID: String? = null,
    val name: String? = null,
    val variants: List<ModelVariant> = emptyList(),
)

@Serializable
data class ModelVariant(val id: String, val settings: JsonObject? = null)

/**
 * Agent de `GET /api/agent`. Seuls les champs d'affichage sont retenus : la reponse reelle
 * porte aussi `system` (prompt complet, plusieurs kilo-octets) et `permissions`, inutiles ici.
 * `mode` vaut `primary`, `subagent` ou `all`.
 */
@Serializable
data class Agent(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean = false,
)

/**
 * Message du listing. Le discriminant est [type] — PAS un enum `role`.
 * Types reels observes : `assistant`, `user`, `idle`, `synthetic`.
 */
@Serializable
data class MessageDto(
    val id: String,
    val type: String,
    val time: TimeInfo? = null,
    /** `user` du listing : texte a plat. */
    val text: String? = null,
    /** `user` via `POST /prompt` : le texte est dans `payload.text`. */
    val payload: PromptPayload? = null,
    /** `assistant` : parts typees `reasoning`, `text`, `tool`. */
    val content: List<ContentPart> = emptyList(),
    val agent: String? = null,
    val model: ModelRef? = null,
    /** `idle` : resultat du tour. */
    val outcome: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val finish: String? = null,
    /** `synthetic` : libelle humain de la part injectee. */
    val description: String? = null,
    val files: List<JsonObject> = emptyList(),
    val agents: List<JsonObject> = emptyList(),
    val metadata: JsonObject? = null,
)

@Serializable
data class PromptPayload(val text: String? = null)

/**
 * Part de `content[]` d'un message assistant.
 * - `reasoning` : `text` + `state`
 * - `text` : `text`
 * - `tool` : `id`, `name`, `executed`, `state` (`{status, input, content, metadata}`)
 */
@Serializable
data class ContentPart(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
    val executed: Boolean? = null,
    val state: JsonObject? = null,
    val time: TimeInfo? = null,
)

@Serializable
data class PromptBody(val text: String)

@Serializable
data class PromptEnvelope(val data: PromptAcceptance)

/** Reponse de `POST /prompt` : l'item d'inbox accepte (spike §10bis). */
@Serializable
data class PromptAcceptance(
    val id: String,
    val sessionID: String,
    val type: String,
    val payload: PromptPayload? = null,
    /** `steer` | `queue`. */
    val delivery: String? = null,
    val time: TimeInfo? = null,
)

@Serializable
data class CreateSessionBody(
    val title: String,
    val model: ModelRef,
    val location: LocationBody,
    /** Optionnel : le serveur applique son agent par defaut si absent. */
    val agent: String? = null,
)

@Serializable
data class LocationBody(val directory: String)

/**
 * Demande de permission. Champs et noms alignes sur `Permission.Request` de l'OpenAPI
 * (`/openapi.json`, 2026-09-24). ⚠️ La forme n'a **jamais ete observee** en trafic reel
 * (auto-approbation, spike §8) : les requis du contrat sont declares non-null, le reste
 * reste tolérant.
 */
@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val source: PermissionSource? = null,
    val message: String? = null,
)

/** `Permission.Source` : decrit l'origine de la demande (aujourd'hui seule variante `tool`). */
@Serializable
data class PermissionSource(
    val type: String,
    val messageID: String? = null,
    val id: String? = null,
)

/** `GET /api/info`. */
@Serializable
data class ServerInfo(
    val version: String,
    val pid: Int? = null,
    val urls: List<String> = emptyList(),
    val paths: JsonObject? = null,
)
