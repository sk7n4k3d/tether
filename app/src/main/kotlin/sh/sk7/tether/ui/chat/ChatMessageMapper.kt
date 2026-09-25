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
import sh.sk7.tether.domain.model.ToolPayload

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
        status = ToolPayload.parseStatus(state),
        raw = state?.let { json.encodeToString(JsonObject.serializer(), it) }.orEmpty(),
        summary = state?.let(ToolPayload::summarizeInput),
        output = state?.let(ToolPayload::extractOutput),
        durationLabel = time?.let { formatDuration(it.ran, it.completed) },
    )

    /**
     * ⚠️ **Le resume, la sortie et le statut d'un outil ne vivent plus ici.**
     *
     * Ils sont partages avec le chemin SSE par [ToolPayload], parce que la meme charge arrive
     * par les deux routes (REST `content[].state` et `session.tool.*`) et que les dupliquer les
     * a fait diverger : le direct n'affichait ni le resume ni la sortie pendant des mois.
     */

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

    private fun ContentPart.rawJson(): String =
        json.encodeToString(
            JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                text?.let { put("text", kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        )
}
