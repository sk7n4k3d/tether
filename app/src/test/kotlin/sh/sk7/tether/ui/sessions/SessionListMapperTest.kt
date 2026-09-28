package sh.sk7.tether.ui.sessions

import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.api.TimeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionListMapperTest {

    private fun session(
        title: String? = "Titre",
        agent: String? = "general",
        model: ModelRef? = ModelRef(id = "deepseek-v4.1-flash", providerID = "ollama-cloud"),
        cost: Double? = 0.10572413999999998,
        created: Long? = 1_790_275_708_982,
        updated: Long? = 1_790_277_771_722,
    ) = Session(
        id = "ses_1",
        title = title,
        agent = agent,
        model = model,
        cost = cost,
        time = TimeInfo(created = created, updated = updated),
    )

    @Test
    fun `un titre absent rend un libelle de repli`() {
        // On compare le **repli fourni**, pas « Sans titre » : la regle verifiee est
        // qu'un titre absent prend le repli, pas que le repli dit un mot en
        // particulier. Une reecriture de traduction ne doit pas casser ce test.
        assertEquals("repli", SessionListMapper.toItem(session(title = null), "repli").title)
        assertEquals("repli", SessionListMapper.toItem(session(title = "   "), "repli").title)
    }

    @Test
    fun `l age se base sur updated en priorite puis created`() {
        assertEquals(1_790_277_771_722, SessionListMapper.toItem(session()).timestamp)
        assertEquals(
            1_790_275_708_982,
            SessionListMapper.toItem(session(updated = null)).timestamp,
        )
        assertNull(SessionListMapper.toItem(session(created = null, updated = null)).timestamp)
    }

    @Test
    fun `le modele est affiche sous la forme provider id`() {
        assertEquals("ollama-cloud/deepseek-v4.1-flash", SessionListMapper.toItem(session()).modelLabel)
        assertNull(SessionListMapper.toItem(session(model = null)).modelLabel)
    }

    @Test
    fun `un cout nul ou absent ne produit pas de libelle`() {
        assertNull(SessionListMapper.toItem(session(cost = null)).costLabel)
        assertNull(SessionListMapper.toItem(session(cost = 0.0)).costLabel)
        assertEquals("0,11 $", SessionListMapper.toItem(session(cost = 0.1057)).costLabel)
    }

    @Test
    fun `le repertoire provient de location`() {
        assertEquals(
            "/home/user",
            SessionListMapper.toItem(
                session().copy(location = sh.sk7.tether.data.api.LocationInfo("/home/user")),
            ).directory,
        )
        assertNull(SessionListMapper.toItem(session()).directory)
    }
}
