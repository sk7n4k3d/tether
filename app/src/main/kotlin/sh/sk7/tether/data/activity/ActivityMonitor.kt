package sh.sk7.tether.data.activity

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.PtyInfoDto
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.api.ShellInfoDto
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.ApplicationScope
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.domain.model.Activity
import sh.sk7.tether.domain.model.FleetState
import sh.sk7.tether.domain.model.SessionActivity
import sh.sk7.tether.domain.model.ShellActivity
import sh.sk7.tether.domain.model.TerminalActivity

/**
 * **Le détenteur unique de l'état vivant de l'installation.**
 *
 * ### Le problème qu'il résout
 * Avant, `SessionStatus` était produit **uniquement** par `EventReducer` — donc uniquement dans
 * l'écran de chat, et uniquement si le flux SSE était ouvert sur cette session. Résultat : la
 * liste des sessions n'avait **aucun** statut, et rien dans l'app ne pouvait dire « ça tourne »
 * sans avoir ouvert la conversation concernée.
 *
 * Ici, un seul objet sait ce qui tourne, partout, tant que l'app est ouverte. Les écrans le lisent
 * au lieu de le redécouvrir. C'est le même mouvement que `ConnectionMonitor` pour la connexion,
 * appliqué à l'activité.
 *
 * ### Pourquoi une interrogation périodique, et pas seulement le flux SSE
 * Le flux `/api/event` n'est écouté que par l'écran de chat, sur **une** session. Trois cas ne
 * peuvent pas en venir :
 *  - les sessions qu'on ne regarde pas (il en tourne parfois plusieurs) ;
 *  - les états qui ont changé **avant** l'ouverture de l'app — le cas principal : on ouvre l'app
 *    *parce qu'on* a été notifié, donc l'événement est déjà passé ;
 *  - l'activité qui se termine pendant que l'app est en arrière-plan.
 *
 * ⚠️ **Le coût est mesuré et faible** : trois requêtes par cycle sur un serveur local, qui
 * renvoient quelques centaines d'octets. La cadence est volontairement lente, et `refresh()` est
 * exposé pour forcer un cycle quand un écran veut l'état tout de suite.
 *
 * ⚠️ **Aucune sonde ne tourne quand l'app est en arrière-plan** : `ApplicationScope` vit tant que
 * le processus vit, mais on **suspend** le cycle quand plus aucun écran n'est abonné. Sans ça, on
 * interrogerait un serveur toutes les 12 s pour personne, en consommant la batterie — le défaut
 * classique de ce genre de fonction.
 */
