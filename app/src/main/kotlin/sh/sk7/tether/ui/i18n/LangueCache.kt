package sh.sk7.tether.ui.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * **La langue choisie, en lecture synchrone.**
 *
 * ## Pourquoi un miroir de [sh.sk7.tether.data.settings.AppearanceStore]
 *
 * La langue doit s'appliquer **avant** la premiere activity, dans `attachBaseContext`.
 * Or ce moment-la ne permet pas de lire un `DataStore` : c'est une API suspend, et
 * `attachBaseContext` est un `override` synchrone appele par le framework, sans
 * coroutine.
 *
 * Il y a deux facons de contourner. La premiere — lire une preference bloqueante dans
 * un thread bloquant — se paie au demarrage, sur l'ecran de lancement, et c'est
 * exactement le moment ou l'utilisateur regarde. La seconde, la copie synchrone : on
 * ecrit le choix dans un `SharedPreferences` au moment ou l'utilisateur le fait, et on
 * le lit au demarrage.
 *
 * Le risque d'une copie, c'est la **derive** : deux endroits qui disent la meme chose.
 * Il est ferme ici par construction : la copie n'est jamais ecrite ailleurs, et la
 * valeur du `DataStore` fait foi — c'est lui que l'ecran lit et affiche. Si les deux
 * divergeaient, l'ecran montrerait une langue et l'app en parlerait une autre ; c'est
 * visible, pas silencieux.
 */
object LangueCache {

    private const val FICHIER = "tether_locale"
    private const val CLE = "code"

    /** `null` = suivre le systeme. */
    fun lire(context: Context): String? =
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)
            .getString(CLE, null)
            ?.takeIf { it.isNotBlank() }

    fun ecrire(context: Context, code: String?) {
        context.getSharedPreferences(FICHIER, Context.MODE_PRIVATE).edit()
            .apply {
                if (code.isNullOrBlank()) remove(CLE) else putString(CLE, code)
            }.apply()
    }

    /**
     * Le `ConfigurationContext` qui porte la langue choisie.
     *
     * Une langue inconnue retombe sur le systeme, pas sur une exception : la valeur
     * vient d'un fichier de preferences, donc de quelque chose d'editable a la main.
     */
    fun appliquer(context: Context, base: Context): Context {
        val code = lire(context) ?: return base
        val locale = try {
            Locale.forLanguageTag(code)
        } catch (e: IllegalArgumentException) {
            return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Les locales effectivement en service, format Android moderne. */
    fun locales(context: Context): LocaleListCompat {
        val code = lire(context) ?: return LocaleListCompat.getEmptyLocaleList()
        return LocaleListCompat.forLanguageTags(code)
    }
}
