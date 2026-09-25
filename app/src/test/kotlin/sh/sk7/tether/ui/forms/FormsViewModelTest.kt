package sh.sk7.tether.ui.forms

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sh.sk7.tether.data.api.FormAnswerValue
import sh.sk7.tether.data.api.FormFieldDto
import sh.sk7.tether.data.api.FormInfoDto
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.testing.NeutralGateway

/**
 * **Le comportement de l'ecran des formulaires, sans serveur ni Compose.**
 *
 * ⚠️ On teste ici ce que le ViewModel **decide** : pre-remplir les defauts, bloquer un envoi
 * invalide, ne pas retirer un formulaire avant l'envoi, relire la liste apres un succes. Le rendu
 * Compose, lui, n'est pas couvert (aucun test d'UI dans ce projet).
 */
class FormsViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val files = mutableListOf<File>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun field(raw: String): FormFieldDto = json.decodeFromString(FormFieldDto.serializer(), raw)

    private fun realStore(settings: ConnectionSettings): ConnectionStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-forms-test").apply { mkdirs() }
        val file = File(dir, "settings-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        runBlocking { store.save(settings) }
        return store
    }

    private fun store(): ConnectionStore = realStore(ConnectionSettings(password = "x", directory = "/d"))

    private fun viewModel(gateway: OpenCodeGateway): FormsViewModel =
        FormsViewModel(store(), gateway, Dispatchers.Unconfined)

    /** Attend qu'une condition soit vraie (les coroutines du VM tournent sur `Unconfined`). */
    private fun await(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        runBlocking {
            withTimeout(timeoutMillis) {
                while (!condition()) kotlinx.coroutines.yield()
            }
        }
    }

    private class FakeGateway(
        val forms: MutableList<FormInfoDto> = mutableListOf(),
        var failList: Throwable? = null,
        var failReply: Throwable? = null,
    ) : NeutralGateway() {
        var replies = 0
        var lastSessionID: String? = null
        var lastFormID: String? = null
        var lastAnswer: Map<String, FormAnswerValue>? = null

        override suspend fun pendingForms(settings: ConnectionSettings): List<FormInfoDto> {
            failList?.let { throw it }
            return forms.toList()
        }

        override suspend fun replyForm(
            settings: ConnectionSettings,
            sessionID: String,
            formID: String,
            answer: Map<String, FormAnswerValue>,
        ): Boolean {
            replies++
            lastSessionID = sessionID
            lastFormID = formID
            lastAnswer = answer
            failReply?.let { throw it }
            // Le serveur est la verite : un formulaire repondu disparait de la liste.
            forms.removeAll { it.id == formID }
            return true
        }
    }

    private fun sampleForm(id: String = "frm_1", sessionID: String = "ses_1") = FormInfoDto(
        id = id,
        sessionID = sessionID,
        title = "Probe",
        fields = listOf(
            field("""{"key":"nom","type":"string","required":true,"default":"tether"}"""),
            field("""{"key":"site","type":"external","url":"https://example.com"}"""),
        ),
    )

    // ------------------------------------------------------------------

    @Test
    fun `load expose les formulaires pendants`() {
        val gateway = FakeGateway(mutableListOf(sampleForm()))
        val vm = viewModel(gateway)

        await { !vm.state.value.loading }

        assertEquals(1, vm.state.value.forms.size)
        assertFalse(vm.state.value.isEmpty)
    }

    /**
     * ⚠️ Ouvrir un formulaire doit **pre-remplir** ses defauts : mesure du 2026-09-26, le serveur
     * n'applique pas les defauts, un champ requis avec defaut serait declare manquant sinon.
     */
    @Test
    fun `open pre-remplit les valeurs par defaut`() {
        val gateway = FakeGateway(mutableListOf(sampleForm()))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }

        vm.open(0)

        assertEquals(FormDraftValue.Raw("tether"), vm.state.value.draft["nom"])
        assertNotNull(vm.state.value.openForm)
    }

    /**
     * ⚠️ Un champ requis vide **bloque** l'envoi : aucune requete ne part, et la raison est dite.
     * C'est la regle de non-mensonge — jamais une valeur inventee pour « que ca passe ».
     */
    @Test
    fun `un champ requis vide bloque l envoi sans appeler le serveur`() {
        val gateway = FakeGateway(mutableListOf(sampleForm()))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)
        vm.setText("nom", "")

        vm.submit()

        assertEquals(0, gateway.replies, "aucun appel ne doit partir")
        assertTrue(vm.state.value.fieldErrors.any { it.key == "nom" })
        assertNotNull(vm.state.value.openForm, "le formulaire reste ouvert pour correction")
    }

    /** Un envoi valide part, avec la `sessionID` du formulaire et l'`external` acquitte. */
    @Test
    fun `un envoi valide appelle le serveur avec la bonne session et l external acquitte`() {
        val gateway = FakeGateway(mutableListOf(sampleForm()))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)

        vm.submit()
        await { gateway.replies == 1 }

        assertEquals("ses_1", gateway.lastSessionID)
        assertEquals("frm_1", gateway.lastFormID)
        assertEquals(FormAnswerValue.Flag(true), gateway.lastAnswer!!["site"])
        assertEquals(FormAnswerValue.Text("tether"), gateway.lastAnswer!!["nom"])
    }

    /**
     * ⚠️ Apres un succes, la liste est **relue** : un formulaire repondu ne doit pas rester
     * affiche, et c'est le serveur qui fait foi, pas une suppression locale optimiste.
     */
    @Test
    fun `apres un succes la liste est relue et le formulaire disparait`() {
        val gateway = FakeGateway(mutableListOf(sampleForm()))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)

        vm.submit()
        await { vm.state.value.openIndex == null && vm.state.value.forms.isEmpty() }

        assertTrue(vm.state.value.isEmpty)
        assertNull(vm.state.value.openForm)
    }

    /**
     * ⚠️ Une `sessionID` `"global"` (elicitation MCP) est transmise **telle quelle** : aucune
     * session n'existe derriere, donc on ne la remplace jamais par autre chose.
     */
    @Test
    fun `une session globale est transmise telle quelle`() {
        val gateway = FakeGateway(mutableListOf(sampleForm(sessionID = "global")))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)

        vm.submit()
        await { gateway.replies == 1 }

        assertEquals("global", gateway.lastSessionID)
    }

    /**
     * ⚠️ Un refus du serveur (400/409) **laisse le formulaire ouvert** et affiche l'erreur : une
     * reponse rejetee doit pouvoir etre corrigee, pas disparaitre.
     */
    @Test
    fun `un refus du serveur laisse le formulaire ouvert avec l erreur`() {
        val gateway = FakeGateway(mutableListOf(sampleForm())).apply {
            failReply = IllegalStateException("409")
        }
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)

        vm.submit()
        await { vm.state.value.submitError != null }

        assertNotNull(vm.state.value.openForm)
        assertFalse(vm.state.value.sending)
    }

    /** Un `external` cache et conditionnel est quand meme acquitte (mesure du serveur). */
    @Test
    fun `un external cache est acquitte dans la reponse`() {
        val form = FormInfoDto(
            id = "frm_hidden",
            sessionID = "ses_1",
            title = "Hidden",
            fields = listOf(
                field("""{"key":"site","type":"external","url":"https://e.com","hidden":true}"""),
            ),
        )
        val gateway = FakeGateway(mutableListOf(form))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)

        vm.submit()
        await { gateway.replies == 1 }

        assertEquals(FormAnswerValue.Flag(true), gateway.lastAnswer!!["site"])
    }

    /** Une erreur de chargement est exposee, pas avalee. */
    @Test
    fun `une erreur de chargement est exposee`() {
        val gateway = FakeGateway().apply { failList = IllegalStateException("boom") }
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }

        assertNotNull(vm.state.value.error)
        assertTrue(vm.state.value.isEmpty)
    }

    /** Fermer le formulaire revient a la liste sans envoyer. */
    @Test
    fun `close revient a la liste sans envoyer`() {
        val gateway = FakeGateway(mutableListOf(sampleForm()))
        val vm = viewModel(gateway)
        await { !vm.state.value.loading }
        vm.open(0)

        vm.close()

        assertNull(vm.state.value.openIndex)
        assertEquals(0, gateway.replies)
    }

    override fun toString(): String = "FormsViewModelTest"
}
