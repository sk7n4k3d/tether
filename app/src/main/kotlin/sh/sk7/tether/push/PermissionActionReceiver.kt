package sh.sk7.tether.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import javax.inject.Inject

/**
 * **Repond a une demande d'autorisation depuis la notification, sans ouvrir l'app.**
 *
 * ### Ce que cela change
 * Une autorisation immobilise l'agent. Avant, il fallait deverrouiller, ouvrir l'app, aller dans
 * Approbations, lire la commande, repondre. Des heures, parfois. Le refus, lui, n'etait possible
 * que de la meme facon — alors que **refuser est le geste de secours** quand on ne sait pas.
 *
 * ### Ce que ce receiver ne fait pas
 * ⚠️ **Il n'invente jamais l'identifiant de la demande.** Il le recoit de la notification, et cette
 * notification l'a lu sur `GET /api/permission/request` — c'est-a-dire **du serveur**. Le topic
 * ntfy accepte l'ecriture anonyme : un tiers peut publier, mais il ne peut pas choisir ce sur quoi
 * ce bouton agit. Voir [PendingApproval].
 *
 * ⚠️ **Le mot de passe est relu, pas suppose.** Si le processus vient d'etre relance par le push,
 * `InMemoryCredentialsProvider` est vide. C'est [ConnectionStore.current] qui le republie avant
 * l'appel — sans quoi le premier bouton presse apres un froid echouerait systematiquement, ce
 * qui ressemblerait a un bouton mort.
 */
@AndroidEntryPoint
class PermissionActionReceiver : BroadcastReceiver() {

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher

    @Inject
    lateinit var connectionStore: ConnectionStore

    @Inject
    lateinit var gateway: sh.sk7.tether.data.api.OpenCodeGateway

    override fun onReceive(context: Context, intent: Intent) {
        val requestID = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val sessionID = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val wire = intent.getStringExtra(EXTRA_DECISION).orEmpty()
        val decision = runCatching { PermissionDecision.entries.first { it.wire == wire } }.getOrNull()

        // ⚠️ Garde-fou : une intent incomplete ne doit rien tenter. On n'appelle pas l'API avec un
        // identifiant vide « pour voir » — une requete qui ne peut pas aboutir est pire que rien.
        if (requestID.isBlank() || sessionID.isBlank() || decision == null) {
            Log.w(TAG, "action incomplete, ignoree : req=$requestID session=$sessionID decision=$wire")
            return
        }

        // ⚠️ `goAsync()` : le reseau ne doit pas tourner dans `onReceive`, et le systeme tuerait
        // le process sinon. Le resultat tient dans la `PendingResult`.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        scope.launch {
            try {
                // ⚠️ `current()` republie les identifiants dans le `CredentialsProvider` : c'est
                // lui qui rend l'appel possible apres un redemarrage du process par le push.
                val settings = connectionStore.current()
                if (!settings.isConfigured) {
                    TetherNotifier.showDecisionResult(context, false, "Serveur non configuré.")
                    return@launch
                }
                val ok = runCatching {
                    gateway.replyPermission(settings, sessionID, requestID, decision)
                }.getOrDefault(false)
                if (ok) {
                    // ⚠️ **On trace le succes, pas seulement l'echec.** Mesure du 2026-09-27 : sans
                    // cette ligne, une action reussie ne laissait AUCUNE trace dans logcat, et un
                    // « le bouton ne fait rien » devenait impossible a distinguer d'un bouton
                    // presse et d'un bouton jamais recu.
                    Log.i(
                        TAG,
                        "decision appliquee : request=$requestID session=$sessionID " +
                            "choix=${decision.wire}",
                    )
                    // La notification d'attente n'a plus lieu d'etre : l'agent n'est plus bloque.
                    // La laisser, c'est un pense-bete qui ment sur l'etat de la session.
                    TetherNotifier.clearOngoing(context)
                    TetherNotifier.showDecisionResult(context, true, decision.resultLabel())
                } else {
                    // ⚠️ **On ne pretend pas avoir repondu.** Le serveur a refuse : l'agent est
                    // toujours bloque, et l'utilisateur doit le savoir plutot que de croire avoir
                    // debloque la session.
                    TetherNotifier.showDecisionResult(
                        context,
                        false,
                        "Le serveur a refusé. L'agent attend toujours.",
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "action en echec : ${e.javaClass.simpleName}", e)
                TetherNotifier.showDecisionResult(context, false, "Action impossible.")
            } finally {
                scope.cancel()
                pending.finish()
            }
        }
    }

    private fun PermissionDecision.resultLabel(): String = when (this) {
        PermissionDecision.Reject -> "Refusée"
        PermissionDecision.Once -> "Autorisée une fois"
        PermissionDecision.Always -> "Toujours autorisée"
    }

    companion object {
        private const val TAG = "TetherPermissionAction"

        const val EXTRA_REQUEST_ID = "sh.sk7.tether.REQUEST_ID"
        const val EXTRA_SESSION_ID = "sh.sk7.tether.SESSION_ID"
        const val EXTRA_DECISION = "sh.sk7.tether.DECISION"

        /** Route du receiver, dans le manifest ET dans chaque `Intent` explicite. */
        const val ACTION = "sh.sk7.tether.PERMISSION_DECISION"
    }
}
