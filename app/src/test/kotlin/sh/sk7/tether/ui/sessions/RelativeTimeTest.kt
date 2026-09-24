package sh.sk7.tether.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `un horodatage absent rend un tiret`() {
        assertEquals("—", RelativeTime.format(now, null))
    }

    @Test
    fun `moins d une minute rend instantane`() {
        assertEquals("à l'instant", RelativeTime.format(now, now - 5_000))
    }

    @Test
    fun `les minutes sont arrondies vers le bas`() {
        assertEquals("il y a 3 min", RelativeTime.format(now, now - 3 * 60_000))
        assertEquals("il y a 59 min", RelativeTime.format(now, now - 59 * 60_000))
    }

    @Test
    fun `les heures remplacent les minutes au dela de 60`() {
        assertEquals("il y a 1 h", RelativeTime.format(now, now - 60 * 60_000))
        assertEquals("il y a 23 h", RelativeTime.format(now, now - 23 * 60 * 60_000))
    }

    @Test
    fun `les jours remplacent les heures au dela de 24`() {
        assertEquals("il y a 1 j", RelativeTime.format(now, now - 24 * 60 * 60_000))
        assertEquals("il y a 6 j", RelativeTime.format(now, now - 6 * 24 * 60 * 60_000))
    }

    @Test
    fun `au dela d une semaine rend une date courte`() {
        assertEquals("01/01/2027", RelativeTime.format(now, 1_798_804_800_000))
    }

    @Test
    fun `un horodatage dans le futur ne rend pas de duree negative`() {
        assertEquals("à l'instant", RelativeTime.format(now, now + 60_000))
    }
}
