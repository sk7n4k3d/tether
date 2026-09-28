package sh.sk7.tether.data.event

import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * Leve quand le serveur ne repond pas en `text/event-stream`.
 *
 * Cas typique : un serveur V2 renvoie `200 text/html` (fallback SPA) sur les
 * anciens chemins V1 — il ne faut **jamais** parser ce HTML comme des evenements
 * (piège KTOR-9023). Le transport doit re-tenter, pas decoder.
 */
class NotSseException(msg: String) : Exception(msg)

/**
 * Parseur SSE **incremental** : le reseau coupe les frames n'importe ou, donc
 * [feed] accumule les morceaux et ne rend que les frames completes.
 *
 * Formes reelles du flux opencode V2 (mesurees le 2026-09-24) :
 * - separateur de frame : ligne vide `\n\n` ;
 * - **aucune** ligne `event:` — le type est dans le JSON (`data.type`) ;
 * - **aucune** ligne `id:` — l'id est dans le JSON (`data.id`) ; `Last-Event-ID`
 *   est donc inutilisable, la resynchronisation passe par le REST ;
 * - des commentaires `: heartbeat` — ignores.
 */
class SseParser {
    private val buffer = StringBuilder()

    /** Ajoute un morceau recu et rend les frames completees par ce morceau. */
    fun feed(chunk: String): List<SseFrame> {
        // ⚠️ Normalisation CRLF : `EventStream` lit via `readUTF8Line()` qui retire deja
        // les CR, donc en pratique on recoit des `\n`. Mais un appelant direct (test sur
        // fixture brute, futur consommateur) pourrait livrer du `\r\n` — et `\r\n\r\n`
        // ne contient PAS `"\n\n"` -> zero frame, echec SILENCIEUX. On normalise ici.
        buffer.append(chunk.replace("\r\n", "\n").replace('\r', '\n'))
        val frames = mutableListOf<SseFrame>()
        while (true) {
            val idx = buffer.indexOf("\n\n")
            if (idx < 0) break
            val raw = buffer.substring(0, idx)
            buffer.delete(0, idx + 2)
            parseFrame(raw)?.let { frames += it }
        }
        return frames
    }

    private fun parseFrame(raw: String): SseFrame? {
        // Les lignes commencant par ":" sont des COMMENTAIRES (heartbeat) -> ignorees.
        val data = raw.lineSequence()
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trimStart() }
        if (data.isEmpty()) return null
        // Pas de ligne `id:` dans le flux V2 ; on garde le support du protocole.
        val id = raw.lineSequence()
            .firstOrNull { it.startsWith("id:") }
            ?.removePrefix("id:")?.trim()
        return SseFrame(id = id, data = data)
    }

    companion object {
        /**
         * Verifie le `Content-Type` **avant** de consommer le flux. Rejette tout
         * ce qui n'est pas `text/event-stream` (dont `text/html` = fallback SPA).
         */
        fun requireEventStream(contentType: String?) {
            if (contentType == null || !contentType.startsWith("text/event-stream"))
                throw NotSseException(Res.of(R.string.content_type_inattendu_824503))
        }
    }
}
