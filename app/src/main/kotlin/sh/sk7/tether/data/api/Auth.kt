package sh.sk7.tether.data.api

/**
 * Identifiants HTTP basic du serveur opencode V2.
 *
 * L'utilisateur est toujours `opencode` ; seul le mot de passe est requis.
 * Le mot de passe n'est PAS lu depuis un fichier : sur telephone il est saisi
 * dans les reglages (Task 1.5), puis fourni ici.
 */
interface CredentialsProvider {
    suspend fun credentials(): BasicAuthCredentials?
}

data class BasicAuthCredentials(val username: String = "opencode", val password: String)

/** Implementation en memoire, alimentee par les reglages. Utilisee par ConnectionStore (Task 1.5). */
class InMemoryCredentialsProvider(initial: BasicAuthCredentials? = null) : CredentialsProvider {
    @Volatile
    private var current: BasicAuthCredentials? = initial

    fun set(credentials: BasicAuthCredentials?) {
        current = credentials
    }

    override suspend fun credentials(): BasicAuthCredentials? = current
}
