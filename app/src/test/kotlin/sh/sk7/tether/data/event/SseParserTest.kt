package sh.sk7.tether.data.event

import kotlinx.serialization.json.Json
import sh.sk7.tether.data.api.OpenCodeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseParserTest {

    private val json = OpenCodeClient.json

    @Test
    fun `decoupe deux frames separes par une ligne vide`() {
        val p = SseParser()
        val out = p.feed("data: {\"a\":1}\n\ndata: {\"b\":2}\n\n")
        assertEquals(2, out.size)
        assertEquals("{\"a\":1}", out[0].data)
        assertEquals("{\"b\":2}", out[1].data)
    }

    @Test
    fun `un chunk coupe au milieu est reassemble`() {
        val p = SseParser()
        assertTrue(p.feed("data: {\"a\":").isEmpty())
        val out = p.feed("1}\n\n")
        assertEquals(1, out.size)
        assertEquals("{\"a\":1}", out[0].data)
    }

    @Test
    fun `un content-type text html est rejete, pas parse`() {
        assertFailsWith<NotSseException> { SseParser.requireEventStream("text/html") }
        SseParser.requireEventStream("text/event-stream")   // ne leve pas
        SseParser.requireEventStream("text/event-stream; charset=utf-8")   // ne leve pas
    }

    @Test
    fun `un content-type absent est rejete`() {
        assertFailsWith<NotSseException> { SseParser.requireEventStream(null) }
    }

    @Test
    fun `le parseur reel produit 17 evenements`() {
        val raw = javaClass.getResourceAsStream("/event-stream-real.txt")!!.readBytes().decodeToString()
        val frames = SseParser().feed(raw)
        assertEquals(17, frames.size)   // capture du 24/09
        assertTrue(frames.any { it.data.contains("session.text.delta") })
    }

    @Test
    fun `feed tolere un separateur crlf`() {
        // ⚠️ Sans normalisation, "\r\n\r\n" ne contient pas "\n\n" -> 0 frame,
        // echec SILENCIEUX. Un proxy (SWAG) pourrait normaliser en CRLF.
        val out = SseParser().feed("data: {\"a\":1}\r\n\r\n")
        assertEquals(1, out.size)
        assertEquals("{\"a\":1}", out[0].data)
    }

    @Test
    fun `les commentaires heartbeat sont ignores`() {
        // le flux reel contient des lignes ": heartbeat" (commentaires SSE)
        val p = SseParser()
        val out = p.feed(": heartbeat\n\ndata: {\"a\":1}\n\n")
        assertEquals(1, out.size)
        assertEquals("{\"a\":1}", out[0].data)
        assertNull(out[0].id)
    }

    @Test
    fun `une frame de pur commentaire ne produit rien`() {
        val p = SseParser()
        assertTrue(p.feed(": heartbeat\n\n").isEmpty())
        assertTrue(p.feed(":\n\n").isEmpty())
    }

    @Test
    fun `un id dans le JSON est lu depuis le JSON, pas une ligne id`() {
        // MESURE : le flux V2 n'emet AUCUNE ligne `id:` — l'id est dans le JSON
        val p = SseParser()
        val out = p.feed("data: {\"id\":\"evt_abc\",\"type\":\"session.created\"}\n\n")
        assertEquals(1, out.size)
        assertTrue(out[0].data.contains("evt_abc"))
        assertNull(out[0].id)   // aucune ligne `id:` -> rien cote transport
    }

    @Test
    fun `une frame multi data joint les lignes par un saut`() {
        val p = SseParser()
        val out = p.feed("data: {\"a\":\ndata: 1}\n\n")
        assertEquals(1, out.size)
        assertEquals("{\"a\":\n1}", out[0].data)
    }

    @Test
    fun `le parseur reel ne produit aucune frame vide`() {
        val raw = javaClass.getResourceAsStream("/event-stream-real.txt")!!.readBytes().decodeToString()
        val frames = SseParser().feed(raw)
        assertTrue(frames.all { it.data.isNotBlank() })
    }

    @Test
    fun `un evenement reel se decode en OcEvent avec sessionID et durable objet`() {
        val raw = javaClass.getResourceAsStream("/event-stream-real.txt")!!.readBytes().decodeToString()
        val frames = SseParser().feed(raw)

        val created = frames
            .map { json.decodeFromString<OcEvent>(it.data) }
            .first { it.type == "session.created" }
        assertEquals("evt_0d4e07cbe001L8Ya0y9Bqasvpi", created.id)
        assertEquals(1790277876926L, created.created)
        assertEquals("ses_f2b1f8344ffe7N0fGcpz3sNX0q", created.sessionID)
        assertNotNull(created.location)
        assertEquals("/home/user", created.location.directory)
        // `durable` est un OBJET, pas un booleen (spike 7)
        assertEquals("ses_f2b1f8344ffe7N0fGcpz3sNX0q", created.durable?.aggregateID)
        assertEquals(0, created.durable?.seq)
        assertEquals(1, created.durable?.version)

        val delta = frames
            .map { json.decodeFromString<OcEvent>(it.data) }
            .first { it.type == "session.text.delta" }
        assertEquals("PONG", delta.data["delta"]?.toString()?.trim('"'))
        assertEquals("ses_f2b1f8344ffe7N0fGcpz3sNX0q", delta.sessionID)
        assertNull(delta.durable)   // delta n'est pas durable
    }

    @Test
    fun `server connected a un data vide mais reste decodable`() {
        val raw = javaClass.getResourceAsStream("/event-stream-real.txt")!!.readBytes().decodeToString()
        val first = json.decodeFromString<OcEvent>(SseParser().feed(raw).first().data)
        assertEquals("server.connected", first.type)
        assertTrue(first.data.isEmpty())
        assertNull(first.sessionID)
    }
}
