package sh.sk7.tether.ui.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Le choix de branche, verifie **sans texte**.
 *
 * ## Pourquoi on ne compare plus le texte
 *
 * Le texte vient des ressources Android : pas de `Context` en test JVM, donc pas de
 * ressources. Comparer du francais ici testerait une chaine, pas une logique — et
 * casserait a la premiere reecriture de traduction, pour une raison qui n'a rien a
 * voir avec le code.
 *
 * Ce qui compte, en revanche, se verifie exactement : **quelle** chaine est choisie
 * selon la duree, et **quelle valeur** on lui passe. C'est la decision que la fonction
 * arbitre, et elle ne depend pas de la langue.
 *
 * Le test fournit un resolveur qui enregistre. L'identifiant d'une branche n'est pas
 * une constante connue ici — `R` n'existe pas hors d'un build Android — mais il
 * n'a pas besoin de l'etre : deux durees qui doivent donner des chaines differentes
 * donnent des identifiants differents, et c'est exactement ce que le test verifie.
 */
class RelativeTimeTest {

    private val now = 1_800_000_000_000L

    private class Choix(val id: Int, val args: Array<out Any>)

    private fun choisir(depuisMillis: Long?): Choix {
        var choix: Choix? = null
        RelativeTime.format(now, depuisMillis) { id, args ->
            choix = Choix(id, args)
            "texte"
        }
        return choix ?: error("aucune chaine demandee pour $depuisMillis")
    }

    @Test
    fun `un horodatage absent rend un tiret`() {
        // Le seul cas qui ne passe pas par une ressource : il n'y a rien a traduire,
        // et le tiret est le meme dans toutes les langues.
        assertEquals("—", RelativeTime.format(now, null))
    }

    @Test
    fun `moins d une minute rend la chaine instantanee, sans argument`() {
        val c = choisir(now - 5_000)
        assertTrue(c.args.isEmpty(), "l'instantane ne prend aucun argument : ${c.args}")
    }

    @Test
    fun `les minutes sont arrondies vers le bas`() {
        // 3 min 59 donne 3, pas 4 : l'arrondi **descend**, donc un message ne peut pas
        // annoncer plus de temps qu'il ne s'en est ecoule.
        assertEquals(3L, choisir(now - 3 * 60_000 - 59_000).args[0])
        assertEquals(59L, choisir(now - 59 * 60_000).args[0])
    }

    @Test
    fun `chaque unite a sa propre chaine`() {
        val seconde = choisir(now - 5_000).id
        val minute = choisir(now - 3 * 60_000).id
        val heure = choisir(now - 3 * 60 * 60_000).id
        val jour = choisir(now - 3 * 24 * 60 * 60_000).id
        val chaines = listOf(seconde, minute, heure, jour)
        assertEquals(
            chaines.size,
            chaines.toSet().size,
            "chaque duree doit choisir sa propre chaine, pas en partager une",
        )
    }

    @Test
    fun `les seuils basculent a l unite superieure`() {
        // 59 min reste en minutes, 60 min passe en heures : c'est le bord exact qui
        // casse quand on utilise `>` au lieu de `>=`.
        val minutes = choisir(now - 59 * 60_000)
        val heures = choisir(now - 60 * 60_000)
        assertNotEquals(minutes.id, heures.id, "60 minutes doit basculer en heures")
        assertEquals(1L, heures.args[0])
    }

    @Test
    fun `un horodatage dans le futur ne rend pas de duree negative`() {
        // Une horloge desynchronisee ne doit pas afficher « il y a -3 min ».
        val c = choisir(now + 3 * 60_000)
        assertTrue(c.args.isEmpty(), "le futur doit tomber sur l'instantane, pas sur un compte")
    }

    @Test
    fun `au dela d une semaine on affiche une date`() {
        // La date se formate seule, sans ressource. Le test verifie qu'on **sort** du
        // relatif : sinon l'ecran afficherait « il y a 400 j ».
        var demande = false
        val rendu = RelativeTime.format(now, now - 40L * 24 * 60 * 60_000) { _, _ ->
            demande = true
            "chaine relative"
        }
        assertTrue(!demande, "au-dela d'une semaine, aucune chaine relative ne doit etre demandee")
        assertTrue(rendu.isNotBlank(), "la date doit s'afficher quand meme")
    }
}
