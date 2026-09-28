package sh.sk7.tether.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.sk7.tether.data.settings.AppearanceStore
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionStatus

/**
 * **Ou l'application doit-elle s'ouvrir ?**
 *
 * ### Pourquoi une decision centralisee
 * Avant, `TetherNavHost` demarrait toujours sur la liste des sessions. Au premier lancement, cette
 * liste ne pouvait rien afficher de juste : l'app n'avait ni adresse ni mot de passe. L'utilisateur
 * tombait donc sur « aucun serveur configure » **au milieu** de l'app, alors que la seule chose a
 * faire etait de renseigner une adresse.
 *
 * ⚠️ La decision prend en compte **deux axes distincts** :
 *  - la configuration (`NotConfigured`) est **connue immediatement**, sans appel reseau ;
 *  - la joignabilite demande un **appel reel**, donc on ne bloque pas l'ouverture dessus. On ouvre
 *    sur les sessions et c'est la liste qui signalera la panne — attendre un appel reseau avant
 *    d'afficher quoi que ce soit ferait clignoter un ecran vide a chaque lancement.
 *
 * ⚠️ **Aucun `Unknown` n'est traite comme une panne** : au demarrage on ne sait pas encore si le
 * serveur repond, et afficher « hors connexion » a ce moment-la serait un mensonge.
 */
sealed interface StartDestination {
    /**
     * Jamais configure, et l'accueil n'a jamais ete vu : on presente l'app avant de
     * demander quoi que ce soit.
     *
     * ⚠️ **Ce cas ne se distingue de [Onboarding] que par une seule chose** : le drapeau
     * « accueil vu ». Une app desinstallee puis reinstallee repasse par ici, et c'est
     * voulu — le nouvel utilisateur n'a pas plus de contexte que le premier.
     */
    data object Welcome : StartDestination

    /** Rien n'est configure : on guide vers la connexion, sans retour possible. */
    data object Onboarding : StartDestination

    /** Configure : on ouvre sur les sessions. La panne eventuelle se dira dans la liste. */
    data object Sessions : StartDestination
}

@HiltViewModel
class StartRouterViewModel @Inject constructor(
    private val monitor: ConnectionMonitor,
    private val apparence: AppearanceStore,
) : ViewModel() {

    private val _destination = MutableStateFlow<StartDestination?>(null)

    /**
     * `null` tant que la lecture des reglages n'est pas finie.
     *
     * ⚠️ **Indispensable, et c'est le meme piege que l'ecran Reglages** : sans cet etat
     * intermediaire, on conclurait « pas configure » avant meme d'avoir lu le disque, et une app
     * parfaitement configuree ouvrirait sur l'ecran de connexion a chaque lancement.
     */
    val destination: StateFlow<StartDestination?> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            // ⚠️ Les deux lectures se font **avant** la decision : l'accueil ne tranche
            // que dans le cas « pas configure », mais le lire conditionnellement
            // ferait dependre le resultat d'un ordre d'execution.
            val configure = monitor.configured.first()
            val accueilVu = apparence.accueilVu.first()
            _destination.value = destinationDeDepart(configure, accueilVu)
            // ⚠️ On demande a `ConnectionMonitor` de poser l'etat initial qui convient : sans cela,
            // l'app resterait sur `Unknown` alors qu'elle sait deja qu'elle n'est pas configuree.
            monitor.refreshFromSettings()
        }
    }
}

/**
 * **Ou ouvrir, a partir de deux faits.**
 *
 * Extraite du ViewModel parce que c'est la seule chose ici qui puisse avoir tort : le
 * reste lit des flux et les passe. Un ViewModel a besoin de Hilt, d'un `Context` et d'un
 * serveur ; aucune de ces trois choses n'est ce qu'on veut opposer a une table de
 * verite de quatre lignes.
 *
 * ⚠️ **`configure` gagne toujours.** Une app configuree ouvre sur les sessions, meme si
 * l'accueil n'a jamais ete vu — le carrousel ne sert qu'a expliquer ce qu'on s'apprete a
 * configurer, et quelqu'un qui a deja ses reglages n'a plus rien a y apprendre.
 */
internal fun destinationDeDepart(configure: Boolean, accueilVu: Boolean): StartDestination = when {
    configure -> StartDestination.Sessions
    accueilVu -> StartDestination.Onboarding
    else -> StartDestination.Welcome
}
