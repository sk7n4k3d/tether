package sh.sk7.tether.ui.sessions

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
import kotlinx.coroutines.withContext
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors

/** Etat de l'ecran de liste des sessions. */
sealed interface SessionListUiState {
    /** Aucun mot de passe enregistre : l'ecran renvoie vers les reglages. */
    data object NeedsSetup : SessionListUiState
    data object Loading : SessionListUiState
    data class Empty(val directory: String) : SessionListUiState

    data class Loaded(
        val items: List<SessionItem>,
        val models: List<Model>,
        val agents: List<Agent>,
        /** Agent du serveur retenu a la creation, `null` = laisser le serveur decider. */
        val createAgent: String? = null,
        /** Modele choisi ; **obligatoire** a la creation (contrainte API). */
        val createModel: ModelRef? = null,
    ) : SessionListUiState

    data class Error(val message: String) : SessionListUiState
}

/** Etat du dialogue de creation de session. */
data class CreateSessionState(
    val visible: Boolean = false,
    val title: String = "",
    val model: ModelRef? = null,
    val agent: String? = null,
    val creating: Boolean = false,
    val error: String? = null,
) {
    /** Le modele est obligatoire : sans lui, le bouton de creation reste inactif. */
    val canSubmit: Boolean get() = !creating && model != null
}

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * Portee propre au ViewModel, parametree par [ioDispatcher].
     *
     * `viewModelScope` est lie a `Dispatchers.Main`, indisponible dans un test JVM ; passer
     * par un dispatcher injecte rend la logique testable sans `kotlinx-coroutines-test`
     * (dependance non autorisee par le brief). L'annulation reste branchee sur le cycle de vie.
     */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _state = MutableStateFlow<SessionListUiState>(SessionListUiState.Loading)
    val state: StateFlow<SessionListUiState> = _state.asStateFlow()

    private val _create = MutableStateFlow(CreateSessionState())
    val create: StateFlow<CreateSessionState> = _create.asStateFlow()

    init {
        refresh()
    }

    override fun onCleared() {
        scope.cancel()
    }

    /** Recharge la liste des sessions, les modeles et les agents depuis le REST. */
    fun refresh() {
        scope.launch {
            val settings = store.current()
            if (!settings.isConfigured) {
                _state.value = SessionListUiState.NeedsSetup
                return@launch
            }
            _state.value = SessionListUiState.Loading
            try {
                _state.value = load(settings)
            } catch (e: Exception) {
                _state.value = SessionListUiState.Error(ConnectionErrors.describe(e))
            }
        }
    }

    private suspend fun load(settings: ConnectionSettings): SessionListUiState {
        val sessions = gateway.sessions(settings)
        if (sessions.isEmpty()) return SessionListUiState.Empty(settings.directory)
        val items = SessionListMapper.toItems(sessions)
        // Modeles et agents sont secondaires : leur echec ne doit pas masquer la liste.
        val models = runCatching { gateway.models(settings) }.getOrDefault(emptyList())
        val agents = runCatching { gateway.agents(settings) }.getOrDefault(emptyList())
        return SessionListUiState.Loaded(
            items = items,
            models = models,
            agents = agents,
            createModel = defaultModel(sessions, models),
        )
    }

    /**
     * Modele propose par defaut a la creation : celui de la session la plus recente, s'il
     * existe encore cote serveur. Replier sur le premier modele de la liste mettrait un
     * modele de demonstration en tete, ce qui n'est pas ce que l'utilisateur veut.
     *
     * ⚠️ On renvoie la reference **du catalogue**, pas celle de la session : une session
     * porte un `variant` (`"default"`), absent du catalogue, et comparer les deux rendrait
     * la selection vide dans le menu deroulant.
     */
    private fun defaultModel(sessions: List<Session>, models: List<Model>): ModelRef? {
        val matching = sessions.firstNotNullOfOrNull { session ->
            session.model?.let { ref -> models.firstOrNull { it.matches(ref) } }
        }
        return (matching ?: models.firstOrNull())?.toRef()
    }

    // ------------------------------------------------------------------
    // Dialogue de creation
    // ------------------------------------------------------------------

    fun openCreate() {
        val loaded = _state.value as? SessionListUiState.Loaded ?: return
        _create.value = CreateSessionState(
            visible = true,
            model = loaded.createModel ?: loaded.models.firstOrNull()?.toRef(),
            agent = loaded.createAgent ?: loaded.agents.firstOrNull()?.id,
        )
    }

    fun dismissCreate() {
        _create.value = CreateSessionState()
    }

    fun onCreateTitleChange(value: String) = _create.update { it.copy(title = value, error = null) }

    fun onCreateModelChange(model: ModelRef) = _create.update { it.copy(model = model, error = null) }

    fun onCreateAgentChange(agent: String?) = _create.update { it.copy(agent = agent, error = null) }

    /** Cree la session avec le modele choisi (obligatoire) puis recharge la liste. */
    fun createSession() {
        val form = _create.value
        val model = form.model
        if (model == null) {
            _create.update { it.copy(error = "Un modèle est obligatoire.") }
            return
        }
        _create.update { it.copy(creating = true, error = null) }
        scope.launch {
            val settings = store.current()
            try {
                val title = form.title.trim().ifBlank { "Nouvelle session" }
                gateway.createSession(settings, title, model, form.agent)
                _create.value = CreateSessionState()
                refreshAfterCreate()
            } catch (e: Exception) {
                _create.update { it.copy(creating = false, error = ConnectionErrors.describe(e)) }
            }
        }
    }

    private suspend fun refreshAfterCreate() {
        val settings = store.current()
        try {
            _state.value = load(settings)
        } catch (e: Exception) {
            _state.value = SessionListUiState.Error(ConnectionErrors.describe(e))
        }
    }

    private fun Model.toRef(): ModelRef =
        ModelRef(id = modelID ?: id, providerID = providerID.orEmpty())

    private fun Model.matches(ref: ModelRef): Boolean =
        (modelID ?: id) == ref.id && providerID == ref.providerID
}
