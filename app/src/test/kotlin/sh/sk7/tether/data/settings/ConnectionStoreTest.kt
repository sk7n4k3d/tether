package sh.sk7.tether.data.settings

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
import sh.sk7.tether.data.api.BasicAuthCredentials
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStoreTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>

    @BeforeTest
    fun setUp() {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-datastore-test")
        dir.mkdirs()
        file = File(dir, "settings-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun `les valeurs par defaut ne designent personne`() = runBlocking {
        // ⚠️ Ces deux defauts pointaient sur l'infra de l'auteur. Publie, l'app de tous
        // les autres essayait de se connecter a sa machine, et dans le LAN d'un ami la
        // connexion aboutissait reellement quelque part. La version publiee n'a le droit
        // de designer que la machine de l'utilisateur.
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        val settings = store.current()
        assertEquals("http://127.0.0.1:4096", settings.baseUrl)
        assertEquals("", settings.directory, "un repertoire vide signifie « celui du serveur »")
        assertEquals("", settings.password)
        assertFalse(settings.isConfigured, "sans mot de passe, la connexion n'est pas configuree")
    }

    @Test
    fun `save persiste puis relit url mot de passe et repertoire`() = runBlocking {
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        store.save(
            ConnectionSettings(
                baseUrl = "http://192.0.2.10:4096/",
                password = "un-mot-de-passe",
                directory = "/tmp/opencode",
            ),
        )

        val settings = store.current()
        assertEquals("http://192.0.2.10:4096/", settings.baseUrl)
        assertEquals("un-mot-de-passe", settings.password)
        assertEquals("/tmp/opencode", settings.directory)
        assertTrue(settings.isConfigured)
    }

    @Test
    fun `le mot de passe survit a une nouvelle instance du store`() = runBlocking {
        ConnectionStore(dataStore, InMemoryCredentialsProvider())
            .save(ConnectionSettings(password = "persistant", directory = "/x"))

        val reopened = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        assertEquals("persistant", reopened.current().password)
        assertEquals("/x", reopened.current().directory)
    }

    @Test
    fun `isConfigured exige une url et un mot de passe`() {
        assertFalse(ConnectionSettings(baseUrl = "", password = "x").isConfigured)
        assertFalse(ConnectionSettings(baseUrl = "http://a", password = "").isConfigured)
        assertTrue(ConnectionSettings(baseUrl = "http://a", password = "x").isConfigured)
    }

    @Test
    fun `une valeur vide enregistree retombe sur le defaut du champ`() = runBlocking {
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        store.save(ConnectionSettings(baseUrl = "   ", password = "x", directory = "  "))

        val settings = store.current()
        assertEquals("http://127.0.0.1:4096", settings.baseUrl)
        assertEquals("", settings.directory)
    }

    @Test
    fun `save pousse les identifiants dans le provider injecte`() = runBlocking {
        val provider = InMemoryCredentialsProvider()
        val store = ConnectionStore(dataStore, provider)

        assertNull(provider.credentials(), "aucun identifiant avant enregistrement")
        store.save(ConnectionSettings(password = "s3cret"))

        val credentials = provider.credentials()
        assertEquals("opencode", credentials?.username)
        assertEquals("s3cret", credentials?.password)
    }

    @Test
    fun `un mot de passe vide retire les identifiants du provider`() = runBlocking {
        val provider = InMemoryCredentialsProvider(BasicAuthCredentials(password = "ancien"))
        val store = ConnectionStore(dataStore, provider)

        store.save(ConnectionSettings(password = ""))

        assertNull(provider.credentials(), "un mot de passe vide ne doit pas rester actif")
    }
}
