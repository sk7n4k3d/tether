package sh.sk7.tether.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.sk7.tether.data.api.ModelRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Dernier choix de modele et d'agent**, pour la creation de session.
 *
 * ### Pourquoi cette existe
 *
 * Le dialogue de creation a ete supprime : il demandait un titre, un modele et un agent, dont deux
 * etaient deja pre-remplis, et dont aucun n'etait necessaire. `POST /api/session` n'exige **aucun**
 * champ (mesure du 2026-09-26 sur le 2.0.x) : le serveur resout l'agent et le modele au premier
 * tour et les laisse `null` avant.
 *
 * Mais « ne rien envoyer » n'est pas non plus la bonne reponse ici : le defaut du serveur est
 * `general` + `deepseek-v4.1-flash` (mesure : `/api/agent` — seul agent en `mode: "all"`, et il
 * porte ce modele), donc une session creee « par defaut » atterrit systematiquement sur le modele
 * que l'utilisateur ne veut pas. Il change d'ailleurs systematiquement, c'est bien le cas.
 *
 * On memorise donc son **dernier** choix et on le renvoie a la creation : un geste, zero champ,
 * et on arrive sur le modele voulu. Le choix reste modifiable dans la conversation, et c'est ce
 * changement-la qui **remeasure** l'ici.
 *
 * ### Ce que ce store n'est pas
 *
 * Ce n'est pas un cache et ce n'est pas une source de verite : le serveur reste seul temoin du
 * modele reellement utilise. Ces valeurs servent uniquement a **pre-remplir une creation**. L'ecran
 * lit `GET /api/session/{id}`, jamais ceci — sinon on retomberait dans le piege du
 * `modelOverride` (ecrit, jamais lu, affiche perime).
 */
@Singleton
class SessionDefaultsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Dernier modele choisi, `null` si l'utilisateur n'en a jamais choisi. */
    // ⚠️ `catch` comme ConnectionStore/AppearanceStore : un fichier DataStore corrompu
    // faisait remonter l'exception jusqu'au collecteur et tuait l'ecran.
    val lastModel: Flow<ModelRef?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
        val id = prefs[KEY_MODEL_ID]
        if (id.isNullOrBlank()) {
            null
        } else {
            ModelRef(
                id = id,
                providerID = prefs[KEY_MODEL_PROVIDER].orEmpty(),
                variant = prefs[KEY_MODEL_VARIANT]?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Dernier agent choisi ; `null` = laisser le serveur resoudre. */
    val lastAgent: Flow<String?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            prefs[KEY_AGENT]?.takeIf { it.isNotBlank() }
        }

    /** Lecture ponctuelle des deux, pour un envoi non suspendu par une collecte. */
    suspend fun current(): Defaults = Defaults(
        model = lastModel.first(),
        agent = lastAgent.first(),
    )

    /**
     * Enregistre un choix.
     *
     * ⚠️ `agent` a `null` = « effacer » : on n'ecrit pas une chaine vide. Distinguer « pas encore
     * choisi » de « choisi puis revoque » n'a aucun interet ici, les deux signifient « laisse le
     * serveur decider », et garder une valeur fantome ferait repartir une session sur un agent que
     * l'utilisateur ne veut plus.
     */
    suspend fun record(model: ModelRef? = null, agent: String? = null) {
        dataStore.edit { prefs ->
            if (model != null) {
                prefs[KEY_MODEL_ID] = model.id
                prefs[KEY_MODEL_PROVIDER] = model.providerID
                if (model.variant != null) prefs[KEY_MODEL_VARIANT] = model.variant
            }
            if (agent != null) {
                prefs[KEY_AGENT] = agent
            }
        }
    }

    /** Les deux valeurs d'un coup, pour un envoi de creation. */
    data class Defaults(val model: ModelRef?, val agent: String?)

    private companion object {
        val KEY_MODEL_ID = stringPreferencesKey("lastModelId")
        val KEY_MODEL_PROVIDER = stringPreferencesKey("lastModelProvider")
        val KEY_MODEL_VARIANT = stringPreferencesKey("lastModelVariant")
        val KEY_AGENT = stringPreferencesKey("lastAgent")
    }
}
