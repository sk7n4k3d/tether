package sh.sk7.tether.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionHealth
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionStatus
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.ui.settings.ConnectionErrors

/**
 * **Un etat d'appel, avec sa cause probable et sa sortie.**
 *
 * ⚠️ Le corps repond aux **trois** questions qu'on se pose devant un ecran qui ne marche pas :
 * ce qui s'est passe, pourquoi (la cause la plus probable), et quoi faire. Un message comme
 * « erreur reseau » laisse l'utilisateur sans action — c'est ce qui donne l'impression d'une app
 * inachevee.
 */
data class OfflineUiState(
    val health: ConnectionHealth = ConnectionHealth(),
    val checking: Boolean = false,
    val host: String = "",
) {
    val status: ConnectionStatus get() = health.status
}

/**
 * **Quand le serveur ne repond pas, l'app le dit — et dit quoi faire.**
 *
 * ### Pourquoi cet ecran est obligatoire
 * Le serveur opencode tourne sur une machine du homelab, joint par Tailscale ou le reseau local.
 * Il est donc **normal** qu'il soit injoignable : machine eteinte, tunnel coupe, autre reseau. Une
 * app compagne qui affiche un ecran vide ou une erreur technique dans ce cas ne rend aucun service
 * — c'est exactement le reproche « les pages hors connexion sont absentes ».
 *
 * ### La distinction qui structure tout
 * ⚠️ **Trois pannes n'ont rien a voir**, et les confondre envoie l'utilisateur au mauvais endroit :
 *  - [ConnectionStatus.NotConfigured] : rien n'est renseigne -> on guide vers la connexion ;
 *  - [ConnectionStatus.Offline] : configure mais muet -> reseau, serveur eteint, mauvaise adresse ;
 *  - [ConnectionStatus.Unauthorized] : il repond mais refuse -> mot de passe errone.
 *
 * Dire « pas de reseau » a quelqu'un dont le mot de passe est faux lui ferait chercher un probleme
 * qui n'existe pas.
 */
@HiltViewModel
class OfflineViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    private val monitor: ConnectionMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(OfflineUiState())
    val state: StateFlow<OfflineUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = store.current()
            _state.update {
                it.copy(host = settings.baseUrl.removePrefix("http://").removePrefix("https://").trimEnd('/'))
            }
            // ⚠️ On observe l'etat **partage** : si un autre ecran reussit un appel pendant qu'on
            // est ici, la banniere doit disparaitre sans qu'on ait rien a faire.
            monitor.health.collect { health ->
                _state.update { it.copy(health = health) }
            }
        }
    }

    /**
     * Retente une connexion **reelle**.
     *
     * ⚠️ On appelle `/api/info` plutot que de faire un `ping` : c'est la meme route que celle
     * utilisee partout ailleurs pour verifier la connexion, donc un succes ici **prouve** que le
     * reste marchera. Un ping reussi qui ne dit rien de l'API serait une fausse bonne nouvelle.
     */
    fun retry() {
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                val current = store.current()
                val info = gateway.info(current)
                monitor.markOnline(info.version)
                _state.update { it.copy(checking = false) }
            } catch (e: Exception) {
                val message = ConnectionErrors.describe(e)
                val unauthorized = message.contains("401") || message.contains("403", ignoreCase = true)
                monitor.markOffline(message, unauthorized)
                _state.update { it.copy(checking = false) }
            }
        }
    }
}
