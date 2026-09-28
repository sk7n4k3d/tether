package sh.sk7.tether.ui.sessions

import sh.sk7.tether.testing.NeutralGateway

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import sh.sk7.tether.data.api.HistoryPage
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
import sh.sk7.tether.data.api.SessionParent
import sh.sk7.tether.data.api.TimeInfo
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.FormFieldDto
import sh.sk7.tether.data.api.FormInfoDto
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
import sh.sk7.tether.data.activity.ActivityMonitor
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.PinnedSessions
import sh.sk7.tether.data.settings.SessionDefaultsStore
import sh.sk7.tether.data.settings.ConnectionStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first

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
        /** Echec **de la creation seule** : la liste doit rester chargee. */
        private val createFailure: Throwable? = null,
        private val created: Session = Session(id = "ses_new", title = "Nouvelle"),
        private val pendingFormList: List<FormInfoDto> = emptyList(),
    ) : NeutralGateway() {
        var sessionsCalls = 0
        var createCalls = 0
        var modelsCalls = 0
        var agentsCalls = 0

        /** Echec **des formulaires seulement** : la liste doit rester chargee et le compteur a 0. */
        var failForms: Boolean = false

        /**
         * Le compteur de formulaires du point d'entree doit lire **le serveur**, jamais une valeur
         * locale : c'est `pendingForms` qui fait foi, surcharge ici pour le test qui l'exerce.
         */
        override suspend fun pendingForms(settings: ConnectionSettings): List<FormInfoDto> {
            failure?.let { throw it }
            if (failForms) throw java.net.ConnectException("injoignable")
            return pendingFormList
        }

        override suspend fun info(settings: ConnectionSettings): ServerInfo {
            failure?.let { throw it }
            return ServerInfo(version = "2.0.x")
        }

        override suspend fun models(settings: ConnectionSettings): List<Model> {
            modelsCalls++
            failure?.let { throw it }
            return models
        }

        override suspend fun agents(settings: ConnectionSettings): List<Agent> {
            agentsCalls++
            failure?.let { throw it }
            return agents
        }

        override suspend fun createSession(
            settings: ConnectionSettings,
            model: ModelRef?,
            agent: String?,
        ): Session {
            createCalls++
            lastCreateModel = model
            lastCreateAgent = agent
            createFailure?.let { throw it }
            return created
        }

        override suspend fun sessionsPage(
            settings: ConnectionSettings,
            limit: Int?,
            cursor: String?,
            parent: SessionParent?,
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

        /**
         * Les fakes renvoient la MEME page que [messagesPage] : les tests ne portent pas sur la
         * pagination, et simuler une vraie fenetre ici ne testerait que le fake lui-meme.
         */
        override suspend fun messagesPageBack(
            settings: ConnectionSettings,
            sessionID: String,
            limit: Int,
            cursor: String?,
        ): HistoryPage {
            val page = messagesPage(settings, sessionID, limit, null, null)
            return HistoryPage(messages = page.data, cursorBack = null)
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
        settings: ConnectionSettings = ConnectionSettings(password = "x", directory = "/home/user"),
    ): SessionListViewModel {
        lastDefaults = SessionDefaultsStore(newDataStore("defaults"))
        return SessionListViewModel(
        realStore(settings),
        gateway,
        // Un vrai DataStore sur un fichier temporaire : l'epinglage ne fait pas partie de ce que
        // ces tests exercent, mais le ViewModel en depend desormais pour son tri.
        PinnedSessions(testDataStore()),
        // Le monitor partage : les tests n'exercent pas l'etat de connexion, mais le ViewModel
        // en depend pour le signaler sur du vecu.
        ConnectionMonitor(realStore(settings)),
        // ⚠️ Le détenteur d'état vivant : ces tests n'exercent pas l'activité, mais le ViewModel
        // s'y abonne pour la liste. Un `ActivityMonitor` réel suffit — il ne fait rien tant que
        // son cycle n'est pas déclenché, et les tests n'attendent pas de lui.
        ActivityMonitor(
            store = realStore(settings),
            gateway = gateway,
            connection = ConnectionMonitor(realStore(settings)),
            // ⚠️ Portee **annulee** : la boucle du monitor ne doit jamais tourner pendant un test,
            // sinon ses cycles (delay de 12 s) pollueraient les compteurs d'appels sur des tests
            // qui durent plus longtemps que l'intervalle.
            appScope = CoroutineScope(Dispatchers.Unconfined).also { it.cancel() },
            dispatcher = Dispatchers.Unconfined,
        ),
        lastDefaults,
        Dispatchers.Unconfined,
        )
    }

    /** Memorise un choix, comme le ferait un changement de modele dans une conversation. */
    private suspend fun recordDefaults(model: ModelRef?, agent: String?) =
        lastDefaults.record(model = model, agent = agent)

    /**
     * Store du dernier choix, cree **par le test** et passe au ViewModel.
     *
     * ⚠️ Le test doit pouvoir y ecrire : c'est ainsi qu'on simule « l'utilisateur a change de
     * modele dans une conversation ». Passer par un membre prive du ViewModel serait un test
     * lie a l'implementation plutot qu'au comportement.
     */
    private lateinit var lastDefaults: SessionDefaultsStore

    /** DataStore jetable, isole par test. */
    private fun testDataStore(): DataStore<Preferences> = newDataStore("pins")

    private fun newDataStore(tag: String): DataStore<Preferences> {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-sessions-$tag").apply { mkdirs() }
        val file = File(dir, "store-${UUID.randomUUID()}.preferences_pb")
        files += file
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

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
    fun `refresh expose la liste chargee et ne charge ni modeles ni agents`() = runBlocking<Unit> {
        val session = Session(
            id = "ses_1",
            title = "Une session",
            agent = "general",
            time = TimeInfo(created = 1_000, updated = 2_000),
        )
        val gateway = FakeGateway(sessions = listOf(session))
        val vm = viewModel(gateway)

        val state = loaded(vm)
        assertEquals(1, state.items.size)
        assertEquals("ses_1", state.items.first().id)
        // ⚠️ **Ni `models` ni `agents`.** Le seul consommateur etait la dialogue de creation,
        // supprimee : on payait deux appels reseau a chaque rafraichissement pour rien. Le
        // catalogue se charge dans la conversation, une fois, ou il sert.
        assertEquals(0, gateway.modelsCalls, "la liste ne doit plus charger le catalogue")
        assertEquals(0, gateway.agentsCalls, "la liste ne doit plus charger les agents")
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
            settings = ConnectionSettings(password = "s3cret", directory = "/home/user"),
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

    /**
     * Un seul appel cree la session, **sans dialogue et sans titre**.
     *
     * ⚠️ Ce test remplace `createSession transmettait le modele obligatoire et l'agent choisi`.
     * Il encode le comportement mesure : `POST /api/session` n'exige aucun champ, et **le serveur
     * ne reecrit pas un titre qu'on lui donne** (mesure du 2026-09-26 : `{"title":"Nouvelle
     * session"}` est conserve tel quel, donc l'envoyer empechait opencode d'en nommer une).
     */
    @Test
    fun `newSession cree en un geste et n envoie aucun titre`() = runBlocking<Unit> {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        loaded(vm)

        vm.newSession()

        awaitCondition("session creee") { gateway.createCalls >= 1 }
        awaitCondition("liste rechargee") { gateway.sessionsCalls >= 2 }
        assertEquals(1, gateway.createCalls, "une seule creation")
        assertEquals(2, gateway.sessionsCalls, "la liste est rechargee apres creation")
    }

    /**
     * Le **dernier choix** est renvoye, et rien d'autre n'est invente.
     *
     * ⚠️ C'est tout l'objet du changement : le defaut du serveur est `general` +
     * `deepseek-v4.1-flash` (mesure : seul `general` est en `mode: "all"` et il porte ce modele),
     * donc « ne rien envoyer » atterrirait sur un modele que l'utilisateur change a chaque session.
     */
    @Test
    fun `newSession renvoie le dernier choix memorise`() = runBlocking<Unit> {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        loaded(vm)
        recordDefaults(ModelRef("space-bunny-free", "opencode"), "build")

        vm.newSession()

        awaitCondition("session creee") { gateway.createCalls >= 1 }
        assertEquals(ModelRef("space-bunny-free", "opencode"), gateway.lastCreateModel)
        assertEquals("build", gateway.lastCreateAgent)
    }

    /**
     * Aucun choix memorise => on ne suppose **rien**.
     *
     * ⚠️ On ne remplit pas avec le `defaultModel(sessions)` de l'ancien dialogue : ce modele
     * venait du catalogue, et une session fraichement creee a `model: null` cote serveur
     * (mesure). Renvoyer un choix qu'on n'a pas fait serait afficher une valeur inventee.
     */
    @Test
    fun `sans choix memorise la creation n invente ni modele ni agent`() = runBlocking<Unit> {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        loaded(vm)

        vm.newSession()

        awaitCondition("session creee") { gateway.createCalls >= 1 }
        assertTrue(gateway.lastCreateModel == null, "aucun modele invente : ${gateway.lastCreateModel}")
        assertTrue(gateway.lastCreateAgent == null, "aucun agent invente : ${gateway.lastCreateAgent}")
    }

    /**
     * Un echec de creation **remonte une erreur et n'ouvre rien**.
     *
     * ⚠️ Ce test ne depend pas de l'etat de la liste : son sujet est l'echec de la creation, et
     * `loaded()` le ferait dependre d'un chargement qui n'est pas ce qu'on veut mesurer ici. La
     * liste qui s'affiche normalement est deja couverte par les autres tests de cette classe.
     */
    @Test
    fun `un echec de creation remonte l erreur sans naviguer`() = runBlocking<Unit> {
        val gateway = FakeGateway(createFailure = IllegalStateException("creation refusee"))
        val vm = viewModel(gateway)
        settled(vm)

        vm.newSession()

        awaitCondition("erreur remontee") { vm.sessionError.value != null }
        assertEquals(1, gateway.createCalls, "la creation a bien ete tentee, une seule fois")
        // ⚠️ `withTimeoutOrNull` et non `first()` : sans evenement, `first()` suspendrait jusqu'au
        // plafond du test. Ici on **prouve** l'absence d'evenement, en quelques centaines de ms.
        // ⚠️ On passe par un booleen explicite : `kotlin.test` expose deux surcharges
        // d'`assertNull` (valeur et bloc), et l'ambiguite rendait l'echec illisible.
        val opened = withTimeoutOrNull(300) { vm.openSession.first() }
        assertTrue(opened == null, "rien a ouvrir, mais on a recu : $opened")
    }

    @Test
    fun `refresh expose le nombre de formulaires pendants au point d entree`() = runBlocking<Unit> {
        val gateway = FakeGateway(
            pendingFormList = listOf(
                FormInfoDto(id = "frm_1", sessionID = "ses_1", title = "A"),
                FormInfoDto(id = "frm_2", sessionID = "global", title = "B"),
            ),
        )
        val vm = viewModel(gateway)

        val state = loaded(vm)

        // ⚠️ La liste reste celle du fake par defaut : le formulaire ne cree pas de session (et
        // c'est le point) — il ne fait que compter. On verifie que `sessionID:"ses_1"` annonce
        // dans le formulaire ne se substitue a aucune session existante.
        assertEquals("ses_existing", state.items.first().id)
        awaitCondition("compteur de formulaires renseigne") { vm.pendingForms.value == 2 }
    }

    @Test
    fun `un echec de lecture des formulaires laisse le compteur a zero sans casser la liste`() =
        runBlocking<Unit> {
            val gateway = FakeGateway(
                pendingFormList = listOf(FormInfoDto(id = "frm_1", sessionID = "global")),
            ).apply { failForms = true }
            val vm = viewModel(gateway)

            loaded(vm)

            assertEquals(0, vm.pendingForms.value)
        }
}
