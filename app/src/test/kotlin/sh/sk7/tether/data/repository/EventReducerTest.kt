package sh.sk7.tether.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import sh.sk7.tether.data.api.OpenCodeClient
import sh.sk7.tether.data.event.OcEvent
import sh.sk7.tether.data.event.SseParser
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.SessionStatus
import sh.sk7.tether.domain.model.SessionUiState
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventReducerTest {

    // ---------------------------------------------------------------------
    // Contrat de base (Review Focus n°4)
    // ---------------------------------------------------------------------

    @Test
    fun `un delta de texte s'ajoute au message en cours`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.text.started"))
        // ⚠️ Le champ est `delta`, PAS `text` (vérifié sur trafic réel, spike §10bis)
        s = EventReducer.reduce(s, ev("session.text.delta", """{"delta":"Bon"}"""))
        s = EventReducer.reduce(s, ev("session.text.delta", """{"delta":"jour"}"""))
        assertEquals("Bonjour", s.streamingText)
    }

    @Test
    fun `execution succeeded cloture le message`() {
        var s = SessionUiState(sessionID = "ses_1").copy(streamingText = "fini")
        s = EventReducer.reduce(s, ev("session.execution.succeeded"))
        assertNull(s.streamingText)
        assertEquals(1, s.messages.size)
    }

    @Test
    fun `un type de contenu inconnu est conserve en repli brut`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.message.content.updated", """{"type":"type_jamais_vu","valeur":1}"""),
        )
        assertTrue(s.messages.any { it.rawFallback != null })
    }

    @Test
    fun `un type d'evenement inconnu ne casse pas le reducer`() {
        val before = SessionUiState(sessionID = "ses_1")
        val after = EventReducer.reduce(before, ev("session.totalement.inconnu"))
        assertEquals(before, after)   // ignoré proprement, état inchangé
    }

    @Test
    fun `usage updated met a jour le cout et les tokens`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.usage.updated", """{"cost":0.0026,"tokens":{"input":10,"output":2,"reasoning":0,"cache":{"read":0,"write":0}}}"""),
        )
        assertEquals(0.0026, s.cost!!, 0.00001)
        assertEquals(10L, s.tokens!!.input)
    }

    // ---------------------------------------------------------------------
    // Texte
    // ---------------------------------------------------------------------

    @Test
    fun `text ended fixe le texte final quand aucun delta n'est arrive`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.text.ended", """{"text":"reponse complete","assistantMessageID":"msg_a"}"""),
        )
        assertEquals("reponse complete", s.streamingText)
        assertEquals("msg_a", s.assistantMessageID)
    }

    @Test
    fun `text ended ne casse pas un texte deja accumule par les deltas`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.text.delta", """{"delta":"Bon"}"""))
        s = EventReducer.reduce(s, ev("session.text.ended", """{"text":"Bon"}"""))
        assertEquals("Bon", s.streamingText)
    }

    @Test
    fun `un delta de texte sans champ delta laisse l'etat inchange`() {
        val before = SessionUiState(sessionID = "ses_1")
        assertEquals(before, EventReducer.reduce(before, ev("session.text.delta")))
    }

    // ---------------------------------------------------------------------
    // Raisonnement
    // ---------------------------------------------------------------------

    @Test
    fun `un delta de raisonnement s'ajoute et se fond dans le message cloture`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.execution.started"))
        s = EventReducer.reduce(s, ev("session.reasoning.started", """{"assistantMessageID":"msg_a"}"""))
        s = EventReducer.reduce(s, ev("session.reasoning.delta", """{"delta":"je "}"""))
        s = EventReducer.reduce(s, ev("session.reasoning.delta", """{"delta":"reflechis"}"""))
        assertEquals("je reflechis", s.streamingReasoning)

        s = EventReducer.reduce(s, ev("session.text.delta", """{"delta":"ok"}"""))
        s = EventReducer.reduce(s, ev("session.reasoning.ended", """{"text":"je reflechis"}"""))
        s = EventReducer.reduce(s, ev("session.execution.succeeded"))

        val message = s.messages.single()
        assertEquals("ok", message.text)
        assertEquals("je reflechis", message.reasoning)
        assertEquals("msg_a", message.id)
        assertNull(s.streamingReasoning)
    }

    @Test
    fun `reasoning ended fixe le texte final quand aucun delta n'est arrive`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.reasoning.ended", """{"text":"pensee finale"}"""),
        )
        assertEquals("pensee finale", s.streamingReasoning)
    }

    // ---------------------------------------------------------------------
    // Etapes
    // ---------------------------------------------------------------------

    @Test
    fun `step started passe le statut en cours et retient l'id du message assistant`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.step.started", """{"assistantMessageID":"msg_a","agent":"general"}"""),
        )
        assertEquals(SessionStatus.Running, s.status)
        assertEquals("msg_a", s.assistantMessageID)
    }

    @Test
    fun `step ended met a jour le cout et les tokens`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.step.ended", """{"assistantMessageID":"msg_a","finish":"stop","cost":0.5,"tokens":{"input":7,"output":3}}"""),
        )
        assertEquals(0.5, s.cost!!, 1e-9)
        assertEquals(7L, s.tokens!!.input)
        assertEquals(3L, s.tokens!!.output)
    }

    @Test
    fun `step streamed retient l'id du message assistant sans autre effet`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1", streamingText = "en cours"),
            ev("session.step.streamed", """{"assistantMessageID":"msg_a"}"""),
        )
        assertEquals("msg_a", s.assistantMessageID)
        assertEquals("en cours", s.streamingText)
    }

    @Test
    fun `step started ne fait pas repasser une session terminale en cours`() {
        val terminal = SessionUiState(sessionID = "ses_1", status = SessionStatus.Succeeded)
        val s = EventReducer.reduce(
            terminal,
            ev("session.step.started", """{"assistantMessageID":"msg_rejoue"}"""),
        )
        assertEquals(SessionStatus.Succeeded, s.status)   // un replay ne relance pas l'UI
        assertEquals("msg_rejoue", s.assistantMessageID)
    }

    @Test
    fun `un nouveau tour repasse bien en cours apres un statut terminal`() {
        var s = SessionUiState(sessionID = "ses_1", status = SessionStatus.Succeeded)
        s = EventReducer.reduce(s, ev("session.execution.started"))
        assertEquals(SessionStatus.Running, s.status)
        s = EventReducer.reduce(s, ev("session.step.started", """{"assistantMessageID":"msg_new"}"""))
        assertEquals(SessionStatus.Running, s.status)
    }

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    @Test
    fun `execution started repart d'un tour propre`() {
        val dirty = SessionUiState(
            sessionID = "ses_1",
            streamingText = "vieux",
            streamingReasoning = "vieux r",
            streamingTools = listOf(ToolCall(id = "t1")),
            assistantMessageID = "msg_old",
            status = SessionStatus.Succeeded,
        )
        val s = EventReducer.reduce(dirty, ev("session.execution.started"))
        assertNull(s.streamingText)
        assertNull(s.streamingReasoning)
        assertTrue(s.streamingTools.isEmpty())
        assertNull(s.assistantMessageID)
        assertEquals(SessionStatus.Running, s.status)
    }

    @Test
    fun `execution failed cloture le message partiel et marque l'echec`() {
        var s = SessionUiState(sessionID = "ses_1", streamingText = "partiel")
        s = EventReducer.reduce(s, ev("session.execution.failed"))
        assertEquals(SessionStatus.Failed, s.status)
        assertEquals("partiel", s.messages.single().text)
        assertNull(s.streamingText)
    }

    @Test
    fun `execution interrupted cloture le message partiel`() {
        var s = SessionUiState(sessionID = "ses_1", streamingText = "partiel")
        s = EventReducer.reduce(s, ev("session.execution.interrupted"))
        assertEquals(SessionStatus.Interrupted, s.status)
        assertEquals(1, s.messages.size)
    }

    @Test
    fun `execution succeeded sans streaming ne fabrique pas de message vide`() {
        val s = EventReducer.reduce(SessionUiState(sessionID = "ses_1"), ev("session.execution.succeeded"))
        assertTrue(s.messages.isEmpty())
        assertEquals(SessionStatus.Succeeded, s.status)
    }

    @Test
    fun `execution succeeded remplace un message deja present sans le dupliquer`() {
        val before = SessionUiState(
            sessionID = "ses_1",
            streamingText = "fini",
            assistantMessageID = "assistant-0",
            messages = listOf(ChatMessage(id = "assistant-0", role = Role.Assistant, text = "vieux")),
        )
        val after = EventReducer.reduce(before, ev("session.execution.succeeded"))
        assertEquals(1, after.messages.size)               // pas dupliqué
        assertEquals("fini", after.messages.single().text) // remplacé, pas ignoré
    }

    // ---------------------------------------------------------------------
    // Inbox
    // ---------------------------------------------------------------------

    @Test
    fun `inbox enqueued ajoute le message utilisateur une seule fois`() {
        val data = """{"inboxID":"msg_u","item":{"type":"user","payload":{"text":"salut"},"delivery":"steer"}}"""
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.inbox.enqueued", data))
        s = EventReducer.reduce(s, ev("session.inbox.enqueued", data))
        assertEquals(1, s.messages.size)
        assertEquals("salut", s.messages.single().text)
        assertEquals(Role.User, s.messages.single().role)
    }

    @Test
    fun `inbox enqueued conserve le mode de livraison`() {
        // ⚠️ Forme reelle capturee le 2026-09-25 : `item.delivery` vaut `queue`/`steer`. La
        // distinction corrige le tour en cours vs attend son tour etait jetee par l'app.
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev(
                "session.inbox.enqueued",
                """{"inboxID":"msg_q","item":{"type":"user","payload":{"text":"attends"},"delivery":"queue"}}""",
            ),
        )

        assertEquals("queue", s.messages.single().delivery)
        assertEquals(true, s.messages.single().isQueued)
    }

    @Test
    fun `inbox delivered retire le marqueur sans retirer le message`() {
        // ⚠️ Mesure : un message en file n'apparait PAS dans `GET /message` (count 0). Donc la
        // resync REST ne peut pas retirer le mode : c'est `session.inbox.delivered` qui le fait.
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(
            s,
            ev(
                "session.inbox.enqueued",
                """{"inboxID":"msg_q","item":{"type":"user","payload":{"text":"attends"},"delivery":"queue"}}""",
            ),
        )
        s = EventReducer.reduce(s, ev("session.inbox.delivered", """{"inboxID":"msg_q"}"""))

        assertEquals(1, s.messages.size, "le message livre reste dans la conversation")
        assertEquals(null, s.messages.single().delivery, "il n'est plus en file")
    }

    @Test
    fun `inbox delivered sans marqueur ne fait rien`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(
            s,
            ev("session.inbox.enqueued", """{"inboxID":"msg_u","item":{"type":"user","payload":{"text":"salut"}}}"""),
        )
        s = EventReducer.reduce(s, ev("session.inbox.delivered", """{"inboxID":"msg_u"}"""))

        assertEquals(1, s.messages.size)
    }

    @Test
    fun `inbox delivered pour un id inconnu ne fait rien`() {
        val before = SessionUiState(sessionID = "ses_1")
        val after = EventReducer.reduce(before, ev("session.inbox.delivered", """{"inboxID":"msg_nope"}"""))

        assertEquals(before, after)
    }

    @Test
    fun `inbox delivered ne duplique pas le message`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(
            s,
            ev("session.inbox.enqueued", """{"inboxID":"msg_u","item":{"type":"user","payload":{"text":"salut"}}}"""),
        )
        s = EventReducer.reduce(s, ev("session.inbox.delivered", """{"inboxID":"msg_u"}"""))
        assertEquals(1, s.messages.size)
    }

    @Test
    fun `inbox enqueued sans texte conserve l'item brut au lieu de le jeter`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev(
                "session.inbox.enqueued",
                """{"inboxID":"msg_u","item":{"type":"user","payload":{"attachment":"photo.png"}}}""",
            ),
        )
        assertEquals(1, s.messages.size)
        assertEquals(Role.User, s.messages.single().role)
        assertEquals("""{"type":"user","payload":{"attachment":"photo.png"}}""", s.messages.single().rawFallback)
    }

    @Test
    fun `inbox enqueued avec un item non objet ne leve pas et garde l'item brut`() {
        val raw = """{"inboxID":"msg_u","item":"pas_un_objet"}"""
        val s = EventReducer.reduce(SessionUiState(sessionID = "ses_1"), ev("session.inbox.enqueued", raw))
        assertEquals(1, s.messages.size)
        assertEquals(raw, s.messages.single().rawFallback)
    }

    // ---------------------------------------------------------------------
    // Instructions
    // ---------------------------------------------------------------------

    @Test
    fun `instructions updated conserve les hashes du delta`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.instructions.updated", """{"delta":{"core/environment":"abc","core/date":"def"}}"""),
        )
        assertEquals("abc", s.instructions["core/environment"])
        assertEquals("def", s.instructions["core/date"])
    }

    // ---------------------------------------------------------------------
    // Contenu de forme inconnue
    // ---------------------------------------------------------------------

    @Test
    fun `le repli brut conserve la charge exacte de l'evenement`() {
        val payload = """{"type":"type_jamais_vu","valeur":1}"""
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.message.content.updated", payload),
        )
        assertEquals(payload, s.messages.single().rawFallback)
    }

    @Test
    fun `un contenu inconnu enrichit le message existant sans ecraser son texte`() {
        val before = SessionUiState(
            sessionID = "ses_1",
            messages = listOf(
                ChatMessage(
                    id = "msg_a",
                    role = Role.Assistant,
                    text = "reponse connue",
                    reasoning = "raisonnement connu",
                ),
            ),
        )
        val raw = """{"assistantMessageID":"msg_a","type":"type_jamais_vu","valeur":1}"""
        val s = EventReducer.reduce(before, ev("session.message.content.updated", raw))

        assertEquals(1, s.messages.size)                     // pas de message en double
        assertEquals("reponse connue", s.messages.single().text)       // connu intact
        assertEquals("raisonnement connu", s.messages.single().reasoning)
        assertEquals(raw, s.messages.single().rawFallback)   // inconnu conserve
    }

    // ---------------------------------------------------------------------
    // Outils (forme jamais capturee -> chemin generique)
    // ---------------------------------------------------------------------

    @Test
    fun `un evenement d'outil sans identifiant ne casse pas le reducer`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.tool.called", """{"truc":"jamais vu","num":3}"""),
        )
        assertTrue(s.messages.isEmpty())
        assertTrue(s.streamingTools.isEmpty())
    }

    @Test
    fun `un evenement d'outil avec une charge garbage ne corrompt pas l'existant`() {
        val existing = ToolCall(id = "call_1", name = "bash", status = ToolStatus.Running, raw = "{}")
        val before = SessionUiState(
            sessionID = "ses_1",
            streamingText = "en cours",
            streamingTools = listOf(existing),
        )
        // id present mais pas un id d'outil, types inattendus (tableau, objet, null)
        val s = EventReducer.reduce(
            before,
            ev("session.tool.success", """{"toolCallID":["pas","un","id"],"state":{"x":1},"name":null}"""),
        )
        // L'entree isolee est ignoree (id non textuel) : l'outil existant est intact.
        assertEquals(listOf(existing), s.streamingTools)
        assertEquals("en cours", s.streamingText)
        assertEquals(1, s.streamingTools.size)
    }

    @Test
    fun `un outil suit ses etats puis se fond dans le message cloture`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.tool.input.started", """{"toolCallID":"call_1","name":"bash"}"""))
        assertEquals(ToolStatus.Running, s.streamingTools.single().status)

        s = EventReducer.reduce(s, ev("session.tool.success", """{"toolCallID":"call_1"}"""))
        assertEquals(ToolStatus.Succeeded, s.streamingTools.single().status)

        s = EventReducer.reduce(s, ev("session.text.delta", """{"delta":"done"}"""))
        s = EventReducer.reduce(s, ev("session.execution.succeeded"))

        val tool = s.messages.single().tools.single()
        assertEquals("bash", tool.name)
        assertEquals(ToolStatus.Succeeded, tool.status)
        assertTrue(tool.raw.isNotBlank())
        assertTrue(s.streamingTools.isEmpty())
    }


    // ---------------------------------------------------------------------
    // Formulaires : la charge est IMBRIQUEE sous `form`
    //
    // ⚠️ Test ecrit sur la reponse **reellement capturee** le 2026-09-26, en creant un
    // formulaire sur une session. Avant le correctif, le reducer lisait `id` a la racine
    // de `data` : la cle n'existe pas, donc `FormRequest.id` valait toujours `null` et le
    // formulaire etait impossible a afficher ou remplir.
    // ---------------------------------------------------------------------

    @Test
    fun `form created decode la charge imbriquee sous form`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev(
                "form.created",
                """{"form":{"id":"frm_0dacf167b001lQnLKr0NkCRi5d","sessionID":"ses_1","title":"Probe Tether","fields":[{"key":"nom","title":"Nom","type":"string"}]}}""",
            ),
        )
        val form = s.pendingForm
        assertNotNull(form, "un form.created doit creer une demande en attente")
        assertEquals("frm_0dacf167b001lQnLKr0NkCRi5d", form.id, "l'id vit sous `form`, pas a la racine")
        assertEquals("ses_1", form.sessionID)
        assertEquals("Probe Tether", form.title)
        // ⚠️ La charge brute est conservee : un formulaire evolue, on veut pouvoir le relire.
        assertTrue(form.raw.containsKey("fields"))
    }

    @Test
    fun `un formulaire global porte sessionID global, sans session derriere`() {
        // ⚠️ Mesure : une elicitation MCP a `sessionID == "global"`. Ce n'est PAS une session :
        // le code ne doit jamais supposer qu'il y en a une derriere.
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("form.created", """{"form":{"id":"frm_x","sessionID":"global","title":"MCP","fields":[]}}"""),
        )
        assertEquals("global", s.pendingForm?.sessionID)
    }

    @Test
    fun `un form created sans objet form ne cree pas de formulaire fantome`() {
        // ⚠️ Forme inattendue : on n'invente pas de donnee. Un formulaire sans identifiant serait
        // pire qu'aucun formulaire — il s'afficherait sans pouvoir etre rempli.
        val before = SessionUiState(sessionID = "ses_1")
        val after = EventReducer.reduce(before, ev("form.created", """{"id":"frm_racine","title":"Faux"}"""))
        assertNull(after.pendingForm)
    }

    // ---------------------------------------------------------------------
    // Ce que l'outil a RECU et PRODUIT, pendant le tour
    //
    // ⚠️ Ces tests portent sur des charges **capturees du flux reel** (2026-09-25). Avant,
    // seuls les messages termines (chemin REST) remplissaient `summary`/`output` : pendant un
    // tour, la carte affichait « shell  en cours » sans rien dire de plus. C'est le reproche
    // exact de Bastien sur l'affichage en direct.
    // ---------------------------------------------------------------------

    /**
     * ⚠️ **Le bug « en direct, tout est condense »**, reproduit sur la sequence reellement
     * capturee le 2026-09-25 : un tour contient **plusieurs messages assistant**, chacun avec son
     * `assistantMessageID`.
     *
     * Sans segmentation, les reponses de toutes les etapes s'empilaient dans un seul bloc :
     * « partout.Maintenant », « devie.Je compile » — collees, sans rail, sans nœud par message.
     */
    @Test
    fun `un tour a plusieurs messages et chacun garde son bloc`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.execution.started"))

        // --- etape 1 : msg_A ---
        s = EventReducer.reduce(s, ev("session.step.started", """{"assistantMessageID":"msg_A"}"""))
        s = EventReducer.reduce(s, ev("session.text.started", """{"assistantMessageID":"msg_A"}"""))
        s = EventReducer.reduce(s, ev("session.text.delta", """{"assistantMessageID":"msg_A","delta":"Premier"}"""))
        s = EventReducer.reduce(s, ev("session.text.ended", """{"assistantMessageID":"msg_A"}"""))
        s = EventReducer.reduce(s, ev("session.step.ended", """{"assistantMessageID":"msg_A"}"""))
        assertEquals("Premier", s.streamingText, "l'etape en cours porte son propre texte")

        // --- etape 2 : msg_B, un AUTRE message ---
        s = EventReducer.reduce(s, ev("session.step.started", """{"assistantMessageID":"msg_B"}"""))
        s = EventReducer.reduce(s, ev("session.text.started", """{"assistantMessageID":"msg_B"}"""))
        s = EventReducer.reduce(s, ev("session.text.delta", """{"assistantMessageID":"msg_B","delta":"Second"}"""))

        // ⚠️ **C'est l'assertion du bug** : le texte de la 2e etape ne doit PAS contenir celui de
        // la 1re. Avant le correctif on obtenait « PremierSecond », d'ou l'affichage colle.
        assertEquals("Second", s.streamingText, "chaque message a son propre texte, jamais l'accumulation")

        // ⚠️ Et le message precedent est **empile**, pas jete : mettre a `null` aurait fait
        // disparaitre les etapes precedentes pendant tout le tour.
        assertEquals(1, s.messages.size, "l'etape precedente doit rester visible")
        assertEquals("msg_A", s.messages.single().id)
        assertEquals("Premier", s.messages.single().text)

        // --- fin du tour : le dernier message clos, l'etat transitoire vide ---
        s = EventReducer.reduce(s, ev("session.execution.succeeded"))
        assertNull(s.streamingText)
        assertEquals(2, s.messages.size, "les deux messages du tour sont dans l'historique")
        assertEquals(listOf("msg_A", "msg_B"), s.messages.map { it.id })
        assertEquals(listOf("Premier", "Second"), s.messages.map { it.text })
    }

    @Test
    fun `un message sans contenu ne cree pas de bulle vide`() {
        var s = SessionUiState(sessionID = "ses_1")
        // ⚠️ Une etape qui n'a rien produit (pas de texte, pas de raisonnement, pas d'outil) ne
        // doit pas laisser de bulle : sinon un tour de 10 etapes semait 10 blocs vides.
        s = EventReducer.reduce(s, ev("session.step.started", """{"assistantMessageID":"msg_A"}"""))
        s = EventReducer.reduce(s, ev("session.step.started", """{"assistantMessageID":"msg_B"}"""))
        assertTrue(s.messages.isEmpty(), "aucune bulle vide, obtenu ${s.messages.map { it.id }}")
    }

    @Test
    fun `un evenement rejoue du meme message ne cloture pas deux fois`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.text.delta", """{"assistantMessageID":"msg_A","delta":"Salut"}"""))
        // ⚠️ Une reconnexion SSE peut **rejouer** un evenement du meme message : il ne doit ni
        // re-empiler un doublon, ni couper le message en deux.
        s = EventReducer.reduce(s, ev("session.step.started", """{"assistantMessageID":"msg_A"}"""))
        s = EventReducer.reduce(s, ev("session.text.delta", """{"assistantMessageID":"msg_A","delta":" toi"}"""))
        assertEquals("Salut toi", s.streamingText)
        assertTrue(s.messages.isEmpty(), "un rejeu du meme id ne cloture rien")

        // ⚠️ Et un evenement **sans** `assistantMessageID` ne doit pas non plus couper le message :
        // sinon `session.tool.progress` (qui n'en porte pas toujours) fragmenterait le texte.
        s = EventReducer.reduce(s, ev("session.tool.progress", """{"id":"call_1"}"""))
        assertEquals("Salut toi", s.streamingText)
    }

    @Test
    fun `session tool called remplit le resume depuis l'objet input`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.tool.input.started", """{"sessionID":"ses_1","id":"call_8p","name":"shell"}"""))
        s = EventReducer.reduce(
            s,
            ev("session.tool.called", """{"id":"call_8p","input":{"command":"uname -a; echo \"---\"; hostname"},"executed":false}"""),
        )
        // Le resume EST ce que l'outil a recu : sans lui, « shell ok » ne dit rien.
        assertEquals("uname -a; echo \"---\"; hostname", s.streamingTools.single().summary)
    }

    @Test
    fun `session tool input ended remplit le resume depuis la chaine JSON`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.tool.input.started", """{"id":"call_8p","name":"shell"}"""))
        // ⚠️ Forme reelle : l'entree arrive en **chaine JSON** sous `text`, PAS en objet.
        s = EventReducer.reduce(s, ev("session.tool.input.ended", """{"id":"call_8p","text":"{\"command\":\"ls -la /tmp\"}"}"""))
        assertEquals("ls -la /tmp", s.streamingTools.single().summary)
    }

    @Test
    fun `session tool success remplit la sortie et ne perd pas le resume`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.tool.called", """{"id":"call_8p","name":"shell","input":{"command":"wc -l f"}}"""))
        s = EventReducer.reduce(
            s,
            ev(
                "session.tool.success",
                """{"id":"call_8p","content":[{"type":"text","text":"42 f"},{"type":"text","text":"ok"}],"metadata":{"status":"completed","exit":0}}""",
            ),
        )
        val tool = s.streamingTools.single()
        // La sortie du depliage doit montrer le TEXTE rendu, pas le JSON du flux.
        assertEquals("42 f\nok", tool.output)
        // ⚠️ Et le resume SURVIT : `success` ne porte pas l'entree, l'ecraser viderait la carte.
        assertEquals("wc -l f", tool.summary)
        assertEquals(ToolStatus.Succeeded, tool.status)
    }

    @Test
    fun `un resume de chemin garde la fin du chemin, seule partie qui identifie le fichier`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(
            s,
            ev("session.tool.called", """{"id":"call_r","name":"read","input":{"path":"/home/utilisateur/.claude/projects/-home-utilisateur/memory/user_sebastien.md"}}"""),
        )
        // Tronque par le debut : trois fichiers differents s'affichaient « /home/sk7n4k… ».
        assertEquals("…/memory/user_sebastien.md", s.streamingTools.single().summary)
    }

    @Test
    fun `un statut terminal ne se degrade pas par un evenement tardif`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.tool.input.started", """{"toolCallID":"call_1","name":"bash"}"""))
        s = EventReducer.reduce(s, ev("session.tool.failed", """{"toolCallID":"call_1"}"""))
        s = EventReducer.reduce(s, ev("session.tool.progress", """{"toolCallID":"call_1","delta":"tard"}"""))
        assertEquals(ToolStatus.Failed, s.streamingTools.single().status)
    }

    // ---------------------------------------------------------------------
    // Attention (permisssions / formulaires) — formes jamais capturees
    // ---------------------------------------------------------------------

    @Test
    fun `permission asked retient la demande puis replied la retire`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(
            s,
            ev("permission.asked", """{"id":"per_1","sessionID":"ses_1","action":"shell","resources":["cmd"]}"""),
        )
        assertEquals("per_1", s.pendingPermission!!.id)

        s = EventReducer.reduce(s, ev("permission.replied", """{"requestID":"per_1"}"""))
        assertNull(s.pendingPermission)
    }

    @Test
    fun `permission asked de forme inconnue laisse l'etat inchange`() {
        val before = SessionUiState(sessionID = "ses_1")
        val after = EventReducer.reduce(before, ev("permission.asked", """{"forme":"jamais_vue"}"""))
        assertEquals(before, after)
    }

    @Test
    fun `form created conserve la charge brute puis replied la retire`() {
        // ⚠️ **Charge reelle** (capture du 2026-09-26) : elle est imbriquee sous `form`.
        // Le test precedent utilisait une forme PLATE inventee (`{"id":"form_1",...}`) — donc il
        // validait le bug au lieu de le detecter : `raw` gardait ce qu'on lui donnait, et personne
        // ne verifiait que l'ID etait decodable.
        val payload =
            """{"form":{"id":"frm_1","sessionID":"ses_1","title":"T","fields":[{"key":"x","type":"string"}]}}"""
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("form.created", payload))
        val form = s.pendingForm!!
        assertEquals("frm_1", form.id, "l'id doit etre DECODE, pas seulement conserve en brut")
        assertTrue(form.raw.containsKey("fields"), "la charge brute est conservee en entier")

        s = EventReducer.reduce(s, ev("form.replied", "{}"))
        assertNull(s.pendingForm)
    }

    @Test
    fun `form cancelled retire la demande`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("form.created", """{"id":"form_1"}"""))
        s = EventReducer.reduce(s, ev("form.cancelled", "{}"))
        assertNull(s.pendingForm)
    }

    // ---------------------------------------------------------------------
    // Robustesse et cloisonnement
    // ---------------------------------------------------------------------

    @Test
    fun `des champs absents ne font jamais lever sur les types sans effet`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.text.started"))
        s = EventReducer.reduce(s, ev("session.text.delta"))
        s = EventReducer.reduce(s, ev("session.text.ended"))
        s = EventReducer.reduce(s, ev("session.reasoning.delta"))
        s = EventReducer.reduce(s, ev("session.reasoning.ended"))
        s = EventReducer.reduce(s, ev("session.usage.updated"))
        s = EventReducer.reduce(s, ev("session.instructions.updated"))
        s = EventReducer.reduce(s, ev("session.step.ended"))
        s = EventReducer.reduce(s, ev("session.step.streamed"))
        s = EventReducer.reduce(s, ev("session.inbox.enqueued"))
        s = EventReducer.reduce(s, ev("session.tool.success"))
        s = EventReducer.reduce(s, ev("permission.asked"))
        s = EventReducer.reduce(s, ev("permission.replied"))
        s = EventReducer.reduce(s, ev("form.replied"))
        assertEquals(SessionUiState(sessionID = "ses_1"), s)
    }

    @Test
    fun `des champs absents ne font jamais lever sur les types a effet`() {
        var s = SessionUiState(sessionID = "ses_1")
        s = EventReducer.reduce(s, ev("session.step.started"))
        assertEquals(SessionStatus.Running, s.status)        // le seul effet attendu
        s = EventReducer.reduce(s, ev("session.message.content.updated"))
        assertEquals(1, s.messages.size)                     // repli brut, jamais d'exception
        // ⚠️ Un `form.created` **sans** objet `form` (charge vide, ou forme inattendue) ne
        // cree **rien** : un formulaire sans identifiant s'afficherait sans pouvoir etre rempli,
        // donc on ne fabrique pas de donnee. Le test attendait l'inverse sur une forme inventee.
        s = EventReducer.reduce(s, ev("form.created"))
        assertNull(s.pendingForm, "pas de formulaire fantome sans identifiant")
    }

    @Test
    fun `un evenement d'une autre session est ignore`() {
        val before = SessionUiState(sessionID = "ses_1")
        val after = EventReducer.reduce(before, ev("session.text.delta", """{"sessionID":"ses_2","delta":"x"}"""))
        assertEquals(before, after)
    }

    @Test
    fun `server connected et session created laissent l'etat inchange`() {
        val before = SessionUiState(sessionID = "ses_1")
        assertEquals(before, EventReducer.reduce(before, ev("server.connected")))
        assertEquals(before, EventReducer.reduce(before, ev("session.created", """{"sessionID":"ses_1","title":"t"}""")))
    }

    @Test
    fun `usage recorded met a jour comme updated`() {
        val s = EventReducer.reduce(
            SessionUiState(sessionID = "ses_1"),
            ev("session.usage.recorded", """{"cost":1.5,"tokens":{"input":4}}"""),
        )
        assertEquals(1.5, s.cost!!, 1e-9)
        assertEquals(4L, s.tokens!!.input)
    }

    // ---------------------------------------------------------------------
    // Integration : les 18 types mesures, joues dans l'ordre du tour reel
    // ---------------------------------------------------------------------

    @Test
    fun `le tour reel produit l'etat final attendu`() {
        val raw = javaClass.getResourceAsStream("/event-stream-real.txt")!!.readBytes().decodeToString()
        val json = OpenCodeClient.json

        var s = SessionUiState(sessionID = "ses_f2b1f8344ffe7N0fGcpz3sNX0q")
        for (frame in SseParser().feed(raw)) {
            s = EventReducer.reduce(s, json.decodeFromString<OcEvent>(frame.data))
        }

        assertEquals(SessionStatus.Succeeded, s.status)
        assertEquals(0.0026658, s.cost!!, 1e-12)
        assertEquals(17644L, s.tokens!!.input)
        assertEquals(32L, s.tokens!!.output)
        assertNull(s.streamingText)
        assertNull(s.streamingReasoning)
        assertTrue(s.streamingTools.isEmpty())

        assertEquals(2, s.messages.size)
        assertEquals(Role.User, s.messages[0].role)
        assertEquals("dis exactement PONG et rien dautre", s.messages[0].text)
        assertEquals(Role.Assistant, s.messages[1].role)
        assertEquals("msg_0d4e07d15001w52zKPV2YqIp3m", s.messages[1].id)
        assertEquals("PONG", s.messages[1].text)
        assertEquals(
            "The user wants me to say exactly \"PONG\" and nothing else. " +
                "This is a trivial request. No skill needed. Just answer.",
            s.messages[1].reasoning,
        )
        assertEquals(6, s.instructions.size)
        assertEquals(
            "f6093d8742b0acdfde24824ea36e726dbf4dcc8748bfdea3b490cea56c0d8e55",
            s.instructions["core/environment"],
        )
    }

    private fun ev(type: String, data: String = "{}") =
        OcEvent(type = type, data = Json.parseToJsonElement(data).jsonObject)
}
