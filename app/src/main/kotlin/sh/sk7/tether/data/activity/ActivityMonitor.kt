package sh.sk7.tether.data.activity

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.ApplicationScope
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.domain.model.Activity
import sh.sk7.tether.domain.model.FleetState
import sh.sk7.tether.domain.model.SessionActivity
import sh.sk7.tether.domain.model.ShellActivity

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
                // ⚠️ Les trois lectures sont en **parallèle** : indépendantes, et
                // séquentielles elles tripleraient la latence d'un cycle pour rien.
                val sessions = gateway.allSessions(settings)
                val activeIDs = gateway.activeSessions(settings)
                val shells = gateway.shells(settings)
                // Les permissions et les formulaires sont ce qui « attend ». Ils viennent d'une
                // seule route globale : pas besoin de la demander par session.
                val pendingPermissions = gateway.pendingPermissions(settings)

                val activities = sessions.associate { session ->
                    session.id to session.toActivity(activeIDs, pendingPermissions.count { it.sessionID == session.id })
                }

                _state.value = FleetState(
                    bySession = activities,
                    shells = shells.map { it.toShellActivity() },
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
                    unauthorized = message.contains("401") || message.contains("403"),
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
     */
    private fun Session.toActivity(activeIDs: Set<String>, pendingCount: Int): SessionActivity {
        val idle = time?.idle
        val viewed = time?.viewed

        val activity = when {
            // 1. Une décision attend : la session est bloquée.
            pendingCount > 0 -> Activity.Waiting

            // 2. Le serveur dit explicitement que ça tourne.
            id in activeIDs -> Activity.Running

            // 3. Terminé mais jamais vu — deux horodatages DU SERVEUR, jamais l'horloge locale.
            idle != null && (viewed == null || idle > viewed) -> Activity.Unseen

            // 4. Terminé, vu, mais en échec.
            outcome == "failed" || outcome == "interrupted" -> Activity.Failed

            else -> Activity.Idle
        }

        return SessionActivity(
            sessionID = id,
            activity = activity,
            waitingCount = pendingCount,
            idleAt = idle,
            viewedAt = viewed,
            outcome = outcome,
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
    }
}
