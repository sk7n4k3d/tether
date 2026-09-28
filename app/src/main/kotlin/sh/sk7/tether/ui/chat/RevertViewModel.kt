package sh.sk7.tether.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.FileDiffDto
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.ui.settings.ConnectionErrors
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **Ce qu'un retour en arrière toucherait, avant de le faire.**
 *
 * ⚠️ Un `revert` modifie des **fichiers sur la machine**. On ne l'execute donc jamais directement :
 * on prepare (`stage`), on **montre** ce qui serait touche, et l'utilisateur confirme. C'est le seul
 * endroit de l'app ou une action peut defaire du travail — sans cet ecran, l'utilisateur
 * approuverait une modification de disque a l'aveugle.
 */
data class RevertPreview(
    val messageID: String,
    val files: List<FileDiffDto>,
) {
    val fileCount: Int get() = files.size
    val isEmpty: Boolean get() = files.isEmpty()
}

sealed interface RevertUiState {
    /** Rien en cours. */
    data object Idle : RevertUiState

    /** Verification en cours. */
    data object Staging : RevertUiState

    /** Ce qui serait touche — et on attend une decision. */
    data class Ready(val preview: RevertPreview) : RevertUiState

    /** Une operation a echoue. */
    data class Failed(val message: String) : RevertUiState
}

/**
 * **Le retour en arrière, en deux temps.**
 *
 * ### Pourquoi deux temps, alors que l'API en offre un
 * L'API permet un `revert` en un appel. On ne s'en sert pas, et c'est deliberé : un aller simple
 * modifierait des fichiers sur la machine **sans que personne ne voie lesquels**. Or les plaintes
 * les plus vives des utilisateurs d'agents portent precisement sur les actions destructives faites
 * sans confirmation (« j'ai perdu des heures de travail »).
 *
 * ⚠️ L'ordre est : `stage` -> on **montre** -> l'utilisateur confirme -> `commit`. Et si
 * l'utilisateur renonce, on appelle `discard` : sans cette troisieme etape, un retour prepare
 * laisserait la session dans un **etat intermediaire sans sortie depuis l'app**.
 *
 * ⚠️ On ne fait **aucun revert automatique**, jamais. Meme un echec d'agent evident n'autorise pas
 * l'app a defaire du travail toute seule.
 */
@HiltViewModel
class RevertViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
) : ViewModel() {

    private val _state = MutableStateFlow<RevertUiState>(RevertUiState.Idle)
    val state: StateFlow<RevertUiState> = _state.asStateFlow()

    /**
     * Prepare le retour vers un message.
     *
     * ⚠️ `messageID` doit etre un identifiant **serveur** (`msg_...`). Un identifiant optimiste de
     * l'app n'existe pas cote serveur : envoyer le retour vers un message qui n'a pas encore ete
     * accepte serait un non-sens, et c'est a l'appelant de le filtrer.
     */
    fun stage(sessionID: String, messageID: String) {
        _state.value = RevertUiState.Staging
        viewModelScope.launch {
            try {
                val current = store.current()
                val result = gateway.stageRevert(current, sessionID, messageID)
                _state.value = RevertUiState.Ready(
                    RevertPreview(messageID = messageID, files = result?.files.orEmpty()),
                )
            } catch (e: Exception) {
                _state.value = RevertUiState.Failed(ConnectionErrors.describe(e))
            }
        }
    }

    /** Applique le retour prepare. Appele **uniquement** apres confirmation de l'utilisateur. */
    fun commit(sessionID: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val current = store.current()
                if (gateway.commitRevert(current, sessionID)) {
                    _state.value = RevertUiState.Idle
                    onDone()
                } else {
                    _state.value = RevertUiState.Failed(Res.of(R.string.serveur_refuse_appliquer_b0c92c))
                }
            } catch (e: Exception) {
                _state.value = RevertUiState.Failed(ConnectionErrors.describe(e))
            }
        }
    }

    /**
     * Abandonne un retour prepare.
     *
     * ⚠️ On appelle `discard` meme si rien n'a ete modifie : le serveur garde un **snapshot** en
     * attendant, et le laisser en place pourrait interferer avec la suite de la session.
     */
    fun discard(sessionID: String) {
        viewModelScope.launch {
            runCatching { gateway.discardRevert(store.current(), sessionID) }
            _state.value = RevertUiState.Idle
        }
    }

    fun dismiss() {
        _state.value = RevertUiState.Idle
    }
}
