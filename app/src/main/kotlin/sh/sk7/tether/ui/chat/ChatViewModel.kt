package sh.sk7.tether.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.event.ConnectionState
import sh.sk7.tether.data.event.EventSource
import sh.sk7.tether.data.event.OcEvent
import sh.sk7.tether.data.repository.EventReducer
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.AwaitingGraceMillis
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.SessionStatus
import sh.sk7.tether.domain.model.SessionUiState
import sh.sk7.tether.ui.settings.ConnectionErrors

/**
 * Phase d'envoi, pilote l'affordance de saisie.
 *
 * - [Sending] : le `POST /prompt` est en vol (rien d'autre n'est vrai encore).
 * - [Awaiting] : le prompt est **accepte**, on attend le flux. ⚠️ C'est un etat **stable et
 *   reessayable** : un prompt accepte ne garantit pas qu'un evenement arrive (Review Focus
 *   n°5 — reseau coupe apres acceptation). L'UI ne doit jamais rester bloquee en [Sending].
 * - [Streaming] : des evenements de la session sont arrives, le tour est en cours.
 * - [Error] : le prompt a echoue, on peut reessayer.
 */
enum class UiPhase { Idle, Sending, Awaiting, Streaming, Error }

/** Etat complet de l'ecran de chat. */
data class ChatUiState(
    val sessionID: String,
    val title: String? = null,
    val chat: SessionUiState,
    val phase: UiPhase = UiPhase.Idle,
    val error: String? = null,
) {
    val isBusy: Boolean get() = phase == UiPhase.Sending || phase == UiPhase.Streaming
}

/**
 * Construit une [EventSource] pour les reglages courants.
 *
 * `EventStream` depend de l'URL de base et des identifiants, qui changent avec les reglages :
 * on le construit donc **a la demande** plutot qu'a l'injection. C'est aussi la couture qui
 * rend le ViewModel testable en JVM (on injecte une fausse source).
 *
 * `suspend` : les identifiants sont resolus via le `CredentialsProvider` partage au moment
 * de la connexion.
 */
fun interface EventStreamFactory {
    suspend fun create(settings: ConnectionSettings): EventSource
}

