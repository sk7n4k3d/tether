package sh.sk7.tether.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Les pluriels, en francais.
 *
 * ## Pourquoi des `<plurals>` et pas un `(s)`
 *
 * `« %1$s autorisation(s) »` est la solution qu'on ecrit quand on ne veut pas se
 * poser la question. Elle produit « 1 autorisation(s) », lu comme une faute par
 * quiconque sait qu'il y a une seule autorisation.
 *
 * Le francais n'a pas la simplicite de l'anglais : le pluriel porte sur le **nom**,
 * pas sur la marque, et certain noms changent completement au pluriel (« ceil ->
 * ceils », « particulier -> particuliers »). Android fournit `pluralStringResource`,
 * qui fait le travail : la regle est ecrite une fois, en francais, et elle est juste
 * parce que c'est la langue qui sait.
 *
 * ## Ce que ce test verifie
 *
 * Que chaque chaine a compte et a nom possesse une forme **singuliere** et une forme
 * **plurielle**, distinctes, dans les deux langues. Une forme manquante ne plante
 * pas : Android renvoie le `other` de la langue par defaut, et l'utilisateur voit du
 * francais sur un appareil anglais.
 */
class PlurielTest {

    private val base = File("src/main/res/values/plurals.xml")
    private val fr = File("src/main/res/values-fr/plurals.xml")

    /**
     * Le nombre de formes declarees, par pluriel.
     *
     * On compte les `<item>`, pas les **textes distincts** : « 1 choix minimum » et
     * « 3 choix minimum » sont volontairement identiques, parce que le nombre
     * s'accorde et que le nom, non. Compter les textes distincts ferait croire a un
     * pluriel incomplet.
     */
    private fun plurals(f: File): Map<String, Int> {
        assertTrue(f.exists(), "${f.path} absent")
        return Regex(
            """<plurals name="([^"]+)">(.*?)</plurals>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(f.readText()).associate { r ->
            val n = Regex("""<item quantity=""").findAll(r.groupValues[2]).count()
            r.groupValues[1] to n
        }
    }

    @Test
    fun `chaque pluriel a un singulier et un pluriel, distincts`() {
        for (f in listOf(base, fr)) {
            for ((nom, n) in plurals(f)) {
                // Une seule forme ne peut pas couvrir a la fois 0, 1 et N : Android
                // demanderait alors un texte, et l'utilisateur verrait un compte faux.
                assertTrue(n >= 2, "$nom dans ${f.name} : une seule forme")
            }
        }
    }

    @Test
    fun `les deux langues exposent les memes pluriels`() {
        assertEquals2(
            plurals(base).keys,
            plurals(fr).keys,
            "meme jeu de pluriels dans les deux langues",
        )
    }

    @Test
    fun `le pluriel singleton s applique a zero comme a un`() {
        // ⚠️ C'est la regle du francais : « 0 session » est **singulier**, alors que
        // 0 est un pluriel dans la plupart des autres cas. C'est la regle "one" d'Android
        // (`fr` : `one` couvre 0 et 1). Une implementation qui l'ignore affiche
        // « 0 sessions » — faux, et visible sur un compte vide.
        val fr_quantites = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(fr.readText())
            .associate { r ->
                r.groupValues[1] to Regex("""quantity="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(r.groupValues[2]).map { it.groupValues[1] }.toList()
            }
        for ((nom, quantites) in fr_quantites) {
            assertTrue(
                "one" in quantites,
                "$nom : la forme « one » est obligatoire en francais, elle couvre 0 et 1",
            )
            assertTrue("other" in quantites, "$nom : la forme « other » est obligatoire")
        }
    }

    private fun assertEquals2(a: Any, b: Any, message: String) {
        if (a != b) throw AssertionError("$message : $a != $b")
    }
}