@Singleton
class ActivityMonitor @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    private val connection: ConnectionMonitor,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(FleetState(loading = true))
    val state: StateFlow<FleetState> = _state.asStateFlow()

    /**
     * Nombre d'écrans actuellement abonnés.
     *
     * ⚠️ Quand il tombe à zéro, la boucle **s'arrête** au lieu de continuer à vide. C'est la
     * différence entre « ça marche » et « ça vide la batterie sans que personne ne regarde ».
     */
    private var subscribers = 0

    /** Un refresh à la fois : deux cycles concurrents doublonneraient les requêtes. */
    private val refreshLock = Mutex()

    /**
     * **Les sessions connues, fournies par l'ecran qui les a chargees.**
     *
     * ⚠️ Choix d'architecture, et il corrige un vrai defaut : le monitor rechargeait
     * `GET /api/session` de son cote, ce qui **doublait un appel** que la liste fait deja — une
     * requete lourde (450 sessions) pour lire quatre champs (`idle`, `viewed`, `outcome`,
     * `parentID`).
     *
     * Le monitor **ne va donc pas chercher** les sessions : on les lui pousse. Son cycle
     * periodique n'interroge que les routes legeres (actives, permissions, shells) et croise le
     * resultat avec ce qu'il sait deja.
     *
     * ⚠️ Consequence assumee : si aucun ecran n'a pousse de sessions, l'activite par session reste
     * vide. L'etat global (actives, shells) et le compteur d'attente fonctionnent quand meme —
     * c'est le strict necessaire, et ca evite de payer 450 sessions pour l'afficher.
     */
    private var knownSessions: List<Session> = emptyList()

    /**
     * Alimente le monitor avec les sessions que l'ecran vient de charger.
     *
     * ⚠️ C'est le chemin **prefere** : il economise un appel lourd (450 sessions) pour lire quatre
     * champs. Mais il ne suffit pas — voir [SESSION_RESCAN_MS].
     */
    /**
     * **Signale qu'une session vient d'etre vue**, sans attendre le prochain cycle.
     *
     * ⚠️ On met a jour l'etat **localement** au lieu de re-interroger le serveur : le fait est
     * connu, et une requete pour confirmer ce qu'on vient d'ecrire serait du gaspillage. Le
     * prochain cycle confirmera cote serveur de toute facon.
     *
     * ⚠️ Sans cet appel, le badge « termine » resterait affiche jusqu'au cycle suivant (12 s) —
     * visible, et desagreable : on vient d'ouvrir la conversation, le travail est lu, et la liste
     * continuerait de dire le contraire.
     */
    fun notifyViewed(sessionID: String, idle: Long) {
        val current = _state.value.bySession[sessionID] ?: return
        _state.value = _state.value.copy(
            bySession = _state.value.bySession + (sessionID to current.copy(viewedAt = idle)),
        )
    }

    /**
     * **Signale combien de messages attendent leur tour** dans une session, sans attendre le
     * prochain cycle.
     *
     * ⚠️ Pourquoi ce chemin existe — et pourquoi le cycle périodique ne peut pas le remplacer :
     * `GET /api/session/{id}/inbox` est une route **par session**. L'interroger pour les 200
     * sessions chargées coûterait 200 requêtes par cycle de 12 s, ce qui est hors de question.
     * Or l'écran de chat charge déjà l'inbox de la session affichée : il **pousse** donc le
     * compte ici, comme il pousse déjà les sessions et le marquage vu.
     *
     * ⚠️ Conséquence assumée : la priorité « en file » de l'état global ne s'applique qu'aux
     * sessions dont l'inbox a été lue. Une session en file qu'on n'a jamais ouverte ne remonte
     * pas dans l'en-tête — on ne l'affirme pas, et on ne l'invente pas non plus. Sans ce
     * mécanisme, `queuedCount` restait à **zéro pour toujours** : la priorité que le plan décrit
     * ne s'exécutait jamais.
     */
    fun publishQueue(sessionID: String, count: Int) {
        val current = _state.value.bySession[sessionID] ?: return
        if (current.queuedCount == count) return
        _state.value = _state.value.copy(
            bySession = _state.value.bySession + (
                sessionID to current.copy(
                    queuedCount = count,
                    // ⚠️ On rejoue la **même** precedence que [toActivity] sur les seuls etats
                    // que la file peut faire bouger : `Waiting > Unseen > Running` restent
                    // au-dessus (ils bloquent ou informent plus), `Queued` passe devant `Failed`
                    // et `Idle`. Sans cette reevaluation, un message pousse en file n'apparaitrait
                    // qu'au cycle suivant (12 s) — visiblement en retard.
                    activity = requeuedActivity(current.activity, count),
                )
                ),
        )
    }

    /**
     * L'activite telle qu'elle doit etre apres un changement de **file d'attente**.
     *
     * ⚠️ Miroir partiel de [Session.toActivity] : les etats superieurs a `Queued` dans l'ordre
     * (`Waiting`, `Unseen`, `Running`) sont **conserves** ; sinon la file prend la main. Volontairement
     * limite a ce que la file peut changer : deviner davantage demanderait de re-interroger le
     * serveur, et c'est le cycle suivant qui fait foi.
     */
    private fun requeuedActivity(current: Activity, count: Int): Activity = when {
        current == Activity.Waiting || current == Activity.Unseen || current == Activity.Running -> current
        count > 0 -> Activity.Queued
        current == Activity.Queued -> Activity.Idle
        else -> current
    }

    fun publishSessions(sessions: List<Session>) {
        knownSessions = sessions
        lastSessionScan = System.currentTimeMillis()
    }

    /**
     * Horodatage du dernier approvisionnement en sessions.
     *
     * ⚠️ Mesure a l'origine de ce champ : une session **creee apres** le chargement de la liste
     * n'apparaissait jamais dans l'etat — le monitor ne la decouvrait pas, puisque plus personne ne
     * rechargeait la liste de sessions. Le tour se terminait en 6 s, et l'en-tete continuait de
     * dire « rien » : exactement le reproche qu'on corrige.
     */
    private var lastSessionScan = 0L

    private var loop: kotlinx.coroutines.Job? = null

    /**
     * Signale qu'un écran se met à observer l'état.
     *
     * ⚠️ L'appel est **idempotent par abonné** : c'est à l'appelant de n'appeler [release] qu'une
     * fois. On ne compte pas les abonnements pour rien — un compteur qui fuit laisserait la boucle
     * allumée pour toujours, et c'est le bug le plus courant de ce motif.
     */
    fun acquire() {
        subscribers++
        if (loop == null) startLoop()
        // Le premier abonné veut l'état **maintenant**, pas dans 12 s.
        appScope.launch(dispatcher) { refresh() }
    }

    fun release() {
        subscribers = (subscribers - 1).coerceAtLeast(0)
        if (subscribers == 0) {
            loop?.cancel()
            loop = null
        }
    }

    private fun startLoop() {
        loop = appScope.launch(dispatcher) {
            while (isActive && subscribers > 0) {
                delay(POLL_INTERVAL_MS)
                refresh()
            }
        }
    }

    /**
     * Un cycle : on lit l'état, on le fusionne, on publie.
     *
     * ⚠️ **`refresh()` ne lance jamais d'exception** : une panne réseau laisse le dernier état
     * connu en place et note l'erreur. Remplacer l'état par du vide ferait disparaître les
     * indicateurs à chaque micro-coupure, ce qui est plus dérangeant qu'utile.
     *
     * ⚠️ Le `Mutex` protège contre les cycles qui s'empilent : sur un serveur lent, l'intervalle
     * court suffirait à accumuler les requêtes en vol.
     */
    suspend fun refresh() {
        refreshLock.withLock {
            val settings = store.current()
            if (!settings.isConfigured) {
                _state.value = FleetState(loading = false)
                return
            }
            try {
                // ⚠️ Les quatre lectures sont en **parallèle** : indépendantes, et
                // séquentielles elles empileraient la latence d'un cycle pour rien.
                // (Le commentaire disait « parallèle » alors que le code attendait chaque
                // appel l'un après l'autre : 4 allers-retours empilés par cycle de 12 s.)
                // ⚠️ Reapprovisionnement **periodique et lent** (voir SESSION_RESCAN_MS) : sans
                // lui, une session creee pendant que l'ecran est ouvert resterait invisible. Le
                // cout est borne et justifie — c'est ce qui rend l'etat vivant au lieu de figé.
                var activeIDs: Set<String> = emptySet()
                var shells: List<ShellInfoDto> = emptyList()
                var terminals: List<PtyInfoDto> = emptyList()
                var pendingPermissions: List<PermissionRequest> = emptyList()
                coroutineScope {
                    if (System.currentTimeMillis() - lastSessionScan > SESSION_RESCAN_MS) {
                        runCatching { gateway.allSessions(settings) }.onSuccess {
                            knownSessions = it
                            lastSessionScan = System.currentTimeMillis()
                        }
                    }

                    val active = async { gateway.activeSessions(settings) }
                    val shellsD = async { gateway.shells(settings) }
                    // ⚠️ Les terminaux sont lus **en plus** des shells : sur ce serveur, la route
                    // `POST /session/{id}/shell` rend 500 (bug de plugin), donc les shells seuls
                    // donneraient une image incomplete du travail de fond.
                    val terminalsD = async { runCatching { gateway.terminals(settings) }.getOrDefault(emptyList()) }
                    // Les permissions et les formulaires sont ce qui « attend ». Ils viennent d'une
                    // seule route globale : pas besoin de la demander par session.
                    val pendingPermissionsD = async { gateway.pendingPermissions(settings) }

                    activeIDs = active.await()
                    shells = shellsD.await()
                    terminals = terminalsD.await()
                    pendingPermissions = pendingPermissionsD.await()
                }

                // ⚠️ On croise les sessions **connues** avec ce qu'on vient d'interroger. Aucune
                // requete lourde n'est refaite : c'est le principe du detenteur unique.
                //
                // ⚠️ Le compte de file est **reporte** depuis l'etat precedent : il vient de
                // l'inbox, que ce cycle ne lit pas (une requete par session, hors de prix). Sans
                // ce report, un compte pousse par l'ecran de chat serait remis a zero toutes les
                // 12 s, et l'en-tete « en file » clignoterait.
                val previous = _state.value.bySession
                val activities = knownSessions.associate { session ->
                    session.id to session.toActivity(
                        activeIDs,
                        pendingPermissions.count { it.sessionID == session.id },
                        queued = previous[session.id]?.queuedCount ?: 0,
                    )
                }

                _state.value = FleetState(
                    bySession = activities,
                    shells = shells.map { it.toShellActivity() },
                    terminals = terminals.map { t ->
                        TerminalActivity(
                            id = t.id,
                            title = t.title,
                            command = t.command,
                            cwd = t.cwd,
                            status = t.status,
                            pid = t.pid,
                            exitCode = t.exitCode,
                        )
                    },
                    loading = false,
                    error = null,
                    polledAt = System.currentTimeMillis(),
                )
                // ⚠️ Un cycle réussi est une **preuve** que la connexion marche : on le dit au
                // monitor partagé plutôt que d'ajouter une sonde. L'état se met à jour sur du vécu.
                connection.markOnline(version = null)
            } catch (e: Exception) {
                val message = sh.sk7.tether.ui.settings.ConnectionErrors.describe(e)
                _state.value = _state.value.copy(
                    loading = false,
                    error = message,
                )
                connection.markOffline(
                    message,
                    // ⚠️ **On interroge l'erreur, pas son texte traduit.**
                    //
                    // Le code faisait `message.contains("401")` — sur la chaine produite par
                    // [ConnectionErrors.describe], qui traduit 401 en « Mot de passe refuse par le
                    // serveur. » **sans chiffre**. Le test etait donc toujours faux :
                    // `ConnectionStatus.Unauthorized` n'etait jamais produit, et l'ecran
                    // hors-connexion envoyait chercher une machine eteinte a quelqu'un dont le
                    // seul probleme etait un mot de passe errone.
                    //
                    // ⚠️ Le typage ne le disait pas : `message` est un `String`, `unauthorized`
                    // un `Boolean`, rien n'empechait de comparer du texte a une cause. C'est
                    // [ConnectionErrors.isUnauthorized] qui porte la regle, sur le TYPE.
                    unauthorized = sh.sk7.tether.ui.settings.ConnectionErrors.isUnauthorized(e),
                )
            }
        }
    }

    /**
     * Traduit une session serveur en état d'activité.
     *
     * ⚠️ **L'ordre des tests est le sens même de la fonction** : ce qui bloque prime sur ce qui
     * tourne, qui prime sur ce qui est fini. Une autorisation en attente immobilise la session ;
     * afficher « en cours » à ce moment-là ferait regarder l'écran sans agir.
     *
     * ⚠️ `queued` est **passé**, et non déduit de `Session` : le nombre de messages en file vient
     * de l'inbox, une route par session que seul l'écran de chat lit. Voir
     * [publishQueue].
     */
    private fun Session.toActivity(
        activeIDs: Set<String>,
        pendingCount: Int,
        queued: Int,
    ): SessionActivity {
        val idle = time?.idle
        val viewed = time?.viewed

        val activity = when {
            // 1. Une décision attend : la session est bloquée.
            pendingCount > 0 -> Activity.Waiting

            // 2. Le serveur dit explicitement que ça tourne.
            id in activeIDs -> Activity.Running

            // 3. Terminé mais jamais vu — deux horodatages DU SERVEUR, jamais l'horloge locale.
            idle != null && (viewed == null || idle > viewed) -> Activity.Unseen

            // 4. Un message attend son tour (`delivery: queue`). Il avancera tout seul : c'est
            //    une information, pas une demande d'action — donc après `Unseen`, avant `Failed`.
            queued > 0 -> Activity.Queued

            // 5. Terminé, vu, mais en échec.
            outcome == "failed" || outcome == "interrupted" -> Activity.Failed

            else -> Activity.Idle
        }

        return SessionActivity(
            sessionID = id,
            activity = activity,
            queuedCount = queued,
            waitingCount = pendingCount,
            idleAt = idle,
            viewedAt = viewed,
            outcome = outcome,
            parentID = parentID,
        )
    }

    private fun sh.sk7.tether.data.api.ShellInfoDto.toShellActivity(): ShellActivity = ShellActivity(
        id = id,
        command = command,
        status = status,
        // ⚠️ Mesure : le `sessionID` est dans `metadata`, pas à la racine. Le schéma OpenAPI ne le
        // détaille pas — s'y fier laissait croire qu'un shell n'était rattachable à rien.
        sessionID = sessionID,
        pid = pid,
        exitCode = exit,
        startedAt = time?.started,
        completedAt = time?.completed,
    )

    private companion object {
        /**
         * 12 s : assez réactif pour suivre une session qui enchaîne, assez lent pour ne pas
         * marteler un serveur local. Le cycle complet coûte trois requêtes de quelques centaines
         * d'octets.
         */
        const val POLL_INTERVAL_MS = 12_000L

        /**
         * 60 s : les sessions changent **peu**, leur etat change **vite**.
         *
         * ⚠️ Deux cadences distinctes, et c'est deliberé : interroger les sessions (lourd, 450
         * elements) a la meme frequence que les etats (leger) gaspillerait la bande passante pour
         * une donnee qui ne bouge presque pas. 60 s suffit a voir apparaitre une session nouvelle
         * sans payer le prix a chaque cycle.
         */
        const val SESSION_RESCAN_MS = 60_000L
    }
}
