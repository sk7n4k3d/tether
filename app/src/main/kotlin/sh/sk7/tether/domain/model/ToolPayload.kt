package sh.sk7.tether.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * **Lecture des charges d'outil**, partagee par les deux chemins qui en recoivent.
 *
 * ### Pourquoi ce fichier existe
 * La meme charge d'outil arrive par **deux routes** :
 *  - le REST (`GET /message`, part `content[].state`), lu par `ChatMessageMapper` ;
 *  - le SSE (`session.tool.*`), lu par `EventReducer`.
 *
 * Les deux avaient besoin des memes regles (quelle cle resume l'entree, comment lire la
 * sortie, quel `status`). Les dupliquer etait la garantie qu'elles divergent — et c'est
 * exactement ce qui s'est produit : le direct n'afficheNI le resume ni la sortie, alors
 * que l'historique les affichait.
 *
 * ⚠️ Toutes ces formes sont des **mesures reelles** (capture du flux du 2026-09-25), jamais
 * des suppositions sur ce que le serveur « devrait » envoyer.
 */
object ToolPayload {

    /**
     * **Resume l'entree de l'outil en une ligne** : `path`, `command`, `pattern`…
     *
     * ⚠️ Quand la charge vient du REST, les cles vivent sous **`state.input`**, pas a la racine
     * (mesure : `{"status":"completed","input":{"command":"uname -a"},"content":[…]}`). Quand
     * elle vient du SSE `session.tool.called`, l'objet **est** deja l'entree
     * (`{"command":"…"}`). On essaie donc `input` d'abord, puis la racine : les deux formes
     * passent, et aucune forme ne casse l'autre.
     *
     * ⚠️ Le choix des cles suit les outils REELLEMENT utilises : `shell` -> `command`,
     * `read` -> `path`, `grep`/`glob` -> `pattern`. Ce n'est **pas** une supposition sur toutes
     * les formes possibles : si aucune cle connue n'existe, on renvoie `null` (carte sans
     * resume) plutot que de fabriquer un libelle.
     *
     * La valeur est **tronquee** : un `command` de 300 caracteres doit rester une ligne.
     */
    fun summarizeInput(payload: JsonObject): String? {
        val candidates = listOfNotNull(payload["input"] as? JsonObject, payload)
        for (source in candidates) {
            for (key in INPUT_KEYS) {
                val value = (source[key] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: continue
                val oneLine = value.replace('\n', ' ').trim()
                // ⚠️ Un chemin se lit par sa FIN. Tronque par la fin (le defaut de l'UI),
                // `/home/user/.claude/projects/-home-utilisateur/memory/user_sebastien.md`
                // devenait `/home/sk7n4k…` — trois fichiers differents, meme texte affiche.
                // On garde donc les deux derniers segments : `…/memory/user_sebastien.md`.
                val display = if (key in PATH_KEYS) shortenPath(oneLine) else oneLine
                return display.let {
                    if (it.length > SUMMARY_MAX) it.take(SUMMARY_MAX) + "…" else it
                }
            }
        }
        return null
    }

    /**
     * **Extrait la sortie texte** de `content[]`.
     *
     * `content` est une liste de parts `{type:"text", text:"…"}` : on joint les textes non
     * vides. C'est ce qu'on veut montrer au depliage — jamais le JSON brut de `raw`.
     *
     * ⚠️ Forme mesuree sur `session.tool.success` :
     * `{"content":[{"type":"text","text":"…"}],"metadata":{"status":"completed"}}`.
     */
    fun extractOutput(payload: JsonObject): String? {
        val parts = payload["content"] as? JsonArray ?: return null
        val text = parts.mapNotNull { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        }.filter { it.isNotBlank() }.joinToString("\n")
        return text.ifBlank { null }
    }

    /** `payload.status` : `running` | `completed` | `error` (formes reelles mesurees). */
    fun parseStatus(payload: JsonObject?): ToolStatus =
        when (payload?.get("status")?.jsonPrimitive?.contentOrNull) {
            "completed" -> ToolStatus.Succeeded
            "error", "failed" -> ToolStatus.Failed
            else -> ToolStatus.Running
        }

    /**
     * **L'entree d'outil, quelle que soit la route qui l'a portee.**
     *
     * - `session.tool.called` : un objet `input` (`{"command":"…"}`) ;
     * - `session.tool.input.ended` : la meme chose sous `text`, en **chaine JSON**
     *   (`"{\"command\":\"…\"}"`) — mesure du 2026-09-25 ;
     * - REST : un objet `state.input`.
     *
     * ⚠️ On accepte les trois plutot que d'en privilegier une : `input.ended` arrive **avant**
     * `called`, et s'en priver ferait apparaitre le resume une seconde trop tard.
     */
    fun extractInput(payload: JsonObject): JsonObject? {
        (payload["input"] as? JsonObject)?.let { return it }
        val text = (payload["text"] as? JsonPrimitive)?.contentOrNull ?: return null
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull()
    }

    /** `…/memory/user_sebastien.md` : la fin du chemin, seule partie qui identifie le fichier. */
    private fun shortenPath(path: String): String {
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return when {
            parts.size <= 2 -> path
            else -> "…/" + parts.takeLast(2).joinToString("/")
        }
    }

    private const val SUMMARY_MAX = 120

    /** Cles d'entree reconnues, par ordre de specificite. */
    private val INPUT_KEYS = listOf("command", "path", "pattern", "query", "url", "filePath", "description")

    /** Cles dont la valeur est un chemin : elles se lisent par leur FIN, pas leur debut. */
    private val PATH_KEYS = setOf("path", "filePath")
}
