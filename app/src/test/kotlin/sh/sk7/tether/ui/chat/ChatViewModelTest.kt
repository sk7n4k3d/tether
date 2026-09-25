package sh.sk7.tether.ui.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import java.io.File
import java.util.UUID
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
import sh.sk7.tether.data.event.OcEvent
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
    ) : OpenCodeGateway {
        var prompts = 0
        var interrupts = 0
        var messagesCalls = 0
        var lastText: String? = null

        /** Si non nul, `prompt` attend cette barriere : rend l'etat `Sending` observable. */
        @Volatile
        var promptGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun info(settings: ConnectionSettings): ServerInfo = ServerInfo(version = "2.0.x")

        override suspend fun sessions(settings: ConnectionSettings): List<Session> = emptyList()

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
            return PromptAcceptance(id = "msg_user_1", sessionID = sessionID, type = "user")
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

        override suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean {
            interrupts++
            return true
        }
    }

    private fun viewModel(
        gateway: FakeGateway,
        source: FakeEventSource,
        graceMillis: Long = 250,
    ): ChatViewModel {
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val factory = EventStreamFactory { source }
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
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.promptGate = gate
        val vm = viewModel(gateway, FakeEventSource(), graceMillis = 10_000)

        vm.send("bonjour")

        assertEquals(UiPhase.Sending, vm.state.value.phase)
        gate.complete(Unit)
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
        val vm = viewModel(FakeGateway(), source)

        vm.send("bonjour")
        settle()
        // Le serveur renvoie le meme message utilisateur avec son vrai id d'inbox.
        runBlocking {
            source.events.emit(
                ev(
                    "session.inbox.enqueued",
                    "\"inboxID\":\"msg_inbox_1\",\"item\":{\"type\":\"user\",\"payload\":{\"text\":\"bonjour\"}}",
                ),
            )
        }

        val settled = awaitValue(vm.state) { st -> st.chat.messages.any { it.id == "msg_inbox_1" } }
        val bonjours = settled.chat.messages.filter { it.role == Role.User && it.text == "bonjour" }
        assertEquals(1, bonjours.size, "le message utilisateur ne doit apparaitre qu'une fois")
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
}
