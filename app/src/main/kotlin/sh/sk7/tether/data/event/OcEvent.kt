package sh.sk7.tether.data.event

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import sh.sk7.tether.data.api.LocationInfo

/**
 * Evenement du flux opencode V2.
 *
 * Enveloppe reelle (mesuree le 2026-09-24) : `{id, created, type, location, data, durable}`.
 * Le **type est dans le JSON** (`type`), pas dans une ligne `event:` du transport.
 * Le **`sessionID` est dans `data.sessionID`**, ni a la racine ni dans `properties`
 * (formes V1).
 */
@Serializable
data class OcEvent(
    val id: String? = null,
    val created: Long? = null,
    val type: String,
    /** Objet `{aggregateID, seq, version}` — **pas** un booleen (spike §7). */
    val durable: DurableInfo? = null,
    val location: LocationInfo? = null,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    /** Raccourci : le sessionID porte par `data.sessionID`. */
    val sessionID: String? get() = data["sessionID"]?.jsonPrimitive?.contentOrNull
}

/** Position d'un evenement dans le log durable (rejeu/correlation). */
@Serializable
data class DurableInfo(
    val aggregateID: String,
    val seq: Int,
    val version: Int,
)
