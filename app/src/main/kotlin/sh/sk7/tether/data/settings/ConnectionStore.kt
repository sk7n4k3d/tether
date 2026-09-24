package sh.sk7.tether.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import sh.sk7.tether.data.api.BasicAuthCredentials
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reglages de connexion au serveur opencode.
 *
 * [baseUrl] vise par defaut le serveur LAN de le serveur : le telephone ne peut pas
 * joindre `127.0.0.1` (ce serait lui-meme). [directory] est le repertoire *location-scoped*
 * de l'API V2 : sans lui, `/api/agent` renvoie une liste vide sans erreur.
 */
data class ConnectionSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val password: String = "",
    val directory: String = DEFAULT_DIRECTORY,
) {
    /** Vrai si les reglages permettent de tenter une connexion (URL + mot de passe). */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && password.isNotBlank()

    fun credentialsOrNull(): BasicAuthCredentials? =
        password.takeIf { it.isNotBlank() }?.let { BasicAuthCredentials(password = it) }

    companion object {
        const val DEFAULT_BASE_URL: String = "http://192.0.2.10:4096"
        const val DEFAULT_DIRECTORY: String = "/home/utilisateur"

        /** Ramene une valeur vide ou blanche a son defaut : un champ vide n'ecrase pas le defaut. */
        fun normalize(baseUrl: String, directory: String): ConnectionSettings = ConnectionSettings(
            baseUrl = baseUrl.trim().ifBlank { DEFAULT_BASE_URL },
            directory = directory.trim().ifBlank { DEFAULT_DIRECTORY },
        )
    }
}

/**
 * Persistance des reglages de connexion (URL, mot de passe, repertoire).
 *
 * ⚠️ **Le mot de passe est stocke en clair** dans le fichier DataStore, lui-meme dans le
 * sandbox prive de l'application (`filesDir`) : lisible par l'app seule, sauf sur un
 * appareil roote. `androidx.security:security-crypto` (EncryptedSharedPreferences) est
 * deprecie et exigerait une dependance de plus ; c'est un choix assume, documente ici.
 *
 * Regles de securite tenues par cette classe et ses consommateurs :
 * - le mot de passe n'est **jamais** journalise ni inclus dans un message d'erreur ;
 * - `ktor-client-logging` est plafonne a `LogLevel.INFO` et l'en-tete `Authorization`
 *   est masque (voir `NetworkModule`), pour ne pas l'ecrire dans logcat.
 *
 * Chaque lecture ou ecriture **pousse les identifiants** dans [credentialsProvider],
 * qui est l'unique source des accreditations pour les clients HTTP. Sans ce cablage,
 * `InMemoryCredentialsProvider` resterait vide.
 */
@Singleton
class ConnectionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentialsProvider: InMemoryCredentialsProvider,
) {
    /** Reglages courants, observes en continu. */
    val settings: Flow<ConnectionSettings> = dataStore.data.map(::decode)

    /** Reglages courants (lecture ponctuelle) ; pousse aussi les identifiants. */
    suspend fun current(): ConnectionSettings = decode(dataStore.data.first()).also(::publish)

    /** Enregistre les reglages, ramene les champs vides au defaut, puis pousse les identifiants. */
    suspend fun save(settings: ConnectionSettings) {
        val normalized = ConnectionSettings.normalize(settings.baseUrl, settings.directory)
            .copy(password = settings.password)
        dataStore.edit { prefs ->
            prefs[KEY_BASE_URL] = normalized.baseUrl
            prefs[KEY_PASSWORD] = normalized.password
            prefs[KEY_DIRECTORY] = normalized.directory
        }
        publish(normalized)
    }

    private fun publish(settings: ConnectionSettings) {
        credentialsProvider.set(settings.credentialsOrNull())
    }

    private fun decode(prefs: Preferences): ConnectionSettings = ConnectionSettings(
        baseUrl = prefs[KEY_BASE_URL] ?: ConnectionSettings.DEFAULT_BASE_URL,
        password = prefs[KEY_PASSWORD].orEmpty(),
        directory = prefs[KEY_DIRECTORY] ?: ConnectionSettings.DEFAULT_DIRECTORY,
    )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("baseUrl")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_DIRECTORY = stringPreferencesKey("directory")
    }
}
