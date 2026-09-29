package sh.sk7.tether.push

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **L'app est-elle visible à l'écran, là, maintenant ?**
 *
 * ### Pourquoi ce détenteur existe
 * Notifier quelque chose que l'utilisateur a sous les yeux est du bruit (tâche 2.2). Il faut donc
 * pouvoir répondre à « l'app est-elle au premier plan ? » depuis le service de push, qui **ne
 * participe pas** au cycle de vie des écrans.
 *
 * ### Pourquoi des callbacks d'activité, et pas `ProcessLifecycleOwner`
 * ⚠️ `ProcessLifecycleOwner` vient de `androidx.lifecycle:lifecycle-process`. Il est **peut-être**
 * déjà transitif, mais s'en servir serait dépendre d'une bibliothèque qu'on n'a **pas déclarée**
 * — donc du hasard de la résolution Gradle. Les callbacks d'activité sont dans le framework : zéro
 * dépendance, et le comportement est explicite.
 *
 * ⚠️ **Compteur et non booléen** : pendant une transition (rotation, passage d'un écran à un
 * autre), une activité se termine **après** que la suivante a démarré. Un booléen remis à `false`
 * par le `onActivityStopped` de la première ferait croire à un passage en arrière-plan alors que
 * l'utilisateur est toujours dans l'app. Le compteur ne tombe à zéro que quand **plus aucune**
 * activité n'est visible.
 */
@Singleton
class ForegroundState @Inject constructor() : Application.ActivityLifecycleCallbacks {

    @Volatile
    private var startedActivities = 0

    @Volatile
    private var appContext: Context? = null

    /** Vrai si au moins un écran de Tether est visible. */
    val isForeground: Boolean get() = startedActivities > 0

    /** Enregistre le suivi sur le processus. Appelé une fois, au démarrage de l'app. */
    fun register(app: Application) {
        appContext = app
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        // ⚠️ Au PREMIER ecran visible (0 -> 1), on rafraichit l'endpoint sur le relais. Ce n'est
        // pas un detail : le distributeur ne re-annonce l'endpoint qu'au demarrage du PROCESSUS,
        // or le processus survit des heures en arriere-plan. Le message du relais, lui, expire
        // (cache-duration ntfy). Sans ce rafraichissement, un endpoint reste perime jusqu'a la
        // prochaine ouverture a froid — et le plugin se replie alors sur un topic que Tether
        // n'ecoute pas : notifications muettes, sans erreur nulle part.
        if (startedActivities == 1) {
            appContext?.let { redeclarerAbonnement(it) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
