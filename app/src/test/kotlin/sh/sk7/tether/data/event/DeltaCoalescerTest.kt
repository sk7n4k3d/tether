package sh.sk7.tether.data.event

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le coalescer doit fusionner les deltas d'une meme fenetre SANS jamais :
 * - reordonner (un `text.ended` passe toujours APRES son texte) ;
 * - perdre un delta (la fenetre expir e ou l'evenement non-delta vide le tampon) ;
 * - melanger texte et raisonnement (deux tampons distincts, raisonnement d'abord).
 */
class DeltaCoalescerTest {

    private fun textDelta(s: String, sessionID: String = "ses_a"): OcEvent = OcEvent(
        type = "session.text.delta",
        data = buildJsonObject {
            put("sessionID", sessionID)
            put("delta", s)
        },
    )

    private fun reasoningDelta(s: String, sessionID: String = "ses_a"): OcEvent = OcEvent(
        type = "session.reasoning.delta",
        data = buildJsonObject {
            put("sessionID", sessionID)
            put("delta", s)
        },
    )

    @Test
    fun `les deltas d'une meme fenetre se fusionnent en un seul evenement`() {
        var now = 0L
        val c = DeltaCoalescer(windowMillis = 50, clock = { now })

        // Trois deltas dans la meme fenetre : rien ne doit encore sortir.
        assertEquals(emptyList<OcEvent>(), c.feed(textDelta("Bon")))
        assertEquals(emptyList<OcEvent>(), c.feed(textDelta("jour")))
        assertEquals(emptyList<OcEvent>(), c.feed(textDelta(" !")))

        // La fenetre expire : un seul delta fusionne sort, avec le texte complet.
        now = 60
        val out = c.flush()
        assertEquals(1, out.size)
        assertEquals("session.text.delta", out[0].type)
        assertEquals("Bonjour !", out[0].data["delta"]!!.toString().trim('"'))
        // L'enveloppe d'origine (sessionID) survit dans l'evenement fusionne.
        assertEquals("ses_a", out[0].sessionID)
    }

    @Test
    fun `un evenement non-delta vide le tampon AVANT de passer`() {
        var now = 0L
        val c = DeltaCoalescer(windowMillis = 50, clock = { now })
        c.feed(textDelta("texte en"))
        c.feed(textDelta(" cours"))

        val ended = OcEvent(type = "session.text.ended", data = buildJsonObject { put("sessionID", "ses_a") })
        val out = c.feed(ended)

        // Ordre : le texte fusionne PUIS l'evenement de fin — jamais l'inverse.
        assertEquals(2, out.size)
        assertEquals("session.text.delta", out[0].type)
        assertEquals("texte en cours", out[0].data["delta"]!!.toString().trim('"'))
        assertEquals("session.text.ended", out[1].type)
    }

    @Test
    fun `les deltas hors fenetre sortent immediatement`() {
        var now = 0L
        val c = DeltaCoalescer(windowMillis = 50, clock = { now })
        c.feed(textDelta("a"))
        now = 100 // fenetre expiree pour le delta suivant
        val out = c.feed(textDelta("b"))
        assertEquals(1, out.size)
        assertEquals("ab", out[0].data["delta"]!!.toString().trim('"'))
    }

    @Test
    fun `le raisonnement sort avant le texte, et flush vide rend une liste vide`() {
        val c = DeltaCoalescer(windowMillis = 50, clock = { 0L })
        c.feed(reasoningDelta("je pense"))
        c.feed(textDelta("je dis"))

        val out = c.flush()
        assertEquals(2, out.size)
        assertEquals("session.reasoning.delta", out[0].type)
        assertEquals("session.text.delta", out[1].type)

        // Un second flush sans nouveau delta ne doit rien rendre (pas de doublon).
        assertTrue(c.flush().isEmpty())
    }

    @Test
    fun `un delta sans champ delta est ignore sans casser le flux`() {
        val c = DeltaCoalescer(windowMillis = 50, clock = { 0L })
        val malformed = OcEvent(type = "session.text.delta", data = buildJsonObject { put("sessionID", "ses_a") })
        assertEquals(emptyList<OcEvent>(), c.feed(malformed))
        // Rien a vider : un evenement non-delta suivant ne genere pas de delta fantome.
        val next = OcEvent(type = "session.text.ended", data = buildJsonObject { put("sessionID", "ses_a") })
        assertEquals(1, c.feed(next).size)
    }
}