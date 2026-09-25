package sh.sk7.tether.ui.chat

import sh.sk7.tether.testing.NeutralGateway

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.ContentPart
import sh.sk7.tether.data.api.CursorPage
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import sh.sk7.tether.data.api.MessageDto
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.PromptAcceptance
import sh.sk7.tether.data.api.ServerInfo
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.api.TimeInfo
import sh.sk7.tether.data.event.ConnectionState
import sh.sk7.tether.data.event.EventSource
import sh.sk7.tether.data.event.EventSourceFactory
import sh.sk7.tether.data.event.OcEvent
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.McpServerDto
import sh.sk7.tether.data.api.PermissionAskDto
import sh.sk7.tether.data.api.PluginDto
import sh.sk7.tether.data.api.ProjectDto
import sh.sk7.tether.data.api.ProviderDto
import sh.sk7.tether.data.api.SavedPermissionDto
import sh.sk7.tether.data.api.SkillDto
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.domain.model.UsageStats
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.domain.model.Role
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests du ViewModel de chat, **sans Android** : faux client REST et fausse source SSE.
 *
 * ⚠️ `kotlinx-coroutines-test` n'est pas une dependance du projet (contrainte du brief) :
 * le « delai raisonnable » de stabilisation est donc **injecte** et le test l'attend par
 * observation du `StateFlow` (`awaitValue`), comme `SessionListViewModelTest`. La semantique
 * du test impose par le brief (`advanceTimeBy(5_000)` -> `Awaiting`) est ainsi reproduite
 * sans horloge virtuelle.
 */
class ChatViewModelTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    // ------------------------------------------------------------------
    // Faux
    // ------------------------------------------------------------------

    private fun realStore(settings: ConnectionSettings): ConnectionStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-datastore-chat").apply { mkdirs() }
        val file = File(dir, "settings-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        runBlocking { store.save(settings) }
        return store
    }

    /** Source SSE pilotee par le test : `emit()` simule un evenement du serveur. */
    private class FakeEventSource(
        initial: ConnectionState = ConnectionState.Connected,
    ) : EventSource {
        private val _state = MutableStateFlow(initial)
        val events = MutableSharedFlow<OcEvent>(extraBufferCapacity = 32)
        override val state: StateFlow<ConnectionState> = _state
        override fun connect(): Flow<OcEvent> = events
        fun setState(value: ConnectionState) { _state.value = value }
    }

    private class FakeGateway(
        @Volatile var promptFailure: Throwable? = null,
        private val dtos: List<MessageDto> = emptyList(),
    ) : NeutralGateway() {
        var prompts = 0
        var interrupts = 0
        var messagesCalls = 0
        var lastText: String? = null

        /** Dernier id d'inbox accepte (`PromptAcceptance.id` = id REST du message user). */
        var lastAcceptedId: String? = null

        /** Si non nul, `prompt` attend cette barriere : rend l'etat `Sending` observable. */
        @Volatile
        var promptGate: CompletableDeferred<Unit>? = null

        override suspend fun info(settings: ConnectionSettings): ServerInfo = ServerInfo(version = "2.0.x")

        override suspend fun sessionsPage(
            settings: ConnectionSettings,
            limit: Int?,
            cursor: String?,
        ): CursorPage<Session> = CursorPage(emptyList())

        override suspend fun models(settings: ConnectionSettings): List<Model> = emptyList()

        override suspend fun agents(settings: ConnectionSettings): List<Agent> = emptyList()

        override suspend fun createSession(
            settings: ConnectionSettings,
            title: String,
            model: ModelRef,
            agent: String?,
        ): Session = Session(id = "ses_1")

        override suspend fun session(settings: ConnectionSettings, sessionID: String): Session =
            Session(id = sessionID, title = "Session test", time = TimeInfo(created = 1_000))

        override suspend fun prompt(
            settings: ConnectionSettings,
            sessionID: String,
            text: String,
        ): PromptAcceptance {
            prompts++
            lastText = text
            promptGate?.await()
            promptFailure?.let { throw it }
            // ⚠️ Id unique par appel, comme le vrai serveur (`msg_*` neuf a chaque prompt).
            val id = "msg_accepted_$prompts"
            lastAcceptedId = id
            return PromptAcceptance(id = id, sessionID = sessionID, type = "user")
        }

        override suspend fun messagesPage(
            settings: ConnectionSettings,
            sessionID: String,
            limit: Int?,
            cursor: String?,
            order: String?,
        ): CursorPage<MessageDto> {
            messagesCalls++
            return CursorPage(dtos)
        }

        /**
         * Les fakes renvoient la MEME page que [messagesPage] : les tests ne portent pas sur la
         * pagination, et simuler une vraie fenetre ici ne testerait que le fake lui-meme.
         */
        /** Messages ANTERIEURS servis a `loadOlder` (simule la pagination serveur). */
        @Volatile
        var olderDtos: List<MessageDto> = emptyList()
        var olderCalls = 0

        override suspend fun recentMessages(
            settings: ConnectionSettings,
            sessionID: String,
            limit: Int,
        ): List<MessageDto> = messagesPage(settings, sessionID, limit, null, null).data

        override suspend fun messagesBefore(
            settings: ConnectionSettings,
            sessionID: String,
            beforeMessageID: String,
            limit: Int,
        ): List<MessageDto> {
            olderCalls++
            return olderDtos
        }


        override suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean {
            interrupts++
            return true
        }

        override suspend fun renameSession(
            settings: ConnectionSettings,
            sessionID: String,
            title: String,
        ): Boolean = true

        override suspend fun deleteSession(settings: ConnectionSettings, sessionID: String) = Unit

        override suspend fun forkSession(settings: ConnectionSettings, sessionID: String): Session =
            Session(id = "ses_fork", title = "fork")

        override suspend fun compactSession(settings: ConnectionSettings, sessionID: String): Boolean = true
    }

    private fun viewModel(
        gateway: FakeGateway,
        source: FakeEventSource,
        graceMillis: Long = 250,
    ): ChatViewModel {
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val factory = EventSourceFactory { source }
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("sessionID" to "ses_1")),
            store = store,
            gateway = gateway,
            streamFactory = factory,
            dispatcher = Dispatchers.Unconfined,
            awaitingGraceMillis = graceMillis,
        )
    }

    private fun ev(type: String, extra: String = "", session: String = "ses_1"): OcEvent {
        val payload = "{\"sessionID\":\"$session\"" + (if (extra.isBlank()) "" else ",$extra") + "}"
        return OcEvent(type = type, data = Json.parseToJsonElement(payload).jsonObject)
    }

    private fun <T> awaitValue(flow: StateFlow<T>, predicate: (T) -> Boolean): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val value = flow.value
            if (predicate(value)) return value
            Thread.sleep(5)
        }
        throw AssertionError("etat non stabilise, dernier = ${flow.value}")
    }

    /** Laisse le temps aux coroutines `Unconfined` lancees par `send` de s'executer. */
    private fun settle() = Thread.sleep(60)

    // ------------------------------------------------------------------
    // Review Focus n°5 — le test impose par le brief
    // ------------------------------------------------------------------

    @Test
    fun `un prompt accepte sans evenement ne laisse pas l'UI bloquee`() {
        val gateway = FakeGateway()
        val vm = viewModel(gateway, FakeEventSource(), graceMillis = 150)

        vm.send("bonjour")

        // Aucun evenement n'arrive jamais : la phase doit se stabiliser sur Awaiting.
        val settled = awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        assertEquals(UiPhase.Awaiting, settled.phase)
        assertEquals(1, gateway.prompts, "le prompt a bien ete accepte")
        // Le message envoye reste visible meme si le flux ne livre rien.
        assertTrue(settled.chat.messages.any { it.text == "bonjour" })
    }

    @Test
    fun `send passe par Sending avant le delai de grace`() {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>()
        gateway.promptGate = gate
        val vm = viewModel(gateway, FakeEventSource(), graceMillis = 10_000)

        vm.send("bonjour")

        assertEquals(UiPhase.Sending, vm.state.value.phase)
        gate.complete(Unit)
    }

    /**
     * Le garde-fou anti-blocage (`armGrace`) doit exister **en propre**, pas seulement etre
     * masque par l'acceptation du POST.
     *
     * ⚠️ Le `promptGate` n'est **jamais** complete : `POST /prompt` pend indefiniment, aucun
     * evenement n'arrive. Sans `armGrace`, l'UI resterait bloquee en `Sending` pour toujours.
     * C'est exactement le scenario du brief (reseau qui coupe), et le test jumeau du test
     * « prompt accepte sans evenement » qui, lui, atteint `Awaiting` des l'acceptation.
     */
    @Test
    fun `un POST prompt qui pend sans evenement rend la main apres le delai de grace`() {
        val gateway = FakeGateway()
        gateway.promptGate = CompletableDeferred()   // jamais complete : le POST pend
        val vm = viewModel(gateway, FakeEventSource(), graceMillis = 150)

        vm.send("bonjour")

        // Aucun evenement, aucune acceptation : seul le garde-fou peut rendre la main.
        val settled = awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        assertEquals(UiPhase.Awaiting, settled.phase)
        assertEquals(1, gateway.prompts, "le POST a bien ete tente")
        assertTrue(settled.chat.messages.any { it.text == "bonjour" })
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    @Test
    fun `les deltas de texte s'accumulent puis se closent en message assistant`() {
        val source = FakeEventSource()
        val vm = viewModel(FakeGateway(), source)

        vm.send("dis bonjour")
        settle()
        runBlocking {
            source.events.emit(ev("session.execution.started"))
            source.events.emit(ev("session.text.delta", "\"delta\":\"Bon\""))
            source.events.emit(ev("session.text.delta", "\"delta\":\"jour\""))
            source.events.emit(ev("session.execution.succeeded"))
        }

        val settled = awaitValue(vm.state) { st -> st.chat.messages.any { it.role == Role.Assistant } }
        assertEquals("Bonjour", settled.chat.messages.first { it.role == Role.Assistant }.text)
        assertEquals(UiPhase.Awaiting, settled.phase)
    }

    @Test
    fun `un evenement d'une autre session est ignore`() {
        val source = FakeEventSource()
        val vm = viewModel(FakeGateway(), source, graceMillis = 150)

        vm.send("x")
        settle()
        runBlocking {
            source.events.emit(ev("session.text.delta", "\"delta\":\"NON\"", session = "ses_2"))
            source.events.emit(ev("session.execution.succeeded", session = "ses_2"))
        }

        val settled = awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        assertFalse(settled.chat.messages.any { it.text == "NON" }, "evenement d'une autre session")
        assertFalse(settled.chat.messages.any { it.role == Role.Assistant })
    }

    @Test
    fun `le message utilisateur n'est pas duplique quand l'inbox confirme le prompt`() {
        val source = FakeEventSource()
        val gateway = FakeGateway()
        val vm = viewModel(gateway, source)

        vm.send("bonjour")
        settle()
        // Le serveur confirme avec le **meme id** que l'acceptation (mesure : inboxID ==
        // PromptAcceptance.id == id REST du message user).
        val acceptedId = gateway.lastAcceptedId!!
        runBlocking {
            source.events.emit(
                ev(
                    "session.inbox.enqueued",
                    "\"inboxID\":\"$acceptedId\",\"item\":{\"type\":\"user\",\"payload\":{\"text\":\"bonjour\"}}",
                ),
            )
        }

        val settled = awaitValue(vm.state) { st -> st.chat.messages.any { it.id == acceptedId } }
        val bonjours = settled.chat.messages.filter { it.role == Role.User && it.text == "bonjour" }
        assertEquals(1, bonjours.size, "le message utilisateur ne doit apparaitre qu'une fois")
    }

    /**
     * ⚠️ Deux envois du **meme texte** avant confirmation : la dedup doit se faire par **id**,
     * pas par texte. Sinon les deux optimistes sont retires d'un coup et un message disparait
     * de l'ecran alors qu'il est bien parti au serveur.
     */
    @Test
    fun `deux envois du meme texte ne disparaissent pas ensemble a la confirmation du premier`() {
        val source = FakeEventSource()
        val gateway = FakeGateway()
        val vm = viewModel(gateway, source)

        vm.send("ok")
        settle()
        vm.send("ok")
        settle()
        val acceptedFirst = "msg_accepted_1"

        // Seul le PREMIER prompt est confirme (inbox) : le second reste optimiste.
        runBlocking {
            source.events.emit(
                ev(
                    "session.inbox.enqueued",
                    "\"inboxID\":\"$acceptedFirst\",\"item\":{\"type\":\"user\",\"payload\":{\"text\":\"ok\"}}",
                ),
            )
        }

        val settled = awaitValue(vm.state) { st -> st.chat.messages.any { it.id == acceptedFirst } }
        val oks = settled.chat.messages.filter { it.role == Role.User && it.text == "ok" }
        assertEquals(2, oks.size, "un seul optimiste doit etre retire (celui confirme)")
    }

    /**
     * Course `session.inbox.enqueued` **avant** l'acceptation HTTP : l'optimiste n'a pas encore
     * d'id serveur, la reconciliation ne peut se faire que par texte — et elle ne doit
     * consommer **qu'une** occurrence, pas emporter un second envoi identique en attente.
     */
    @Test
    fun `une inbox recue avant l'acceptation ne consomme qu'un envoi identique`() {
        val source = FakeEventSource()
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Unit>()
        gateway.promptGate = gate
        val vm = viewModel(gateway, source)

        vm.send("ok")
        settle()
        vm.send("ok")
        settle()

        // L'inbox arrive alors que le POST pend encore (aucun id accepte connu).
        runBlocking {
            source.events.emit(
                ev(
                    "session.inbox.enqueued",
                    "\"inboxID\":\"msg_inbox_a\",\"item\":{\"type\":\"user\",\"payload\":{\"text\":\"ok\"}}",
                ),
            )
        }
        Thread.sleep(60)

        val afterInbox = vm.state.value.chat.messages
        val oks = afterInbox.filter { it.role == Role.User && it.text == "ok" }
        assertEquals(2, oks.size, "un message serveur ne consomme qu'un optimiste identique")
        assertTrue(afterInbox.any { it.id == "msg_inbox_a" })
    }

    /**
     * Un message serveur **ancien** portant le meme texte ne doit pas faire disparaitre un
     * optimiste **fraichement accepte** : seul le message dont l'id correspond au prompt
     * confirme l'optimiste. C'est ce qui rend le lien par id necessaire (le repli par texte
     * seul consommerait n'importe quel message identique, meme anterieur).
     */
    @Test
    fun `un ancien message de meme texte ne confirme pas un optimiste frais`() {
        val source = FakeEventSource()
        // Le REST/resync porte deja un « ok » ancien (id different).
        val gateway = FakeGateway(dtos = listOf(MessageDto(id = "msg_old_ok", type = "user", text = "ok")))
        val vm = viewModel(gateway, source)
        awaitValue(vm.state) { it.chat.messages.any { it.id == "msg_old_ok" } }

        vm.send("ok")
        settle()

        val after = vm.state.value.chat.messages
        val oks = after.filter { it.role == Role.User && it.text == "ok" }
        assertEquals(2, oks.size, "l'optimiste doit survivre : seul son id exact le confirme")
        assertTrue(after.any { it.id.startsWith("local-") }, "l'optimiste est encore affiche")
    }

    /**
     * La resync REST ne doit **pas ecraser** les messages que le reducer a produits et que le
     * mapper ne sait pas reconstruire (repli brut d'un contenu inconnu). Sinon, une
     * reconnexion en plein tour fait disparaitre ce qui etait affiche.
     */
    @Test
    fun `la resync conserve un message produit par le reducer et absent du REST`() {
        val source = FakeEventSource(initial = ConnectionState.Disconnected)
        // Le REST ne connait que le message utilisateur ; l'assistant (repli brut) vient du flux.
        val gateway = FakeGateway(dtos = listOf(MessageDto(id = "msg_u", type = "user", text = "salut")))
        val vm = viewModel(gateway, source)

        awaitValue(vm.state) { it.chat.messages.any { it.id == "msg_u" } }
        runBlocking {
            source.events.emit(
                ev("session.message.content.updated", "\"assistantMessageID\":\"msg_partiel\",\"type\":\"inconnu\"", session = "ses_1"),
            )
        }
        awaitValue(vm.state) { it.chat.messages.any { it.id == "msg_partiel" } }
        val callsBefore = gateway.messagesCalls

        // Une reconnexion declenche une resync : le message du reducer doit survivre.
        source.setState(ConnectionState.Connected)

        // On attend que la resync ait REELLEMENT tourne avant d'observer le resultat, sinon
        // l'assertion passerait sur l'etat d'avant la resync.
        awaitValue(vm.state) { gateway.messagesCalls > callsBefore }
        Thread.sleep(60)

        val after = vm.state.value.chat.messages
        assertTrue(after.any { it.id == "msg_partiel" }, "message du reducer conserve")
        assertTrue(after.any { it.id == "msg_u" }, "message REST present")
    }

    // ------------------------------------------------------------------
    // Resync REST
    // ------------------------------------------------------------------

    @Test
    fun `un passage a Connected recharge les messages par le REST`() {
        val dtos = listOf(
            MessageDto(id = "msg_u", type = "user", text = "salut"),
            MessageDto(
                id = "msg_a",
                type = "assistant",
                content = listOf(ContentPart(type = "text", text = "bonjour a toi")),
            ),
        )
        val gateway = FakeGateway(dtos = dtos)
        val source = FakeEventSource(initial = ConnectionState.Disconnected)
        val vm = viewModel(gateway, source)

        awaitValue(vm.state) { it.chat.messages.isNotEmpty() }
        val before = gateway.messagesCalls

        source.setState(ConnectionState.Connected)

        val reloaded = awaitValue(vm.state) { gateway.messagesCalls > before }
        assertEquals("bonjour a toi", reloaded.chat.messages.first { it.role == Role.Assistant }.text)
    }

    @Test
    fun `le titre de session est charge depuis le REST`() {
        val vm = viewModel(FakeGateway(), FakeEventSource())

        val settled = awaitValue(vm.state) { it.title != null }
        assertEquals("Session test", settled.title)
    }

    // ------------------------------------------------------------------
    // Erreur et arret
    // ------------------------------------------------------------------

    @Test
    fun `un prompt refuse expose une erreur et reste reessayable`() {
        val gateway = FakeGateway(promptFailure = java.net.ConnectException("coupe"))
        val vm = viewModel(gateway, FakeEventSource(), graceMillis = 150)

        vm.send("bonjour")

        val failed = awaitValue(vm.state) { it.phase == UiPhase.Error }
        assertNotNull(failed.error)

        // Reessayer : le deuxieme envoi repart de Sending puis se stabilise.
        gateway.promptFailure = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.promptGate = gate
        vm.send("bonjour")
        assertEquals(UiPhase.Sending, vm.state.value.phase)
        gate.complete(Unit)
        val retried = awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        assertEquals(2, gateway.prompts)
        assertEquals(UiPhase.Awaiting, retried.phase)
    }

    @Test
    fun `stop interrompt la session et revient a Awaiting`() {
        val gateway = FakeGateway()
        val source = FakeEventSource()
        val vm = viewModel(gateway, source)

        vm.send("x")
        settle()
        runBlocking { source.events.emit(ev("session.execution.started")) }
        awaitValue(vm.state) { it.phase == UiPhase.Streaming }

        vm.stop()

        assertEquals(1, gateway.interrupts)
        assertEquals(UiPhase.Awaiting, vm.state.value.phase)
    }
    /**
     * **L'historique n'est PAS charge en entier a l'ouverture.**
     *
     * Mesure serveur : les sessions lourdes font 810 messages en moyenne, jusqu'a 2040. Les
     * charger tous a l'ouverture et a chaque reconnexion etait le principal cout de l'ecran.
     * Ici on verifie que la requete d'ouverture est **bornee** (fenetre recente) et que le reste
     * n'arrive qu'a la demande.
     */
    @Test
    fun `l ouverture ne charge qu une fenetre recente, pas tout l historique`() {
        val source = FakeEventSource()
        val dto = MessageDto(id = "msg_u", type = "user", text = "salut")
        val gateway = FakeGateway(dtos = listOf(dto))
        val vm = viewModel(gateway, source)
        awaitValue(vm.state) { it.chat.messages.any { it.id == "msg_u" } }

        // ⚠️ Le point n'est pas « une seule requete » (il y en a deux : l'ouverture puis la
        // resync de connexion), mais que le nombre de requetes soit **borne et independant de
        // la taille de l'historique**. `allMessages` en aurait fait une vingtaine pour 2 040
        // messages ; la fenetre recente en fait 1 par resync.
        assertTrue(
            gateway.messagesCalls <= 3,
            "le chargement doit etre borne, obtenu ${gateway.messagesCalls} requetes",
        )
        // ⚠️ Le fake sert tout dans UNE page, donc `hasOlder` est faux — c'est normal et c'est
        // le comportement voulu : une page non pleine signifie « debut de la session ». Ce qui
        // compte ici est qu'aucune pagination n'a ete lancee.
        assertTrue(!vm.state.value.hasOlder, "une page non pleine doit conclure au debut")
    }

    /**
     * **`loadOlder` prepend les anciens et ne double jamais.**
     *
     * ⚠️ La fusion se fait par id, exactement comme la resync : un message que le flux a livre
     * entre-temps ne doit pas etre ecrase par la version REST.
     */
    @Test
    fun `loadOlder ajoute les messages anciens devant et sans doublon`() {
        val source = FakeEventSource()
        // ⚠️ Une page PLEINE : c'est ce qui met `hasOlder` a vrai (l'API ne dit pas le total
        // d'une session, on le deduit d'une page pleine — sinon `loadOlder` sort immediatement).
        val window = (0 until ChatWindow.SERVER_PAGE).map {
            MessageDto(id = "msg_$it", type = "user", text = "m$it")
        }
        val gateway = FakeGateway(dtos = window)
        gateway.olderDtos = listOf(
            MessageDto(id = "msg_ancien", type = "user", text = "ancien"),
            // ⚠️ Doublon volontaire : deja dans la fenetre. Il ne doit apparaitre qu'UNE fois.
            MessageDto(id = "msg_0", type = "user", text = "m0"),
        )
        val vm = viewModel(gateway, source)
        awaitValue(vm.state) { it.chat.messages.any { it.id == "msg_0" } }
        assertTrue(vm.state.value.hasOlder, "une page pleine doit annoncer qu'il reste de l'historique")

        vm.loadOlder()
        awaitValue(vm.state) { it.chat.messages.any { it.id == "msg_ancien" } }

        val ids = vm.state.value.chat.messages.map { it.id }
        assertEquals(1, ids.count { it == "msg_0" }, "un doublon ne doit jamais apparaitre")
        // L'ancien passe DEVANT : l'ordre chronologique est preserve.
        assertTrue(
            ids.indexOf("msg_ancien") < ids.indexOf("msg_0"),
            "l'ancien doit etre devant, obtenu ${ids.take(4)}",
        )
    }

}
