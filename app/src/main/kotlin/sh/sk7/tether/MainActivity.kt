package sh.sk7.tether

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import sh.sk7.tether.ui.TetherRoot
import sh.sk7.tether.ui.i18n.LangueCache
import sh.sk7.tether.ui.permission.LocalNetworkGate
import sh.sk7.tether.ui.theme.TetherBackground
import sh.sk7.tether.ui.theme.TetherThemed

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * La langue s'applique **ici**, et nulle part ailleurs.
     *
     * `attachBaseContext` est le seul moment ou une `Activity` peut choisir ses locales :
     // plus tard, les ressources sont deja chargees, et les changer ne
     * reconstruirait pas l'ecran deja compose. En amont de `super.onCreate`, comme il
     * se doit.
     *
     * Un `context` deja de langue differente — cas d'un changement d'accent suivi d'une
     * recreation — est reutilise tel quel : on ne reapplique rien par-dessus, sinon les
     * locales s'empilent.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LangueCache.appliquer(newBase, newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Le porteur est resolu **ici**, pas dans `TetherThemed` : c'est le seul
            // endroit ou un `ViewModelStoreOwner` existe. Le theme dure autant que
            // l'activite, ce qui est le bon cycle de vie pour une preference d'ecran.
            TetherThemed(appearance = hiltViewModel()) {
                Surface(modifier = Modifier.fillMaxSize(), color = TetherBackground) {
                    LocalNetworkGate { TetherRoot() }
                }
            }
        }
    }
}
