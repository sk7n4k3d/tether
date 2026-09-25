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
import sh.sk7.tether.data.activity.ActivityMonitor
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import sh.sk7.tether.data.api.PromptAcceptance
import sh.sk7.tether.data.api.PromptBody
import sh.sk7.tether.data.api.ServerInfo
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.event.ConnectionState
import sh.sk7.tether.data.event.EventSource
import sh.sk7.tether.data.event.EventSourceFactory
import sh.sk7.tether.data.event.OcEvent
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.testing.NeutralGateway
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Le transport des pieces jointes par le ViewModel.**
 *
 * ### Pourquoi ces tests portent sur le ViewModel et pas sur le client
 * Le client sait deja fabriquer un `PromptBody` (teste dans `ServerCapabilitiesTest`). Ce qui ne se
 * voit **pas** a la compilation, c'est la **decision** : quand part-on par le prompt minimal, quand
 * part-on avec pieces jointes, et que devient la piece jointe apres un envoi reussi ou rate.
 *
 * ⚠️ Trois faits y sont figes, et deux sont des pieges :
 *  - un texte **vide** avec un fichier joint est un envoi legitime (« regarde ceci ») ;
 *  - les pieces jointes ne partent **pas** d'un envoi qui echoue (l'utilisateur ne doit pas les
 *    re-joindre apres une coupure reseau) ;
 *  - elles sont **vides** apres un envoi reussi (sinon on les enverrait deux fois).
 */
class ChatAttachmentsTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    private class FakeEventSource : EventSource {
        private val _state = MutableStateFlow(ConnectionState.Connected)
        override val state: StateFlow<ConnectionState> = _state
        override fun connect(): Flow<OcEvent> = MutableSharedFlow()
    }

    /**
     * Faux gateway qui **capture le corps envoye** et n'exerce que ce qui est utile ici.
     */
    private class FakeGateway(
        @Volatile var failure: Throwable? = null,
    ) : NeutralGateway() {
        var promptCalls = 0
        var lastBody: PromptBody? = null
        var lastText: String? = null

        override suspend fun info(settings: ConnectionSettings): ServerInfo = ServerInfo(version = "2")

        override suspend fun session(settings: ConnectionSettings, sessionID: String): Session =
            Session(id = sessionID, title = "t")

        override suspend fun prompt(
            settings: ConnectionSettings,
            sessionID: String,
            text: String,
        ): PromptAcceptance {
            promptCalls++
            lastText = text
            failure?.let { throw it }
            return PromptAcceptance(id = "msg_$promptCalls", sessionID = sessionID, type = "user")
        }

        override suspend fun prompt(
            settings: ConnectionSettings,
            sessionID: String,
            body: PromptBody,
        ): PromptAcceptance {
            promptCalls++
            lastBody = body
            failure?.let { throw it }
            return PromptAcceptance(id = "msg_$promptCalls", sessionID = sessionID, type = "user")
        }
    }

    private fun viewModel(gateway: FakeGateway): ChatViewModel {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-datastore-attach").apply { mkdirs() }
        val file = File(dir, "settings-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        runBlocking { store.save(ConnectionSettings(password = "x", directory = "/home/utilisateur")) }
        return ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("sessionID" to "ses_1")),
            store = store,
            gateway = gateway,
            activity = ActivityMonitor(
                store = store,
                gateway = gateway,
                connection = ConnectionMonitor(store),
                appScope = CoroutineScope(Dispatchers.Unconfined).also { it.cancel() },
                dispatcher = Dispatchers.Unconfined,
            ),
            streamFactory = EventSourceFactory { FakeEventSource() },
            dispatcher = Dispatchers.Unconfined,
            awaitingGraceMillis = 150,
        )
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

    @Test
    fun `un fichier joint part dans le corps du prompt`() {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.attachBytes("note.txt", "text/plain", "hello".toByteArray())
        assertEquals(1, vm.state.value.attachments.size)

        vm.send("regarde ça")

        val settled = awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        val body = gateway.lastBody
        assertTrue(body != null, "le corps avec pieces jointes doit etre utilise")
        assertEquals("regarde ça", body.text)
        assertEquals(1, body.files.size)
        assertEquals("data:text/plain;base64,aGVsbG8=", body.files[0].uri)
        assertEquals("note.txt", body.files[0].name)
        // Vidées après un envoi réussi : sinon on les renverrait au message suivant.
        assertTrue(settled.attachments.isEmpty(), "les pieces jointes doivent etre videes")
    }

    @Test
    fun `un texte seul continue de partir sans corps explicite`() {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)

        vm.send("bonjour")

        awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        // ⚠️ Point important : sans piece jointe, on garde le chemin minimal. Cela garantit aussi
        // que les fakes qui ne surchargent que `prompt(text)` restent valides.
        assertEquals("bonjour", gateway.lastText)
        assertTrue(gateway.lastBody == null, "aucun corps explicite attendu")
    }

    @Test
    fun `un fichier joint sans texte est un envoi legitime`() {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.attachBytes("capture.png", "image/png", byteArrayOf(1, 2, 3))

        // ⚠️ Le texte est vide : sans l'exception sur `hasContent`, ce send ne ferait rien du tout
        // et l'utilisateur croirait que l'app a ignore son fichier.
        vm.send("")

        awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        assertEquals(1, gateway.promptCalls)
        assertEquals(1, gateway.lastBody?.files?.size)
    }

    @Test
    fun `un envoi rate conserve les pieces jointes pour reessayer`() {
        val gateway = FakeGateway(failure = RuntimeException("réseau coupé"))
        val vm = viewModel(gateway)
        vm.attachBytes("note.txt", "text/plain", "hello".toByteArray())

        vm.send("regarde ça")

        val failed = awaitValue(vm.state) { it.phase == UiPhase.Error }
        // ⚠️ Perdre les fichiers sur un echec serait une double punition : l'envoi a rate ET il
        // faut re-joindre. On les garde tant que rien n'est parti.
        assertEquals(1, failed.attachments.size, "les pieces jointes doivent survivre a l'echec")
    }

    @Test
    fun `un fichier trop gros est refuse avec un message, jamais attache`() {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)

        vm.attachBytes("gros.bin", null, ByteArray(PromptAttachments.MAX_FILE_BYTES + 1))

        val state = vm.state.value
        assertTrue(state.attachments.isEmpty(), "le fichier trop gros ne doit pas etre attache")
        assertTrue(state.error != null, "un message doit expliquer le refus")
    }

    @Test
    fun `retirer une piece jointe la sort de l envoi`() {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.attachBytes("a.txt", "text/plain", "a".toByteArray())
        vm.attachBytes("b.txt", "text/plain", "b".toByteArray())

        vm.removeAttachment("a.txt")

        val state = vm.state.value
        assertEquals(listOf("b.txt"), state.attachments.map { it.name })

        vm.send("x")
        awaitValue(vm.state) { it.phase == UiPhase.Awaiting }
        assertEquals(1, gateway.lastBody?.files?.size)
        assertEquals("b.txt", gateway.lastBody?.files?.get(0)?.name)
    }
}
