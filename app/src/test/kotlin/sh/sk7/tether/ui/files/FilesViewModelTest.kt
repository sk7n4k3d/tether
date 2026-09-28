package sh.sk7.tether.ui.files

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
import sh.sk7.tether.data.api.FsEntryDto
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.testing.NeutralGateway
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **L'explorateur de fichiers : navigation, tri, lecture, bornes.**
 *
 * ### Ce que ces tests figent, et pourquoi c'est ici plutot qu'a l'ecran
 * Le serveur impose deux contraintes mesurees : les chemins sont **relatifs** au repertoire
 * configure, et `read` rend du **binaire brut**. Les consequences -- ordre d'affichage, distinction
 * texte/binaire, refus des fichiers trop gros -- sont de la logique, donc elles se testent sans
 * ecran ni Android.
 *
 * ⚠️ Le tri est teste parce qu'il **change ce qu'on voit en premier** : les dossiers d'abord. Un
 * explorateur qui melange fichiers et dossiers par ordre alphabetique oblige a lire chaque ligne
 * pour trouver ou entrer — c'est un defaut d'usage, pas un detail.
 */
class FilesViewModelTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    private class FakeGateway : NeutralGateway() {
        var listedPaths = mutableListOf<String?>()
        var listResult: List<FsEntryDto> = emptyList()
        var readResult: ByteArray? = null
        var readPath: String? = null
        var searchResult: List<FsEntryDto> = emptyList()
        var searchQuery: String? = null
        var failList: Throwable? = null

        override suspend fun fsList(
            settings: ConnectionSettings,
            path: String?,
        ): List<FsEntryDto> {
            listedPaths += path
            failList?.let { throw it }
            return listResult
        }

        override suspend fun fsRead(settings: ConnectionSettings, path: String): ByteArray? {
            readPath = path
            return readResult
        }

