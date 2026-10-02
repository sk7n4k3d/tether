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
import sh.sk7.tether.data.api.BasicAuthCredentials
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reglages de connexion au serveur opencode.
 *
 * [baseUrl] vise par defaut le serveur LAN : le telephone ne peut pas
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
        /**
         * ⚠️ `127.0.0.1` et non une adresse de developpement.
         *
         * La version precedente pointait sur le serveur de l'auteur. Publie, cela
         * signifiait que l'app de tous les autres essayait de se connecter a sa machine
         * — et, dans le LAN d'un ami, que la connexion aboutissait reellement quelque part.
         * Un defaut qui ne marche que chez celui qui l'ecrit n'est pas un defaut, c'est
         * une adresse fuitee.
         *
         * C'est aussi le port par defaut d'opencode : un utilisateur qui fait tourner
         * le serveur sur la meme machine tombe juste.
         */
        const val DEFAULT_BASE_URL: String = "http://127.0.0.1:4096"
        const val DEFAULT_DIRECTORY: String = ""

        /** Ramene une valeur vide ou blanche a son defaut : un champ vide n'ecrase pas le defaut. */
        // ⚠️ Le schema est complet si absent : une adresse tapee sans `http://` (ex.
        // `192.168.1.10:4096`) partait telle quelle vers Ktor, qui echoue sur une URL
        // sans schema — une erreur obscure pour une faute de frappe courante. `https`
        // reste explicite : l'utilisateur qui le tape l'obtient.
        fun normalize(baseUrl: String, directory: String): ConnectionSettings = ConnectionSettings(
            baseUrl = baseUrl.trim()
                .ifBlank { DEFAULT_BASE_URL }
                .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" },
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
    val settings: Flow<ConnectionSettings> = dataStore.data
        // Un fichier illisible ne doit pas empecher l'app de **lire** ses reglages : le
        // pire qui puisse arriver est de repartir des defauts, pas de fermer l'ecran de
        // connexion qui sert justement a les corriger.
        .catch { emit(emptyPreferences()) }
        .map(::decode)

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

    /**
     * Enregistre **la seule adresse**, et rien d'autre.
     *
     * ⚠️ Ecrite pour le scan d'un QR : le code d'appairage porte l'adresse du serveur,
     * mais **pas le mot de passe** — c'est tout son interet. Ecrire par [save] aurait
     * donc envoye une chaine vide a la place du mot de passe deja enregistre, et
     * l'utilisateur configure se serait retrouve deconnecte par un scan.
     *
     * On n'appelle pas `publish` : le mot de passe est inchange, donc les identifiants
     * en memoire le sont aussi. Les republier demanderait de relire le fichier pour rien.
     */
    suspend fun enregistrerAdresse(baseUrl: String) {
        val propre = baseUrl.trim()
        if (propre.isBlank()) return
        dataStore.edit { it[KEY_BASE_URL] = propre }
    }

    private fun publish(settings: ConnectionSettings) {
        credentialsProvider.set(settings.credentialsOrNull())
    }

    /**
     * ⚠️ **Chaque cle est lue a l'abri d'un `runCatching`**, et pas via un `catch` global
     * sur le flux : `prefs[cle]` demande la valeur du type attendu et **leve** une
     * `ClassCastException` si le fichier en porte un autre. Renvoyer tout aux defauts
     * parce qu'une seule cle est douteuse effacerait des reglages valides — l'utilisateur
     * perdrait son adresse parce qu'un accent a ete mal ecrit.
     */
    private fun decode(prefs: Preferences): ConnectionSettings = ConnectionSettings(
        baseUrl = prefs.lireTexte(KEY_BASE_URL, ConnectionSettings.DEFAULT_BASE_URL),
        password = prefs.lireTexte(KEY_PASSWORD, "").orEmpty(),
        directory = prefs.lireTexte(KEY_DIRECTORY, ConnectionSettings.DEFAULT_DIRECTORY),
    )

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("baseUrl")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_DIRECTORY = stringPreferencesKey("directory")
    }
}
