package sh.sk7.tether.data.event

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import sh.sk7.tether.data.api.BasicAuthCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class EventStreamTest {

    private val creds = BasicAuthCredentials(password = "s3cret")
    private val fastBackoff = Backoff(initialMillis = 5, maxMillis = 10)

    private fun client(engine: MockEngine) = HttpClient(engine)

    @Test
    fun `un content-type text html est rejete et aucun evenement n est emis`() = runBlocking<Unit> {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("<html>SPA fallback</html>"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val stream = EventStream("http://host:4096", creds, client(engine), backoff = fastBackoff)

        // Le flux re-tente sans fin et n'emet rien : le timeout le prouve.
        val received = withTimeoutOrNull(300) { stream.connect().toList() }
        assertNull(received)
        assertIs<NotSseException>(stream.lastError)
    }

    @Test
    fun `le flux event-stream est decode en OcEvent`() = runBlocking<Unit> {
        val payload = "data: {\"id\":\"evt_1\",\"type\":\"session.text.delta\",\"data\":{\"sessionID\":\"ses_1\",\"delta\":\"PO\"}}\n\n" +
            ": heartbeat\n\n" +
            "data: {\"id\":\"evt_2\",\"type\":\"session.text.delta\",\"data\":{\"sessionID\":\"ses_1\",\"delta\":\"NG\"}}\n\n"
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val stream = EventStream("http://host:4096", creds, client(engine), backoff = fastBackoff)

        val events = stream.connect().take(2).toList()
        assertEquals(2, events.size)
        assertEquals("evt_1", events[0].id)
        assertEquals("ses_1", events[0].sessionID)
        assertEquals("NG", events[1].data["delta"]?.toString()?.trim('"'))
    }

    @Test
    fun `un evenement json illisible est ignore, le flux continue`() = runBlocking<Unit> {
        val payload = "data: pas du json\n\n" +
            "data: {\"id\":\"evt_2\",\"type\":\"server.connected\",\"data\":{}}\n\n"
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(payload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        }
        val stream = EventStream("http://host:4096", creds, client(engine), backoff = fastBackoff)

        val events = stream.connect().take(1).toList()
        assertEquals(1, events.size)
        assertEquals("server.connected", events[0].type)
    }

    @Test
    fun `le backoff croit et se plafonne`() {
        val b = Backoff(initialMillis = 500, maxMillis = 4_000, factor = 2.0)
        assertEquals(500, b.delayMillis(0))
        assertEquals(1_000, b.delayMillis(1))
        assertEquals(2_000, b.delayMillis(2))
        assertEquals(4_000, b.delayMillis(3))
        assertEquals(4_000, b.delayMillis(10))
    }
}
