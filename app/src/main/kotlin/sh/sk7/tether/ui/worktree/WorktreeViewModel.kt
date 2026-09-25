package sh.sk7.tether.ui.worktree

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
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.WorktreeDirDto
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors

data class WorktreeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Le repertoire est-il versionne ? Sans VCS, un arbre de travail n'a pas de sens. */
    val versioned: Boolean = true,
    val items: List<WorktreeDirDto> = emptyList(),
    /** Nom saisi pour le prochain arbre. */
    val draftName: String = "",
    val creating: Boolean = false,
    /** Suppression en cours, avec `force` : on attend la confirmation. */
    val removing: WorktreeDirDto? = null,
)

/**
 * **Les arbres de travail isoles.**
 *
 * ### Pourquoi cette fonction compte
 * Un agent qui ecrit des fichiers travaille directement dans le depot. Un **arbre de travail** lui
 * donne une copie a part : on essaie une approche risquee sans que l'arbre principal soit touche.
 * C'est la reponse concrete a « je veux essayer sans risquer mon depot », et ca n'existe pas dans
 * une interface de chat.
 *
 * ### Ce qu'on n'expose PAS, et c'est mesure
 * ⚠️ Les champs `branch` et `from` **ne sont pas** des references git malgre leur nom. Mesure du
 * 2026-09-25 : `{name:"x", branch:"probe-tether"}` rend `400 référence invalide : probe-tether`, et
 * `from:"HEAD"` rend `400 Worktree directory unavailable: HEAD`. Ils designent des arbres
 * **existants**, pas des branches a creer. On ne les propose donc pas : un champ qui echoue a coup
 * sur serait pire que pas de champ.
 */
@HiltViewModel
class WorktreeViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(WorktreeUiState())
    val state: StateFlow<WorktreeUiState> = _state.asStateFlow()

    init {
        load()
    }

    override fun onCleared() {
        scope.cancel()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val settings = store.current()
            // ⚠️ On verifie d'abord que le repertoire est versionne. Un arbre de travail repose sur
            // git : dans un repertoire sans gestion de version, la creation echouerait avec un
            // message obscur. Mieux vaut le dire avant d'essayer.
            val versioned = runCatching { gateway.vcsInfo(settings, settings.directory)?.isVersioned }
                .getOrNull() ?: true

            runCatching { gateway.worktrees(settings) }
                .onSuccess { list ->
                    _state.update {
                        it.copy(loading = false, versioned = versioned, items = list, error = null)
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            versioned = versioned,
                            error = ConnectionErrors.describe(e),
                        )
                    }
                }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(draftName = value) }

    /**
     * Cree un arbre de travail.
     *
     * ⚠️ Le nom est **obligatoire** ici : mesure, `{projectID}` seul n'est pas teste mais un nom
     * vide produirait un dossier sans signification. Un nom vide est refuse plutot que genere en
     * silence, parce que l'utilisateur doit pouvoir retrouver son arbre plus tard.
     */
    fun create() {
        val name = _state.value.draftName.trim()
        if (name.isEmpty()) return
        _state.update { it.copy(creating = true, error = null) }
        scope.launch {
            runCatching { gateway.createWorktree(store.current(), name) }
                .onSuccess {
                    _state.update { it.copy(creating = false, draftName = "") }
                    load()
                }
                .onFailure { e ->
                    _state.update { it.copy(creating = false, error = ConnectionErrors.describe(e)) }
                }
        }
    }

    /** Demande la confirmation de suppression. */
    fun askRemove(item: WorktreeDirDto) = _state.update { it.copy(removing = item) }

    fun cancelRemove() = _state.update { it.copy(removing = null) }

    /**
     * Supprime un arbre de travail.
     *
     * ⚠️ `force = true` est un choix **explicite de l'utilisateur**, jamais un defaut. Un arbre avec
     * des modifications non commitees ne se retire pas sans forcer — le forcer en silence, ce serait
     * detruire du travail sans le dire. Le bouton le nomme.
     */
    fun remove(force: Boolean) {
        val item = _state.value.removing ?: return
        _state.update { it.copy(removing = null) }
        scope.launch {
            runCatching { gateway.removeWorktree(store.current(), item.directory, force) }
                .onSuccess { load() }
                .onFailure { e ->
                    _state.update { it.copy(error = ConnectionErrors.describe(e)) }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
