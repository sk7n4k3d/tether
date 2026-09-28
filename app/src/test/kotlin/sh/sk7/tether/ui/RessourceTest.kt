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
 * Une application internationalisee a trois facons de mentir, et aucune ne se voit a
 * la compilation :
 *
 *  1. **Une cle ajoutee dans une seule langue.** L'utilisateur voit la chaine de base —
 *     anglais — sans qu'aucune erreur ne soit levee. C'est la faute la plus commune, et
 *     la plus tolerante des outils : Android l'accepte sans protester.
 *  2. **Une cle orpheline.** La chaine a ete renommee d'un cote, l'autre garde
 *     l'ancien nom. Invisible, jusqu'a ce qu'une reprise de traduction ne trouve plus
 *     rien.
 *  3. **Une chaine vide.** Elle se compile, elle s'affiche, et elle ne dit rien.
 *
 * Aucun de ces cas n'echoue a la compilation : Gradle les accepte tous les trois. Seule
 * une lecture comparee des deux fichiers les voit — d'ou ce test, qui n'a besoin ni
 * d'emulateur, ni de `Context`, ni d'Android.
 *
 * ## Pourquoi lire le fichier et non `getString`
 *
 * `getString` ne fonctionne que sous Android, avec un `Context` : impossible en test
 * JVM. Et surtout, il ne verrait qu'**une** langue a la fois, donc ne pourrait pas
 * detecter la derive entre les deux. Le fichier, lui, se lit partout.
 */
class RessourceTest {

    private val base = File("src/main/res/values/strings.xml")
    private val fr = File("src/main/res/values-fr/strings.xml")

    private fun toutes(f: File): Map<String, String> {
        assertTrue(f.exists(), "${f.path} absent")
        val m = Regex(
            """<string name="([^"]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(f.readText())
        return m.associate { it.groupValues[1] to dechapper(it.groupValues[2]) }
    }

    /** `\'` et `\"` sont de l'echappement XML Android, pas du contenu. */
    private fun dechapper(v: String) = v.replace("\\'", "'").replace("\\\"", "\"")

    private val anglais by lazy { toutes(base) }
    private val francais by lazy { toutes(fr) }

    @Test
    fun `les deux langues exposent exactement les memes cles`() {
        assertEquals(
            anglais.keys - francais.keys,
            emptySet(),
            "cles presentes en anglais mais absentes du francais : elles s'afficheraient en anglais",
        )
        assertEquals(
            francais.keys - anglais.keys,
            emptySet(),
            "cles presentes en francais mais absentes de la base : elles ne s'afficheraient jamais",
        )
    }

    @Test
    fun `aucune chaine n est vide`() {
        for ((nom, valeur) in anglais + francais) {
            assertTrue(valeur.isNotBlank(), "chaine vide : $nom")
        }
    }

    @Test
    fun `aucune phrase n est identique dans les deux langues`() {
        // ⚠️ Une **phrase** identique des deux cotes est presque toujours une
        // traduction oubliee : on a copie la chaine de base dans `values-fr`.
        //
        // Le seuil de quatre mots est ce qui rend le test utile. En dessous, l'identite
        // est ** normale : « Session », « Sessions », « Volume », « Maximum %1$s » sont
        // les memes en francais et en anglais, et les marquer « non traduisible » serait
        // un mensonge — le traducteur doit pouvoir les reprendre. Au-dessus, l'identite
        // ne s'explique pas.
        val phrases = (anglais.keys intersect francais.keys).filter { cle ->
            val a = anglais[cle].orEmpty()
            a == francais[cle] && a.split(" ").count { it.any { c -> c.isLetter() } } >= 4
        }
        assertTrue(
            phrases.isEmpty(),
            "phrases identiques des deux cotes — traduction oubliee : $phrases",
        )
    }

    @Test
    fun `le detail sans distributeur nomme l application a installer`() {
        // ⚠️ « aucun distributeur UnifiedPush » seul ne dit pas quoi faire. La chaine
        // retenue doit nommer une application concrete, sinon l'utilisateur reste
        // devant une impasse — et il ne sait pas ce qu'il doit installer.
        assertTrue(
            francais.any { it.value.contains("distributeur", true) && it.value.contains("ntfy", true) },
            "aucune chaine ne dit a la fois « distributeur » et « ntfy » : " +
                "l'utilisateur ne sait pas quoi installer",
        )
    }

    @Test
    fun `aucune interpolation Kotlin n a survecu a l extraction`() {
        // Android ne sait pas evaluer `${...}`. Si une chaine garde cette forme, elle
        // s'affiche **litteralement** a l'ecran — le pire resultat possible, parce
        // qu'elle a l'air d'une valeur.
        for (f in listOf(base, fr)) {
            val coupables = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(f.readText())
                .map { it.groupValues[1] to it.groupValues[2] }
                .filter { it.second.contains("\${") }
                .map { it.first }
                .toList()
            assertTrue(coupables.isEmpty(), "interpolation non convertie dans ${f.name} : $coupables")
        }
    }
}
