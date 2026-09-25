package sh.sk7.tether.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTO de `GET /api/experimental/session/stats`.
 *
 * ⚠️ Formes relevees sur le serveur le 2026-09-25 :
 * `{data: {range:{from,to}, sessions, subagents, prompts, steps, tokens:{...}, cost,
 *  tools:{mode, totals:{calls, succeeded, failed, unfinished}}, activeDays, streak,
 *  activity:[{date, steps}], models:[{model:{id, providerID, variant}, steps, tokens, cost}]}}`
 *
 * On garde les noms du serveur (y compris `providerID` dans un objet imbrique) plutot que de
 * renommer : une divergence de nom entre le DTO et l'API est ce qui produit les bugs les plus
 * couteux a diagnostiquer, parce qu'ils sont silencieux (le champ vaut simplement `null`).
 */
@Serializable
data class StatsEnvelope(val data: StatsDto? = null)

@Serializable
data class StatsDto(
    val range: StatsRangeDto? = null,
    val sessions: Int = 0,
    val subagents: Int = 0,
    val prompts: Int = 0,
    val steps: Int = 0,
    val tokens: StatsTokensDto? = null,
    val cost: Double = 0.0,
    val tools: StatsToolsDto? = null,
    val activeDays: Int = 0,
    val streak: Int = 0,
    val activity: List<StatsActivityDto> = emptyList(),
    val models: List<StatsModelDto> = emptyList(),
)

@Serializable
data class StatsRangeDto(val from: Long? = null, val to: Long? = null)

@Serializable
data class StatsTokensDto(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: StatsCacheDto? = null,
)

@Serializable
data class StatsCacheDto(val read: Long = 0, val write: Long = 0)

@Serializable
data class StatsToolsDto(val mode: String? = null, val totals: StatsToolsTotalsDto? = null)

@Serializable
data class StatsToolsTotalsDto(
    val calls: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val unfinished: Int = 0,
)

@Serializable
data class StatsActivityDto(val date: String, val steps: Int = 0)

@Serializable
data class StatsModelDto(
    val model: ModelRef? = null,
    val steps: Int = 0,
    val tokens: StatsTokensDto? = null,
    val cost: Double = 0.0,
)

/**
 * Un projet connu du serveur (`GET /api/project`).
 *
 * ⚠️ `canonical` est le chemin reel ; `id` en est le hash. On affiche le chemin, jamais le hash :
 * un identifiant technique ne dit rien a personne.
 */
@Serializable
data class ProjectDto(
    val id: String,
    val canonical: String? = null,
    val time: TimeInfo? = null,
    val sandboxes: List<JsonObject> = emptyList(),
)

/**
 * Une entree d'une **collection** de l'API (`/api/command`, `/api/skill`, `/api/mcp`,
 * `/api/plugin`, `/api/provider`).
 *
 * ⚠️ Ces routes renvoient `{location: {...}, data: [...]}` — une enveloppe **differente** de
 * `{data: [...]}` utilisee ailleurs. Confondre les deux donne un `data` vide sans erreur.
 */
@Serializable
data class LocatedEnvelope<T>(val location: JsonObject? = null, val data: List<T> = emptyList())

/** `GET /api/command` : une commande slash disponible. */
@Serializable
data class CommandDto(val name: String, val description: String? = null)

/** `GET /api/skill` : une competence chargeable. */
@Serializable
data class SkillDto(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

/** `GET /api/mcp` : un serveur MCP et son etat de connexion. */
@Serializable
data class McpServerDto(
    val name: String,
    val status: McpStatusDto? = null,
)

@Serializable
data class McpStatusDto(val status: String? = null)

/** `GET /api/plugin` : un plugin et son etat. */
@Serializable
data class PluginDto(
    val id: String,
    val features: JsonObject? = null,
    val state: PluginStateDto? = null,
)

@Serializable
data class PluginStateDto(val status: String? = null)

/** `GET /api/provider` : un fournisseur de modeles. */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String? = null,
    val activation: String? = null,
)

/** `GET /api/permission/saved` : une autorisation accordee et memorisee. */
@Serializable
data class SavedPermissionDto(
    val id: String,
    val action: String? = null,
    val resource: String? = null,
    val time: TimeInfo? = null,
)

/** Enveloppe simple `{data: [...]}` (permissions sauvegardees, projets). */
@Serializable
data class ListEnvelope<T>(val data: List<T> = emptyList())
