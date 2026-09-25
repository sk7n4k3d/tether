package sh.sk7.tether.ui.permissions

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.ui.settings.ConnectionErrors

data class PermissionsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val pending: List<PermissionRequest> = emptyList(),
    /** Ids en cours de reponse : on desactive leurs boutons pour eviter le double envoi. */
    val replying: Set<String> = emptySet(),
) {
    val hasAny: Boolean get() = pending.isNotEmpty()
}

/**
 * **Les demandes d'autorisation en attente — le coeur d'un client d'agent.**
 *
 * ### Pourquoi cet ecran est le plus important de l'app
 * Les deux rapports d'usage d'opencode convergent sur ce point, et c'est aussi la frustration la
 * mieux documentee cote utilisateurs : *« Sessions can sit idle in the question dialog without
 * the user realizing they need attention »*. Une session peut rester **bloquee des heures** parce
 * que personne n'a vu la demande. Repondre depuis le telephone est la raison d'etre premiere
 * d'une app compagne — plus que lire des reponses.
 *
 * ### Le polling, assume et explique
 * ⚠️ On interroge le serveur **periodiquement** plutot que d'attendre le flux SSE. Raison : le
 * flux n'emet les demandes que si l'app est connectee **au moment ou elles arrivent**. Or une
 * demande en attente peut avoir ete emise avant l'ouverture de l'app — c'est meme le cas
 * principal (on ouvre l'app *parce qu'on* a ete notifie). Le polling couvre ce cas, le flux non.
 * L'intervalle est volontairement lent : ces requetes sont minuscules et le serveur est local.
 *
 * ⚠️ **On ne repond jamais automatiquement.** Chaque decision est un acte explicite de
 * l'utilisateur : c'est le seul endroit de l'app ou un choix a des consequences reelles sur sa
 * machine.
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(PermissionsUiState())
    val state: StateFlow<PermissionsUiState> = _state.asStateFlow()

    init {
        load()
        poll()
    }

    override fun onCleared() {
        scope.cancel()
    }

    fun load() {
        scope.launch {
            try {
                val settings = store.current()
                val pending = gateway.pendingPermissions(settings)
                _state.update { it.copy(loading = false, error = null, pending = pending) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = ConnectionErrors.describe(e))
                }
            }
        }
    }

    /**
     * Surveillance continue, tant que l'ecran est ouvert.
     *
     * ⚠️ On ne relance **pas** une requete si la precedente n'est pas finie : `load` est
     * asynchrone et l'intervalle est court. Sans ce garde-fou, un serveur lent accumulerait les
     * requetes empilees, ce qui degraderait exactement ce qu'on veut ameliorer.
     */
    private fun poll() {
        scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                load()
            }
        }
    }

    /**
     * Repond a une demande.
     *
     * ⚠️ On **retire la demande de la liste des l'envoi**, sans attendre la confirmation du
     * serveur. Sinon elle reste affichee pendant l'aller-retour et l'utilisateur appuie deux
     * fois — ce qui enverrait deux decisions contradictoires pour la meme demande. En cas
     * d'echec, on la **remet** : une demande qui disparait sans avoir ete traitee serait pire
     * qu'un doublon.
     */
    fun reply(request: PermissionRequest, decision: PermissionDecision) {
        if (request.id in _state.value.replying) return
        _state.update {
            it.copy(
                pending = it.pending.filterNot { p -> p.id == request.id },
                replying = it.replying + request.id,
            )
        }
        scope.launch {
            val settings = store.current()
            runCatching {
                gateway.replyPermission(settings, request.sessionID, request.id, decision)
            }
                .onSuccess {
                    _state.update { it.copy(replying = it.replying - request.id) }
                }
                .onFailure { e ->
                    // La demande revient : l'utilisateur doit pouvoir reessayer.
                    _state.update {
                        it.copy(
                            pending = (it.pending + request).distinctBy { p -> p.id },
                            replying = it.replying - request.id,
                            error = ConnectionErrors.describe(e),
                        )
                    }
                }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        /**
         * 6 s : assez reactif pour suivre une session qui enchaine, assez lent pour ne pas
         * marteler le serveur. Le cout est de quelques centaines d'octets par tour.
         */
        const val POLL_INTERVAL_MS = 6_000L
    }
}
