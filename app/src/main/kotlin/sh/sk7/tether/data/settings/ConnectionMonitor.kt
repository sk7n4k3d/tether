package sh.sk7.tether.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **L'etat de connexion, au niveau de l'application.**
 *
 * ### Pourquoi ce type existe
 * Avant, chaque ecran decouvrait la panne **a sa facon** : la liste affichait « Connexion
 * impossible », le chat affichait une erreur en bas, et le reste ne disait rien. Resultat : trois
 * formulations differentes pour le meme fait, aucune ne sachant si le probleme venait du reseau,
 * du mot de passe ou de l'URL. C'est exactement ce qui donne l'impression d'une app inachevee.
 *
 * Ici on nomme les etats **une fois**, et chaque ecran dit la meme chose de la meme facon.
 *
 * ### Deux axes distincts, jamais confondus
 *  - **configure** : a-t-on de quoi se connecter ? (URL + mot de passe enregistres)
 *  - **joignable** : le serveur repond-il *maintenant* ?
 *
 * ⚠️ Les melanger etait le defaut principal : une app configuree mais dont le serveur est
 * eteint est un cas **different** d'une app jamais configuree, et l'utilisateur doit pouvoir
 * les distinguer pour savoir quoi faire.
 */
enum class ConnectionStatus {
    /** Jamais configure : c'est la premiere ouverture, on doit guider. */
    NotConfigured,

    /** Configure, mais on n'a pas encore teste (ouverture de l'app). */
    Unknown,

    /** Le serveur repond. */
    Online,

    /** Configure, mais injoignable : reseau coupe, serveur eteint, ou mauvaise adresse. */
    Offline,

    /** Le serveur repond mais refuse les identifiants. */
    Unauthorized,
}

/** Ce que l'app sait de sa connexion, en un seul endroit. */
data class ConnectionHealth(
    val status: ConnectionStatus = ConnectionStatus.Unknown,
    val version: String? = null,
    /**
     * Message lisible de la derniere panne.
     *
     * ⚠️ **Jamais le mot de passe** (regle de `ConnectionErrors`). Ce texte est destine a etre
     * affiche tel quel, donc il doit rester comprehensible sans le journal.
     */
    val message: String? = null,
    /** Horodatage de la derniere verification reussie (millisecondes locales). */
    val lastOnlineAt: Long? = null,
) {
    val isOnline: Boolean get() = status == ConnectionStatus.Online
    val canUseApp: Boolean get() = status != ConnectionStatus.NotConfigured
}

/**
 * Detient l'etat de connexion et le maintient a jour.
 *
 * ⚠️ **Un seul detenteur pour toute l'app** (singleton Hilt) : si chaque ecran sondait le
 * serveur de son cote, on multiplierait les requetes et les ecrans pourraient afficher des etats
 * contradictoires.
 *
 * ⚠️ [markOnline] et [markOffline] sont appeles par les ecrans qui **viennent d'echouer ou de
 * reussir** un appel reel. C'est volontaire : l'etat se met a jour sur du **vecu**, jamais sur
 * une supposition. Pas de sonde periodique — elle couterait de la batterie pour une information
 * que les appels reels donnent deja.
 */
@Singleton
class ConnectionMonitor @Inject constructor(
    private val store: ConnectionStore,
) {
    private val _health = MutableStateFlow(ConnectionHealth())

    /** Etat courant, observable par toute l'app. */
    val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    /**
     * Vrai si l'app est configuree, **en observant** les reglages.
     *
     * ⚠️ Un `Flow` et non un `Boolean` : si l'utilisateur enregistre son mot de passe, l'app
     * doit sortir de « non configure » **sans redemarrage**. Un instantane fige laisserait
     * l'ecran d'accueil affiche apres une connexion reussie.
     */
    val configured: Flow<Boolean> = store.settings.map { it.isConfigured }

    init {
        // Au demarrage, on est « configure ou pas », sans encore savoir si le serveur repond.
        _health.value = ConnectionHealth(status = ConnectionStatus.Unknown)
    }

    /** A appeler juste avant une requete, pour signaler qu'on ne sait pas encore. */
    fun markChecking() {
        if (_health.value.status == ConnectionStatus.Online) return
        _health.updateStatus(ConnectionStatus.Unknown)
    }

    /** Le serveur a repondu : on note la version et l'instant. */
    fun markOnline(version: String?) {
        _health.value = ConnectionHealth(
            status = ConnectionStatus.Online,
            version = version,
            lastOnlineAt = System.currentTimeMillis(),
        )
    }

    /**
     * Un appel a echoue : on qualifie la panne.
     *
     * ⚠️ On **distingue** le refus d'identifiants (401/403) de l'injoignabilite. Les confondre
     * ferait dire « pas de reseau » a quelqu'un dont le seul probleme est un mot de passe
     * errone — et il chercherait au mauvais endroit.
     */
    fun markOffline(message: String, unauthorized: Boolean = false) {
        _health.value = _health.value.copy(
            status = if (unauthorized) ConnectionStatus.Unauthorized else ConnectionStatus.Offline,
            message = message,
        )
    }

    /** Verifie la configuration courante et pose l'etat initial qui convient. */
    suspend fun refreshFromSettings(): ConnectionHealth {
        val settings = store.current()
        if (!settings.isConfigured) {
            _health.value = ConnectionHealth(status = ConnectionStatus.NotConfigured)
        }
        return _health.value
    }

    private fun MutableStateFlow<ConnectionHealth>.updateStatus(status: ConnectionStatus) {
        value = value.copy(status = status)
    }
}
