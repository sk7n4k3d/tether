package sh.sk7.tether.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import sh.sk7.tether.data.settings.AppearanceStore

/**
 * Le theme, **branche sur le choix de l'utilisateur**.
 *
 * ## Pourquoi deux fonctions
 *
 * [TetherTheme] est pure : elle recoit un `Accent` et n'accede a rien. C'est ce qui la
 * rend testable, et ce qui permet a une preview de forcer une teinte sans DataStore.
 *
 * Celle-ci lit le choix en amont et le transmet. La separation evite que le theme
 * **tire lui-meme** ses reglages — et qu'une preview doive declencher une lecture de
 * disque pour s'afficher.
 *
 * ## Pourquoi le porteur est en parametre, sans valeur par defaut
 *
 * `hiltViewModel()` cherche un `ViewModelStoreOwner` et un `lifecycle` dans le
 * `CompositionLocal` — et ces valeurs ne sont disponibles que dans le **corps** d'un
 * composable appele depuis une `NavHost` ou une `Activity`.
 *
 * Une valeur par defaut de parametre est evaluee dans le contexte de l'appelant, donc
 * meme ici elle echoue : `No value passed for parameter 'lifecycle'`. On ne peut donc
 * resoudre le porteur qu'au point d'entree, ou le contexte existe — ce que fait
 * `MainActivity`. C'est aussi plus juste : le theme doit vivre aussi longtemps que
 * l'activite, pas celle du dernier ecran visite.
 */
@Composable
fun TetherThemed(
    appearance: AppearanceViewModel,
    content: @Composable () -> Unit,
) {
    // ⚠️ `collectAsStateWithLifecycle()` sur un `Flow` **nu** demande un `initialValue`
    // et un `lifecycle` explicites : le premier rendu n'a encore rien lu, donc il n'y a
    // pas de valeur a fournir — d'ou l'ecran vide au demarrage.
    //
    // Un `StateFlow` n'a pas ce probleme : il a toujours une valeur. On convertit donc
    // ici, plutot que de donner une valeur par defaut qui serait **fausse** pendant une
    // frame — le theme changerait de couleur au premier ecran, puis se corrigerait.
    val accent by remember(appearance) { appearance.store.accent }
        .collectAsState(initial = Accent.parDefaut)
    TetherTheme(accent = accent, content = content)
}

/**
 * Un porteur trivial, pour que Hilt fournisse le singleton a un composable.
 *
 * Il ne porte aucun etat : l'accent vient du store, le store est un singleton, et rien
 * ici ne survit a une rotation. Il n'existe que parce que Hilt ne resout un singleton
 * que dans un composant.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    val store: AppearanceStore,
) : androidx.lifecycle.ViewModel() {

    /**
     * Marque l'accueil comme vu, **une fois pour toutes**.
     *
     * ⚠️ Le drapeau est ecrit meme si l'ecriture echoue, parce qu'on ne peut rien y faire
     * et que bloquer la navigation pour un booleen serait pire : l'utilisateur resterait
     * sur le carrousel qu'il vient de quitter. Le pire cas est de revoir l'accueil une
     * fois de trop.
     */
    fun marquerAccueilVu() {
        viewModelScope.launch {
            store.marquerAccueilVu()
        }
    }
}
