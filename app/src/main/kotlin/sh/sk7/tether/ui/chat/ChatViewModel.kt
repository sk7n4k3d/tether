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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.PromptBody
import sh.sk7.tether.data.api.SkillDto
import sh.sk7.tether.data.activity.ActivityMonitor
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
import sh.sk7.tether.domain.model.Activity
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

    /**
     * **Information neutre a montrer a l'utilisateur**, distincte d'une erreur.
     *
     * ⚠️ Pourquoi un champ a part et pas [error] : « rien ne bloquait, l'appel etait sans effet »
     * n'est **pas** une panne. La ranger avec les erreurs ferait afficher en rouge un resultat
     * normal, et banaliserait la couleur d'alerte qui doit rester rare.
     */
    val notice: String? = null,

    /** Ids des messages en file dont l'annulation est en vol (desactive leur bouton). */
    val cancelling: Set<String> = emptySet(),

    /**
     * Les fichiers joints au prochain envoi, **pas encore envoyes**.
     *
     * ⚠️ Ils vivent dans l'etat et pas dans un `remember` d'ecran : un changement de
     * configuration (rotation) ne doit pas perdre ce que l'utilisateur vient de joindre. C'est
     * exactement le genre de perte qu'on ne remarque qu'apres avoir appuye sur Envoyer.
     */
    val attachments: List<PendingAttachment> = emptyList(),
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
    private val activity: ActivityMonitor,
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
     * **Les modèles et agents disponibles**, pour le sélecteur d'envoi.
     *
     * ⚠️ Meme regle que les commandes : charge une fois, echec silencieux. Un selecteur vide
     * degrade l'experience, il ne la casse pas.
     */
    private val _models = MutableStateFlow<List<Model>>(emptyList())
    val models: StateFlow<List<Model>> = _models.asStateFlow()

    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()

    /**
     * **L'activite de CETTE session, vue par le detenteur partage.**
     *
     * ⚠️ Distincte de [ChatUiState.phase] : la phase vient du flux SSE, donc elle ne sait rien
     * d'un tour deja en cours **avant** l'ouverture de l'ecran ou pendant une coupure du flux. Le
     * detenteur partage interroge le serveur periodiquement, donc il sait. C'est ce qui fait
     * apparaitre l'action « passer en arriere-plan » sur une session qu'on vient d'ouvrir.
     *
     * ⚠️ **Un `val`, jamais un `get()`** : un accesseur qui reconstruirait le flux a chaque
     * lecture abonnerait une collection de plus a chaque recomposition — fuite garantie, et
     * autant d'abonnements que de frames.
     */
    val sessionActivity: StateFlow<Activity?> = activity.state
        .map { it.bySession[sessionID]?.activity }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Les competences disponibles (`GET /api/skill`), pour les activer dans cette session.
     *
     * ⚠️ Meme regle que les commandes et les modeles : charge une fois, echec silencieux. Ne pas
     * avoir la liste ne doit pas empecher d'ecrire — et un selecteur vide se dit, il ne casse rien.
     */
    private val _skills = MutableStateFlow<List<SkillDto>>(emptyList())
    val skills: StateFlow<List<SkillDto>> = _skills.asStateFlow()

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
        // ⚠️ On s'abonne au détenteur d'état partagé : sans cela, ouvrir le chat **directement**
        // (deep link de notification) laisserait `sessionActivity` vide, puisque aucun autre écran
        // ne fait tourner la boucle. Le bouton « arrière-plan » ne saurait alors pas qu'un tour
        // est en cours.
        activity.acquire()
        // ⚠️ Charge avant tout envoi : l'utilisateur peut taper `/` des la premiere seconde, et
        // un selecteur vide a ce moment-la ferait croire que le serveur n'a aucune commande.
        loadReferenceData()

        start()
    }

    override fun onCleared() {
        // ⚠️ Toujours relâcher, même si l'écran est détruit par une erreur : un abonnement qui
        // fuit laisserait la boucle allumée pour toute la vie du processus.
        activity.release()
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
            // ⚠️ On marque APRES la resync : c'est elle qui charge l'état de la session, dont on
            // lit ensuite l'`idle` pour le marquage.
            markSessionViewed()
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
            runCatching { gateway.skills(current) }.onSuccess { _skills.value = it }
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

    /**
     * **Active une competence dans cette session** (`POST /experimental/session/{id}/skill`).
     *
     * ⚠️ C'est un **chemin different** d'une piece `skills` de prompt : ici la competence est
     * attachee a la session et l'execution reprend, alors qu'une piece de prompt ne vaut que pour
     * le tour envoye. Comme l'agent et le modele, c'est un reglage **persistant** — et c'est pour
     * ca qu'on le dit a l'utilisateur au lieu de le faire en silence.
     *
     * ⚠️ L'effet est **asynchrone** : la competence arrive comme message par le flux. On ne peut
     * donc pas afficher un etat local « activee » qui pretendrait connaitre le resultat avant le
     * serveur.
     */
    fun activateSkill(skillID: String) {
        scope.launch {
            try {
                val current = settings ?: store.current().also { settings = it }
                if (gateway.activateSkill(current, sessionID, skillID)) {
                    _state.update { if (it.phase == UiPhase.Idle) it.copy(phase = UiPhase.Awaiting) else it }
                } else {
                    _state.update { it.copy(error = "Le serveur a refusé d'activer « $skillID ».") }
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
                // ⚠️ La file est lue **en plus** de l'historique, et pas dedans : mesure du
                // 2026-09-25, un message en file n'apparait PAS dans `GET /message` (une session
                // a deux prompts en file rend `count 0`). Sans cette lecture, un message en
                // attente anterieur a l'ouverture de l'app serait invisible — alors que c'est le
                // cas principal, puisque l'inbox vit pendant que le serveur travaille.
                val inbox = runCatching { gateway.sessionInbox(current, sessionID) }
                    .getOrDefault(emptyList())
                val queued = ChatMessageMapper.fromInboxItems(inbox)
                _state.update { state ->
                    // ⚠️ On ne remplace PAS tout : les messages plus anciens deja charges par
                    // [loadOlder] doivent survivre a une resync, sinon remonter puis perdre la
                    // connexion effacerait l'historique qu'on vient de charger.
                    // ⚠️ Ordre : [anciens charges] + [fenetre recente du REST]. Les anciens
                    // sont ceux que [loadOlder] a remontes ; ils gardent leur place en tete.
                    val alreadyOlder = state.chat.messages.filter { it.id !in restIds }
                    // ⚠️ Ordre : on **fusionne la file d'abord**, puis on déduplique. L'item
                    // d'inbox porte l'id serveur du message envoyé (`accepted.id`), donc le faire
                    // entrer avant [dedupeOptimistic] permet à l'optimiste d'être reconnu par id —
                    // l'inverse laisserait temporairement les deux à l'écran.
                    val merged = state.chat.copy(messages = alreadyOlder + fromRest)
                    val withInbox = ChatMessageMapper.mergeInbox(merged.messages, queued)
                    state.copy(
                        chat = dedupeOptimistic(merged.copy(messages = withInbox)),
                        hasOlder = state.hasOlder || dtos.size >= ChatWindow.SERVER_PAGE,
                    )
                }
                // ⚠️ On pousse le compte de file au détenteur d'état partagé : c'est la seule
                // session dont on connait l'inbox, et c'est ce qui fait vivre la priorité
                // « en file » de l'en-tête (voir `ActivityMonitor.publishQueue`).
                activity.publishQueue(sessionID, queued.size)
                loadMeta(current)
            } catch (e: Exception) {
                // Une resync ratee ne doit pas effacer ce qui est deja affiche.
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    /**
     * **Recharge seulement la file d'attente** (`GET /inbox`).
     *
     * ⚠️ Chemin leger et distinct de [resync] : on ne recharge tout l'historique que pour savoir
     * ce qui attend. Appele apres l'envoi d'un prompt (le message part en file) et apres une
     * annulation (le serveur est la verite, on ne devine pas l'effet localement).
     */
    fun refreshQueue() {
        scope.launch {
            val current = settings ?: store.current().also { settings = it }
            if (!current.isConfigured) return@launch
            runCatching { gateway.sessionInbox(current, sessionID) }
                .onSuccess { inbox ->
                    val queued = ChatMessageMapper.fromInboxItems(inbox)
                    _state.update { state ->
                        val withInbox = ChatMessageMapper.mergeInbox(state.chat.messages, queued)
                        state.copy(chat = dedupeOptimistic(state.chat.copy(messages = withInbox)))
                    }
                    activity.publishQueue(sessionID, queued.size)
                }
                .onFailure { e ->
                    android.util.Log.w("TetherChat", "lecture de la file impossible", e)
                }
        }
    }

    /**
     * **Annule un message en file** (`DELETE /session/{id}/inbox/{inboxID}`).
     *
     * ⚠️ Reponse directe a l'issue #4821 (126 👍) : un message soumis pendant que l'agent tourne
     * part en file et, sans cette route, **ne peut plus etre annule**. C'est exactement le
     * probleme qu'un client compagnon doit resoudre.
     *
     * ⚠️ On retire le message **optimistement** (l'appel est en vol) puis on **relit la file** :
     * le serveur est la verite, et une annulation refusee doit faire revenir le message plutot que
     * de laisser croire a un succes. ⚠️ `DELETE` rend **204 meme sur un id inconnu** (mesure), donc
     * un `isSuccess` ne prouve pas que l'entree existait : c'est la relecture qui tranche.
     */
    fun cancelQueued(inboxID: String) {
        if (inboxID in _state.value.cancelling) return
        _state.update { it.copy(cancelling = it.cancelling + inboxID) }
        scope.launch {
            val current = settings ?: store.current().also { settings = it }
            val ok = runCatching { gateway.dismissInbox(current, sessionID, inboxID) }.getOrDefault(false)
            _state.update { state ->
                val kept = if (ok) {
                    state.chat.messages.filterNot { it.id == inboxID }
                } else {
                    state.chat.messages
                }
                state.copy(
                    chat = state.chat.copy(messages = kept),
                    cancelling = state.cancelling - inboxID,
                    notice = if (ok) "Message retiré de la file." else "Annulation refusée par le serveur.",
                )
            }
            refreshQueue()
        }
    }

    /** Efface l'information neutre (apres l'avoir montree). */
    fun clearNotice() = _state.update { it.copy(notice = null) }

    /**
     * **Deplace les outils bloquants en observation d'arriere-plan**
     * (`POST /session/{id}/background`).
     *
     * ⚠️ Mesure du 2026-09-25 : la route repond **204** sur une session existante et **404** sur
     * une session inconnue — elle est saine, contrairement a `POST /shell` qui rend **500** sur ce
     * serveur. On peut donc l'exposer.
     *
     * ⚠️ **C'est un no-op si rien ne bloque** (doc serveur : « Idle requests are a no-op »). On le
     * dit a l'utilisateur au lieu de le laisser croire a un echec : une action sans effet n'est pas
     * une panne, et l'annoncer en rouge serait faux.
     */
    fun backgroundTools() {
        scope.launch {
            val current = settings ?: store.current().also { settings = it }
            if (!current.isConfigured) {
                _state.update { it.copy(error = "Aucun serveur configuré.") }
                return@launch
            }
            runCatching { gateway.backgroundTools(current, sessionID) }
                .onSuccess { ok ->
                    _state.update {
                        it.copy(
                            notice = if (ok) {
                                "Outils déplacés en arrière-plan s'il y en avait."
                            } else {
                                "Le serveur a refusé la mise en arrière-plan."
                            },
                        )
                    }
                }
                .onFailure { e ->
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
     * Envoie un prompt, **avec les pieces jointes en attente**.
     *
     * L'acceptation (`{data: msg_*}`) est un **item d'inbox**, pas une reponse : la reponse
     * arrive ensuite par le flux. On passe donc a [UiPhase.Awaiting] des l'acceptation, et
     * on ne reste jamais bloque en [UiPhase.Sending].
     *
     * ⚠️ On garde le chemin **sans corps** quand il n'y a rien a joindre : un texte seul continue
     * de partir par le prompt minimal, et surtout les fakes de test qui n'exercent que le texte
     * restent valides. Le corps explicite n'est emis **que** s'il porte quelque chose de plus.
     *
     * ⚠️ Les pieces jointes ne sont retirees qu'en cas de **succes**. Si l'envoi echoue, elles
     * restent attachees : l'utilisateur reessaie sans avoir a re-joindre ses fichiers — les perdre
     * sur un echec reseau serait une double punition.
     */
    fun send(text: String) {
        val body = text.trim()
        val attachments = _state.value.attachments
        // ⚠️ Un message **vide** avec un fichier joint est un envoi legitime : « regarde ceci »
        // se dit par la piece jointe seule. Sans cette exception, on ne pourrait rien envoyer
        // sans accompagner le fichier d'une phrase.
        if (body.isEmpty() && attachments.isEmpty()) return

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
                val accepted = if (attachments.isEmpty()) {
                    gateway.prompt(current, sessionID, body)
                } else {
                    gateway.prompt(
                        current,
                        sessionID,
                        PromptBody(
                            // ⚠️ Le serveur exige `text` meme non vide dans son schema, mais un
                            // texte vide avec fichier a ete accepte en pratique ; on envoie tel
                            // quel, c'est le cas « regarde ceci ».
                            text = body,
                            files = attachments.map { it.toWire() },
                        ),
                    )
                }
                // ⚠️ `accepted.id` EST l'id REST du message utilisateur : on le retient pour
                // dedupliquer par id (et non par texte) des sa prochaine apparition.
                acceptedOptimistic[optimistic.id] = accepted.id
                // Accepte : etat stable. Le flux peut ne jamais livrer (reseau coupe).
                _state.update {
                    val cleared = if (attachments.isEmpty()) it else it.copy(attachments = emptyList())
                    if (cleared.phase == UiPhase.Sending) cleared.copy(phase = UiPhase.Awaiting) else cleared
                }
                // L'inbox a pu arriver avant l'acceptation : on reconcilie maintenant.
                _state.update { it.copy(chat = dedupeOptimistic(it.chat)) }
                // ⚠️ Un prompt envoye pendant que le serveur tourne part en **file** : on relit
                // l'inbox pour afficher son mode (`steer` / `queue`) sans attendre un evenement
                // qui, si le flux est coupe, n'arrivera jamais.
                refreshQueue()
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
    // Pieces jointes
    // ------------------------------------------------------------------

    /**
     * Ajoute un fichier **du telephone** aux pieces jointes du prochain envoi.
     *
     * ⚠️ Le contenu est lu **tout de suite**, pas au moment de l'envoi : si l'utilisateur deplace
     * ou renomme le fichier entre-temps — ou si l'URI de contenu expire (ce qui arrive avec les
     * documents recents sous Android) — l'envoi echouerait apres coup, sans lien visible avec le
     * geste qui a echoue.
     *
     * ⚠️ On refuse les fichiers trop gros **ici**, avec un message : le serveur accepterait 4 Mo
     * (mesure), l'app se fixe la meme borne. Un echec silencieux au moment de l'envoi serait pire.
     */
    fun attachBytes(name: String, mime: String?, bytes: ByteArray) {
        val attachment = PromptAttachments.fromBytes(name, mime, bytes)
        if (attachment == null) {
            _state.update {
                it.copy(
                    error = "« $name » dépasse ${PromptAttachments.formatSize(PromptAttachments.MAX_FILE_BYTES)}.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                attachments = it.attachments + PendingAttachment(
                    name = name,
                    sizeBytes = bytes.size,
                    uri = attachment.uri,
                ),
                error = null,
            )
        }
    }

    /**
     * Ajoute un fichier **du serveur**, par chemin absolu.
     *
     * ⚠️ Passe par `file://` : le serveur lit le fichier lui-meme. C'est utile quand on a deja
     * l'explorateur ouvert et qu'on ne veut pas transporter le contenu par le telephone.
     */
    fun attachServerPath(path: String) {
        val attachment = PromptAttachments.fromServerPath(path)
        if (attachment == null) {
            _state.update { it.copy(error = "Chemin non absolu : « $path ».") }
            return
        }
        _state.update {
            it.copy(
                attachments = it.attachments + PendingAttachment(
                    name = attachment.name ?: path,
                    sizeBytes = 0,
                    uri = attachment.uri,
                ),
                error = null,
            )
        }
    }

    /** Retire une piece jointe en attente. Le nom affiche sert d'identite. */
    fun removeAttachment(name: String) {
        _state.update { current ->
            current.copy(attachments = current.attachments.filterNot { it.name == name })
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

    /**
     * **Marque la session comme vue, jusqu'à son dernier `idle`.**
     *
     * ### Pourquoi c'est l'app qui doit le faire
     * ⚠️ `session.time.viewed` n'est **jamais** mis à jour par le serveur : c'est une écriture que
     * le client déclenche. Sans cet appel, toutes les sessions restent « terminé, pas vu » **à
     * jamais**, et l'indicateur qu'on vient de construire perdrait tout son sens — il signalerait
     * un travail vieux de trois jours comme s'il venait d'arriver.
     *
     * ### Ce qu'on envoie, et pourquoi
     * ⚠️ On envoie l'`idle` **du serveur**, pas l'heure locale. C'est ce qui rend la comparaison
     * « terminé / pas vu » juste : le serveur compare deux grandeurs de même origine. Envoyer
     * l'horloge du téléphone ferait apparaître ou disparaître un « pas vu » selon le fuseau.
     *
     * ⚠️ **On ne marque pas si le tour est en cours.** `idle` serait celui du tour *précédent*, et
     * le marquer comme vu ferait disparaître l'annonce du tour **en cours** dès qu'il se
     * terminerait — on aurait marqué vu quelque chose qu'on n'a pas vu.
     *
     * ⚠️ Un échec est **silencieux** : ne pas pouvoir marquer vu ne doit pas empêcher de lire la
     * conversation. C'est un indicateur, pas une fonction critique.
     */
    private fun markSessionViewed() {
        scope.launch {
            val current = settings ?: return@launch
            runCatching {
                val info = gateway.session(current, sessionID)
                val idle = info.time?.idle
                // Pas d'`idle` = le tour n'est pas terminé : rien à marquer, et surtout pas
                // l'`idle` précédent.
                if (idle != null) {
                    gateway.markViewed(current, sessionID, idle)
                }
                idle
            }.onSuccess { idle ->
                if (idle != null) {
                    // ⚠️ On previent le detenteur d'etat : le badge « termine » de la liste doit
                    // disparaitre tout de suite, sans attendre le prochain cycle d'interrogation.
                    activity.notifyViewed(sessionID, idle)
                }
            }.onFailure { e ->
                android.util.Log.w("TetherChat", "marquage vu impossible", e)
            }
        }
    }
}
