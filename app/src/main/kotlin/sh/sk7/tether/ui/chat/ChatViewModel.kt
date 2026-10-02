package sh.sk7.tether.ui.chat

import kotlinx.serialization.json.JsonObject
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.PermissionRequest
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.PromptBody
import sh.sk7.tether.data.api.SkillDto
import sh.sk7.tether.data.activity.ActivityMonitor
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.event.ConnectionState
import sh.sk7.tether.data.event.DeltaCoalescer
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
import sh.sk7.tether.domain.model.FormRequest
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.SessionStatus
import sh.sk7.tether.domain.model.SessionUiState
import sh.sk7.tether.ui.settings.ConnectionErrors
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

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
     *
     * ⚠️ **[meta] est la SEULE source de verite du modele et de l'agent a l'ecran.** Il y avait
     * des champs `modelOverride` / `agentOverride` « a afficher a cote » : ils etaient **ecrits
     * a chaque changement et jamais lus**, donc l'ecran continuait d'afficher la valeur chargee a
     * l'ouverture, et changer de modele paraissait ne rien faire. Un champ d'etat qu'on ecrit
     * n'actualise rien : apres un changement accepte, on **relit** la session (voir [loadMeta]).
     * Ne pas reintroduire ces champs.
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
    val hasOlder: Boolean = false,
    /** Un chargement de messages anciens est en cours (indicateur en haut de la liste). */
    val loadingOlder: Boolean = false,

    /**
     * **Curseur vers la tranche precedente**, `null` quand il n'y a plus rien.
     *
     * ⚠️ C'est lui qui rend [loadOlder] O(1) par tranche. Le jeter (ancien comportement)
     * obligeait a repartir du sommet a chaque cran : O(n²), ~200 requetes pour remonter une
     * session de 2 040 messages.
     *
     * ⚠️ **`null` est une preuve de fin d'historique** quand il provient de [loadOlder] : le
     * gateway sonde le curseur et ne le rend que si la page suivante contient vraiment
     * quelque chose.
     */
    val olderCursor: String? = null,

    /**
     * **On a atteint le debut de la session, et c'est prouve.**
     *
     * ⚠️ Pourquoi ce drapeau separe, alors que [olderCursor] `null` pourrait suffire : une
     * **resync** relit la page la plus recente, dont le curseur pointe evidemment vers la
     * deuxieme page. Sans ce drapeau, chaque resync **re-armait** `hasOlder` sur une session
     * entierement remontee, et le scroll vers le haut repartait charger une tranche deja
     * affichee (doublons filtres par id, mais requete gaspillee et « charger plus » qui
     * reapparait sans raison).
     *
     * ⚠️ Il ne redevient **jamais** faux, et c'est correct : l'historique grandit par sa **fin**
     * (messages recents), jamais par son debut. Une fois le plus ancien message vu, il le reste.
     */
    val historyExhausted: Boolean = false,

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
/**
 * **Les listes de reference, partagees par processus** (bug S11).
 *
 * ⚠️ Pourquoi un `object` et pas le cache Hilt : ces listes (`/api/command`, `/api/model`,
 * `/api/agent`, `/api/skill`) sont **globales au serveur**, pas a une session. Les recharger a
 * chaque ouverture de conversation faisait quatre requetes identiques par session ouverte.
 *
 * ⚠️ **Pourquoi ce n'est pas un mensonge.** Le serveur reste la verite : on ne fige rien sur
 * disque, le cache meurt avec le processus, et une ecriture n'a lieu que sur un **succes** — un
 * echec reseau ne memorise jamais une liste vide. Le seul ecart possible est celui du temps de
 * vie du processus, borne par le fait que ces listes ne changent qu'a l'ajout d'un modele ou
 * d'une competence, gestes rares et faits sur le serveur.
 *
 * ⚠️ `isLoaded` distingue « jamais charge » de « charge et vide » : sans lui, un serveur qui
 * repond legitimement des listes vides passerait pour non charge et serait interroge sans fin.
 */
internal object ReferenceCache {
    var commands: List<CommandDto> = emptyList()
    var models: List<Model> = emptyList()
    var agents: List<Agent> = emptyList()
    var skills: List<SkillDto> = emptyList()

