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
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.event.ConnectionState
import sh.sk7.tether.data.event.EventSource
import sh.sk7.tether.data.event.EventSourceFactory
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
    /**
     * **L'en-tete d'instrument** : modele, agent, provider, date de debut.
     *
     * ⚠️ Le `design-soul.md` §5 l'exige : « En-tete de session : modele, agent, cout cumule en
     * direct, tokens, statut ». La liste des sessions le montre deja ; le chat l'oubliait, alors
     * que c'est l'ecran ou l'on passe le plus de temps. Un cockpit qui ne dit ce qu'il pilote
     * que sur la page d'accueil n'est pas un cockpit.
     */
    val meta: SessionMeta? = null,
    /**
     * Il reste des messages **plus anciens** non charges sur le serveur.
     *
     * ⚠️ On ne peut pas le savoir de facon exacte : l'API ne dit pas le total d'une session. On
     * l'**deduit** d'une page pleine — si le serveur a rendu exactement la taille demandee, il y
     * a probablement une suite. C'est une heuristique, et quand elle se trompe on affiche une
     * ligne « charger plus » qui ne charge rien, ce qui est un echec benin ; l'inverse (croire
     * qu'il n'y a plus rien) ferait disparaitre l'historique.
     */
    /**
     * Le modele choisi par l'utilisateur dans cette session.
     *
     * ⚠️ Affiche **a cote** du modele reel de l'en-tete, jamais a sa place : le modele reel vient
     * du serveur et fait foi. Afficher l'override seul ferait disparaitre ce que la session
     * utilise vraiment si le changement a echoue.
     */
    val modelOverride: String? = null,

    /** L'agent choisi dans cette session, meme regle que [modelOverride]. */
    val agentOverride: String? = null,

    val hasOlder: Boolean = false,
    /** Un chargement de messages anciens est en cours (indicateur en haut de la liste). */
    val loadingOlder: Boolean = false,
) {
    val isBusy: Boolean get() = phase == UiPhase.Sending || phase == UiPhase.Streaming
}

/**
 * Metadonnees d'affichage d'une session, **telles que le serveur les donne**.
 *
 * ⚠️ Volontairement pauvre et sans calcul : chaque champ est un fait du serveur, jamais une
 * deduction. Un champ absent reste absent (on n'affiche pas « 0,00 $ » pour faire joli).
 */
