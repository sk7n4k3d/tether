package sh.sk7.tether

import android.app.Application
import sh.sk7.tether.push.ForegroundState
import sh.sk7.tether.push.registerForPush
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import sh.sk7.tether.ui.i18n.Res

@HiltAndroidApp
class TetherApp : Application() {

    /**
     * ⚠️ Injecté sur l'`Application` (et non lu au moment du push) : le suivi du premier plan doit
     * être branché **avant** tout affichage, sinon le premier message reçu pourrait croire que
     * l'app est en arrière-plan alors qu'elle est ouverte.
     */
    @Inject
    lateinit var foregroundState: ForegroundState

    override fun onCreate() {
        super.onCreate()
        // ⚠️ Avant tout le reste : sans cela, un ViewModel qui prepare un message
        // d'erreur n'a aucune ressource a traduire, et l'ecran affiche `res:<id>` a
        // la place du texte. C'est un etat global, donc il doit etre pose une fois.
        Res.installer(this)
        foregroundState.register(this)
        // ⚠️ L'enregistrement UnifiedPush est **idempotent** : le refaire a chaque demarrage
        // est le comportement attendu par la bibliotheque. Il echoue proprement (et sans bruit
        // pour l'utilisateur) si aucun distributeur n'est installe : l'app reste utilisable,
        // seules les notifications manquent.
        registerForPush(this)
    }
}