    /** Vrai des qu'au moins une lecture a **reussi** : on ne rejoue plus le cycle complet. */
    var isLoaded: Boolean = false
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    private val activity: ActivityMonitor,
    private val streamFactory: EventSourceFactory,
    private val sessionDefaults: sh.sk7.tether.data.settings.SessionDefaultsStore,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
    /** Delai sans evenement avant de rendre la main (etat stable, reessayable). */
    @param:AwaitingGraceMillis
    private val awaitingGraceMillis: Long = DEFAULT_AWAITING_GRACE_MILLIS,
) : ViewModel() {

    val sessionID: String = checkNotNull(savedStateHandle.get<String>("sessionID")) {
        Res.of(R.string.sessionid_manquant_route_b6b1c5)
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

    /** Fusionne les deltas de texte/raisonnement d'une meme fenetre (cf. [connect]). */
    private val coalescer = DeltaCoalescer()

    /** Horodatage du dernier evenement recu pendant un tour — sert au réarmement de [armGrace]. */
    @Volatile
    private var graceArmedAt: Long = 0

    /**
     * **Le lien entre un message optimiste et son identifiant serveur.**
     *
     * `PromptAcceptance.id` **est** l'id REST du message utilisateur : c'est lui qui permet une
     * confirmation exacte, plutot qu'une comparaison de textes (voir [dedupeOptimistic]).
     *
     * ⚠️ **Thread-safe, et c'est obligatoire** (bug B9). Ces cartes sont lues et ecrites par
     * plusieurs coroutines a la fois : le flux SSE (`applyEvent` -> `dedupeOptimistic`), l'envoi
     * (`send`), et la resync. Un `mutableMapOf` n'est pas concu pour ca — deux ecritures
     * simultanees peuvent en perdre une, et une lecture pendant un redimensionnement peut lever.
     * Sur un dispatcher multi-thread (`Dispatchers.IO`), la fenetre est reelle.
     *
     * ⚠️ **On ne serialise pas avec un Mutex pour autant** : les acces sont courts et sans
     * suspension, et `ConcurrentHashMap` les rend corrects sans bloquer. Introduire un `Mutex`
     * ici obligerait a suspendre dans `dedupeOptimistic`, qui est appele **dans** un
     * `_state.update` — un bloc qui doit rester non-suspendable pour rejouer son compare-and-set.
     */
    private val acceptedOptimistic = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Ids optimistes dont l'envoi **est en cours** (le `POST /prompt` n'a pas repondu).
     *
     * ⚠️ Existe pour une seule raison : [pruneOptimisticLinks] purge [preexistingUserIds] sur la
     * liste des optimistes **presents dans l'etat**. Or dans [send] l'instantane est ecrit
     * *avant* la publication de l'optimiste, sans quoi une resync le consomme par texte (bug
     * corrige, voir [send]). Il faut que la purge ne jette pas l'entree pendant l'intervalle.
     *
     * ⚠️ **Gardien non prouve par un test.** La fenetre miroir (purge entre l'ecriture de
     * l'instantane et la publication) n'a pas pu etre reproduite : `send` ne suspend pas entre
     * ces deux instructions, il faudrait donc un entrelacement multi-thread. Le set est **defensif**
     * et cout 4 lignes ; on le garde parce qu'il rend la purge correcte par construction plutot
     * que par chance, pas parce qu'un echec l'aurait demontre. Si un jour il devient inutile,
     * `pruneOptimisticLinks` est le seul endroit a toucher.
     *
     * Retire a chaque sortie d'envoi (accepte, refuse, erreur, non configure).
     */
    private val inFlightSends = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    private val preexistingUserIds = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

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
                _state.update { it.copy(phase = UiPhase.Error, error = Res.of(R.string.aucun_serveur_configure_4ca91e)) }
                return@launch
            }
            connect(loaded)
            resync()
            // ⚠️ On marque APRES la resync : c'est elle qui charge l'état de la session, dont on
            // lit ensuite l'`idle` pour le marquage.
            markSessionViewed()
            // ⚠️ **Et on remarque aussi ce qui se termine PENDANT qu'on regarde** (bug B6).
            //
            // Avant, `markSessionViewed()` n'etait appele qu'une fois, a l'ouverture. Un tour qui
            // se terminait pendant qu'on avait la conversation sous les yeux restait donc
            // « termine / pas vu » : le badge revenait dans la liste des sessions, et le pousser
            // demandait de **quitter puis rouvrir** la conversation. C'est exactement l'inverse de
            // ce qu'on veut — on etait en train de le lire.
            //
            // ⚠️ On observe l'etat **reduit du flux** (`_state`), pas le detenteur d'activite :
            // c'est la fin d'un tour de CETTE session qu'on veut remarquer, et le detenteur
            // interroge le serveur toutes les 12 s (trop lent, et il ignore quelle conversation
            // est a l'ecran).
            observeViewingEndOfTurn()
        }
    }

    /**
     * **Marque vu des qu'un tour se termine pendant qu'on regarde.**
     *
     * ⚠️ `distinctUntilChanged` est indispensable : sans lui, chaque emission d'etat d'un tour
     * deja termine relancerait un marquage — et [markSessionViewed] fait un appel HTTP. On ne
     * veut reagir qu'au **passage** a un statut terminal.
     *
     * ⚠️ Le marquage reste **silencieux en cas d'echec** : ne pas pouvoir marquer vu ne doit
     * jamais empecher de lire la conversation (voir [markSessionViewed]).
     */
    private fun observeViewingEndOfTurn() {
        scope.launch {
            _state
                .map { it.chat.status }
                .distinctUntilChanged()
                .filter { it.isTerminal() }
                .collect { markSessionViewed() }
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
                // ⚠️ Coalescence des deltas : le modele emet en rafale, et chaque delta
                // recopiait la String accumulee (O(n²) par tour) + un cycle UI complet.
                // On fusionne les deltas d'une meme fenetre de 50 ms en un seul evenement.
                for (e in coalescer.feed(event)) applyEvent(e)
            }
            .catch { /* le flux ne doit jamais tuer l'ecran ; la reconnexion est interne */ }
            .launchIn(scope)

        source.state
            .filter { it == ConnectionState.Connected }
            .onEach {
                // ⚠️ Dedup avec le `resync()` direct de [start] : l'EventStream passe a
                // Connected des l'ouverture, donc la resync partait DEUX fois de suite a
                // chaque entree dans un chat (~6 requetes doubees pour rien).
                val now = System.currentTimeMillis()
                if (now - lastResyncMillis < RESYNC_DEDUPE_MS) return@onEach
                lastResyncMillis = now
                resync()
            }
            .launchIn(scope)
    }

    private fun applyEvent(event: OcEvent) {
        // ⚠️ **La reduction se fait DANS le `update`, pas avant** (bug B9).
        //
        // L'ancien code reduisait depuis `_state.value.chat` puis ecrivait
        // `current.copy(chat = reduced)` : il **jetait `current.chat`**. Si une resync (ou un
        // envoi) modifiait la conversation entre la lecture et l'ecriture, sa mise a jour etait
        // perdue — un message valide disparaissait. `MutableStateFlow.update` rejoue son bloc
        // jusqu'a reussir le compare-and-set : en reduisant **depuis `current`**, chaque tentative
        // part de l'etat reellement en place, et aucune mise a jour concurrente ne se perd.
        //
        // ⚠️ **Corollaire : le bloc doit rester PUR.** `update` peut le rejouer plusieurs fois ;
        // tout effet de bord y serait applique a chaque tentative. C'est pour ca que la purge des
        // liens d'optimistes vit hors de la transaction (voir [pruneOptimisticLinks]) — dedans,
        // elle faisait perdre le lien id-local -> id-serveur sur un rejeu, et la confirmation
        // retombait sur le match par texte (course revéléee par
        // `un ancien message de meme texte ne confirme pas un optimiste frais`).
        //
        // ⚠️ `running` est declare **hors** du bloc pour ressortir : `update` ne rend pas de
        // valeur. Il est recalcule a chaque tentative, donc apres la derniere il est juste.
        var running = false
        _state.update { current ->
            val reduced = EventReducer.reduce(current.chat, event)
            running = reduced.status == SessionStatus.Running
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
        // ⚠️ Hors transaction : l'etat ecrit est celui sur lequel on purge.
        pruneOptimisticLinks()
        if (running) armGrace() else cancelGrace()
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
        // ⚠️ Sortie immediate si les maps de liens sont vides ET qu'aucun envoi est en vol :
        // ce scan etait O(n) par delta de texte. Le cas nominal (lecture d'un tour, pas
        // d'envoi en vol) ne doit rien payer. `preexistingUserIds` couvre la course ou
        // l'inbox arrive avant l'acceptation HTTP (lien par texte, sans id).
        if (acceptedOptimistic.isEmpty() && preexistingUserIds.isEmpty() && inFlightSends.isEmpty()) return chat
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

        // ⚠️ **AUCUN effet de bord ici.** [dedupeOptimistic] est appele **dans** un
        // `_state.update` (voir [applyEvent]) : `MutableStateFlow` **rejoue** ce bloc quand le
        // compare-and-set echoue, donc tout effet de bord y serait applique plusieurs fois, ou
        // applique alors que l'ecriture d'etat n'a pas eu lieu.
        //
        // Concretement, la purge des liens qui vivait ici (`acceptedOptimistic.keys.retainAll`)
        // faisait perdre le lien id-local -> id-serveur sur un rejeu. La confirmation retombait
        // alors sur le match **par texte**, et un ancien message identique consommait l'envoi
        // frais : l'optimiste disparaissait de l'ecran. C'est la course que le test
        // `un ancien message de meme texte ne confirme pas un optimiste frais` revelait de
        // facon intermittente (1 echec sur 5 runs complets, 0 en isolation).
        //
        // La purge vit desormais dans [pruneOptimisticLinks], **hors** de la transaction.
        return chat.copy(messages = kept)
    }

    /**
     * **Retire les liens des optimistes qui ne sont plus affiches.**
     *
     * ⚠️ Appelee **apres** un `_state.update`, jamais dedans : c'est de l'hygiene memoire, pas
     * une regle de correction. Les entrees orphelines ne sont jamais lues (on n'interroge les
     * maps qu'avec l'id d'un message present), donc les oublier ne casserait rien — mais les
     * laisser croitre sans borne sur une longue session serait neglige.
     */
    private fun pruneOptimisticLinks() {
        // ⚠️ Sortie immediate sans optimiste ni envoi en vol : cette purge etait appelee
        // apres CHAQUE delta de texte, et scannait toute la liste de messages (3 collections
        // allouees) pour un resultat quasi toujours vide — du churn GC constant pendant
        // tout le streaming.
        if (acceptedOptimistic.isEmpty() && preexistingUserIds.isEmpty() && inFlightSends.isEmpty()) return
        val present = _state.value.chat.messages
            .filter { it.isOptimistic }
            .map { it.id }
            .toHashSet()
        // ⚠️ `+ inFlightSends` : un envoi declare mais pas encore publie n'est pas dans l'etat, et
        // son instantane doit survivre a la purge. Sans ca, la fenetre de [send] se rouvre.
        val keep = present + inFlightSends
        acceptedOptimistic.keys.retainAll(keep)
        preexistingUserIds.keys.retainAll(keep)
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
     * Charge les listes **de reference** de l'ecran : commandes, modeles, agents, skills.
     *
     * ⚠️ Chaque appel est isole (`runCatching`) : un serveur qui ne repond pas a `/api/agent` ne
     * doit pas priver l'utilisateur de ses commandes slash. Les quatre listes sont independantes.
     *
     * ⚠️ **Le resultat est partage par processus** (bug S11). Avant, ces quatre lectures etaient
     * refaites a **chaque ouverture de conversation** — 4 requetes HTTP pour des listes qui ne
     * changent qu'a l'ajout d'un modele ou d'une competence. Ouvrir dix sessions dans la meme
     * minute faisait quarante requetes identiques, alors que le contenu est le meme pour tout
     * l'appareil : ces routes sont **globales**, elles ne dependent pas de la session.
     *
     * ⚠️ On ne les met pas en cache sur disque : le serveur reste la verite, et une liste figee
     * afficherait un modele supprime. Le cache vit le **temps du processus**, ce qui couvre
     * exactement le cas couteux (enchainement d'ouvertures) sans jamais mentir apres un
     * redemarrage.
     *
     * ⚠️ `refresh = true` reste possible pour forcer une relecture (bouton, apres un ajout de
     * competence), et un echec **n'ecrit rien** dans le cache : on ne memorise pas un vide, sinon
     * une panne reseau passagere figerait des listes vides pour toute la session.
     */
    private fun loadReferenceData(refresh: Boolean = false) {
        scope.launch {
            val current = settings ?: store.current().also { settings = it }
            if (!refresh && ReferenceCache.isLoaded) {
                _commands.value = ReferenceCache.commands
                _models.value = ReferenceCache.models
                _agents.value = ReferenceCache.agents
                _skills.value = ReferenceCache.skills
                return@launch
            }
            // ⚠️ On ne marque le cache charge que si **au moins une** lecture a reussi. Un
            // serveur injoignable ne doit pas figer des listes vides pour tout le processus :
            // c'est la meme regle que pour chaque liste prise separement.
            var anySuccess = false
            runCatching { gateway.commands(current) }
                .onSuccess { _commands.value = it; ReferenceCache.commands = it; anySuccess = true }
            runCatching { gateway.models(current) }
                .onSuccess { _models.value = it; ReferenceCache.models = it; anySuccess = true }
            runCatching { gateway.agents(current) }
                .onSuccess { _agents.value = it; ReferenceCache.agents = it; anySuccess = true }
            runCatching { gateway.skills(current) }
                .onSuccess { _skills.value = it; ReferenceCache.skills = it; anySuccess = true }
            if (anySuccess) ReferenceCache.isLoaded = true
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
                    _state.update { it.copy(error = Res.of(R.string.commande_name_refusee_2e2df3)) }
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
                    // ⚠️ On **memorise** pour la prochaine creation de session. Sans ca, « Nouvelle
                    // session » retomberait sur le defaut du serveur (`general` + deepseek-v4.1-flash,
                    // mesure du 2026-09-26) alors que l'utilisateur change de modele a chaque
                    // session. C'est le memoriser, et non le redemander dans une dialogue, qui
                    // supprime les 3 champs sans rien lui faire perdre.
                    sessionDefaults.record(model = model)
                    // ⚠️ On **relit** la session au lieu d'ecrire la valeur dans un champ d'etat.
                    //
                    // Le champ `modelOverride` faisait exactement ca : il etait ecrit (ici) et
                    // **jamais lu** — l'en-tete et le selecteur lisaient `meta`, charge une seule
                    // fois a l'ouverture. Le serveur changeait bien, l'app affichait l'ancien, et
                    // « je ne peux pas changer de modele » en decoulait.
                    //
                    // `assume` n'est utilise que si la relecture echoue : le POST a repondu 2xx,
                    // donc la valeur vient du serveur, pas d'une supposition. La resync confirmera.
                    loadMeta(
                        current,
                        assume = _state.value.meta?.copy(
                            model = model.id,
                            provider = model.providerID.ifBlank { null },
                        ) ?: SessionMeta(model = model.id, provider = model.providerID.ifBlank { null }),
                    )
                } else {
                    _state.update { it.copy(error = Res.of(R.string.serveur_refuse_changement_b2eabd)) }
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
                    sessionDefaults.record(agent = agent)
                    // Meme raison que [setModel] : relire plutot qu'ecrire un champ mort.
                    loadMeta(
                        current,
                        assume = _state.value.meta?.copy(agent = agent) ?: SessionMeta(agent = agent),
                    )
                } else {
                    _state.update { it.copy(error = Res.of(R.string.serveur_refuse_changement_0a0a6b)) }
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
                    _state.update { it.copy(error = Res.of(R.string.serveur_refuse_activer_67e27c)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = ConnectionErrors.describe(e)) }
            }
        }
    }

    /**
     * **Ouvre l'historique sur sa fin**, en gardant le curseur pour remonter.
     *
     * ⚠️ On conserve le `cursorBack` de la page : c'est lui qui rend [loadOlder] **O(1)** par
     * tranche. L'ancien code le jetait, et devait donc repartir du sommet a chaque cran.
     *
     * ⚠️ On ne remplace PAS tout : les messages plus anciens deja charges par [loadOlder]
     * doivent survivre a une resync, sinon remonter puis perdre la connexion effacerait
     * l'historique qu'on vient de charger.
     */
    fun resync() {
        val current = settings ?: return
        // ⚠️ Horodate : le collecteur `Connected` deduplique avec ce chiffre (cf. [connect]).
        lastResyncMillis = System.currentTimeMillis()
        scope.launch {
            try {
                val page = gateway.messagesPageBack(current, sessionID, ChatWindow.SERVER_PAGE, cursor = null)
                val fromRest = ChatMessageMapper.fromDtos(page.messages)
                val restIds = fromRest.map { it.id }.toHashSet()
                // ⚠️ La file est lue **en plus** de l'historique, et pas dedans : mesure du
                // 2026-09-25, un message en file n'apparait PAS dans `GET /message` (une session
                // a deux prompts en file rend `count 0`). Sans cette lecture, un message en
                // attente anterieur a l'ouverture de l'app serait invisible — alors que c'est le
                // cas principal, puisque l'inbox vit pendant que le serveur travaille.
                val inbox = runCatching { gateway.sessionInbox(current, sessionID) }
                    .getOrDefault(emptyList())
                val queued = ChatMessageMapper.fromInboxItems(inbox)

                // ⚠️ **On relit aussi ce qui ATTEND** (permissions, formulaires).
                //
                // ### Pourquoi c'est indispensable
                // Le flux SSE est *« volatile by contract »* — l'OpenAPI le dit lui-meme :
                // « events during disconnection are missed ». Il n'existe **ni `Last-Event-ID` ni
                // `since`** sur `GET /api/event` (mesure : `/session/{id}/history?after=` et
                // `/event?after=` rendent **404** sur notre 2.0.x).
                //
                // Consequence : une `permission.asked` ou un `form.created` tombe pendant une
                // coupure etait **invisible pour toujours** — l'agent restait bloque sans que rien
                // ne l'indique. La resync ne relisait que les messages et la file.
                //
                // ⚠️ Route **globale** (`/api/permission/request`, `/api/form`) : ce sont les
                // seules qui voient tout. Mesure du 2026-09-26 : une permission demandee par un
                // **sous-agent** appartient a la session **enfant**, et
                // `GET /api/session/<RACINE>/permission` rend `{"data":[]}`. On filtre donc sur
                // notre session, mais on ne s'appuie pas sur la route par session.
                //
                // ⚠️ Les lectures sont **isolees** (`runCatching`) : ne pas pouvoir lire les
                // demandes ne doit pas empecher d'afficher la conversation. C'est un indicateur,
                // pas une fonction critique.
                // ⚠️ **Deux types portent le meme nom, et il faut le savoir.**
                //
                // `gateway.pendingPermissions` rend le type du **domaine**
                // (`domain.model.PermissionRequest`, fichier `PermissionAsk.kt`) : c'est ce que
                // consomment l'ecran Approbations, le badge de la liste et le detenteur d'activite.
                //
                // Mais `SessionUiState.pendingPermission` porte le type du **DTO**
                // (`data.api.PermissionRequest`) : c'est celui que le reducer decode
                // directement depuis la charge d'un evenement, qui a la meme forme JSON que la
                // route. On convertit donc ici — explicitement plutot que par un cast.
                //
                // ⚠️ **Aucune perte** : le DTO est un sur-ensemble du domaine (`metadata` et
                // `source` en plus, que la route fournit et que le domaine ignore). Convertir dans
                // ce sens ne jette rien ; l'inverse, si.
                val pendingHere = runCatching { gateway.pendingPermissions(current) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.sessionID == sessionID }
                    ?.let { ask ->
                        PermissionRequest(
                            id = ask.id,
                            sessionID = ask.sessionID,
                            action = ask.action,
                            resources = ask.resources,
                            save = ask.save,
                            message = ask.message,
                        )
                    }
                val pendingFormsHere = runCatching { gateway.pendingForms(current) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.sessionID == sessionID }

                _state.update { state ->
                    // ⚠️ **Trois groupes, trois places** — et c'est le sens de ce bloc.
                    //
                    //  1. `history` : ce que [loadOlder] a remonte, anterieur a la fenetre REST.
                    //     Il passe DEVANT (c'est le plus ancien).
                    //  2. `fromRest` : la fenetre recente, qui fait foi.
                    //  3. `optimistic` : les messages ecrits ici et pas encore confirmes. Ils
                    //     passent **DERRIERE** : ils sont les plus recents.
                    //
                    // ⚠️ **Bug B5** : l'ancien filtre `id !in restIds` rangeait les optimistes
                    // dans `history`, donc il les **remontait tout en haut** de la conversation —
                    // un message qu'on venait d'ecrire apparaissait des centaines de lignes plus
                    // haut. Les exclure sans les replacer les faisait **disparaitre**, ce qui est
                    // pire. Ils ont une troisieme place, a la fin.
                    val history = state.chat.messages.filter { message ->
                        !message.isOptimistic && message.id !in restIds
                    }
                    val optimistic = state.chat.messages.filter { it.isOptimistic }
                    // ⚠️ On **fusionne la file d'abord**, puis on deduplique : l'item d'inbox porte
                    // l'id serveur du message envoye (`accepted.id`), donc le faire entrer avant
                    // [dedupeOptimistic] permet a l'optimiste d'etre reconnu par id — l'inverse
                    // laisserait temporairement les deux a l'ecran.
                    val merged = state.chat.copy(messages = history + fromRest + optimistic)
                    val withInbox = ChatMessageMapper.mergeInbox(merged.messages, queued)
                    // ⚠️ **Ce qui attend, relu a la source.** Une demande tombee pendant une
                    // coupure du flux serait autrement invisible pour toujours : le SSE ne rejoue
                    // rien, et il n'y a pas de `since` (mesure : les routes `?after=` rendent 404
                    // sur notre 2.0.x). On ECRASE volontairement — le serveur est la verite, donc
                    // une demande deja reglee ailleurs ne doit pas rester affichee comme si elle
                    // attendait encore.
                    val chatWithPending = dedupeOptimistic(merged.copy(messages = withInbox)).copy(
                        pendingPermission = pendingHere,
                        pendingForm = pendingFormsHere?.let { info ->
                            FormRequest(
                                id = info.id,
                                sessionID = info.sessionID,
                                title = info.title,
                                raw = JsonObject(emptyMap()),
                            )
                        },
                    )
                    state.copy(
                        chat = chatWithPending,
                        // ⚠️ **Une resync ne re-arme jamais l'historique, et n'ecrase pas le
                        // curseur courant.** Elle relit la page la plus recente, dont le curseur
                        // pointe vers la **deuxieme** page : s'en servir aveuglement remettrait le
                        // curseur en arriere apres une pagination profonde, et **re-armerait**
                        // `hasOlder` sur une session entierement remontee (le test
                        // `loadOlder ajoute les messages anciens devant et sans doublon` le
                        // detecte).
                        //
                        //   - `historyExhausted` reste la verite : une fois le debut atteint, il
                        //     l'est pour toujours, l'historique grandissant par sa **fin** ;
                        //   - le curseur **courant** est conserve (`?:`), et celui de la resync
                        //     ne sert que s'il n'y en a pas encore — c'est le cas de l'ouverture.
                        hasOlder = !state.historyExhausted && (state.hasOlder || page.cursorBack != null),
                        olderCursor = state.olderCursor ?: page.cursorBack,
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
                    notice = if (ok) Res.of(R.string.message_retire_file_0f14a5) else Res.of(R.string.annulation_refusee_serveur_beccd1),
                )
            }
            refreshQueue()
        }
    }

    /**
     * **Bascule un message en file entre « attend son tour » et « corrige le tour en cours ».**
     * (`PATCH /api/session/{id}/inbox/{inboxID}`, corps `{"delivery": "steer"|"queue"}`.)
     *
     * ### Pourquoi ce bouton existe
     * L'app **affichait** deja le mode (« corrige le tour en cours » / « attend son tour ») mais ne
     * pouvait pas le **changer**. Un message en file restait donc fige dans le mode ou il avait ete
     * accepte, alors que c'est justement au moment ou l'agent travaille qu'on se rend compte qu'on
     * voulait corriger le tour plutot qu'attendre le suivant.
     *
     * ⚠️ **Le serveur est la verite, on ne devine pas l'effet.** On ne bascule pas l'etat
     * localement en supposant que le PATCH a marche : on relit la file ([refreshQueue]) apres coup,
     * exactement comme [cancelQueued]. Un `409` (mesure : « Pending input cannot change to queue »,
     * le message est deja en cours de livraison) doit laisser le mode tel qu'il est, pas afficher
     * celui qu'on esperait.
     *
     * ⚠️ `busy` marque l'identifiant pendant l'aller-retour, et il est **retire dans tous les cas**
     * (succes, echec, exception) : un message qui resterait marque « en vol » aurait un bouton
     * desactive pour toujours.
     */
    fun toggleQueuedDelivery(inboxID: String) {
        val message = _state.value.chat.messages.firstOrNull { it.id == inboxID } ?: return
        // ⚠️ On ne bascule pas ce qui est deja en vol (annulation ou autre bascule).
        if (inboxID in _state.value.cancelling) return
        // ⚠️ Le mode cible est l'INVERSE du mode affiche. `steer` corrige le tour en cours,
        // `queue` attend son tour — les deux seules valeurs de `Session.Inbox.Delivery`.
        val target = if (message.isSteering) "queue" else "steer"

        _state.update { it.copy(cancelling = it.cancelling + inboxID) }
        scope.launch {
            val current = settings ?: store.current().also { settings = it }
            val ok = runCatching {
                gateway.updateInboxDelivery(current, sessionID, inboxID, target)
            }.getOrDefault(false)
            _state.update { state ->
                state.copy(
                    cancelling = state.cancelling - inboxID,
                    // ⚠️ Neutralite : changer de mode n'est pas une panne, donc `notice` et pas
                    // `error`. Et un echec est DIT — ne rien dire laisserait croire que le mode a
                    // change alors qu'il n'en est rien.
                    notice = if (ok) {
                        if (target == "steer") {
                            Res.of(R.string.message_corrigera_tour_1bbd26)
                        } else {
                            Res.of(R.string.message_attendra_tour_32f4ec)
                        }
                    } else {
                        // ⚠️ On nomme la cause la plus probable sans l'affirmer : mesure du
                        // 2026-09-26, un `409` signifie que le message est deja en cours de
                        // livraison — auquel cas il n'y a plus de mode a changer, et ce n'est pas
                        // une erreur de l'utilisateur.
                        Res.of(R.string.serveur_refuse_changer_1439f8)
                    },
                )
            }
            // ⚠️ On relit TOUJOURS la file, succes ou echec : c'est elle qui porte le mode reel.
            refreshQueue()
        }
    }

    /** Efface l'information neutre (apres l'avoir montree). */
    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** Ferme le bandeau d'erreur au geste — une erreur lue ne doit pas rester affichee. */
    fun clearError() = _state.update { it.copy(error = null) }

    /** Confirmation visible de la copie presse-papiers — le geste ne doit jamais sembler mort. */
    fun afficherCopie() = _state.update {
        it.copy(notice = Res.of(R.string.texte_copie_presse_papiers_9d43e1))
    }

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
                _state.update { it.copy(error = Res.of(R.string.aucun_serveur_configure_4ca91e)) }
                return@launch
            }
            runCatching { gateway.backgroundTools(current, sessionID) }
                .onSuccess { ok ->
                    _state.update {
                        it.copy(
                            notice = if (ok) {
                                Res.of(R.string.outils_deplaces_arriere_e70f43)
                            } else {
                                Res.of(R.string.serveur_refuse_mise_2b2c21)
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
     *
     * [assume] est la valeur affichee **seulement si la relecture echoue** apres un changement
     * accepte. Deux cas distincts, a ne pas confondre :
     * - au chargement initial il n'y a rien a montrer : l'en-tete reste vide, ce qui est exact ;
     * - apres un `POST` repondu 2xx, la valeur vient du serveur — on l'affiche plutot que de
     *   laisser une valeur perimee, et la resync confirmera.
     */
    private suspend fun loadMeta(current: ConnectionSettings, assume: SessionMeta? = null) {
        val session = runCatching { gateway.session(current, sessionID) }.getOrNull()
        if (session == null) {
            if (assume != null) _state.update { it.copy(meta = assume) }
            return
        }
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
    /**
     * **Remonte d'une tranche**, en suivant le curseur memorise.
     *
     * ⚠️ **Une requete par tranche, quelle que soit la profondeur** (voir
     * `OpenCodeGateway.messagesPageBack`). L'ancienne version cherchait un message de reference
     * en repartant du sommet a chaque cran : O(n²), ~200 requetes pour remonter 2 040 messages.
     *
     * ⚠️ On ne cherche plus « le message le plus ancien deja charge » : la fenetre n'est pas
     * forcement contigue (les optimistes, la file, et les marqueurs de tour filtres par le
     * mapper rendent tout index local faux). Le curseur, lui, vient du serveur.
     *
     * ⚠️ On **prepend** les anciens et on conserve le reste, fusion par id : un message arrive
     * par le flux entre-temps ne doit jamais etre perdu.
     */
    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || !current.hasOlder) return
        val cursor = current.olderCursor ?: return
        val settings = settings ?: return

        _state.update { it.copy(loadingOlder = true) }
        scope.launch {
            try {
                val page = gateway.messagesPageBack(settings, sessionID, ChatWindow.SERVER_PAGE, cursor)
                val older = ChatMessageMapper.fromDtos(page.messages)
                _state.update { state ->
                    // Fusion par id : les anciens passent DEVANT, les existants sont conserves.
                    val known = state.chat.messages.map { it.id }.toHashSet()
                    val fresh = older.filter { it.id !in known }
                    state.copy(
                        chat = state.chat.copy(messages = fresh + state.chat.messages),
                        // ⚠️ `cursorBack == null` = fin d'historique **prouvee** (le gateway
                        // sonde le curseur et rend `null` quand la page suivante est vide).
                        // ⚠️ On pose `historyExhausted` en plus : une resync posterieure ne doit
                        // pas re-armer `hasOlder` depuis sa page recente (elle a toujours un
                        // curseur, par construction).
                        hasOlder = page.cursorBack != null,
                        olderCursor = page.cursorBack,
                        historyExhausted = page.cursorBack == null,
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

        // ⚠️ **L'instantane s'ecrit AVANT de publier l'optimiste.** C'est le correctif d'un bug
        // **reproduit** : dans l'ordre inverse (publier, puis instantaner), une resync qui atterrit
        // entre les deux voit l'optimiste **sans** son instantane, donc `preexistingFor` rend un
        // ensemble vide, l'ancien message de meme texte redevient consommable et **l'envoi frais
        // disparait de l'ecran**. Mesure : 1 echec sur 8 sur
        // `un ancien message de meme texte ne confirme pas un optimiste frais` ; rejoue sur
        // l'etat d'avant (675e174), ce test echoue a tous les coups.
        //
        // [inFlightSends] ferme en plus la fenetre miroir (la purge de [pruneOptimisticLinks]
        // qui jetterait l'entree entre l'ecriture et la publication) — voir sa documentation :
        // gardien defensif, non prouve par un test.
        val preexisting = _state.value.chat.messages
            .filter { it.role == Role.User && !it.id.startsWith(OPTIMISTIC_PREFIX) }
            .map { it.id }
            .toHashSet()
        inFlightSends.add(optimistic.id)
        preexistingUserIds[optimistic.id] = preexisting

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
                    inFlightSends.remove(optimistic.id)
                    _state.update {
                        it.copy(
                            chat = it.chat.copy(messages = it.chat.messages - optimistic),
                            phase = UiPhase.Error,
                            error = Res.of(R.string.aucun_serveur_configure_4ca91e),
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
                // ⚠️ L'envoi n'est plus « en vol » : desormais c'est le lien **par id** qui fait foi
                // (voir [dedupeOptimistic]), l'instantane par texte n'est plus consulte.
                inFlightSends.remove(optimistic.id)
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
                inFlightSends.remove(optimistic.id)
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
                    error = Res.of(R.string.name_depasse_promptattachments_7f0156, name, PromptAttachments.formatSize(PromptAttachments.MAX_FILE_BYTES)),
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
            _state.update { it.copy(error = Res.of(R.string.chemin_non_absolu_206c47)) }
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
        // ⚠️ Un seul job, rearme par horodatage et non recree par evenement : avant, CHAQUE
        // delta annulait + relancait une coroutine (des centaines par seconde en rafale).
        // Le job existant fait le boulot lui-meme : il attend la grace, et si de nouveaux
        // evenements sont arrives entre-temps, il se rearme au lieu de tirer.
        val existing = graceJob
        if (existing != null && existing.isActive) {
            graceArmedAt = System.currentTimeMillis()
            return
        }
        graceArmedAt = System.currentTimeMillis()
        graceJob = scope.launch {
            while (true) {
                delay(awaitingGraceMillis)
                val idleSince = System.currentTimeMillis() - graceArmedAt
                if (idleSince < awaitingGraceMillis) continue // un evenement est arrive : reattendre le reste
                _state.update { state ->
                    when (state.phase) {
                        UiPhase.Sending, UiPhase.Streaming -> state.copy(phase = UiPhase.Awaiting)
                        else -> state
                    }
                }
                return@launch
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

        /** Fenetre de deduplication des resync (ms) : deux triggers a moins de ca n'en font qu'une. */
        const val RESYNC_DEDUPE_MS: Long = 2_000
    }

    /** Horodatage de la derniere resync lancee — sert au dedup du collecteur `Connected`. */
    @Volatile
    private var lastResyncMillis: Long = 0

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
