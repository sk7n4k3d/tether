package sh.sk7.tether.data.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Fusionne les deltas de texte et de raisonnement consecutifs en un seul evenement.
 *
 * ### Pourquoi
 * Le modele emet ses deltas en rafale (plusieurs par trame de 16 ms), et chaque delta
 * recopiait la String accumulee dans le reducer — O(n²) sur la duree du tour, plus une
 * allocation par delta. L'ecran ne redessine qu'a ~60 fps : traiter plus d'un delta
 * par fenetre est du travail jete.
 *
 * ### Contrat
 * - [feed] rend la liste des evenements a appliquer : vide tant que les deltas
 *   s'accumulent dans la fenetre, le delta fusionne quand la fenetre expire, et tout
 *   evenement NON-delta vide d'abord le tampon (l'ordre vis-a-vis du reducer est
 *   preserve : un `text.ended` ne doit jamais passer devant son propre texte).
 * - La fonction est **pure** hors l'horloge injectee : testable sans coroutine.
 * - Le pire cas ajoute [windowMillis] de latence d'affichage si le modele fait une
 *   pause sans clore son bloc — la fin de bloc vide toujours le tampon, donc le texte
 *   ne peut pas rester bloque en fin de tour.
 */
class DeltaCoalescer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val textBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private var lastFlushAt = 0L
    private var lastTextEnvelope: JsonObject? = null
    private var lastReasoningEnvelope: JsonObject? = null

    /** Un evenement entre ; rend ceux qu'il faut appliquer, dans l'ordre. */
    fun feed(event: OcEvent): List<OcEvent> = when (event.type) {
        TYPE_TEXT_DELTA -> {
            event.data.str(DELTA)?.let { textBuffer.append(it) }
            lastTextEnvelope = event.data
            if (clock() - lastFlushAt >= windowMillis) flush() else emptyList()
        }
        TYPE_REASONING_DELTA -> {
            event.data.str(DELTA)?.let { reasoningBuffer.append(it) }
            lastReasoningEnvelope = event.data
            if (clock() - lastFlushAt >= windowMillis) flush() else emptyList()
        }
        else -> flush() + event
    }

    /** Vide les tampons en deltas fusionnes. Rend vide si rien n'etait en attente. */
    fun flush(): List<OcEvent> {
        val out = mutableListOf<OcEvent>()
        if (reasoningBuffer.isNotEmpty()) {
            out += merged(TYPE_REASONING_DELTA, reasoningBuffer.toString(), lastReasoningEnvelope)
            reasoningBuffer.clear()
        }
        if (textBuffer.isNotEmpty()) {
            out += merged(TYPE_TEXT_DELTA, textBuffer.toString(), lastTextEnvelope)
            textBuffer.clear()
        }
        if (out.isNotEmpty()) lastFlushAt = clock()
        return out
    }

    /** Reconstruit l'enveloppe du dernier delta vu, sa cle `delta` remplacee par le texte fusionne. */
    private fun merged(type: String, delta: String, envelope: JsonObject?): OcEvent {
        val data = buildJsonObject {
            envelope?.forEach { (key, value) -> put(key, value) }
            put(DELTA, delta)
        }
        return OcEvent(type = type, data = data)
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val TYPE_TEXT_DELTA = "session.text.delta"
        const val TYPE_REASONING_DELTA = "session.reasoning.delta"
        const val DELTA = "delta"
        const val DEFAULT_WINDOW_MILLIS = 50L
    }
}