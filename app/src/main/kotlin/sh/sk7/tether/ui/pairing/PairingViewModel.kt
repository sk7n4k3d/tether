package sh.sk7.tether.ui.pairing

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.TetherRpcException
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.push.DeviceIdentity
import sh.sk7.tether.push.DeviceRegistration
import sh.sk7.tether.push.PairingLink
import sh.sk7.tether.push.PushSubscription

/** Ce que l'ecran affiche, et rien de plus. */
data class PairingUiState(
    /** La demande en attente, ou `null` si rien n'a ete scanne. */
    val demande: PairingLink.Demande? = null,
    /** Ce que le distributeur a remis. `null` tant qu'il n'a rien fourni. */
    val abonnement: PushSubscription.Abonnement? = null,
    /** Un enregistrement est en cours : les boutons sont desactives. */
    val enCours: Boolean = false,
    /** Un message sous les boutons. `null` tant qu'il n'y a rien a dire. */
    val erreur: String? = null,
) {
    /** Peut-on autoriser ? Il faut une demande **et** un abonnement. */
    val peutAutoriser: Boolean get() = demande != null && abonnement != null && !enCours
}

/**
 * Faut-il traiter cette demande, ou a-t-elle deja ete traitee ?
 *
 * ### Le defaut que cette fonction empeche
 *
 * Android redonne l'`Intent` de lancement a **chaque recreation d'activite**. Tourner
 * l'ecran apres avoir appaire rejouerait donc le traitement : l'ecran de confirmation
 * reviendrait apres coup, alors que la demande est consommee et le jeton invalide.
 * L'utilisateur verrait un ecran de consentement pour un lien qui ne peut plus rien
 * donner — et n'aurait plus aucun moyen de s'en liberer, puisque le jeton est mort.
 *
 * ⚠️ « Deja traitee » veut dire **la meme** demande. Un deuxieme scan, meme serveur et
 * meme jeton, est traite comme neuf : c'est le seul cas ou l'utilisateur a reellement
 * rescann, et le lui refuser serait absurde. Un jeton a usage unique rejette de toute
 * facon le second essai, avec un message clair.
 *
 * Fonction pure et non methode : c'est elle qui porte la regle, et elle doit pouvoir
 * etre exercee sans Hilt, sans `Context` et sans Android.
 */
internal fun demandeATraiter(
    demande: PairingLink.Demande,
    dejaVue: PairingLink.Demande?,
): Boolean = demande != dejaVue

/**
 * L'appairage en attente, et l'enregistrement de cet appareil aupres du serveur demande.
 *
 * ## Pourquoi un ViewModel, et pas un `remember`
 *
 * L'appairage traverse une recreation d'activite : l'utilisateur scanne, puis tourne
 * l'ecran ou bascule sur une autre tache, et Android recree l'activite. Un `remember`
 * perdrait la demande — l'ecran afficherait « rien a appairer » alors que le scan a bien
 * eu lieu, et le jeton serait perdu. Le ViewModel survit a la recreation, ce qui est
 * exactement la duree de vie utile : de l'ouverture du QR a la decision.
 *
 * ## Ce que ce ViewModel ne fait pas
 *
 * Il ne **valide** pas le lien : c'est [PairingLink], en pur, et teste. Ici on recoit deja
 * une [PairingLink.Demande] construite. Une validation ici serait dupliquee, donc
 * divergerait de celle que le serveur applique.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val gateway: OpenCodeGateway,
    private val store: ConnectionStore,
    private val identity: DeviceIdentity,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    /** La derniere demande prise en charge, pour ne pas la rejouer (voir [ouvrir]). */
    private var demandeDejaVue: PairingLink.Demande? = null

    init {
        rafraichirAbonnement()
    }

    /**
     * Relit ce que le distributeur a deja remis.
     *
     * ⚠️ Appele au demarrage **et** a chaque [ouvrir]. Un distributeur peut parler apres
     * l'ouverture de l'ecran — c'est le cas general, puisque c'est le service de push qui
     * demarre. Sans ce second chargement, l'ecran afficherait « aucun distributeur » alors
     * qu'un point d'acces est arrive depuis.
     */
    fun rafraichirAbonnement() {
        _state.update { it.copy(abonnement = PushSubscription.load(context)) }
    }

    /**
     * Un lien d'appairage a ete scanne. Remplace toute demande en attente.
     *
     * Renvoie `false` si cette demande a **deja** ete prise en charge — voir
     * [demandeATraiter] pour pourquoi c'est une regle et non une commodite.
     */
    fun ouvrir(demande: PairingLink.Demande): Boolean {
        if (!demandeATraiter(demande, demandeDejaVue)) return false
        demandeDejaVue = demande
        _state.value = PairingUiState(
            demande = demande,
            abonnement = PushSubscription.load(context),
        )
        return true
    }

    /**
     * L'utilisateur a refuse.
     *
     * Aucune requete n'est partie — refuser ne fait **rien**, c'est le chemin le plus
     * simple, et il doit le rester. L'ecran se ferme sur `onFinished`, qui appelle ceci.
     */
    fun refuser() {
        _state.value = PairingUiState(abonnement = PushSubscription.load(context))
    }

    /**
     * Enregistre cet appareil aupres du serveur demande.
     *
     * ⚠️ Le jeton est **a usage unique** et le serveur le consomme **avant** d'eregistrer.
     * Un echec ne se rejoue donc pas : on remonte l'erreur, on ne boucle pas, et
     * l'utilisateur rescane. Boucler sur une erreur reseau consommerait un jeton deja
     * invalide et afficherait un echec qui n'est pas la vraie cause.
     */
    fun autoriser() {
        val demande = _state.value.demande ?: return
        val abonnement = _state.value.abonnement ?: return
        if (_state.value.enCours) return

        _state.update { it.copy(enCours = true, erreur = null) }
        scope.launch {
            val settings = store.current()
            runCatching {
                gateway.registerDevice(
                    settings = settings,
                    server = demande.server,
                    deviceId = identity.id(),
                    endpoint = abonnement.url,
                    p256dh = abonnement.p256dh,
                    authSecret = abonnement.auth,
                    pairingToken = demande.token,
                    distributor = abonnement.distributor,
                )
            }
                .onSuccess { ok ->
                    if (ok) {
                        // ⚠️ Le serveur n'est retenu **qu'apres** un enregistrement reussi.
                        // L'inverse ferait croire a l'app qu'elle est connectee alors que le
                        // serveur ne l'a jamais entendue, et son renouvellement d'endpoint
                        // echouerait en silence, indefiniment.
                        //
                        // Le jeton, lui, n'est **pas** conserve : a usage unique, un secret
                        // deja consomme ne sert plus a rien.
                        DeviceRegistration.remember(context, demande.server, settings.directory)
                        _state.value = PairingUiState(abonnement = abonnement)
                    } else {
                        _state.update {
                            it.copy(enCours = false, erreur = "Le serveur a refuse l'enregistrement.")
                        }
                    }
                }
                .onFailure { cause ->
                    _state.update {
                        it.copy(
                            enCours = false,
                            erreur = if (cause is TetherRpcException && cause.jetonPerdu) {
                                "Ce QR a expire ou a deja servi. Relancez l'appairage."
                            } else {
                                cause.message ?: "Enregistrement impossible."
                            },
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
