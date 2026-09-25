package sh.sk7.tether.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import sh.sk7.tether.data.api.ContentPart
import sh.sk7.tether.data.api.InboxItemDto
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

    /**
     * **Un message encore dans la file**, tel que `GET /api/session/{id}/inbox` le rend.
     *
     * ⚠️ Cette route est indispensable au-delà du direct : une entrée en file **antérieure** à
     * l'ouverture de l'app n'a produit aucun événement SSE pour nous, et elle n'est **pas** dans
     * `GET /message` (mesure du 2026-09-25 : une session à deux prompts en file rend `count 0`).
     * Sans ce chemin, le message en attente serait invisible à l'ouverture — exactement le cas
     * principal, puisque l'inbox est ce qui reste quand le serveur travaille.
     *
     * ⚠️ On porte le `delivery` tel quel : c'est lui qui distingue ce qui **corrige le tour en
     * cours** (`steer`) de ce qui **attend son tour** (`queue`).
     */
    fun fromInbox(dto: InboxItemDto): ChatMessage = ChatMessage(
        id = dto.id,
        role = Role.User,
        text = dto.displayText.orEmpty(),
        delivery = dto.delivery,
        // Un item sans texte exploitable (piece jointe, `move`, `compaction`) garde sa charge
        // brute plutot que de disparaitre : c'est la regle de repli de l'app.
        rawFallback = if (dto.displayText.isNullOrBlank()) {
            dto.payload?.let { json.encodeToString(JsonObject.serializer(), it) }
        } else {
            null
        },
    )

    fun fromInboxItems(dtos: List<InboxItemDto>): List<ChatMessage> = dtos.map(::fromInbox)

    /**
     * **Reconcilie la file d'attente REST avec les messages deja affiches.**
     *
     * ### Pourquoi cette fonction existe, et pourquoi elle est pure
     * La file est une **verite serveur** (`GET /inbox`) qui vit en dehors de l'historique. Elle
     * arrive par deux chemins concurrents — le flux SSE (`session.inbox.enqueued`) et une lecture
     * REST a l'ouverture — et l'un peut passer avant l'autre. Une fusion ecrite au fil de l'eau
     * dans le ViewModel serait intestable et finirait par diverger ; ici, les regles sont
     * explicites et eprouvables.
     *
     * Regles, dans l'ordre :
     *  1. un message **deja affiche en file** et **encore** dans l'inbox garde sa place, mais son
     *     mode est **rafraichi** (le serveur peut avoir bascule `queue` -> `steer` par `PATCH`) ;
     *  2. un message **deja affiche en file** et **absent** de l'inbox n'attend plus : il perd son
     *     marqueur mais **reste** (il a ete livre) ;
     *  3. un message de l'inbox **inconnu** est ajoute **a la fin** — c'est un message qui vient
     *     d'entrer, il apparait apres le dernier tour ;
     *  4. tout message non concerne est conserve tel quel.
     *
     * ⚠️ On ne se fie **jamais** a l'index : les marqueurs de tour (`idle`, `synthetic`) ne
     * produisent pas de bulle, donc un index local ne correspond pas a la position serveur.
     */
    fun mergeInbox(existing: List<ChatMessage>, inbox: List<ChatMessage>): List<ChatMessage> {
        val inboxById = inbox.associateBy { it.id }
        val existingIds = existing.map { it.id }.toHashSet()

        // 1 & 2 : on corrige les marqueurs des messages deja presents.
        val reconciled = existing.map { message ->
            if (!message.isQueued) return@map message
            val fresh = inboxById[message.id]
            when {
                fresh != null -> message.copy(delivery = fresh.delivery)
                else -> message.copy(delivery = null)
            }
        }

        // 3 : les entrees d'inbox qu'on ne connait pas encore, ajoutees a la fin.
        val added = inbox.filter { it.id !in existingIds }

        return reconciled + added
    }

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
        // Duree du raisonnement : portee par la part `reasoning` elle-meme (`time.created` ->
        // `time.completed`), pas par le message. On garde la premiere mesure non nulle.
        var reasoningDuration: String? = null

        dto.content.forEachIndexed { index, part ->
            when (part.type) {
                "text" -> part.text?.let { text.append(it) }
                "reasoning" -> {
                    part.text?.let { reasoning.append(it) }
                    if (reasoningDuration == null) {
                        reasoningDuration = formatDuration(part.time?.created, part.time?.completed)
                    }
                }
                "tool" -> tools += part.toToolCall(index)
                else -> unknown += part.rawJson()
            }
        }

        return ChatMessage(
            id = dto.id,
            role = Role.Assistant,
            text = text.toString(),
            reasoning = reasoning.toString(),
            reasoningDurationLabel = reasoningDuration,
            tools = tools,
            rawFallback = unknown.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }

    private fun ContentPart.toToolCall(index: Int): ToolCall = ToolCall(
        id = id ?: "tool-$index",
        name = name.orEmpty(),
        status = parseStatus(state),
        raw = state?.let { json.encodeToString(JsonObject.serializer(), it) }.orEmpty(),
        summary = state?.summarizeInput(),
        output = state?.extractOutput(),
        durationLabel = time?.let { formatDuration(it.ran, it.completed) },
    )

    /**
     * **Resume l'entree de l'outil en une ligne** : `path`, `command`, `pattern`…
     *
     * ⚠️ Les cles vivent sous **`state.input`**, pas a la racine de `state` (mesure sur le
     * serveur : `{"status":"completed","input":{"command":"uname -a"},"content":[…]}`). On
     * accepte aussi la racine en repli, pour ne pas casser si une forme d'evenement place
     * l'entree a plat.
     *
     * ⚠️ Le choix des cles suit les outils REELLEMENT utilises : `shell` -> `command`,
     * `read` -> `path`, `grep`/`glob` -> `pattern`. Ce n'est **pas** une supposition sur
     * toutes les formes possibles : si aucune cle connue n'existe, on renvoie `null` (carte
     * sans resume) plutot que de fabriquer un libelle.
     *
     * La valeur est **tronquee** : un `command` de 300 caracteres doit rester une ligne.
     */
    private fun JsonObject.summarizeInput(): String? {
        val candidates = listOfNotNull(
            this["input"] as? JsonObject,
            this,
        )
        for (source in candidates) {
            for (key in INPUT_KEYS) {
                val value = (source[key] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: continue
                val oneLine = value.replace('\n', ' ').trim()
                // ⚠️ Un chemin se lit par sa FIN. Tronque par la fin (le defaut de l'UI),
                // `/home/utilisateur/.claude/projects/-home-utilisateur/memory/user_sebastien.md`
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

    /** `…/memory/user_sebastien.md` : la fin du chemin, seule partie qui identifie le fichier. */
    private fun shortenPath(path: String): String {
        val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
        return when {
            parts.size <= 2 -> path
            else -> "…/" + parts.takeLast(2).joinToString("/")
        }
    }

    /**
     * **Extrait la sortie texte** de `state.content[]`.
     *
     * `content` est une liste de parts `{type:"text", text:"…"}` : on joint les textes non
     * vides. C'est ce qu'on veut montrer au depliage — jamais le JSON de [raw].
     */
    private fun JsonObject.extractOutput(): String? {
        val parts = this["content"] as? JsonArray ?: return null
        val text = parts.mapNotNull { part ->
            ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull
        }.filter { it.isNotBlank() }.joinToString("\n")
        return text.ifBlank { null }
    }

    /** `time.ran` -> `time.completed` : la duree **mesuree par le serveur**. */
    private fun formatDuration(ran: Long?, completed: Long?): String? {
        if (ran == null || completed == null) return null
        val delta = completed - ran
        if (delta < 0 || delta > 3_600_000) return null
        return when {
            delta < 1_000 -> "${delta} ms"
            delta < 60_000 -> "${delta / 1_000} s"
            else -> "${delta / 60_000} min ${(delta % 60_000) / 1_000} s"
        }
    }

    private const val SUMMARY_MAX = 120

    /** Cles d'entree reconnues, par ordre de specificite. */
    private val INPUT_KEYS = listOf("command", "path", "pattern", "query", "url", "filePath", "description")

    /** Cles dont la valeur est un chemin : elles se lisent par leur FIN, pas leur debut. */
    private val PATH_KEYS = setOf("path", "filePath")

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
