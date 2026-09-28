package sh.sk7.tether.ui.files

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.FsEntryDto
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **L'etat de l'explorateur : ou l'on est, ce qu'on voit, ce qu'on a ouvert.**
 *
 * ⚠️ Un etat a part pour la lecture d'un fichier : `browsedPath` decrit le **dossier courant**,
 * `openFile` decrit le **fichier affiche**. Les melanger ferait disparaitre la liste des qu'on
 * ouvre un fichier — or on veut pouvoir refermer le fichier et retrouver sa place.
 */
data class FilesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Dossier courant, **relatif au repertoire configure** (`""` = racine du `location`). */
    val path: String = "",
    val entries: List<FsEntryDto> = emptyList(),
    /** Le fichier ouvert, ou `null` si l'on regarde le dossier. */
    val openFile: OpenFile? = null,
    /** Un fichier est en cours de lecture. */
    val loadingFile: Boolean = false,
    /** Fichier trop gros pour etre affiche : on dit sa taille plutot que de charger des Mo. */
    val fileTooBig: FileTooBig? = null,
) {
    /** Le parent du dossier courant, ou `null` si l'on est a la racine. */
    val parent: String? get() = if (path.isBlank()) null else path.substringBeforeLast('/', "")

    val isRoot: Boolean get() = path.isBlank()
}

/**
 * Le contenu d'un fichier, **borne en memoire**.
 *
 * ⚠️ On garde le texte **et** le drapeau de troncature. Un fichier de 300 000 lignes affiche
 * d'un bloc ferait ramer la liste, donc on tronque — mais on le **dit**, parce qu'un fichier
 * tronque en silence se lirait comme un fichier complet.
 */
data class OpenFile(
    val path: String,
    val text: String,
    val truncated: Boolean,
    val binary: Boolean,
)

/** Un fichier qu'on refuse d'afficher, avec la raison. */
data class FileTooBig(val path: String, val bytes: Int)

