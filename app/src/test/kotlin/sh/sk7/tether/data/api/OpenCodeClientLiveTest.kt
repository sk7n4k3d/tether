package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Ignore

/**
 * Test d'integration contre le vrai serveur opencode V2.
 *
 * Ignore par defaut : ne doit pas casser la suite si le serveur est absent.
 * A lancer manuellement :
 *
 * ./gradlew :app:testDebugUnitTest --tests '*OpenCodeClientLiveTest*' \
 *   -Dtether.baseUrl=http://127.0.0.1:4096 \
 *   -Dtether.password="$(cat ~/.config/opencode/ocremote-password)"
 */
@Ignore("Test d'integration : requiert un serveur opencode joignable")
class OpenCodeClientLiveTest {

    private val baseUrl: String = System.getProperty("tether.baseUrl") ?: "http://127.0.0.1:4096"
    private val password: String = System.getProperty("tether.password") ?: ""

    private fun client(): OpenCodeClient {
        val http = HttpClient(OkHttp) { configureTether() }
        return OpenCodeClient(baseUrl, BasicAuthCredentials(password = password), http)
    }

    @Test
    fun `info renvoie la version`() = runBlocking {
        val info = client().info()
        assertTrue(info.version.isNotBlank(), "version vide")
    }

    @Test
    fun `sessions filtre reellement par directory`() = runBlocking {
        val location = System.getProperty("tether.location") ?: "/tmp/opencode"
        val sessions = client().sessions(location)
        assertTrue(sessions.isNotEmpty(), "aucune session pour $location")
        assertTrue(sessions.first().id.isNotBlank())
        // Preuve du filtre : toutes les sessions doivent venir du repertoire demande.
        val foreign = sessions.filterNot { it.location?.directory == location }
        assertTrue(
            foreign.isEmpty(),
            "sessions hors $location: ${foreign.map { it.id to it.location?.directory }.take(3)}",
        )
    }
}