        override suspend fun fsFind(
            settings: ConnectionSettings,
            query: String,
            type: String?,
        ): List<FsEntryDto> {
            searchQuery = query
            return searchResult
        }
    }

    private fun viewModel(gateway: FakeGateway): FilesViewModel {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-datastore-files").apply { mkdirs() }
        val file = File(dir, "settings-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = ConnectionStore(dataStore, InMemoryCredentialsProvider())
        runBlocking { store.save(ConnectionSettings(password = "x", directory = "/home/user")) }
        return FilesViewModel(store, gateway, Dispatchers.Unconfined)
    }

    private fun <T> awaitValue(flow: kotlinx.coroutines.flow.StateFlow<T>, predicate: (T) -> Boolean): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val value = flow.value
            if (predicate(value)) return value
            Thread.sleep(5)
        }
        throw AssertionError("etat non stabilise, dernier = ${flow.value}")
    }

    @Test
    fun `les dossiers viennent avant les fichiers`() {
        val gateway = FakeGateway()
        gateway.listResult = listOf(
            FsEntryDto("zzz.txt", "file"),
            FsEntryDto("app/", "directory"),
            FsEntryDto("aaa.kt", "file"),
            FsEntryDto("build/", "directory"),
        )
        val vm = viewModel(gateway)

        val state = awaitValue(vm.state) { !it.loading && it.entries.isNotEmpty() }

        assertEquals(
            listOf("app/", "build/", "aaa.kt", "zzz.txt"),
            state.entries.map { it.path },
            "dossiers d'abord, puis alphabetique",
        )
    }

    @Test
    fun `ouvrir un dossier le liste, un fichier le lit`() {
        val gateway = FakeGateway()
        gateway.listResult = listOf(FsEntryDto("app/", "directory"), FsEntryDto("notes.md", "file"))
        val vm = viewModel(gateway)
        awaitValue(vm.state) { it.entries.isNotEmpty() }

        gateway.listResult = listOf(FsEntryDto("app/src/", "directory"))
        vm.open(FsEntryDto("app/", "directory"))
        val inApp = awaitValue(vm.state) { !it.loading && it.path == "app/" }
        assertEquals(listOf("app/src/"), inApp.entries.map { it.path })

        gateway.readResult = "contenu".toByteArray()
        vm.open(FsEntryDto("app/src/Main.kt", "file"))
        val opened = awaitValue(vm.state) { it.openFile != null }
        assertEquals("app/src/Main.kt", opened.openFile?.path)
        assertEquals("contenu", opened.openFile?.text)
        assertTrue(opened.openFile?.binary == false)
        // ⚠️ Le dossier courant ne bouge pas : ouvrir un fichier ne change pas d'endroit.
        assertEquals("app/", opened.path)
    }

    @Test
    fun `remonter au parent, et jamais au-dessus de la racine`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        vm.load("Projects/tether/app")
        awaitValue(vm.state) { it.path == "Projects/tether/app" }

        vm.up()
        awaitValue(vm.state) { it.path == "Projects/tether" }

        vm.up()
        awaitValue(vm.state) { it.path == "Projects" }

        vm.up()
        val root = awaitValue(vm.state) { it.path.isEmpty() }
        assertNull(root.parent, "la racine n'a pas de parent")

        vm.up()
        // ⚠️ Depuis la racine, `up()` ne doit rien faire : sans ce garde-fou, on fabriquerait un
        // chemin negatif et on demanderait au serveur un dossier qui n'existe pas.
        assertEquals("", vm.state.value.path)
    }

    @Test
    fun `la racine est listee sans parametre path`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        // ⚠️ `null` et non `""` : le serveur accepte `path` absent (il liste la racine du
        // `location`) ; envoyer une chaine vide n'est pas la meme chose et n'a pas ete mesure.
        assertEquals(listOf<String?>(null), gateway.listedPaths)
    }

    @Test
    fun `un fichier binaire est signale comme tel, jamais converti en charabia`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        // Octets invalides en UTF-8 (0xFF n'est jamais valide seul).
        gateway.readResult = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)
        vm.openFile("image.png")

        val opened = awaitValue(vm.state) { it.openFile != null }
        assertTrue(opened.openFile?.binary == true, "un binaire doit etre signale")
    }

    @Test
    fun `un fichier trop gros est refuse avec sa taille, jamais charge`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        gateway.readResult = ByteArray(FilesViewModel.MAX_OPEN_BYTES + 1)
        vm.openFile("gros.log")

        val state = awaitValue(vm.state) { it.fileTooBig != null }
        assertEquals("gros.log", state.fileTooBig?.path)
        assertNull(state.openFile, "le contenu ne doit pas etre charge")
    }

    @Test
    fun `un fichier de taille a la borne est affiche`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        gateway.readResult = ByteArray(FilesViewModel.MAX_OPEN_BYTES) { 'a'.code.toByte() }
        vm.openFile("limite.txt")

        val opened = awaitValue(vm.state) { it.openFile != null }
        // ⚠️ La borne est inclusive. Un `>=` refuserait un fichier que le serveur sert sans
        // probleme, et l'utilisateur n'aurait aucun moyen de comprendre la limite.
        assertEquals(false, opened.openFile?.binary)
        assertEquals(true, opened.openFile?.truncated)
    }

    @Test
    fun `un fichier introuvable est une absence, pas un plantage`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        gateway.readResult = null
        vm.openFile("absent.txt")

        val state = awaitValue(vm.state) { !it.loadingFile }
        assertTrue(state.error != null, "l'absence doit etre dite")
        assertNull(state.openFile)
    }

    @Test
    fun `la recherche transmet la requete et rend les resultats`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        gateway.searchResult = listOf(FsEntryDto("app/build.gradle.kts", "file"))
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        var received: List<FsEntryDto>? = null
        vm.search("gradle", onResult = { received = it }, onError = { })
        Thread.sleep(80)

        assertEquals("gradle", gateway.searchQuery)
        assertEquals(listOf("app/build.gradle.kts"), received?.map { it.path })
    }

    @Test
    fun `une recherche vide ne fait aucun appel serveur`() {
        val gateway = FakeGateway()
        gateway.listResult = emptyList()
        val vm = viewModel(gateway)
        awaitValue(vm.state) { !it.loading }

        var received: List<FsEntryDto>? = null
        vm.search("   ", onResult = { received = it }, onError = { })
        Thread.sleep(40)

        assertNull(gateway.searchQuery, "aucun appel ne doit partir pour une requete vide")
        assertEquals(emptyList(), received)
    }
}
