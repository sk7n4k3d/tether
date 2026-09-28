package sh.sk7.tether.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore

/** Resultat du bouton « Tester ». */
sealed interface ConnectionTestResult {
    data object Idle : ConnectionTestResult
    data object Testing : ConnectionTestResult
    data class Success(val version: String) : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

data class ConnectionUiState(
    val baseUrl: String = ConnectionSettings.DEFAULT_BASE_URL,
    val password: String = "",
    val directory: String = ConnectionSettings.DEFAULT_DIRECTORY,
    val result: ConnectionTestResult = ConnectionTestResult.Idle,
    val saved: Boolean = false,
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    /**
     * L'app a-t-elle de quoi se connecter ?
     *
     * ⚠️ **Lue au moment d'un scan**, et c'est ce qui decide ou l'utilisateur atterrit.
     * La provenance ne suffit pas : quelqu'un dont les identifiants sont enregistres mais
     * qui arrive au scanner depuis l'accueil — parce qu'il a passe le carrousel — se
     * serait retrouve sur un formulaire de connexion deja rempli, sans raison.
     *
     * `Eagerly` et non `WhileSubscribed` : la valeur est lue ponctuellement, dans un
     * rappel de navigation, pas observee par un ecran.
     */
    val configured: StateFlow<Boolean> = store.settings
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val saved = store.current()
            _state.update {
                it.copy(
                    baseUrl = saved.baseUrl,
                    password = saved.password,
                    directory = saved.directory,
                )
            }
        }
    }

    fun onBaseUrlChange(value: String) = _state.update {
        it.copy(baseUrl = value, result = ConnectionTestResult.Idle, saved = false)
    }

    fun onPasswordChange(value: String) = _state.update {
        it.copy(password = value, result = ConnectionTestResult.Idle, saved = false)
    }

    fun onDirectoryChange(value: String) = _state.update {
        it.copy(directory = value, result = ConnectionTestResult.Idle, saved = false)
    }

    /**
     * L'adresse vient d'un QR, pas du clavier.
     *
     * ⚠️ Elle est ecrite **tout de suite dans le store**, pas seulement dans l'etat de
     * cet ecran : celui qui scanne arrive d'un autre ecran, et ce ViewModel-la est cree
     * au moment ou l'ecran s'affiche. Passer par le store fait que l'ecran lit deja la
     * bonne adresse a son ouverture, sans argument de navigation a encoder.
     */
    fun adopterAdresse(url: String) {
        _state.update { it.copy(baseUrl = url, result = ConnectionTestResult.Idle, saved = false) }
        viewModelScope.launch { store.enregistrerAdresse(url) }
    }

    /**
     * Enregistre, pousse les identifiants, puis interroge `/api/info`.
     * Aucune erreur affichee ne contient le mot de passe (voir [ConnectionErrors]).
     */
    fun testConnection() {
        val current = _state.value
        _state.update { it.copy(result = ConnectionTestResult.Testing, saved = false) }
        viewModelScope.launch {
            val settings = ConnectionSettings(
                baseUrl = current.baseUrl,
                password = current.password,
                directory = current.directory,
            )
            try {
                store.save(settings)
                val info = gateway.info(store.current())
                _state.update {
                    it.copy(result = ConnectionTestResult.Success(info.version), saved = true)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(result = ConnectionTestResult.Failure(ConnectionErrors.describe(e)))
                }
            }
        }
    }
}