/**
 * Detient l'etat de l'ecran de chat et l'alimente de deux sources :
 * - le **flux SSE** ([EventSource]) pour le direct, filtre sur la session affichee ;
 * - le **REST** ([OpenCodeGateway]) a chaque connexion et reconnexion, seule source de
 *   verite de l'historique (le flux n'a pas de `Last-Event-ID`, spec §4.2).
 *
 * `EventReducer` reste pur : ce ViewModel est le seul detenteur de l'etat.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    private val streamFactory: EventStreamFactory,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
    /** Delai sans evenement avant de rendre la main (etat stable, reessayable). */
    @param:AwaitingGraceMillis
    private val awaitingGraceMillis: Long = DEFAULT_AWAITING_GRACE_MILLIS,
) : ViewModel() {

    val sessionID: String = checkNotNull(savedStateHandle.get<String>("sessionID")) {
        "sessionID manquant dans la route de navigation"
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(
        ChatUiState(sessionID = sessionID, chat = SessionUiState(sessionID = sessionID)),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var settings: ConnectionSettings? = null
    private var graceJob: Job? = null

    init {
        start()
    }

    override fun onCleared() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    private fun start() {
        scope.launch {
            val loaded = store.current()
            settings = loaded
            if (!loaded.isConfigured) {
                _state.update { it.copy(phase = UiPhase.Error, error = "Aucun serveur configuré.") }
                return@launch
            }
            connect(loaded)
            resync()
        }
    }

    /**
     * Ouvre le flux et branche la resynchronisation REST sur chaque reconnexion.
     *
     * ⚠️ Le flux est **global** : on ne reduit que les evenements de cette session. Le
     * reducer filtre deja, mais on filtre aussi ici (le ViewModel ne doit rien recevoir
     * d'une autre session).
     */
    private suspend fun connect(loaded: ConnectionSettings) {
        val source = streamFactory.create(loaded)

        source.connect()
            .onEach { event ->
                if (event.sessionID != sessionID) return@onEach
                applyEvent(event)
            }
            .catch { /* le flux ne doit jamais tuer l'ecran ; la reconnexion est interne */ }
            .launchIn(scope)

        source.state
            .filter { it == ConnectionState.Connected }
            .onEach { resync() }
            .launchIn(scope)
    }

    private fun applyEvent(event: OcEvent) {
        val reduced = EventReducer.reduce(_state.value.chat, event)
        val running = reduced.status == SessionStatus.Running
        _state.update { current ->
            current.copy(
                chat = dedupeOptimistic(reduced),
                phase = when {
                    running -> UiPhase.Streaming
                    event.type.startsWith("session.execution.") -> UiPhase.Awaiting
                    else -> current.phase
                },
                error = null,
            )
        }
        if (reduced.status == SessionStatus.Running) armGrace() else cancelGrace()
    }

    /**
     * Retire les messages optimistes desormais confirmes par une source serveur.
     *
     * Le prompt reste affiche immediatement, mais l'evenement `session.inbox.enqueued` (ou la
     * resync REST) ajoute le **meme** message avec son vrai id : sans ce nettoyage, l'ecran
     * montre deux fois le message de l'utilisateur (constate sur le Pixel).
     */
    private fun dedupeOptimistic(chat: SessionUiState): SessionUiState {
        if (chat.messages.none { it.id.startsWith(OPTIMISTIC_PREFIX) }) return chat
        val confirmed = chat.messages.filterNot { it.id.startsWith(OPTIMISTIC_PREFIX) }
        val kept = chat.messages.filter { local ->
            !local.id.startsWith(OPTIMISTIC_PREFIX) ||
                confirmed.none { it.role == Role.User && it.text == local.text }
        }
        return if (kept.size == chat.messages.size) chat else chat.copy(messages = kept)
    }

    /**
     * Recharge l'historique depuis le REST (verite de l'etat).
     *
     * Les messages optimistes (envoyes localement mais pas encore presents cote serveur) sont
     * conserves, dedupliques par texte pour ne pas afficher deux fois le meme message.
     */
    fun resync() {
        val current = settings ?: return
        scope.launch {
            try {
                val dtos = gateway.allMessages(current, sessionID)
                val fromRest = ChatMessageMapper.fromDtos(dtos)
                _state.update { state ->
                    val optimistic = state.chat.messages.filter { local ->
                        local.id.startsWith(OPTIMISTIC_PREFIX) &&
                            fromRest.none { it.role == Role.User && it.text == local.text }
                    }
                    state.copy(chat = state.chat.copy(messages = fromRest + optimistic))
                }
                loadTitle(current)
            } catch (e: Exception) {
                // Une resync ratee ne doit pas effacer ce qui est deja affiche.
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    private suspend fun loadTitle(current: ConnectionSettings) {
        if (_state.value.title != null) return
        runCatching { gateway.session(current, sessionID) }
            .getOrNull()
            ?.title
            ?.takeIf { it.isNotBlank() }
            ?.let { title -> _state.update { it.copy(title = title) } }
    }

    // ------------------------------------------------------------------
    // Envoi
    // ------------------------------------------------------------------

    /**
     * Envoie un prompt.
     *
     * L'acceptation (`{data: msg_*}`) est un **item d'inbox**, pas une reponse : la reponse
     * arrive ensuite par le flux. On passe donc a [UiPhase.Awaiting] des l'acceptation, et
     * on ne reste jamais bloque en [UiPhase.Sending].
     */
    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return

        val optimistic = ChatMessage(id = "$OPTIMISTIC_PREFIX${optimisticCounter++}", role = Role.User, text = body)
        _state.update {
            it.copy(
                chat = it.chat.copy(messages = it.chat.messages + optimistic),
                phase = UiPhase.Sending,
                error = null,
            )
        }
        armGrace()

        scope.launch {
            try {
                // Les reglages sont resolus ici (et non au constructeur) : un envoi ne doit pas
                // echouer parce que le chargement initial du DataStore n'est pas termine.
                val current = settings ?: store.current().also { settings = it }
                if (!current.isConfigured) {
                    cancelGrace()
                    _state.update {
                        it.copy(
                            chat = it.chat.copy(messages = it.chat.messages - optimistic),
                            phase = UiPhase.Error,
                            error = "Aucun serveur configuré.",
                        )
                    }
                    return@launch
                }
                gateway.prompt(current, sessionID, body)
                // Accepte : etat stable. Le flux peut ne jamais livrer (reseau coupe).
                _state.update { if (it.phase == UiPhase.Sending) it.copy(phase = UiPhase.Awaiting) else it }
                cancelGrace()
            } catch (e: Exception) {
                cancelGrace()
                _state.update {
                    it.copy(
                        chat = it.chat.copy(messages = it.chat.messages - optimistic),
                        phase = UiPhase.Error,
                        error = ConnectionErrors.describe(e),
                    )
                }
            }
        }
    }

    /** Stoppe l'execution en cours cote serveur, puis revient a un etat stable. */
    fun stop() {
        val current = settings ?: return
        cancelGrace()
        _state.update { it.copy(phase = UiPhase.Awaiting) }
        scope.launch {
            runCatching { gateway.interrupt(current, sessionID) }
        }
    }

    // ------------------------------------------------------------------
    // Garde-fou de stabilisation
    // ------------------------------------------------------------------

    /**
     * Arme un delai : si aucun evenement n'arrive alors que l'on est en [UiPhase.Sending]
     * (le POST ne repond pas) ou en [UiPhase.Streaming] (le flux se tait), on revient a
     * [UiPhase.Awaiting]. La phase n'est jamais bloquee indefiniment.
     */
    private fun armGrace() {
        cancelGrace()
        graceJob = scope.launch {
            delay(awaitingGraceMillis)
            _state.update { state ->
                when (state.phase) {
                    UiPhase.Sending, UiPhase.Streaming -> state.copy(phase = UiPhase.Awaiting)
                    else -> state
                }
            }
        }
    }

    private fun cancelGrace() {
        graceJob?.cancel()
        graceJob = null
    }

    private var optimisticCounter = 0

    companion object {
        /** Delai sans evenement avant de rendre la main (ms). */
        const val DEFAULT_AWAITING_GRACE_MILLIS: Long = 20_000

        /** Prefixe des messages locaux en attente de confirmation REST. */
        const val OPTIMISTIC_PREFIX: String = "local-"
    }
}
