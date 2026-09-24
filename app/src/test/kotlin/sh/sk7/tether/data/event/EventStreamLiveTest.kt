package sh.sk7.tether.data.event

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import sh.sk7.tether.data.api.BasicAuthCredentials
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Ignore

/**
 * Test d'integration du flux SSE contre le vrai serveur opencode V2.
 *
 * Ignore par defaut : ne doit pas casser la suite si le serveur est absent.
 * A lancer manuellement :
 *
 * ./gradlew :app:testDebugUnitTest --tests '*EventStreamLiveTest*' \
 *   -Dtether.baseUrl=http://127.0.0.1:4096 \
 *   -Dtether.password="$(cat ~/.config/opencode/ocremote-password)"
 */
@Ignore("Test d'integration : requiert un serveur opencode joignable")
class EventStreamLiveTest {

    private val baseUrl: String = System.getProperty("tether.baseUrl") ?: "http://127.0.0.1:4096"
    private val password: String = System.getProperty("tether.password") ?: ""

    @Test
    fun `le serveur emet au moins server connected`() = runBlocking<Unit> {
        val http = HttpClient(OkHttp)
        val stream = EventStream(baseUrl, BasicAuthCredentials(password = password), http)
        val events = withTimeout(10_000) { stream.connect().take(1).toList() }
        assertTrue(events.isNotEmpty(), "aucun evenement recu")
        assertTrue(events.first().type.isNotBlank(), "type vide")
    }
}
