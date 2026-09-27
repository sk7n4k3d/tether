package sh.sk7.tether.ui.sessions

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.activity.ActivityMonitor
import sh.sk7.tether.domain.model.FleetState
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.PinnedSessions
import sh.sk7.tether.data.settings.SessionDefaultsStore
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
        /** Consommation agregee. `null` = le serveur ne l'expose pas (pas d'erreur). */
        val usage: UsageInfo? = null,
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

    /**
     * @param unauthorized vrai si le serveur a **refuse les identifiants**, faux s'il est
     *        injoignable. ⚠️ Le fait est porte par l'etat, pas redevine par l'UI en testant le
     *        texte du message : c'est ce qui rend la distinction fiable. Les confondre envoie
     *        l'utilisateur chercher un probleme reseau quand son mot de passe est faux.
     */
    data class Error(
        val message: String,
        val unauthorized: Boolean = false,
    ) : SessionListUiState
}


@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    private val pinned: PinnedSessions,
    private val monitor: ConnectionMonitor,
    private val activity: ActivityMonitor,
    private val defaults: SessionDefaultsStore,
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

    /**
     * Evenement **ponctuel** : la session qu'on vient de creer, pour que l'ecran l'ouvre.
     *
     * ⚠️ Un `Channel` et non un etat : une session creee ne doit pas etre rejouee a chaque
     * recomposition ni rappelee apres rotation. `receiveAsFlow` le consomme une fois.
     */
    private val _openSession = Channel<String>(Channel.BUFFERED)
    val openSession: Flow<String> = _openSession.receiveAsFlow()

    /**
     * **L'état vivant de toute l'installation**, lu directement au détenteur partagé.
     *
     * ⚠️ On ne le recalcule **pas** ici : la liste n'est qu'un des lecteurs. Le jour où un second
     * écran veut le même état, il lira la même source — et les deux ne pourront pas se contredire.
     */
    val fleet: StateFlow<FleetState> = activity.state


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

    /**
     * Nombre de **formulaires en attente** sur le serveur.
     *
     * ⚠️ Meme role que [pendingApprovals] : un formulaire bloque l'agent exactement comme une
     * permission (mesure : la session reste immobile tant que personne ne repond). Le point
     * d'entree doit donc porter **les deux** compteurs — sinon l'icone afficherait « calme » alors
     * qu'un agent est bloque sur une question. C'est le mensonge par omission que la teinte
     * d'alerte existe pour empecher.
     *
     * ⚠️ Un echec est avale (`getOrElse`) : le badge est un confort, une erreur de badge ne doit
     * pas transformer un detail en panne apparente.
     */
    private val _pendingForms = MutableStateFlow(0)
    val pendingForms: StateFlow<Int> = _pendingForms.asStateFlow()

    /**
     * Identifiants des sessions epinglees.
     *
     * ⚠️ Expose a l'UI pour qu'elle puisse **remonter** les epingles en tete de liste. Un
     * epinglage qui ne change pas l'ordre ne sert a rien : l'interet est de retrouver vite.
     */
    val pinnedIds: StateFlow<Set<String>> = pinned.ids
        .let { flow ->
            MutableStateFlow<Set<String>>(emptySet()).also { state ->
                scope.launch { flow.collect { state.value = it } }
            }
        }
        .asStateFlow()

    /** Epingle ou depingle une session. */
    fun togglePin(sessionID: String) {
        scope.launch { pinned.toggle(sessionID) }
    }

    init {
        // ⚠️ On s'abonne : la boucle du monitor s'arrête quand plus personne ne regarde, ce qui
        // évite d'interroger le serveur toutes les 12 s pour un écran fermé.
        activity.acquire()
        refresh()
    }

    override fun onCleared() {
        // ⚠️ Toujours relâcher, même si l'écran est détruit par une erreur : un abonnement qui
        // fuit laisserait la boucle allumée pour toute la vie du processus.
        activity.release()
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
                monitor.markOnline(version = _healthVersion(settings))
            } catch (e: Exception) {
                // ⚠️ On CONSERVE la liste : un rafraichissement rate ne doit pas faire
                // disparaitre ce qu'on lisait. L'erreur se dit a part.
                _state.value = loaded.copy(refreshing = false)
                val message = ConnectionErrors.describe(e)
                _sessionError.value = message
                monitor.markOffline(message, unauthorized = ConnectionErrors.isUnauthorized(e))
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
                // ⚠️ On marque l'etat sur un appel **reel** qui a reussi : c'est du vecu, pas une
                // supposition. C'est ce qui alimente l'ecran hors-connexion sans sonde periodique.
                monitor.markOnline(
                    version = runCatching { gateway.info(settings).version }.getOrNull(),
                )
            } catch (e: Exception) {
                val message = ConnectionErrors.describe(e)
                val unauthorized = ConnectionErrors.isUnauthorized(e)
                monitor.markOffline(message, unauthorized = unauthorized)
                _state.value = SessionListUiState.Error(message, unauthorized = unauthorized)
            }
        }
    }

    /**
     * La panne est-elle un **refus d'identifiants** plutot qu'une injoignabilite ?
     *
     * ⚠️ La distinction change ce qu'on dit a l'utilisateur : « verifie ton mot de passe » contre
     * « la machine est peut-etre eteinte ». Les confondre l'enverrait chercher au mauvais endroit.
     */
    /** La version du serveur, quand on peut l'obtenir. Un echec rend `null`, jamais une erreur. */
    private suspend fun _healthVersion(settings: ConnectionSettings): String? =
        runCatching { gateway.info(settings).version }.getOrNull()

    private suspend fun load(settings: ConnectionSettings): SessionListUiState {
        val sessions = gateway.allSessions(settings)
        // ⚠️ On **donne** les sessions au détenteur d'état au lieu de le laisser les recharger :
        // un seul appel réseau pour les deux besoins (la liste affiche, le monitor calcule).
        activity.publishSessions(sessions)
        // ⚠️ On compte les approbations en attente **a chaque chargement de la liste** : c'est le
        // moment ou l'utilisateur regarde l'app, donc celui ou le badge doit etre juste. Un
        // echec est ignore (`getOrElse`) : le badge est un confort, pas une fonction critique.
        _pendingApprovals.value =
            runCatching { gateway.pendingPermissions(settings).size }.getOrElse { 0 }
        // ⚠️ Meme raison que ci-dessus pour les formulaires : le point d'entree doit signaler
        // **les deux** types de blocage (permission et formulaire). Un formulaire compte ici sans
        // etre ouvert : c'est le seul moyen de savoir qu'une reponse est attendue.
        _pendingForms.value =
            runCatching { gateway.pendingForms(settings).size }.getOrElse { 0 }
        if (sessions.isEmpty()) return SessionListUiState.Empty(settings.directory)
        // L'arbre : chaque parent suivi de ses sous-agents (67 % des sessions reelles).
        val items = SessionListMapper.toTree(sessions)
        // ⚠️ **Ni `models` ni `agents` ici.** Le seul consommateur etait la dialogue de creation,
        // supprimee : on payait deux appels reseau a chaque rafraichissement de la liste pour un
        // resultat que personne n'affichait. Le choix de modele se fait dans la conversation, ou le
        // catalogue est charge une fois (cache S11) et ou il sert vraiment.
        return SessionListUiState.Loaded(
            items = items,
            usage = aggregateUsage(sessions),
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
    // ------------------------------------------------------------------
    // Creation de session
    // ------------------------------------------------------------------

    /**
     * Cree une session et demande l'ouverture de l'ecran de chat.
     *
     * ⚠️ **Aucun dialogue, aucun champ.** Le serveur n'exige rien (mesure du 2026-09-26 :
     * `SessionCreate` n'a aucun `required`, et `{"location":{…}}` seul rend 200). On renvoie le
     * **dernier choix** de l'utilisateur ([SessionDefaultsStore]) plutot que de le redemander a
     * chaque fois, parce que le defaut du serveur est `general` + `deepseek-v4.1-flash` — un
     * modele qu'il change a chaque session de sa vie.
     *
     * ⚠️ **Aucun titre n'est envoye.** Le serveur ne reecrit pas le titre qu'on lui donne
     * (mesure : `{"title":"Nouvelle session"}` est conserve tel quel), donc en envoyer un empechait
     * opencode d'en generer un descriptif. Une session fraiche a `title: null` tant que le premier
     * tour n'a pas tourne — la liste affiche alors « Sans titre », pas un nom invente.
     *
     * ⚠️ L'echec **ne** navigue **pas** : sans session, il n'y a rien a ouvrir, et navigationner
     * quand meme ouvrirait un ecran vide en pretending que la creation a reussi.
     */
    fun newSession() {
        scope.launch {
            try {
                val current = store.current()
                val last = defaults.current()
                val created = gateway.createSession(
                    settings = current,
                    model = last.model,
                    agent = last.agent,
                )
                _openSession.send(created.id)
                refreshAfterCreate()
            } catch (e: Exception) {
                // ⚠️ On reutilise [sessionError] : une creation ratee est une action impossible,
                // et l'ecran sait deja la montrer. Un second canal.Display serait dupliqué pour rien.
                _sessionError.value = ConnectionErrors.describe(e)
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
