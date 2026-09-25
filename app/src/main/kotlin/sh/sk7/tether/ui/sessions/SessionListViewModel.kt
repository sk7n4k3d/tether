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

/**
 * Consommation agregee du serveur opencode.
 *
 * ⚠️ **Principe de non-mensonge** (`docs/design-soul.md` §3) : Bastien ne voyait ni son cout
 * ni ses tokens, alors que l'API les expose. Ce bloc les rend visibles **sans qu'on les
 * demande** — c'est un instrument de controle, pas un chat.
 *
 * @param costTotal somme des couts des sessions chargees.
 * @param sessions nombre de sessions agregees (pour dire honnetement sur quoi on calcule).
 */
data class UsageInfo(
    val costTotal: Double,
    val sessions: Int,
    val tokensIn: Long,
    val tokensOut: Long,
    val cacheRead: Long,
) {
    val hasAny: Boolean get() = costTotal > 0.0 || tokensIn > 0 || tokensOut > 0
}

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
        /** Consommation agregee. `null` = le serveur ne l'expose pas (pas d'erreur). */
        val usage: UsageInfo? = null,
        /** Agent du serveur retenu a la creation, `null` = laisser le serveur decider. */
        val createAgent: String? = null,
        /** Modele choisi ; **obligatoire** a la creation (contrainte API). */
        val createModel: ModelRef? = null,
        /**
         * Un rafraichissement est en cours **alors que la liste est deja affichee**.
         *
         * ⚠️ **Distinct de [Loading]** et c'est essentiel : `Loading` remplace la liste par une
         * roue — acceptable au premier chargement, insupportable sur un tirer-pour-rafraichir,
         * ou l'on perd l'ecran qu'on etait en train de lire. Ici la liste **reste a l'ecran**,
         * seule l'indicateur du geste tourne. C'est la difference entre « je charge » et
         * « j'actualise ».
         */
        val refreshing: Boolean = false,
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

    /**
     * Erreur d'une action de session (renommer, fork, suppression…).
     *
     * ⚠️ **Distincte de [SessionListUiState.Error]** : une resync qui echoue ne doit pas
     * remplacer la liste par un ecran d'erreur. Une action qui echoue est une information
     * ponctuelle a montrer, pas un etat d'ecran.
     */
    private val _sessionError = MutableStateFlow<String?>(null)
    val sessionError: StateFlow<String?> = _sessionError.asStateFlow()

    /**
     * Nombre de demandes d'autorisation en attente sur le serveur.
     *
     * ⚠️ **Compte separe de la liste des sessions**, et rafraichi independamment : une demande
     * d'autorisation peut concerner une session qui n'est pas dans la page courante, ou arriver
     * alors que la liste est deja chargee. Le badge doit refleter l'etat **du serveur**, pas ce
     * qu'on a sous les yeux.
     *
     * ⚠️ Un echec ici ne remonte rien a l'utilisateur : ce compteur est un confort. Le faire
     * echouer bruyamment transformerait un detail en panne apparente.
     */
    private val _pendingApprovals = MutableStateFlow(0)
    val pendingApprovals: StateFlow<Int> = _pendingApprovals.asStateFlow()

    init {
        refresh()
    }

    override fun onCleared() {
        scope.cancel()
    }

    /**
     * Rafraichit **sans vider l'ecran** : la liste reste affichee pendant le chargement.
     *
     * ⚠️ C'est ce qui branche le geste « tirer vers le bas ». Passer par [refresh] ferait
     * disparaitre la liste pour une roue centree — on perdrait l'ecran qu'on etait en train de
     * lire, pour un geste dont tout l'interet est d'etre **non destructif**.
     * L'erreur eventuelle est remontee dans [sessionError], pas en remplacant la liste.
     */
    fun startRefresh() {
        val loaded = _state.value as? SessionListUiState.Loaded
        if (loaded == null) {
            // Rien a preserver (premier chargement ou etat d'erreur) : le chemin normal suffit.
            refresh()
            return
        }
        if (loaded.refreshing) return
        _state.value = loaded.copy(refreshing = true)
        scope.launch {
            val settings = store.current()
            try {
                val fresh = load(settings) as? SessionListUiState.Loaded
                _state.value = fresh?.copy(refreshing = false) ?: fresh ?: loaded.copy(refreshing = false)
            } catch (e: Exception) {
                // ⚠️ On CONSERVE la liste : un rafraichissement rate ne doit pas faire
                // disparaitre ce qu'on lisait. L'erreur se dit a part.
                _state.value = loaded.copy(refreshing = false)
                _sessionError.value = ConnectionErrors.describe(e)
            }
        }
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
        val sessions = gateway.allSessions(settings)
        // ⚠️ On compte les approbations en attente **a chaque chargement de la liste** : c'est le
        // moment ou l'utilisateur regarde l'app, donc celui ou le badge doit etre juste. Un
        // echec est ignore (`getOrElse`) : le badge est un confort, pas une fonction critique.
        _pendingApprovals.value =
            runCatching { gateway.pendingPermissions(settings).size }.getOrElse { 0 }
        if (sessions.isEmpty()) return SessionListUiState.Empty(settings.directory)
        // L'arbre : chaque parent suivi de ses sous-agents (67 % des sessions reelles).
        val items = SessionListMapper.toTree(sessions)
        // Modeles et agents sont secondaires : leur echec ne doit pas masquer la liste.
        val models = runCatching { gateway.models(settings) }.getOrDefault(emptyList())
        val agents = runCatching { gateway.agents(settings) }.getOrDefault(emptyList())
        return SessionListUiState.Loaded(
            items = items,
            models = models,
            agents = agents,
            usage = aggregateUsage(sessions),
            createModel = defaultModel(sessions, models),
        )
    }

    /**
     * Agrege la consommation sur les sessions chargees.
     *
     * ⚠️ On calcule sur ce qu'on a **reellement recu** (et on dit combien), jamais sur une
     * estimation : un chiffre invente dans un cockpit est pire que pas de chiffre.
     * Le cache est compte a part car il represente 94,7 % du volume reel du profil.
     */
    private fun aggregateUsage(sessions: List<Session>): UsageInfo? {
        var cost = 0.0
        var tIn = 0L
        var tOut = 0L
        var cache = 0L
        for (s in sessions) {
            cost += s.cost ?: 0.0
            s.tokens?.let {
                tIn += it.input
                tOut += it.output
                cache += it.cache.read
            }
        }
        val usage = UsageInfo(cost, sessions.size, tIn, tOut, cache)
        return if (usage.hasAny) usage else null
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

    // ------------------------------------------------------------------
    // Options de session (renommer, fork, compacter, supprimer)
    // ------------------------------------------------------------------

    /**
     * Renomme une session (`PATCH`).
     *
     * ⚠️ On recharge la liste ensuite : le titre est une donnee **serveur**, on ne le devine
     * pas localement. Si l'appel echoue, rien ne change a l'ecran (pas de renommage fantome).
     */
    fun renameSession(sessionID: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        scope.launch {
            val settings = store.current()
            runCatching { gateway.renameSession(settings, sessionID, clean) }
                .onSuccess { refreshAfterCreate() }
                .onFailure { error -> _sessionError.value = ConnectionErrors.describe(error) }
        }
    }

    /**
     * Forke une session (`POST /fork`) puis recharge la liste : la copie apparait en haut.
     */
    fun forkSession(sessionID: String) {
        scope.launch {
            val settings = store.current()
            runCatching { gateway.forkSession(settings, sessionID) }
                .onSuccess { refreshAfterCreate() }
                .onFailure { error -> _sessionError.value = ConnectionErrors.describe(error) }
        }
    }

    /** Interrompt le tour en cours (`POST /interrupt`), puis recharge (l'etat a change). */
    fun interruptSession(sessionID: String) {
        scope.launch {
            val settings = store.current()
            runCatching { gateway.interrupt(settings, sessionID) }
                .onSuccess { refreshAfterCreate() }
                .onFailure { error -> _sessionError.value = ConnectionErrors.describe(error) }
        }
    }

    /**
     * Compacte le contexte (`POST /compact`).
     *
     * ⚠️ Operation **longue et asynchrone** : l'appel est accepte, le resume arrive ensuite par
     * le flux. On ne recharge donc pas immediatement (rien n'a encore change) — l'utilisateur
     * le verra dans la conversation.
     */
    fun compactSession(sessionID: String) {
        scope.launch {
            val settings = store.current()
            runCatching { gateway.compactSession(settings, sessionID) }
                .onFailure { error -> _sessionError.value = ConnectionErrors.describe(error) }
        }
    }

    /**
     * Supprime une session (`DELETE`).
     *
     * ⚠️ **Irreversible** : c'est l'appelant qui doit avoir demande confirmation. Le ViewModel
     * ne supprime que sur ordre explicite.
     */
    fun deleteSession(sessionID: String) {
        scope.launch {
            val settings = store.current()
            runCatching { gateway.deleteSession(settings, sessionID) }
                .onSuccess { refreshAfterCreate() }
                .onFailure { error -> _sessionError.value = ConnectionErrors.describe(error) }
        }
    }

    /** Efface l'erreur d'action (apres l'avoir montree). */
    fun clearSessionError() {
        _sessionError.value = null
    }

    private fun Model.toRef(): ModelRef =
        ModelRef(id = modelID ?: id, providerID = providerID.orEmpty())

    private fun Model.matches(ref: ModelRef): Boolean =
        (modelID ?: id) == ref.id && providerID == ref.providerID
}
