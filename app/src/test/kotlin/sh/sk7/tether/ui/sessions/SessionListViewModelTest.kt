package sh.sk7.tether.ui.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import sh.sk7.tether.data.api.Agent
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
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SessionListViewModelTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    private fun realStore(settings: ConnectionSettings): ConnectionStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-datastore-test").apply { mkdirs() }
        val file = File(dir, "settings-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        runBlocking { store.save(settings) }
        return store
    }

    private class FakeGateway(
        private val sessions: List<Session> = listOf(
            Session(id = "ses_existing", title = "Existante", time = TimeInfo(created = 1_000)),
        ),
        private val models: List<Model> = listOf(
            Model(id = "deepseek-v4.1-flash", modelID = "deepseek-v4.1-flash", providerID = "ollama-cloud"),
        ),
        private val agents: List<Agent> = listOf(Agent(id = "general", name = "General")),
        private val failure: Throwable? = null,
        private val created: Session = Session(id = "ses_new", title = "Nouvelle"),
    ) : OpenCodeGateway {
        var sessionsCalls = 0
        var lastCreateAgent: String? = null
        var lastCreateTitle: String? = null

        override suspend fun info(settings: ConnectionSettings): ServerInfo {
            failure?.let { throw it }
            return ServerInfo(version = "2.0.x")
        }

        override suspend fun models(settings: ConnectionSettings): List<Model> {
            failure?.let { throw it }
            return models
        }

        override suspend fun agents(settings: ConnectionSettings): List<Agent> {
            failure?.let { throw it }
            return agents
        }

        override suspend fun createSession(
            settings: ConnectionSettings,
            title: String,
            model: ModelRef,
            agent: String?,
        ): Session {
            lastCreateTitle = title
            lastCreateAgent = agent
            failure?.let { throw it }
            return created
        }

        override suspend fun sessionsPage(
            settings: ConnectionSettings,
            limit: Int?,
            cursor: String?,
        ): CursorPage<Session> {
            sessionsCalls++
            failure?.let { throw it }
            return CursorPage(sessions)
        }

        override suspend fun session(settings: ConnectionSettings, sessionID: String): Session {
            failure?.let { throw it }
            return sessions.firstOrNull { it.id == sessionID } ?: Session(id = sessionID)
        }

        override suspend fun prompt(
            settings: ConnectionSettings,
            sessionID: String,
            text: String,
        ): PromptAcceptance {
            failure?.let { throw it }
            return PromptAcceptance(id = "msg_1", sessionID = sessionID, type = "user")
        }

        override suspend fun messagesPage(
            settings: ConnectionSettings,
            sessionID: String,
            limit: Int?,
            cursor: String?,
            order: String?,
        ): CursorPage<MessageDto> {
            failure?.let { throw it }
            return CursorPage(emptyList())
        }

        override suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean {
            failure?.let { throw it }
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
        settings: ConnectionSettings = ConnectionSettings(password = "x", directory = "/home/utilisateur"),
    ) = SessionListViewModel(realStore(settings), gateway, Dispatchers.Unconfined)

    /**
     * Attend que l'etat quitte [SessionListUiState.Loading] ou que le dialogue de creation
     * se referme. `refresh()` etant lance dans `init`, il faut laisser le DataStore et le
     * faux gateway s'executer : on observe le StateFlow jusqu'a stabilisation, avec un
     * plafond qui **echoue** le test plutot que de le laisser passer silencieusement.
     */
    private fun <T> awaitValue(flow: kotlinx.coroutines.flow.StateFlow<T>, predicate: (T) -> Boolean): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val value = flow.value
            if (predicate(value)) return value
            Thread.sleep(5)
        }
        throw AssertionError("etat non stabilise, dernier = ${flow.value}")
    }

    private fun awaitCondition(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition non atteinte : $description")
    }

    private suspend fun loaded(vm: SessionListViewModel): SessionListUiState.Loaded =
        awaitValue(vm.state) { it !is SessionListUiState.Loading } as? SessionListUiState.Loaded
            ?: throw AssertionError("attendu Loaded, recu ${vm.state.value}")

    private fun settled(vm: SessionListViewModel): SessionListUiState =
        awaitValue(vm.state) { it !is SessionListUiState.Loading }

    @Test
    fun `refresh expose la liste chargee et resout modeles et agents`() = runBlocking<Unit> {
        val session = Session(
            id = "ses_1",
            title = "Une session",
            agent = "general",
            time = TimeInfo(created = 1_000, updated = 2_000),
        )
        val vm = viewModel(FakeGateway(sessions = listOf(session)))

        val state = loaded(vm)
        assertEquals(1, state.items.size)
        assertEquals("ses_1", state.items.first().id)
        assertEquals(1, state.models.size)
        assertEquals(1, state.agents.size)
    }

    @Test
    fun `refresh sur une liste vide expose Empty`() = runBlocking<Unit> {
        val vm = viewModel(FakeGateway(sessions = emptyList()))
        assertIs<SessionListUiState.Empty>(settled(vm))
    }

    @Test
    fun `refresh mappe l echec en message sans mot de passe`() = runBlocking<Unit> {
        val vm = viewModel(
            FakeGateway(failure = java.net.ConnectException("connexion refusee")),
            settings = ConnectionSettings(password = "s3cret", directory = "/home/utilisateur"),
        )

        val state = settled(vm)
        assertIs<SessionListUiState.Error>(state)
        assertFalse(state.message.contains("s3cret"), "le mot de passe ne doit pas fuir")
    }

    @Test
    fun `sans mot de passe aucun appel reseau n est fait`() = runBlocking<Unit> {
        val gateway = FakeGateway(sessions = emptyList())
        val vm = viewModel(gateway, settings = ConnectionSettings(password = "", directory = "/x"))

        assertIs<SessionListUiState.NeedsSetup>(settled(vm))
        assertEquals(0, gateway.sessionsCalls)
    }

    @Test
    fun `createSession transmet le modele obligatoire et l agent choisi`() = runBlocking<Unit> {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        loaded(vm)
        vm.onCreateTitleChange("Ma nouvelle session")
        vm.onCreateModelChange(ModelRef("deepseek-v4.1-flash", "ollama-cloud"))
        vm.onCreateAgentChange("general")

        vm.createSession()

        awaitCondition("liste rechargee") { gateway.sessionsCalls >= 2 }
        awaitValue(vm.create) { !it.creating }
        assertEquals("Ma nouvelle session", gateway.lastCreateTitle)
        assertEquals("general", gateway.lastCreateAgent)
        assertEquals(2, gateway.sessionsCalls, "la liste est rechargee apres creation")
    }

    @Test
    fun `createSession refuse un modele absent et n appelle pas le serveur`() = runBlocking<Unit> {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        loaded(vm)
        vm.onCreateTitleChange("Sans modele")

        vm.createSession()

        assertEquals("Un modèle est obligatoire.", vm.create.value.error)
        assertEquals(1, gateway.sessionsCalls, "aucune creation ni recharge")
        assertEquals(null, gateway.lastCreateTitle)
    }

    @Test
    fun `le modele par defaut est celui de la session la plus recente`() = runBlocking<Unit> {
        val gateway = FakeGateway(
            sessions = listOf(
                Session(
                    id = "ses_recent",
                    title = "Récente",
                    // ⚠️ `variant` est present sur une session reelle mais PAS dans le catalogue.
                    model = ModelRef("mimo-v2.6-flash-free", "opencode", variant = "default"),
                    time = TimeInfo(created = 2_000),
                ),
            ),
            models = listOf(
                Model(id = "space-bunny-free", modelID = "space-bunny-free", providerID = "opencode"),
                Model(id = "mimo-v2.6-flash-free", modelID = "mimo-v2.6-flash-free", providerID = "opencode"),
            ),
        )

        val state = loaded(vm = viewModel(gateway))

        assertEquals(ModelRef("mimo-v2.6-flash-free", "opencode"), state.createModel)
    }

    @Test
    fun `un modele de session absent du catalogue retombe sur le premier modele`() = runBlocking<Unit> {
        val gateway = FakeGateway(
            sessions = listOf(
                Session(
                    id = "ses_old",
                    title = "Ancienne",
                    model = ModelRef(id = "modele-supprime", providerID = "ollama-cloud"),
                    time = TimeInfo(created = 2_000),
                ),
            ),
            models = listOf(
                Model(id = "deepseek-v4.1-flash", modelID = "deepseek-v4.1-flash", providerID = "ollama-cloud"),
            ),
        )

        val state = loaded(vm = viewModel(gateway))

        assertEquals(ModelRef("deepseek-v4.1-flash", "ollama-cloud"), state.createModel)
    }
}
