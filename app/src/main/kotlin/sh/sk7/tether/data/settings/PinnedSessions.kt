package sh.sk7.tether.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **Les sessions epinglees**, conservees localement.
 *
 * ### Pourquoi localement, et pas sur le serveur
 * L'API opencode n'expose **aucun** champ d'epinglage ni d'archivage (verifie sur `/openapi.json`
 * le 2026-09-25). Le choix est donc entre ne rien faire, ou stocker cette preference sur le
 * telephone. On stocke.
 *
 * ⚠️ **Ce que ca implique, et qu'il faut dire a l'utilisateur** : l'epinglage vit sur **cet
 * appareil**. Il ne suivra pas sur un autre telephone, et une reinstallation le perd. C'est un
 * compromis assume plutot qu'un mensonge : l'alternative serait de faire croire a une donnee
 * synchronisee.
 *
 * ⚠️ On epingle par **identifiant de session**, jamais par titre : un titre peut etre renomme, et
 * une epingle qui se perd au renommage serait un bug incomprehensible.
 */
@Singleton
class PinnedSessions @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Identifiants epingles, observes en continu. */
    val ids: Flow<Set<String>> = dataStore.data.map { it[KEY] ?: emptySet() }

    /** Lecture ponctuelle. */
    suspend fun current(): Set<String> = dataStore.data.first()[KEY] ?: emptySet()

    /**
     * Epingle ou depingle, et renvoie le nouvel etat.
     *
     * ⚠️ Une seule methode pour les deux gestes : l'appelant ne peut pas se tromper de sens en
     * passant deux drapeaux contradictoires.
     */
    suspend fun toggle(sessionID: String): Boolean {
        var nowPinned = false
        dataStore.edit { prefs ->
            val current = prefs[KEY] ?: emptySet()
            nowPinned = sessionID !in current
            prefs[KEY] = if (nowPinned) current + sessionID else current - sessionID
        }
        return nowPinned
    }

    private companion object {
        val KEY = stringSetPreferencesKey("pinned-sessions")
    }
}
