package sh.sk7.tether.data.repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
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
import sh.sk7.tether.domain.model.ToolPayload
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

        // ⚠️ **Frontiere de message, appliquee AVANT tout traitement** (bug de l'affichage
        // « tout condense » en direct).
        //
        // Mesure du 2026-09-25 sur le flux reel : un tour produit **plusieurs messages
        // assistant**, chacun avec son propre `assistantMessageID`. Chaque etape emet son
        // `session.text.started` avec un id **different** du precedent :
        //
        //     step.started  msg=i9eIZZ
        //     text.started  msg=i9eIZZ
        //     text.delta    msg=i9eIZZ
        //     step.ended    msg=i9eIZZ
        //     step.started  msg=pB0J7s   <- NOUVEAU message
        //     text.started  msg=pB0J7s
        //
        // Or `streamingText` n'etait remis a zero qu'au **début du tour**. Les reponses de
        // toutes les etapes s'empilaient donc dans un seul bloc, collees sans espace
        // (« partout.Maintenant », « devie.Je compile »), et le rail ne marquait qu'un seul
        // nœud pour cinq messages. C'est exactement le constat de Bastien : en direct,
        // l'organisation disparait.
        //
        // ⚠️ On le fait **ici et pas dans chaque handler** : huit types d'evenements portent un
        // `assistantMessageID` (texte, raisonnement, outils, etapes). Un seul point de passage
        // ne peut pas etre oublie quand un type s'ajoute.
        //
        // ⚠️ `val state =` **masque volontairement** le parametre : le `when` ci-dessous opere
        // donc sur l'etat deja segmente, sans que chaque branche ait a y penser.
        val state = state.beginSegmentIfNewMessage(event.data)

        return when (event.type) {
            // --- Texte ---
            "session.text.started" -> state
            "session.text.delta" -> onTextDelta(state, event.data)
            "session.text.ended" -> onTextEnded(state, event.data)

            // --- Raisonnement ---
            "session.reasoning.started" -> onReasoningStarted(state, event.data, event.created)
            "session.reasoning.delta" -> onReasoningDelta(state, event.data)
            "session.reasoning.ended" -> onReasoningEnded(state, event.data, event.created)

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
            "session.inbox.delivered" -> onInboxDelivered(state, event.data)

            // --- Instructions ---
            "session.instructions.updated" -> onInstructionsUpdated(state, event.data)

            // --- Usage ---
            "session.usage.updated", "session.usage.recorded" -> onUsageUpdated(state, event.data)

            // --- Outils : forme de charge jamais capturee -> chemin generique ---
            // ⚠️ Les noms ci-dessous sont PROVISOIRES : aucun evenement `session.tool.*`
            // n'a jamais ete capture (le tour de reference n'appelait pas d'outil). Ils
            // sont au spec §5.3 et seront a confirmer par capture reelle en Phase 2.
            "session.tool.input.started",
            "session.tool.input.delta",
            "session.tool.input.ended",
            "session.tool.called",
            "session.tool.progress" -> onToolEvent(state, event.data, ToolStatus.Running, event.created)
            "session.tool.success" -> onToolEvent(state, event.data, ToolStatus.Succeeded, event.created)
            "session.tool.failed" -> onToolEvent(state, event.data, ToolStatus.Failed, event.created)

            // --- Contenu de forme inconnue : conserve, jamais interprete ---
            "session.message.content.updated" -> onUnknownContent(state, event)

            // --- Attention : formes jamais capturees, par repli prudent ---
            "permission.asked" -> onPermissionAsked(state, event.data)
            "permission.replied" -> state.copy(pendingPermission = null)
            // --- Formulaires : forme MESUREE (capture du 2026-09-26) ---
            //
            // ⚠️ **La charge est IMBRIQUEE sous `form`**, et le code lisait la racine.
            //
            // Mesure reelle, en creant un formulaire :
            //   form.created -> {"form":{"id":"frm_…","sessionID":"ses_…","title":"…","fields":[…]}}
            //
            // Le code faisait `event.data.str("id")` : la cle n'existe pas a la racine, donc
            // `FormRequest.id` valait **toujours `null`**, et ni le titre ni les champs ni la
            // session n'etaient portes. Un formulaire ne pouvait donc **jamais** etre affiche ni
            // rempli — la fonctionnalite etait morte a la source, pas seulement non cablee.
            //
            // ⚠️ `sessionID` peut valoir **`"global"`** (elicitation MCP, mesure) : ce n'est pas
            // une session. On le conserve tel quel, c'est `FormInfoDto.isGlobal` qui tranche.
            "form.created" -> onFormCreated(state, event.data)
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

    private fun onReasoningStarted(state: SessionUiState, data: JsonObject, at: Long?): SessionUiState =
        state
            .withAssistantMessageID(data.str("assistantMessageID"))
            // On retient le debut pour pouvoir afficher « Raisonne 12 s » une fois fini.
            .copy(reasoningStartedAt = at)

    private fun onReasoningDelta(state: SessionUiState, data: JsonObject): SessionUiState {
        val delta = data.str("delta") ?: return state
        return state.copy(streamingReasoning = (state.streamingReasoning ?: "") + delta)
    }

    private fun onReasoningEnded(state: SessionUiState, data: JsonObject, at: Long?): SessionUiState {
        var next = state.withAssistantMessageID(data.str("assistantMessageID"))
        if (next.streamingReasoning == null) {
            data.str("text")?.let { next = next.copy(streamingReasoning = it) }
        }
        // Duree du raisonnement : la ligne repliee doit la porter, sinon on ne peut pas
        // distinguer 2 s de reflexion de 2 min (defaut releve sur ChatGPT/Claude/Grok).
        return next.copy(reasoningDurationLabel = formatDuration(state.reasoningStartedAt, at))
    }

    // ------------------------------------------------------------------
    // Etapes
    // ------------------------------------------------------------------

    private fun onStepStarted(state: SessionUiState, data: JsonObject): SessionUiState {
        // ⚠️ Un `session.step.started` peut etre **rejoue** apres une reconnexion SSE suivie
        // d'une resync REST. S'il est deja terminal, on ne le fait PAS repasser en cours
        // (l'UI afficherait « en cours » sur une session finie). Un nouveau tour legitime
        // passe par `session.execution.started`, qui lui remet toujours l'etat a Running.
        val status = if (state.status.isTerminal()) state.status else SessionStatus.Running
        return state.copy(
            status = status,
            assistantMessageID = data.str("assistantMessageID") ?: state.assistantMessageID,
        )
    }

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
        // ⚠️ `as?` et non `jsonObject` : un item primitif/tableau ne doit pas lever.
        val item = data["item"] as? JsonObject
        val text = (item?.get("payload") as? JsonObject)?.str("text")
        // ⚠️ Le mode de livraison vit dans l'item (`delivery`), capture reelle du 2026-09-25 :
        // `{"item":{"payload":{"text":"…"},"delivery":"queue"}}`. On le porte sur le message pour
        // que le chat le DISE au lieu de jeter la distinction `steer` / `queue`.
        val delivery = (item?.get("delivery") as? JsonPrimitive)?.contentOrNull
        // Item sans texte (piece jointe, image) : on conserve la charge brute de l'item
        // plutot que de faire disparaitre le message utilisateur sans trace.
        val message = if (text != null) {
            ChatMessage(inboxID, Role.User, text = text, delivery = delivery)
        } else {
            ChatMessage(inboxID, Role.User, rawFallback = (item ?: data).toJsonString(), delivery = delivery)
        }
        return state.copy(messages = state.messages + message)
    }

    /**
     * Le message est **remis a l'agent** : il n'est plus en file.
     *
     * ⚠️ C'est le seul signal fiable de sortie de file. `GET /api/session/{id}/message` ne
     * l'expose pas : mesure du 2026-09-25, une session qui a deux prompts en file rend
     * `count 0` — l'inbox vit a part de l'historique. Donc une resync REST ne peut pas retirer le
     * mode : c'est `session.inbox.delivered` qui le fait, message par message.
     *
     * ⚠️ On ne retire **que** le marqueur, jamais le message : l'agent vient de le recevoir, il
     * doit rester dans la conversation.
     */
    private fun onInboxDelivered(state: SessionUiState, data: JsonObject): SessionUiState {
        val inboxID = data.str("inboxID") ?: return state
        val index = state.messages.indexOfFirst { it.id == inboxID }
        if (index < 0) return state
        val current = state.messages[index]
        if (current.delivery == null) return state
        return state.copy(
            messages = state.messages.toMutableList().also { it[index] = current.copy(delivery = null) },
        )
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

    /**
     * Traitement des evenements d'outil.
     *
     * ⚠️ **Formes desormais MESUREES** (capture du flux du 2026-09-25), elles ne sont plus
     * supposees. Un tour reel produit, dans cet ordre :
     *  - `session.tool.input.started` : `{id, name}` — l'outil est choisi, sans entree ;
     *  - `session.tool.input.ended`   : `{id, text}` — l'entree, en **chaine JSON** ;
     *  - `session.tool.called`        : `{id, input:{…}, executed}` — l'entree, en **objet** ;
     *  - `session.tool.progress`      : `{id, metadata:{shellID}}` ;
     *  - `session.tool.success`       : `{id, content:[{type,text}], metadata:{status,exit}}`.
     *
     * ⚠️ **Ce qui manquait, et pourquoi c'etait visible** : la sortie et le resume n'etaient
     * remplis que par le chemin REST ([sh.sk7.tether.ui.chat.ChatMessageMapper]). Pendant un tour,
     * rien ne les remplissait — la carte affichait « shell  en cours » **sans dire ce que
     * l'outil avait recu ni produit**. C'est le reproche exact de Bastien sur l'affichage en
     * direct. Les regles de lecture vivent maintenant dans
     * [sh.sk7.tether.domain.model.ToolPayload], partagees par les deux chemins.
     */
    private fun onToolEvent(state: SessionUiState, data: JsonObject, status: ToolStatus, at: Long?): SessionUiState {
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
        // ⚠️ Le resume et la sortie se **cumulent** au fil des evenements : chaque type n'en
        // porte qu'une partie (`called` l'entree, `success` le resultat). On garde ce qui est
        // deja connu quand l'evenement courant ne dit rien, sinon la carte se viderait au
        // passage de `called` a `success`.
        val input = ToolPayload.extractInput(data)
        val updated = ToolCall(
            id = id,
            name = name,
            status = effective,
            raw = data.toJsonString(),
            summary = input?.let(ToolPayload::summarizeInput) ?: existing?.summary,
            output = ToolPayload.extractOutput(data) ?: existing?.output,
            // A la creation on retient le debut ; sinon on conserve celui deja connu.
            startedAt = existing?.startedAt ?: at,
        )
        val tools = if (existing == null) {
            state.streamingTools + updated
        } else {
            state.streamingTools.map { if (it.id == id) updated else it }
        }
        // Duree de l'outil : un `shell` de 40 s sans duree passe pour de la reflexion.
        val duration = if (status.isTerminal()) formatDuration(updated.startedAt, at) else null
        val durations = if (duration != null) state.toolDurations + (id to duration) else state.toolDurations
        return state.copy(streamingTools = tools, toolDurations = durations)
    }

    /**
     * Formate une duree entre deux horodatages serveur.
     *
     * Fonction **pure** : aucun appel a l'horloge du telephone. Renvoie `null` si l'un des
     * deux horodatages manque ou si l'ecart est aberrant (flux rejoue, resync) — mieux vaut
     * aucune duree qu'une duree fausse.
     */
    private fun formatDuration(startedAt: Long?, endedAt: Long?): String? {
        if (startedAt == null || endedAt == null) return null
        val delta = endedAt - startedAt
        if (delta <= 0 || delta > 3_600_000) return null
        return when {
            delta < 1_000 -> "${delta} ms"
            delta < 60_000 -> "${delta / 1_000} s"
            else -> "${delta / 60_000} min ${(delta % 60_000) / 1_000} s"
        }
    }

    /**
     * **Un formulaire vient d'etre cree** — on le decode depuis la bonne cle.
     *
     * ⚠️ Mesure du 2026-09-26 : la charge est `{"form":{"id","sessionID","title","fields",…}}`.
     * L'ancien code lisait `id` a la racine, ce qui rendait `FormRequest.id` **toujours `null`**.
     * Le formulaire etait donc stocke sans identifiant — impossible a rendre ou a remplir.
     *
     * ⚠️ On conserve la charge **brute** (`raw`) en plus des champs decodes : un formulaire est
     * du texte non fiable, et si une forme evolue on veut pouvoir la lire au lieu de la perdre.
     * C'est la meme regle que `rawFallback` pour les messages.
     *
     * ⚠️ Un `form.created` **sans** objet `form` (forme inattendue) laisse l'etat inchange
     * plutot que de creer un formulaire fantome sans identifiant : on ne fabrique pas de donnee.
     */
    private fun onFormCreated(state: SessionUiState, data: JsonObject): SessionUiState {
        val form = data["form"] as? JsonObject ?: return state
        return state.copy(
            pendingForm = FormRequest(
                id = form.str("id"),
                sessionID = form.str("sessionID"),
                title = form.str("title"),
                raw = form,
            ),
        )
    }

    private fun ToolStatus.isTerminal(): Boolean =
        this == ToolStatus.Succeeded || this == ToolStatus.Failed

    // ⚠️ `SessionStatus.isTerminal()` vit desormais sur l'enum (voir ChatMessage.kt) : trois
    // fichiers en ont besoin, et une copie privee par fichier est ce qui fait diverger une regle.

    // ------------------------------------------------------------------
    // Contenu de forme inconnue
    // ------------------------------------------------------------------

    private fun onUnknownContent(state: SessionUiState, event: OcEvent): SessionUiState {
        // ⚠️ Regle : conserver l'inconnu SANS jamais ecraser le connu. Si le message
        // existe deja, on l'enrichit de `rawFallback` (text/reasoning/tools intacts).
        val raw = event.data.toJsonString()
        val id = event.data.str("assistantMessageID")
        val existing = id?.let { messageId -> state.messages.firstOrNull { it.id == messageId } }
        val merged = existing?.copy(rawFallback = raw)
            ?: ChatMessage(
                id = id ?: "fallback-${state.messages.size}",
                role = Role.Assistant,
                rawFallback = raw,
            )
        val index = state.messages.indexOfFirst { it.id == merged.id }
        return if (index >= 0) {
            state.copy(messages = state.messages.toMutableList().also { it[index] = merged })
        } else {
            state.copy(messages = state.messages + merged)
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

    /**
     * **Cloture le message precedent et ouvre le nouveau**, quand l'id change.
     *
     * ### Pourquoi c'est necessaire
     * ⚠️ Un **tour** (`session.execution.*`) contient **plusieurs messages** assistant. Mesure du
     * 2026-09-25 sur le flux reel : `step.started / text.started / text.delta / step.ended` se
     * repete avec un `assistantMessageID` **different** a chaque etape.
     *
     * Or les champs `streaming*` n'etaient remis a zero qu'au **début du tour**
     * (`session.execution.started`). Resultat : les reponses de toutes les etapes s'empilaient
     * dans un seul bloc — « partout.Maintenant », « devie.Je compile » — sans separation, sans
     * rail, avec un seul nœud pour cinq messages. C'est le bug « en direct, tout est condense,
     * il manque des informations ».
     *
     * ### Pourquoi on CLOTURE au lieu de jeter
     * ⚠️ Mettre `streamingText = null` ferait **disparaitre** les etapes precedentes pendant tout
     * le tour : l'ecran ne montrerait jamais que la derniere. On empile donc le message termine
     * dans `messages`, exactement comme le fait [closeAssistantMessage] en fin de tour — meme
     * donnee, meme rendu, meme rail.
     *
     * ⚠️ On ne cloture pas un etat sans contenu : sinon chaque `step.started` ajouterait une
     * bulle vide entre les messages.
     *
     * ⚠️ L'id precedent peut **revenir** (rejeu apres reconnexion, ou evenement desordonne) :
     * dans ce cas on ne cloture rien et on continue le message en cours — `id == null` comme
     * `id == current` retombent tous deux sur l'etat inchange.
     */
    private fun SessionUiState.beginSegmentIfNewMessage(data: JsonObject): SessionUiState {
        val incoming = data.str("assistantMessageID") ?: return this
        val current = assistantMessageID ?: return copy(assistantMessageID = incoming)
        if (incoming == current) return this

        val text = streamingText
        val reasoning = streamingReasoning
        val tools = streamingTools
        val hasContent = text != null || reasoning != null || tools.isNotEmpty()

        val messages = if (!hasContent) {
            messages
        } else {
            val closed = ChatMessage(
                id = current,
                role = Role.Assistant,
                text = text.orEmpty(),
                reasoning = reasoning.orEmpty(),
                tools = tools,
                reasoningDurationLabel = reasoningDurationLabel,
            )
            // Remplacement par id : une reconnexion peut rejouer un message deja empile.
            val index = messages.indexOfFirst { it.id == current }
            if (index >= 0) messages.toMutableList().also { it[index] = closed }
            else messages + closed
        }

        return copy(
            messages = messages,
            assistantMessageID = incoming,
            streamingText = null,
            streamingReasoning = null,
            streamingTools = emptyList(),
            reasoningDurationLabel = null,
            reasoningStartedAt = null,
            // ⚠️ Les durees d'outil restent : elles sont indexees par id d'outil, pas par message,
            // et l'outil appartient au message qu'on vient de cloturer — les effacer ferait
            // perdre la duree d'une carte deja affichee.
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.strValue(): String =
        (this as? JsonPrimitive)?.contentOrNull ?: toJsonString()

    private fun JsonElement.toJsonString(): String =
        json.encodeToString(JsonElement.serializer(), this)
}
