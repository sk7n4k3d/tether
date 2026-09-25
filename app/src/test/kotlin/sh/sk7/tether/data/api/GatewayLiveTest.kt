package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import sh.sk7.tether.data.settings.ConnectionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test d'integration du chemin **reellement utilise par l'UI** ([KtorOpenCodeGateway]) contre
 * le serveur opencode de le serveur.
 *
 * Ignore par defaut : ne doit pas casser la suite si le serveur est absent.
 * A lancer manuellement :
 *
 * ./gradlew :app:testDebugUnitTest --tests '*GatewayLiveTest*' \
 *   -Dtether.baseUrl=http://192.0.2.10:4096 \
 *   -Dtether.password="$(cat ~/.config/opencode/ocremote-password)"
 */
@Ignore("Test d'integration : requiert un serveur opencode joignable")
class GatewayLiveTest {

    private val baseUrl: String = System.getProperty("tether.baseUrl") ?: "http://127.0.0.1:4096"
    private val password: String = System.getProperty("tether.password") ?: ""
    private val directory: String = System.getProperty("tether.directory") ?: "/home/utilisateur"

    private fun gateway(): KtorOpenCodeGateway {
        val http = HttpClient(OkHttp) { configureTether() }
        val credentials = InMemoryCredentialsProvider(BasicAuthCredentials(password = password))
        return KtorOpenCodeGateway(http, credentials)
    }

    private fun settings() = ConnectionSettings(
        baseUrl = baseUrl,
        password = password,
        directory = directory,
    )

    @Test
    fun `le gateway liste les 50 sessions du repertoire`() = runBlocking<Unit> {
        val sessions = gateway().sessions(settings())
        assertEquals(50, sessions.size, "l'API pagine par defaut a 50")
        assertTrue(sessions.all { it.id.startsWith("ses_") })
    }

    @Test
    fun `le gateway resout modeles et agents avec le parametre location`() = runBlocking<Unit> {
        val gateway = gateway()
        val models = gateway.models(settings())
        val agents = gateway.agents(settings())
        assertTrue(models.isNotEmpty(), "aucun modele : le parametre location[directory] est mal signe")
        assertTrue(agents.isNotEmpty(), "aucun agent : le parametre location[directory] est mal signe")
        assertTrue(models.any { it.id == "deepseek-v4.1-flash" })
    }

    /**
     * Defaut connu de la Task 1.5 : `GET /api/session` pagine a 50 par defaut. `allSessions`
     * doit suivre le curseur et rendre **plus de 50** sessions (428 mesurees le 2026-09-25).
     */
    @Test
    fun `allSessions suit le curseur et depasse la premiere page de 50`() = runBlocking<Unit> {
        val gateway = gateway()
        val first = gateway.sessionsPage(settings(), limit = 50, cursor = null)
        assertEquals(50, first.data.size, "la premiere page fait 50")
        assertTrue(first.next != null, "le curseur next doit etre present apres la premiere page")

        val all = gateway.allSessions(settings())
        assertTrue(all.size > 50, "pagination non suivie : ${all.size} sessions seulement")
        assertEquals(all.size, all.map { it.id }.distinct().size, "sessions dupliquees entre pages")
        println("allSessions -> ${all.size} sessions (${all.size / 50 + 1} pages)")
    }
}
