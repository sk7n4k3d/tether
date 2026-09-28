package sh.sk7.tether.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.sk7.tether.ui.i18n.LangueCache
import sh.sk7.tether.ui.theme.Accent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Les preferences locales de l'app**, et rien d'autre.
 *
 * ## Ce qui les separe des reglages de serveur
 *
 * Deux d'entre elles changent **l'ecran lui-meme** plutot que son contenu : la couleur
 * et les mots. La troisieme — l'accueil vu — ne change rien a l'affichage mais decide
 * **quel** ecran s'ouvre au lancement. Toutes les trois ont en commun de ne rien dire
 * sur ce a quoi l'app parle : serveur, repertoire, modele decrivent la machine d'en
 * face, et vivent dans `ConnectionStore`.
 *
 * ## L'accent est une **cle**, pas une couleur
 *
 * Stocker une valeur hexad decimale, ce serait stocker un choix de l'utilisateur comme
 * s'il venait de nous : impossible a lire, impossible a corriger, et chaque teinte
 * devront etre verifiee une par une a l'aveugle. On stocke la **cle** de l'enumeration —
 * `sarcelle`, `azur` — et c'est `Accent.depuisCle` qui la traduit. Un reglage inconnu
 * retombe sur le defaut au lieu de planter : c'est un fichier de preferences, donc
 * modifiable a la main.
 *
 * ## La langue suit le meme principe
 *
 * Une cle de langue, avec une valeur spéciale « systeme » qui suit Android. C'est le
 * comportement attendu de la majorite des applications, et surtout le seul qui ne
 * demande rien a l'utilisateur qui veut simplement son telephone en francais.
 */
@Singleton
class AppearanceStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private val KEY_ACCENT = stringPreferencesKey("appearance.accent")
    private val KEY_LANGUE = stringPreferencesKey("appearance.langue")
    private val KEY_ACCUEIL_VU = booleanPreferencesKey("appearance.accueilVu")

    /** L'accent choisi, reagit a chaque changement. */
    val accent: Flow<Accent> = dataStore.data.map { prefs ->
        Accent.depuisCle(prefs[KEY_ACCENT])
    }

    /**
     * La langue choisie, **ou `null`** pour « suivre le systeme ».
     *
     * `null` et non une chaine vide : les deux se confondent, et une chaine vide
     * passerait pour une langue inexistante — donc pour l'anglais par defaut, qui est
     * exactement le reproche qu'on veut eviter (« l'app est en anglais alors que mon
     * telephone est en francais »).
     */
    val langue: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_LANGUE]?.takeIf { it.isNotBlank() }
    }

    /**
     * L'accueil a-t-il deja ete vu ?
     *
     * ⚠️ **Marque des qu'on quitte l'accueil, quelle que soit la sortie** — « Passer »,
     * « Configurer a la main », « Scanner ». Pas seulement quand on atteint la derniere
     * page : quelqu'un qui passe immediatement a compris qu'il n'en avait pas besoin, et
     * lui remontrer le carrousel au prochain lancement serait le punir.
     *
     * Ce drapeau n'est jamais remis a `false` : il n'y a pas de « revoir l'accueil » dans
     * les reglages, et un utilisateur qui vide les donnees de l'app le retrouve a `false`
     * avec tout le reste.
     */
    val accueilVu: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ACCUEIL_VU] ?: false
    }

    suspend fun marquerAccueilVu() {
        dataStore.edit { it[KEY_ACCUEIL_VU] = true }
    }

    suspend fun choisirAccent(accent: Accent) {
        dataStore.edit { it[KEY_ACCENT] = accent.cle }
    }

    /** `null` rend le suivi systeme. */
    /**
     * Change la langue, et **recradre l'activite** pour qu'elle soit appliquee.
     *
     * L'ecriture se fait a deux endroits, volontairement : le `DataStore`, qui fait foi
     * et que l'ecran lit, et [LangueCache], que `attachBaseContext` lit de facon synchrone
     * au prochain demarrage. Voir ce dernier pour pourquoi le miroir existe.
     *
     * ⚠️ L'ordre compte : le `DataStore` d'abord. Si l'ecriture du cache echoue, la
     * valeur de verite reste la bonne, et l'app se reconnectera au bon endroit au
     * redemarrage. L'inverse laisserait un cache qui promet une langue que rien ne
     * confirme.
     */
    suspend fun choisirLangue(context: Context, code: String?) {
        dataStore.edit { prefs ->
            if (code == null) prefs.remove(KEY_LANGUE) else prefs[KEY_LANGUE] = code
        }
        LangueCache.ecrire(context, code)
    }
}
