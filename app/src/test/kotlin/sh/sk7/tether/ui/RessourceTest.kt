package sh.sk7.tether.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Les ressources de langue, **lues dans les fichiers**.
 *
 * ## Pourquoi un test qui lit du XML
 *
 * Une application internationalisee a trois facons de mentir, et aucune ne se voit a la
 * compilation :
 *
 *  1. **Une cle ajoutee dans une seule langue.** L'utilisateur voit la chaine de base —
 *     anglais — sans qu'aucune erreur ne soit levee. C'est la faute la plus commune, et la
 *     plus tolerante des outils : Android l'accepte sans protester.
 *  2. **Une cle orpheline.** La chaine a ete renommee d'un cote, l'autre garde l'ancien
 *     nom. Invisible, jusqu'a ce qu'une reprise de traduction ne trouve plus rien.
 *  3. **Une chaine vide.** Elle se compile, elle s'affiche, et elle ne dit rien.
 *
 * Aucun de ces cas n'echoue a la compilation : Gradle les accepte tous les trois. Seule
 * une lecture comparée des deux fichiers les voit — d'ou ce test, qui n'a besoin ni
 * d'emulateur, ni de `Context`, ni de Android.
 *
 * ## Pourquoi lire le fichier et non `getString`
 *
 * `getString` ne fonctionne qusous Android, avec un `Context` : impossible en test JVM.
 * Et surtout, il ne verrait qu'**une** langue a la fois, donc ne pourrait pas detecter la
 * derive entre les deux. Le fichier, lui, se lit partout.
 */
class RessourceTest {

    private val base = File("src/main/res/values/strings.xml")
    private val fr = File("src/main/res/values-fr/strings.xml")

    private fun paires(f: File): Map<String, String> {
        assertTrue(f.exists(), "${f.path} absent")
        val m = Regex(
            """<string name="([^"]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(f.readText())
        return m.associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `les deux langues exposent exactement les memes cles`() {
        val a = paires(base).keys
        val b = paires(fr).keys
        assertEquals(
            a - b,
            emptySet(),
            "cles presentes en anglais mais absentes du francais : elles s'afficheraient en anglais",
        )
        assertEquals(
            b - a,
            emptySet(),
            "cles presentes en francais mais absentes de la base : elles ne s'afficheraient jamais",
        )
    }

    @Test
    fun `aucune chaine n est vide`() {
        for ((nom, valeur) in paires(base) + paires(fr)) {
            assertTrue(valeur.isNotBlank(), "chaine vide : $nom")
        }
    }

    @Test
    fun `le detail sans distributeur nomme l application a installer`() {
        // ⚠️ « aucun distributeur UnifiedPush » seul ne dit pas quoi faire. La chaine retenue
        // doit nommer une application concrete, sinon l'utilisateur reste devant une impasse.
        val nom = paires(base).filterValues { it.contains("distributor", ignoreCase = true) }
            .filterValues { it.contains("ntfy", ignoreCase = true) }
        assertTrue(
            nom.isNotEmpty(),
            "aucune chaine ne dit a la fois « distributeur » et « ntfy » : " +
                "l'utilisateur ne sait pas quoi installer",
        )
    }

    @Test
    fun `les traductions francaises ne sont pas des copies de l anglais`() {
        // Une cle non traduite se copie telle quelle. On tolere les cas legitimes —
        // « Tether », « AGENT », les chaines marquees non traduisibles — mais une copie
        // francaise d'une phrase anglaise signale une traduction oubliee.
        val anglais = paires(base)
        val francais = paires(fr)
        val suspects = francais.filter { (cle, valeur) ->
            val a = anglais[cle].orEmpty()
            val motsAnglais = a.split(" ").count { it.length > 3 }
            // Une phrase anglaise de plusieurs mots, identique en francais, n'est pas traduite.
            motsAnglais >= 4 && a == valeur && a.any { it.isUpperCase() } && a.contains(" ")
        }
        assertTrue(
            suspects.isEmpty(),
            "chaines vraisemblablement non traduites : ${suspects.keys}",
        )
    }

    @Test
    fun `les chaines marquees non traduisibles sont bien presentes des deux cotes`() {
        // Une chaine `translatable="false"` existe dans **les deux** fichiers : Android la
        // sert telle quelle. L'oublier dans `values-fr` ne casse rien, mais la base doit rester
        // la reference complete.
        val nonTrad = Regex("""<string name="([^"]+)" translatable="false">""")
            .findAll(base.readText()).map { it.groupValues[1] }.toSet()
        assertTrue(nonTrad.isNotEmpty(), "aucune chaine non traduisible : le marqueur est-il preserve ?")
        assertTrue(
            nonTrad.all { it in paires(fr) },
            "chaines non traduisibles absentes du francais : ${nonTrad - paires(fr).keys}",
        )
    }
}
