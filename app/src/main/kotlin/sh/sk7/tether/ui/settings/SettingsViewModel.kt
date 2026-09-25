package sh.sk7.tether.ui.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
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
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors

/** Ce que les reglages affichent de la connexion courante. */
data class SettingsUiState(
    val baseUrl: String = "",
    val directory: String = "",
    val hasPassword: Boolean = false,
    val version: String? = null,
    val checking: Boolean = false,
    val reachable: Boolean? = null,
    val error: String? = null,
    /**
     * Les reglages ont-ils ete lus ?
     *
     * ⚠️ **Indispensable, et c'est un bug attrape en testant** : `hasPassword` vaut `false` dans
     * l'etat initial. Sans ce drapeau, l'ecran concluait « pas de mot de passe » **avant meme
     * d'avoir lu le disque**, et redirigeait vers la connexion a chaque ouverture. Le drapeau
     * distingue « je n'ai pas encore lu » de « j'ai lu, et il n'y a rien ».
     */
    val loaded: Boolean = false,
) {
    /** L'adresse affichee sans schema ni chemin, pour tenir sur une ligne. */
    val host: String
        get() = baseUrl.removePrefix("http://").removePrefix("https://").trimEnd('/')
}

/**
 * Les reglages : **l'etat de l'app, pas un formulaire**.
 *
 * ### Ce qui change par rapport a l'ancien ecran
 * L'ecran de connexion etait un **formulaire** : trois champs, un bouton, et rien sur l'etat
 * reel. Ici c'est l'inverse — on **montre** d'abord (a quoi on est connecte, est-ce que ca
 * repond, quelle version), et on propose les actions ensuite. Un utilisateur qui ouvre les
 * reglages veut savoir **ou il en est**, pas retaper une URL.
 *
 * ⚠️ On separe **les reglages** (cet ecran) de **l'onboarding** (`ConnectionScreen` en
 * `firstRun`) : ce sont deux situations differentes. La premiere fois, on guide ; ensuite, on
 * informe et on laisse agir.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val settings = store.current()
            _state.update {
                it.copy(
                    baseUrl = settings.baseUrl,
                    directory = settings.directory,
                    hasPassword = settings.password.isNotBlank(),
                    // ⚠️ On marque la lecture AVANT `check()` : l'ecran peut ainsi distinguer
                    // « pas encore lu » de « lu, rien configure ».
                    loaded = true,
                )
            }
            check()
        }
    }

    override fun onCleared() {
        scope.cancel()
    }

    /**
     * Verifie que le serveur repond, et note sa version.
     *
     * ⚠️ On met a jour [SettingsUiState.version] **et** on efface l'erreur precedente : sans ca,
     * une connexion retablie continuerait d'afficher l'ancien message d'echec, et l'utilisateur
     * croirait que rien n'a change.
     */
    fun check() {
        _state.update { it.copy(checking = true, error = null) }
        scope.launch {
            try {
                val info = gateway.info(store.current())
                _state.update {
                    it.copy(checking = false, reachable = true, version = info.version, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        checking = false,
                        reachable = false,
                        version = null,
                        error = ConnectionErrors.describe(e),
                    )
                }
            }
        }
    }

    /**
     * Efface les identifiants.
     *
     * ⚠️ On repasse en `NotConfigured` : c'est ce qui fera reapparaitre l'ecran d'accueil au lieu
     * d'un ecran qui echoue en boucle sur une adresse qu'on voulait oublier.
     */
    fun disconnect() {
        scope.launch {
            store.save(
                sh.sk7.tether.data.settings.ConnectionSettings(
                    baseUrl = "",
                    password = "",
                    directory = "",
                ),
            )
            _state.update {
                it.copy(
                    hasPassword = false,
                    // ⚠️ `loaded` reste vrai : on a bien lu, et il n'y a plus rien. C'est ce qui
                    // autorise la redirection vers l'ecran de connexion apres une deconnexion.
                    loaded = true,
                    version = null,
                    reachable = null,
                    error = null,
                )
            }
        }
    }
}
