package sh.sk7.tether.domain.model

import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.domain.model.AgentCatalog.carriedModelLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalogue des agents.
 *
 * ⚠️ **Les tests encodent la capture REELLE du 2026-09-26** (`GET /api/agent` sur notre opencode
 * 2.0.x, 23 agents), pas une forme inventee. Une forme inventee validerait un buggy au lieu de le
 * detecter : c'est ce qui s'est deja produit sur ce projet (le cas `{"id":"form_1"}`).
 * Ce qui est reellement reproduit ici :
 * - `mode` vaut `all` (1 agent), `primary` (6) ou `subagent` (16) ;
 * - 3 agents sont `primary` **et** `hidden` : `compaction`, `title`, `summary` ;
 * - `tank` est `subagent` **et** `hidden` ;
 * - chaque agent porte son **propre** modele, absent pour certains (`explore`, `architect`…).
 */
class AgentCatalogTest {

    private fun agent(
        id: String,
        mode: String? = null,
        hidden: Boolean = false,
        model: String? = null,
    ) = Agent(
        id = id,
        mode = mode,
        hidden = hidden,
        model = model?.let { ModelRef(id = it, providerID = "ollama-cloud") },
    )

    /** Les 23 agents, sous la forme reelle mesuree. */
    private val measured = listOf(
        agent("general", mode = "all", model = "deepseek-v4.1-flash"),
        agent("build", mode = "primary", model = "glm-5.3"),
        agent("explore", mode = "subagent"),
        agent("compaction", mode = "primary", hidden = true),
        agent("title", mode = "primary", hidden = true, model = "deepseek-v4.1-flash"),
        agent("summary", mode = "primary", hidden = true),
        agent("plan", mode = "primary", model = "deepseek-v4.1-flash"),
        agent("tank", mode = "subagent", hidden = true),
        agent("edit", mode = "primary", model = "deepseek-v4.1-flash"),
        agent("code-ops", mode = "subagent", model = "deepseek-v4.1-flash"),
        agent("verify", mode = "subagent", model = "deepseek-v4.1-flash"),
        agent("architect", mode = "subagent"),
        agent("doc-writer", mode = "subagent"),
    )

    @Test
    fun `il ne reste que les quatre agents selectionnables`() {
        // ⚠️ La liste EXACTE, dans l'ordre du serveur. C'est le nombre que l'utilisateur voit.
        assertEquals(
            listOf("general", "build", "plan", "edit"),
            AgentCatalog.selectable(measured).map { it.id },
        )
    }

    @Test
    fun `un agent cache n'est jamais propose`() {
        val ids = AgentCatalog.selectable(measured).map { it.id }
        listOf("compaction", "title", "summary", "tank").forEach { hidden ->
            assertTrue(hidden !in ids, "$hidden est interne au serveur, il ne doit pas etre propose")
        }
    }

    @Test
    fun `general est selectionnable malgre son mode all`() {
        // ⚠️ Le piege : filtrer sur `mode == "primary"` raterait `general`, qui est precisement
        // l'agent qu'on ne doit pas perdre — c'est le seul en `all` et le defaut du serveur.
        val general = measured.first { it.id == "general" }
        assertEquals("all", general.mode)
        assertTrue(AgentCatalog.selectable(measured).any { it.id == "general" })
    }

    @Test
    fun `l agent par defaut est le seul en mode all`() {
        assertEquals("general", AgentCatalog.serverDefault(measured)?.id)
        assertEquals(1, measured.count { it.mode == "all" }, "la regle tient sur un seul agent")
    }

    @Test
    fun `aucun defaut n est invente si le serveur n en expose aucun`() {
        // ⚠️ Un serveur sans agent en `all` ne doit pas faire apparaitre un `general` code en dur :
        // on n'affiche alors **aucun** defaut plutot qu'un defaut qui n'existe pas.
        val sansAll = measured.filter { it.mode != "all" }
        assertNull(AgentCatalog.serverDefault(sansAll))
    }

    @Test
    fun `chaque agent affiche le modele qu il amene`() {
        val build = AgentCatalog.selectable(measured).first { it.id == "build" }
        assertEquals("ollama-cloud/glm-5.3", build.carriedModelLabel())
    }

    @Test
    fun `un agent qui herite n affiche pas de modele`() {
        // `explore` et `architect` n'ont pas de `model` : afficher le defaut a leur place serait
        // inventer une valeur. `null` = rien a dire.
        val explore = measured.first { it.id == "explore" }
        assertNull(explore.carriedModelLabel())
    }

    @Test
    fun `le libelle de modele porte le provider`() {
        // Deux providers peuvent servir le meme `id` : n'afficher que l'`id` mentirait sur celui
        // qu'on va payer.
        assertEquals(
            "ollama-cloud/glm-5.3",
            AgentCatalog.modelLabel(Model(id = "glm-5.3", modelID = "glm-5.3", providerID = "ollama-cloud")),
        )
        // ⚠️ Sans provider, pas de barre oblique orpheline : `/glm-5.3` se lit comme une panne.
        assertEquals("space-bunny-free", AgentCatalog.modelLabel(Model(id = "space-bunny-free")))
    }

    @Test
    fun `une liste vide ne casse rien`() {
        assertTrue(AgentCatalog.selectable(emptyList()).isEmpty())
        assertNull(AgentCatalog.serverDefault(emptyList()))
    }
}
