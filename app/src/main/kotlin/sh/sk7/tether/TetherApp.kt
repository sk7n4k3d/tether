package sh.sk7.tether

import android.app.Application
import sh.sk7.tether.push.registerForPush
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TetherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ⚠️ L'enregistrement UnifiedPush est **idempotent** : le refaire a chaque demarrage
        // est le comportement attendu par la bibliotheque. Il echoue proprement (et sans bruit
        // pour l'utilisateur) si aucun distributeur n'est installe : l'app reste utilisable,
        // seules les notifications manquent.
        registerForPush(this)
    }
}