/**
 * **L'explorateur de fichiers du serveur.**
 *
 * ### Pourquoi cet ecran
 * L'agent lit et ecrit des fichiers sur la machine ; sans cet ecran, l'app ne peut montrer que ce
 * que l'agent a bien voulu citer dans sa reponse. Trois gestes deviennent possibles :
 * **verifier un chemin avant de l'envoyer** (une faute de frappe coute un aller-retour de
 * conversation), **voir ce que l'agent verra** d'un fichier, et **chercher** ou il se trouve.
 *
 * ### Les deux contraintes que le serveur impose, et qui sont mesurees
 * 1. **Les chemins sont relatifs au repertoire configure.** Un chemin absolu rend un
 *    `404 FileNotFoundError` (mesure). On ne fabrique donc jamais de chemin absolu cote app : on
 *    navigue par segments sous la racine du `location`.
 * 2. **`read` rend du binaire brut**, pas du JSON. Un fichier non-UTF-8 (ou un binaire) est
 *    affiche comme **non textuel** plutot que converti en charabia.
 *
 * ⚠️ **Echec partiel assume**, comme l'inventaire du serveur : une lecture de fichier qui echoue
 * ne doit pas vider la liste des fichiers. Ce sont deux faits independants.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    init {
        load("")
    }

    override fun onCleared() {
        scope.cancel()
    }

    /**
     * Charge le contenu d'un dossier.
     *
     * ⚠️ On **remplace** le chemin avant la reponse, et on vide l'erreur precedente : afficher le
     * dossier precedent en indiquant le nouveau chemin serait le pire des etats — l'utilisateur
     * croirait que le dossier est vide alors qu'il regarde encore l'ancien.
     */
    fun load(path: String) {
        _state.update {
            it.copy(
                loading = true,
                error = null,
                path = path,
                entries = emptyList(),
                // ⚠️ On referme le fichier : rester sur le fichier precedent en ayant change de
                // dossier afficherait un contenu qui ne correspond plus a l'endroit ou l'on est.
                openFile = null,
                fileTooBig = null,
            )
        }
        scope.launch {
            runCatching { gateway.fsList(store.current(), path.ifBlank { null }) }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(
                            loading = false,
                            // ⚠️ Dossiers d'abord, puis alphabetique : c'est l'ordre que tout le
                            // monde attend d'un explorateur. Le serveur rend l'ordre du disque,
                            // qui n'en est pas un.
                            entries = entries.sortedWith(
                                compareByDescending<FsEntryDto> { it.isDirectory }.thenBy { it.path },
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = ConnectionErrors.describe(e)) }
                }
        }
    }

    /** Entre dans un sous-dossier (chemin relatif deja rendu par le serveur). */
    fun open(entry: FsEntryDto) {
        if (entry.isDirectory) load(entry.path) else openFile(entry.path)
    }

    /** Remonte au dossier parent. */
    fun up() {
        _state.value.parent?.let { load(it) }
    }

    /** Referme le fichier affiche et revient a la liste du dossier courant. */
    fun closeFile() {
        _state.update { it.copy(openFile = null, fileTooBig = null) }
    }

    /**
     * **Recherche recursive** dans tout le repertoire configure (`GET /api/fs/find`).
     *
     * ⚠️ Les resultats ne sont **pas** stockes dans [FilesUiState] : ils appartiennent a un appel
     * transitoire et a une requete precise. Les mettre dans l'etat partage ferait apparaitre des
     * resultats d'une recherche precedente apres avoir change de dossier — un affichage qui ne
     * correspond a rien.
     *
     * ⚠️ Le callback rend **aussi** les erreurs plutot que de les lever : une recherche qui echoue
     * ne doit pas faire tomber l'ecran, elle doit pouvoir le dire a cote de la liste.
     */
    fun search(
        query: String,
        onResult: (List<FsEntryDto>) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (query.isBlank()) {
            onResult(emptyList())
            return
        }
        scope.launch {
            runCatching { gateway.fsFind(store.current(), query) }
                .onSuccess { onResult(it) }
                .onFailure { onError(ConnectionErrors.describe(it)) }
        }
    }

    /**
     * Ouvre un fichier : lit ses octets et decode ce qui est decodable.
     *
     * ⚠️ **Borne de taille** : au-dela de [MAX_OPEN_BYTES], on ne charge pas et on le dit. Un
     * fichier de plusieurs mega-octets converti en `String` ferait un `Text` de plusieurs millions
     * de caracteres dans un `LazyColumn` — l'ecran se figerait, et l'utilisateur n'aurait aucun
     * moyen de savoir pourquoi.
     *
     * ⚠️ **Binaire != texte** : on tente un decodage UTF-8 strict et on expose `binary` en cas
     * d'echec. Afficher un PNG converti de force produirait des milliers de caracteres de
     * remplacement, ce qui est pire que de dire « ce n'est pas du texte ».
     */
    fun openFile(path: String) {
        _state.update { it.copy(loadingFile = true, error = null, fileTooBig = null, openFile = null) }
        scope.launch {
            runCatching { gateway.fsRead(store.current(), path) }
                .onSuccess { bytes ->
                    if (bytes == null) {
                        _state.update {
                            it.copy(loadingFile = false, error = Res.of(R.string.fichier_introuvable_path_003b27))
                        }
                        return@onSuccess
                    }
                    if (bytes.size > MAX_OPEN_BYTES) {
                        _state.update {
                            it.copy(loadingFile = false, fileTooBig = FileTooBig(path, bytes.size))
                        }
                        return@onSuccess
                    }
                    val decoded = decodeStrictUtf8(bytes)
                    _state.update {
                        it.copy(
                            loadingFile = false,
                            openFile = OpenFile(
                                path = path,
                                text = decoded ?: "",
                                truncated = bytes.size >= MAX_OPEN_BYTES,
                                binary = decoded == null,
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loadingFile = false, error = ConnectionErrors.describe(e)) }
                }
        }
    }

    /**
     * Decode en UTF-8 **strict**, ou rend `null` si ce n'est pas du texte.
     *
     * ⚠️ Un decodage permissif remplacerait chaque octet invalide par U+FFFD : la page serait
     * remplie de losanges, et on ne saurait pas si le fichier est binaire ou simplement dans un
     * autre encodage. `null` est un fait ; du charabia n'en est pas un.
     */
    private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
        val decoder = java.nio.charset.Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    companion object {
        /**
         * Taille maximale chargee pour affichage.
         *
         * ⚠️ 512 Ko : un fichier de texte fait rarement plus, et un `Text` de 512 Ko reste
         * affichable. Au-dela, on dit la taille — ce qui informe plus que de faire ramer l'ecran.
         */
        const val MAX_OPEN_BYTES: Int = 512 * 1024
    }
}
