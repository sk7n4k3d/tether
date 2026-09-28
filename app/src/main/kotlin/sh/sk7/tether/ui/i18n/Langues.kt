package sh.sk7.tether.ui.i18n

import android.content.Context
import android.os.Build
import android.app.LocaleManager
import android.os.LocaleList
import java.util.Locale

/**
 * Les langues que l'app sait parler, et ce qu'elle en fait.
 *
 * ## Pourquoi la liste est dans le code, et pas dans les ressources
 *
 * Android sait lister les langues declarees — via `locales_config.xml` et
 * `LocaleManager`. Mais cette liste est **statique** : elle ne dit pas laquelle
 * l'utilisateur a choisie. Il faut donc les deux, et c'est ce que fait cet objet : la
 * liste pour l'ecran, [LocaleManager] pour que le systeme sache aussi.
 *
 * ## Pourquoi l'API systeme, et pas seulement notre cache
 *
 * `LangueCache` suffit a l'app. Il ne suffit pas a Android : sans
 * [android.app.LocaleManager.setApplicationLocales], l'entree « Tether » n'apparait pas
 * dans **Reglages > Applications > Langues**, et le systeme ne recharge pas l'app quand
 * l'utilisateur change de langue en dehors. C'est l'API 33. En dessous, notre cache
 * suffit — et c'est le seul qui existe.
 */
object Langues {

    /** Le code `fr`, et [SYSTEME] pour « suivre le systeme ». */
    const val FRANCAIS = "fr"

    /**
     * Suivre le systeme.
     *
     * ⚠️ Un `val` et non un `const` : en Kotlin, `const val SYSTEME = null` donne le
     * type `Nothing?`, qui n'est pas un type de constante. La valeur reste la meme,
     * seule la declaration change.
     */
    @JvmField
    val SYSTEME: String? = null

    /** Ce que l'ecran propose. */
    val disponibles: List<Pair<String, String>> = listOf(
        FRANCAIS to "Français",
        "en" to "English",
    )

    /**
     * Dit au systeme quelle langue l'app utilise.
     *
     * Sans effet sous l'API 33 : la methode n'existe pas. Ce n'est pas une erreur, c'est
     * la voie disponible — le cache interne prend le relais.
     */
    fun declarerAuSysteme(context: Context, code: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val gestionnaire = context.getSystemService(LocaleManager::class.java) ?: return
        val locales = if (code == SYSTEME) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList(Locale.forLanguageTag(code))
        }
        try {
            gestionnaire.applicationLocales = locales
        } catch (e: IllegalArgumentException) {
            // Une langue que le systeme refuse. Notre cache reste valable.
        }
    }
}