data class SessionMeta(
    val model: String? = null,
    val provider: String? = null,
    val agent: String? = null,
    val startedAt: Long? = null,
)

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
    private val streamFactory: EventSourceFactory,
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

    /**
     * **Les commandes slash du serveur** (`GET /api/command`, 28 mesurees).
     *
     * ⚠️ Chargees une seule fois, pas a chaque frappe : la liste ne change pas pendant une
     * conversation, et la redemander a chaque caractere `/` serait un appel reseau par touche.
     * Un echec est **silencieux** : ne pas avoir les commandes ne doit pas empecher d'ecrire.
     */
    private val _commands = MutableStateFlow<List<CommandDto>>(emptyList())
    val commands: StateFlow<List<CommandDto>> = _commands.asStateFlow()

    /**
     * Les modeles et agents disponibles, pour le selecteur d'envoi.
     *
     * ⚠️ Meme regle que les commandes : charge une fois, echec silencieux. Un selecteur vide
     * degrade l'experience, il ne la casse pas.
     */
    private val _models = MutableStateFlow<List<Model>>(emptyList())
    val models: StateFlow<List<Model>> = _models.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    private var settings: ConnectionSettings? = null
    private var graceJob: Job? = null

    /**
     * Lien id-local (`local-N`) -> id serveur accepte (`msg_*`) pour chaque optimiste.
     *
     * `PromptAcceptance.id` **est** l'id REST du message utilisateur : c'est lui qui permet
     * une confirmation exacte, plutot qu'une comparaison de textes (voir [dedupeOptimistic]).
     */
    private val acceptedOptimistic = mutableMapOf<String, String>()

    /**
     * Ids serveur des messages utilisateur **deja vus** au moment ou l'optimiste est cree.
     *
     * ⚠️ Indispensable : le repli par texte ci-dessous ne doit **jamais** pouvoir consommer un
     * message **anterieur**. Concretement, si « ok » a deja ete envoye plus tot, un nouvel
     * « ok » se faisait confirmer par l'ancien message des que celui-ci arrivait du REST — et
     * le message frais **disparaisssait de l'ecran**. Mesure sur le Pixel, et reproduit par
     * `un ancien message de meme texte ne confirme pas un optimiste frais`.
     *
     * En snapshotant les ids au moment de l'envoi, seuls les messages **posterieurs** peuvent
     * confirmer l'optimiste : c'est la seule lecture honnete de « un message serveur identique
     * confirme un envoi ».
     */
    private val preexistingUserIds = mutableMapOf<String, Set<String>>()

    init {
        // ⚠️ Charge avant tout envoi : l'utilisateur peut taper `/` des la premiere seconde, et
        // un selecteur vide a ce moment-la ferait croire que le serveur n'a aucune commande.
        loadReferenceData()

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
     * La confirmation se fait **par id** : `PromptAcceptance.id` renvoye par `POST /prompt`
     * **est** l'id REST du message utilisateur (verifie sur le serveur : l'id accepte apparait
     * ensuite dans `GET /session/{id}/message`). [acceptedOptimistic] garde le lien
     * id-local -> id-serveur ; des que le message serveur de cet id existe, l'optimiste part.
     *
     * ⚠️ Ne JAMAIS dedupliquer principalement par texte : deux envois du meme texte avant
     * confirmation disparaitraient **ensemble** alors que les deux sont partis au serveur.
     *
     * Le repli par texte ne subsiste que pour un optimiste **pas encore accepte** (absent de
     * [acceptedOptimistic]) : on ne le retire que si un message serveur strictement identique
     * existe deja. C'est ce qui couvre l'arrivee de `session.inbox.enqueued` avant que
     * l'acceptation HTTP ne soit traitee.
     */
    private fun dedupeOptimistic(chat: SessionUiState): SessionUiState {
        val hasOptimistic = chat.messages.any { it.id.startsWith(OPTIMISTIC_PREFIX) }
        if (!hasOptimistic) return chat

        val serverMessages = chat.messages.filterNot { it.id.startsWith(OPTIMISTIC_PREFIX) }
        val serverIds = serverMessages.map { it.id }.toHashSet()

        // Compteur des messages serveur utilisateur par texte, **consomme** au fur et a mesure :
        // un message serveur confirme **un** optimiste, pas tous ceux qui partagent son texte.
        //
        // ⚠️ On exclut les messages **anterieurs a l'envoi** (snapshot pris dans `send`). Sans
        // ce filtre, un ancien « ok » confirmait un nouvel envoi « ok » des son arrivee du REST,
        // et le message frais disparaissait de l'ecran.
        val availableByText = serverMessages
            .filter { it.role == Role.User && it.id !in preexistingFor(chat) }
            .groupingBy { it.text }
            .eachCount()
            .toMutableMap()

        val kept = chat.messages.filter { message ->
            if (!message.id.startsWith(OPTIMISTIC_PREFIX)) return@filter true
            val acceptedId = acceptedOptimistic[message.id]
            if (acceptedId != null) {
                // Lien exact par id (cas nominal : inboxID == PromptAcceptance.id == id REST).
                acceptedId !in serverIds
            } else {
                // Course ou l'inbox arrive avant l'acceptation HTTP : on ne peut lier que par
                // texte, et on ne consomme **qu'une** occurrence (FIFO) pour ne pas emporter
                // un deuxieme envoi identique encore en attente.
                val remaining = availableByText[message.text] ?: 0
                if (remaining > 0) {
                    availableByText[message.text] = remaining - 1
                    false
                } else {
                    true
                }
            }
        }
        if (kept.size == chat.messages.size) return chat

        // Purge les liens des optimistes desormais retires (pas de croissance sans borne).
        val keptLocalIds = kept.filter { it.id.startsWith(OPTIMISTIC_PREFIX) }.map { it.id }.toHashSet()
        acceptedOptimistic.keys.retainAll(keptLocalIds)
        preexistingUserIds.keys.retainAll(keptLocalIds)
        return chat.copy(messages = kept)
    }

    /**
     * Ids serveur qui precedent les optimistes presents.
     *
     * ⚠️ Sans cela, un message **ancien** partageant le texte d'un nouvel envoi le confirmerait :
     * l'envoi frais serait retire a tort (verifie par test).
     */
    private fun preexistingFor(chat: SessionUiState): Set<String> =
        chat.messages
            .filter { it.id.startsWith(OPTIMISTIC_PREFIX) }
            .mapNotNull { preexistingUserIds[it.id] }
            .fold(emptySet<String>()) { acc, ids -> acc + ids }

    /**
     * Recharge l'historique depuis le REST (verite de l'etat).
     *
     * **Fusion par id**, pas ecrasement : le REST fait foi pour les ids qu'il porte, mais les
     * messages **absents** du REST sont conserves.
     *
     * ⚠️ Ecraser (`messages = fromRest`) perdrait les messages produits par le reducer que le
     * mapper ne sait pas reconstruire : repli brut de `session.message.content.updated`
     * ([EventReducer]), ou type de message inconnu du mapper. Sur une **reconnexion en milieu
     * de tour** (le scenario meme du brief), ils disparaitraient de l'ecran.
     *
     * Les optimistes sont ensuite reconcilies par [dedupeOptimistic] (par id).
     */
    /**
     * Charge les listes **de reference** de l'ecran : commandes, modeles, agents.
     *
     * ⚠️ Chaque appel est isole (`runCatching`) : un serveur qui ne repond pas a `/api/agent` ne
     * doit pas priver l'utilisateur de ses commandes slash. Les trois listes sont independantes.
     */
    private fun loadReferenceData() {
        scope.launch {
            val current = settings ?: store.current().also { settings = it }
            runCatching { gateway.commands(current) }.onSuccess { _commands.value = it }
            runCatching { gateway.models(current) }.onSuccess { _models.value = it }
            runCatching { gateway.agents(current) }.onSuccess { _agents.value = it }
        }
    }

    /**
     * Lance une **commande slash**, plutot que de l'envoyer comme texte.
     *
     * ⚠️ La distinction est reelle : le serveur valide le **nom** contre sa liste (28 mesurees).
     * Envoyer `/review` comme texte de prompt ne declenche rien du tout — l'agent le lirait comme
     * une phrase. C'est pour ca que ce chemin est separe de [send].
     */
    fun runCommand(name: String, text: String = "") {
        scope.launch {
            try {
                val current = settings ?: store.current().also { settings = it }
                val ok = gateway.runCommand(current, sessionID, name, text)
                if (!ok) {
                    _state.update { it.copy(error = "Commande « /$name » refusée par le serveur.") }
                    return@launch
                }
                // L'effet de la commande arrive par le flux, comme un prompt normal.
                _state.update { if (it.phase == UiPhase.Idle) it.copy(phase = UiPhase.Awaiting) else it }
            } catch (e: Exception) {
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    /**
     * Change le **modele de cette session** (`POST /session/{id}/model`).
     *
     * ⚠️ Le changement est **persistant** cote serveur : il vaut pour les tours suivants, pas
     * seulement pour le prochain message. C'est ce qui distingue ce reglage d'une selection
     * ponctuelle, et c'est pour ca qu'on le dit a l'utilisateur au lieu de le faire en silence.
     */
    fun setModel(model: ModelRef) {
        scope.launch {
            try {
                val current = settings ?: store.current().also { settings = it }
                if (gateway.setSessionModel(current, sessionID, model)) {
                    _state.update { it.copy(modelOverride = model.id) }
                } else {
                    _state.update { it.copy(error = "Le serveur a refusé le changement de modèle.") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    /** Change l'**agent de cette session** (`POST /session/{id}/agent`). */
    fun setAgent(agent: String) {
        scope.launch {
            try {
                val current = settings ?: store.current().also { settings = it }
                if (gateway.setSessionAgent(current, sessionID, agent)) {
                    _state.update { it.copy(agentOverride = agent) }
                } else {
                    _state.update { it.copy(error = "Le serveur a refusé le changement d'agent.") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    fun resync() {
        val current = settings ?: return
        scope.launch {
            try {
                // ⚠️ **Fenetre recente, pas tout l'historique.** `allMessages` pagine depuis le
                // debut : mesure sur le serveur, les sessions lourdes font **810 messages en
                // moyenne** et jusqu'a **2 040**. Charger tout a chaque ouverture et a chaque
                // reconnexion etait le principal cout de cet ecran. On demande donc les N
                // derniers, et le reste vient au scroll ([loadOlder]).
                val dtos = gateway.recentMessages(current, sessionID, ChatWindow.SERVER_PAGE)
                val fromRest = ChatMessageMapper.fromDtos(dtos)
                val restIds = fromRest.map { it.id }.toHashSet()
                _state.update { state ->
                    // ⚠️ On ne remplace PAS tout : les messages plus anciens deja charges par
                    // [loadOlder] doivent survivre a une resync, sinon remonter puis perdre la
                    // connexion effacerait l'historique qu'on vient de charger.
                    // ⚠️ Ordre : [anciens charges] + [fenetre recente du REST]. Les anciens
                    // sont ceux que [loadOlder] a remontes ; ils gardent leur place en tete.
                    val alreadyOlder = state.chat.messages.filter { it.id !in restIds }
                    val merged = state.chat.copy(messages = alreadyOlder + fromRest)
                    state.copy(
                        chat = dedupeOptimistic(merged),
                        hasOlder = state.hasOlder || dtos.size >= ChatWindow.SERVER_PAGE,
                    )
                }
                loadMeta(current)
            } catch (e: Exception) {
                // Une resync ratee ne doit pas effacer ce qui est deja affiche.
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    /**
     * Charge les metadonnees de session (titre + en-tete d'instrument).
     *
     * ⚠️ **Un seul appel** pour les deux : `loadTitle` faisait deja ce `GET` et jetait tout sauf
     * le titre. Le cout en direct, lui, vient du flux (`session.usage.updated`), pas d'ici.
     */
    private suspend fun loadMeta(current: ConnectionSettings) {
        runCatching { gateway.session(current, sessionID) }
            .getOrNull()
            ?.let { session ->
                _state.update {
                    it.copy(
                        title = session.title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                        meta = SessionMeta(
                            model = session.model?.id,
                            provider = session.model?.providerID,
                            agent = session.agent,
                            startedAt = session.time?.created,
                        ),
                    )
                }
            }
    }

    /**
     * Charge les **messages plus anciens** que ce qui est affiche (scroll vers le haut).
     *
     * ⚠️ On remonte depuis l'**id du message le plus ancien deja charge**, jamais depuis un
     * index : l'index local ne correspond pas a la position serveur, parce que les marqueurs de
     * tour (`idle`, `synthetic`…) ne produisent pas de bulle et sont filtres par le mapper.
     * Utiliser un index decalerait la fenetre a chaque cran.
     *
     * ⚠️ On **prepend** les anciens et on conserve le reste : la fusion se fait par id, comme la
     * resync, pour qu'un message arrive par le flux entre-temps ne soit jamais perdu.
     */
    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || !current.hasOlder) return
        val oldest = current.chat.messages.firstOrNull() ?: return
        val settings = settings ?: return

        _state.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val dtos = gateway.messagesBefore(settings, sessionID, oldest.id, ChatWindow.SERVER_PAGE)
                val older = ChatMessageMapper.fromDtos(dtos)
                _state.update { state ->
                    // Fusion par id : les anciens passent DEVANT, les existants sont conserves.
                    val known = state.chat.messages.map { it.id }.toHashSet()
                    val fresh = older.filter { it.id !in known }
                    state.copy(
                        chat = state.chat.copy(messages = fresh + state.chat.messages),
                        // Une tranche vide ou incomplete = on a atteint le debut de la session.
                        hasOlder = dtos.size >= ChatWindow.SERVER_PAGE,
                        loadingOlder = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loadingOlder = false, error = ConnectionErrors.describe(e)) }
            }
        }
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
        // ⚠️ Snapshot des messages utilisateur **deja presents** : seuls ceux qui arriveront
        // APRES cet envoi pourront le confirmer par texte. Sans ce garde-fou, un ancien message
        // de meme texte validait le nouvel envoi et le faisait disparaitre de l'ecran.
        preexistingUserIds[optimistic.id] = _state.value.chat.messages
            .filter { it.role == Role.User && !it.id.startsWith(OPTIMISTIC_PREFIX) }
            .map { it.id }
            .toHashSet()
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
                val accepted = gateway.prompt(current, sessionID, body)
                // ⚠️ `accepted.id` EST l'id REST du message utilisateur : on le retient pour
                // dedupliquer par id (et non par texte) des sa prochaine apparition.
                acceptedOptimistic[optimistic.id] = accepted.id
                // Accepte : etat stable. Le flux peut ne jamais livrer (reseau coupe).
                _state.update { if (it.phase == UiPhase.Sending) it.copy(phase = UiPhase.Awaiting) else it }
                // L'inbox a pu arriver avant l'acceptation : on reconcilie maintenant.
                _state.update { it.copy(chat = dedupeOptimistic(it.chat)) }
                cancelGrace()
            } catch (e: Exception) {
                cancelGrace()
                acceptedOptimistic.remove(optimistic.id)
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
