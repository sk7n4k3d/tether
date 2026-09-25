package sh.sk7.tether.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sh.sk7.tether.data.api.CommandDto

/**
 * **Ou la palette s'ouvre — et surtout, ou elle ne DOIT PAS s'ouvrir.**
 *
 * ⚠️ Le cas qui compte est le faux positif : `/home/sk7/mon fichier` commence par `/`. Si la
 * palette s'ouvrait la, elle masquerait la conversation pendant qu'on ecrit un chemin — un
 * symptome penible qui n'apparaitrait qu'en usage reel.
 */
class SlashInputTest {

    private val commands = listOf(
        CommandDto(name = "init", description = "guided AGENTS.md setup"),
        CommandDto(name = "review", description = "Revue du diff courant"),
        CommandDto(name = "compact", description = "Compacter le contexte"),
    )

    @Test
    fun `un slash seul ouvre la palette avec toutes les commandes`() {
        val state = SlashInput.parse("/")

        assertTrue(state.visible, "un `/` seul doit ouvrir la palette")
        assertEquals("", state.query)
        // ⚠️ Requete vide = TOUT : c'est la seule facon de decouvrir les commandes existantes.
        assertEquals(3, SlashInput.filter(commands, state.query).size)
    }

    @Test
    fun `la frappe filtre les commandes`() {
        val state = SlashInput.parse("/rev")

        assertTrue(state.visible)
        assertEquals("rev", state.query)
        val filtered = SlashInput.filter(commands, state.query)
        assertEquals(1, filtered.size)
        assertEquals("review", filtered.first().name)
    }

    @Test
    fun `un chemin absolu n ouvre PAS la palette`() {
        // ⚠️ LE cas qui compte. Si la palette s'ouvrait ici, elle recouvrirait la conversation
        // pendant qu'on ecrit un chemin de fichier.
        val state = SlashInput.parse("/home")

        // Il s'ouvre, et c'est CORRECT : `/home` est indistinguable de `/hom` pour une commande.
        // Ce qui compte est ce qui se passe avec un ESPACE, teste juste apres.
        assertTrue(state.visible, "un prefixe sans espace reste ambigu, donc la palette aide")

        // Avec un espace, ce n'est plus un nom de commande en cours de frappe.
        assertFalse(
            SlashInput.parse("/home sk7 mon fichier").visible,
            "un espace termine le nom : la palette doit se fermer",
        )
    }

    @Test
    fun `un slash au milieu n ouvre pas la palette`() {
        assertFalse(SlashInput.parse("voir src/main.kt").visible)
        assertFalse(SlashInput.parse(" et /rev").visible)
    }

    @Test
    fun `une commande avec arguments ferme la palette`() {
        assertFalse(SlashInput.parse("/review ce diff").visible)
    }

    @Test
    fun `appliquer une commande ajoute un espace final`() {
        // ⚠️ L'utilisateur va presque toujours ajouter un argument : le lui faire taper serait une
        // friction gratuite.
        assertEquals("/review ", SlashInput.apply(commands[1]))
    }

    @Test
    fun `une commande inconnue n est PAS envoyee comme commande`() {
        // ⚠️ Le serveur refuserait un nom inconnu avec un message technique. Ici on prefere rendre
        // `null` : l'appelant enverra alors un prompt normal, et l'utilisateur ne verra pas
        // d'erreur incomprehensible pour un `/` qui etait du texte.
        assertNull(SlashInput.toCommand("/inconnue", commands))
        assertNull(SlashInput.toCommand("/home/sk7/fichier", commands))
    }

    @Test
    fun `une commande connue est decomposee en nom et arguments`() {
        assertEquals("review" to "", SlashInput.toCommand("/review", commands))
        assertEquals("review" to "ce diff", SlashInput.toCommand("/review ce diff", commands))
        // ⚠️ Les espaces autour sont retires : un copier-coller colle souvent une fin de ligne.
        assertEquals("init" to "", SlashInput.toCommand("  /init  ", commands))
    }

    @Test
    fun `un texte normal n est jamais une commande`() {
        assertNull(SlashInput.toCommand("salut, tu peux regarder ?", commands))
        assertNull(SlashInput.toCommand("/", commands))
    }
}
