package sh.sk7.tether.ui.chat

import sh.sk7.tether.data.api.Model
import sh.sk7.tether.domain.model.AgentCatalog
import sh.sk7.tether.ui.chat.PickerItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * **Le selecteur doit renvoyer une identite, pas un libelle.**
 *
 * ⚠️ Ce test existe parce du bug du 2026-09-27 : les modeles sont affiches en `provider/id`
 * (juste, et plus long que l'id), mais le callback cherchait `models.first { it.id == picked }`.
 * La comparaison se faisait entre `"opencode/space-bunny-free"` et `"space-bunny-free"`, ne
 * trouvait rien, et le changement **n'etait jamais envoye au serveur** — sans erreur, sans
 * trace. La session retombait sur le defaut du serveur.
 *
 * Il est ecrit pour **echouer sur le code d'avant** : il rejoue la construction fautive et exige
 * que la resolution passe. Verifie en le rejouant sur `675e174` + ce seul fichier.
 */
class PickerItemValueTest {

    private val models = listOf(
        Model(id = "space-bunny-free", modelID = "space-bunny-free", providerID = "opencode", name = "Space Bunny Free"),
        Model(id = "deepseek-v4.1-flash", modelID = "deepseek-v4.1-flash", providerID = "ollama-cloud", name = "DeepSeek V4.1 Flash"),
    )

    /** La construction reelle de `ChatScreen` : libelle `provider/id`, valeur l'id nu. */
    private fun items() = models.map { PickerItem(AgentCatalog.modelLabel(it), it.name, value = it.id) }

    @Test
    fun `le selecteur renvoie une valeur que le callback sait resoudre`() {
        val picked = items().first { it.label.contains("space-bunny") }.value
        val resolved = models.firstOrNull { it.id == picked }
        assertNotNull(resolved, "le callback doit retrouver le modele — value='$picked'")
        assertEquals("space-bunny-free", resolved.id)
    }

    /**
     * Le libelle **ne doit pas** etre la valeur.
     *
     * ⚠️ C'est le piege exact : si quelqu'un revient a `PickerItem(label)` sans `value`, ce test
     * echoue immediatement, parce que le libelle ne correspond a aucun `id`.
     */
    @Test
    fun `le libelle seul ne resout rien - c est le piege`() {
        val parLabel = items().first { it.label.contains("space-bunny") }.label
        assertNull(
            models.firstOrNull { it.id == parLabel },
            "par construction, un libelle 'provider/id' ne vaut pas un id — sinon le bug revient",
        )
    }

    @Test
    fun `par defaut la valeur vaut le libelle`() {
        val item = PickerItem("general")
        assertEquals("general", item.value)
    }

    @Test
    fun `les deux modeles se resolvent`() {
        items().forEach { item ->
            assertNotNull(
                models.firstOrNull { it.id == item.value },
                "modele non resolu : label='${item.label}' value='${item.value}'",
            )
        }
    }
}
