package sh.sk7.tether.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chaque chaine a le bon nombre d'arguments, et aucun `%N$s` ne survit.
 *
 * ## Le defaut que ce test ferme
 *
 * Une chaine qui porte `%1$s` affiche **litteralement** `%1$s` si on ne lui passe pas
 * d'argument. Aucune erreur de compilation ne le signale : l'appel est bien forme, il
 * est simplement incomplet.
 *
 * C'est ce que la capture d'ecran a montre — « Actuel : %1$s » dans les reglages, et
 * onze autres. Tous venaient de la meme conversion, ou un argument s'est perdu en
 * chemin. Un test qui compare le rendu l'aurait vu tard, et seulement sur l'ecran ou
 * l'utilisateur l'atteint.
 *
 * ## Ce qu'on peut verifier ici
 *
 * Exactement, et sans appareil : on lit le XML — le nombre de placeholders de chaque
 * chaine — et le code source — le nombre d'arguments de chaque appel. Le comptage se
 * fait sur le texte de l'appel, parentheses equilibrees : un argument peut contenir une
 * parenthese (`if (x > 1) "s"`, `maxOf { it.steps }`), et compter les virgules donnerait
 * une reponse fausse dans les deux sens.
 */
class ArgumentsTest {

    private val racine = File("src/main/kotlin")

    /** Le nombre de placeholders de chaque chaine, par nom de ressource. */
    private fun placeholders(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (f in listOf("values/strings.xml", "values-fr/strings.xml")) {
            val texte = File("src/main/res/$f").readText()
            val m = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            for (mm in m.findAll(texte)) {
                val n = Regex("""%(\d+)\$""").findAll(mm.groupValues[2])
                    .map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
                out[mm.groupValues[1]] = maxOf(out[mm.groupValues[1]] ?: 0, n)
            }
        }
        return out
    }

    /** Chaque `stringResource(...)` d'une ligne : le nom, et les arguments passes. */
    private fun appels(ligne: String): List<Pair<String, Int>> {
        val resultat = mutableListOf<Pair<String, Int>>()
        for (m in Regex("""stringResource\(""").findAll(ligne)) {
            val debut = m.range.last + 1
            var i = debut
            var prof = 1
            var guillemet = false
            while (i < ligne.length && prof > 0) {
                val c = ligne[i]
                when {
                    guillemet -> if (c == '"') guillemet = false
                    c == '"' -> guillemet = true
                    c == '(' -> prof++
                    c == ')' -> prof--
                }
                i++
            }
            if (prof > 0) continue
            val corps = ligne.substring(debut, i - 1).trim()
            // Le nom et les arguments sont separes par la **premiere** virgule de
            // niveau zero. Prendre `corps` entier comme nom et le recompter comme
            // arguments compterait le nom lui-meme : « in, 3 » donnerait 2.
            val nom = corps.replace("R.string.", "").substringBefore(',').trim()
            val args = corps.removePrefix(corps.substringBefore(',')).removePrefix(",")
            resultat += nom to compter(args)
        }
        return resultat
    }

    /** Le nombre d'arguments d'une liste, virgules de fond ignorees. */
    private fun compter(args: String): Int {
        if (args.isBlank()) return 0
        var n = 1
        var prof = 0
        var guillemet = false
        for (c in args) {
            when {
                guillemet -> if (c == '"') guillemet = false
                c == '"' -> guillemet = true
                c in "([{" -> prof++
                c in ")]}" -> prof--
                c == ',' && prof == 0 -> n++
            }
        }
        return n
    }

    @Test
    fun `chaque stringResource recoit le nombre d arguments de sa chaine`() {
        val attendus = placeholders()
        val fautifs = mutableListOf<String>()
        for (f in racine.walkTopDown().filter { it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, l ->
                for ((nom, n) in appels(l)) {
                    val attendu = attendus[nom] ?: continue
                    if (attendu != n) {
                        fautifs += "${f.name}:${i + 1}  $nom attend $attendu, recoit $n"
                    }
                }
            }
        }
        assertTrue(
            fautifs.isEmpty(),
            "appels dont le nombre d'arguments ne correspond pas — la chaine affichera " +
                "son placeholder en clair :\n" + fautifs.joinToString("\n"),
        )
    }

    @Test
    fun `aucune interpolation Kotlin ne subsiste dans les ressources`() {
        // Android ne sait pas evaluer `${...}`. Si une chaine garde cette forme, elle
        // s'affiche litteralement : elle a l'air d'une valeur, et n'en est pas une.
        for (f in listOf("values/strings.xml", "values-fr/strings.xml", "values/plurals.xml")) {
            val coupables = Regex(
                """<string name="([^"]+)"[^>]*>([^<]*\$\{[^<]*)</string>""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(File("src/main/res/$f").readText())
                .map { it.groupValues[1] }
                .toList()
            assertTrue(coupables.isEmpty(), "interpolation non convertie dans $f : $coupables")
        }
    }

    @Test
    fun `aucun pluriel ne declare qu une seule forme`() {
        // Une seule forme ne peut pas couvrir a la fois 0, 1 et N. Android demanderait
        // alors un texte, et l'utilisateur verrait un compte faux.
        for (f in listOf("values/plurals.xml", "values-fr/plurals.xml")) {
            val p = Regex(
                """<plurals name="([^"]+)">(.*?)</plurals>""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(File("src/main/res/$f").readText())
            for (m in p) {
                val n = Regex("""<item quantity=""").findAll(m.groupValues[2]).count()
                assertTrue(n >= 2, "${m.groupValues[1]} dans $f : une seule forme")
            }
        }
    }

    @Test
    fun `les deux langues exposent les memes ressources`() {
        val noms = { f: String ->
            Regex("""<string name="([^"]+)" """)
                .findAll(File("src/main/res/$f").readText())
                .map { it.groupValues[1] }.toSet()
        }
        assertEquals(noms("values/strings.xml"), noms("values-fr/strings.xml"))
    }
}
