package sh.sk7.tether.ui.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sh.sk7.tether.data.api.FileDiffDto

/**
 * **Le parseur de diff : les cas ou la regle « ca commence par + » est fausse.**
 *
 * ⚠️ Ces tests ne verifient pas la mise en forme, ils verifient le **classement** des lignes.
 * C'est la seule partie qui peut se tromper en silence, et une erreur ici afficherait un en-tete
 * de fichier comme du code ajoute — un diff faux, sans que rien ne le signale.
 */
class UnifiedDiffTest {

    private fun dto(patch: String, file: String = "src/main.kt") = FileDiffDto(
        file = file,
        patch = patch,
        additions = 0,
        deletions = 0,
        status = "modified",
    )

    @Test
    fun `un en-tete de fichier n est pas une ligne ajoutee`() {
        // ⚠️ LE cas qui casse une regex naive : `+++ b/fichier` commence par `+`.
        val patch = """
            |diff --git a/f.kt b/f.kt
            |index abc123..def456 100644
            |--- a/f.kt
            |+++ b/f.kt
            |@@ -1,3 +1,3 @@
            | ligne
            |-ancienne
            |+nouvelle
        """.trimMargin()

        val parsed = UnifiedDiff.parse(dto(patch))

        val kinds = parsed.lines.map { it.kind }
        // Les quatre premieres lignes sont des en-tetes, jamais des ajouts.
        assertEquals(UnifiedDiff.Kind.FileHeader, kinds[0], "diff --git")
        assertEquals(UnifiedDiff.Kind.FileHeader, kinds[1], "index")
        assertEquals(UnifiedDiff.Kind.FileHeader, kinds[2], "--- a/")
        assertEquals(UnifiedDiff.Kind.FileHeader, kinds[3], "+++ b/ — LE piege")
        assertEquals(UnifiedDiff.Kind.HunkHeader, kinds[4], "@@")
        assertEquals(UnifiedDiff.Kind.Context, kinds[5])
        assertEquals(UnifiedDiff.Kind.Deletion, kinds[6])
        assertEquals(UnifiedDiff.Kind.Addition, kinds[7])
    }

    @Test
    fun `du code qui commence par trois plus reste du contenu ajoute`() {
        // ⚠️ Ecrire `+++` dans du contenu est courant (du Markdown, un diff dans un diff). Une
        // fois dans un bloc `@@`, un `+++` ne peut PAS etre un en-tete : la borne de position le
        // garantit.
        val patch = """
            |@@ -10,1 +10,2 @@
            | contexte
            |++++ bar
        """.trimMargin()

        val parsed = UnifiedDiff.parse(dto(patch))
        val lines = parsed.lines

        val addition = lines.last()
        assertEquals(UnifiedDiff.Kind.Addition, addition.kind)
        // Le `+` de diff est retire, les trois du contenu restent : `++++ bar` -> `+++ bar`.
        assertEquals("+++ bar", addition.text)
    }

    @Test
    fun `la ligne d absence de saut de ligne n est ni ajout ni suppression`() {
        val patch = """
            |@@ -1,1 +1,1 @@
            |-a
            |+b
            |\ No newline at end of file
        """.trimMargin()

        val parsed = UnifiedDiff.parse(dto(patch))
        val lines = parsed.lines

        assertEquals(UnifiedDiff.Kind.Meta, lines.last().kind)
        assertTrue(lines.last().text.startsWith("\\"), lines.last().text)
    }

    @Test
    fun `une addition de ligne vide est bien une addition`() {
        // ⚠️ `+` tout seul : le prefixe est la, le texte est vide. La confondre avec du contexte
        // perdrait une ligne ajoutee — un diff qui ne dit pas tout ce qui a change.
        val patch = "@@ -1,1 +1,2 @@\n a\n+"

        val parsed = UnifiedDiff.parse(dto(patch))
        val addition = parsed.lines.last()

        assertEquals(UnifiedDiff.Kind.Addition, addition.kind)
        assertEquals("", addition.text)
    }

    @Test
    fun `plusieurs blocs sont tous conserves`() {
        val patch = """
            |@@ -1,2 +1,2 @@
            |-un
            |+deux
            |@@ -50,2 +50,2 @@
            |-trois
            |+quatre
        """.trimMargin()

        val parsed = UnifiedDiff.parse(dto(patch))

        val headers = parsed.lines.filter { it.kind == UnifiedDiff.Kind.HunkHeader }
        assertEquals(2, headers.size, "les deux reperes de bloc doivent exister")
        assertEquals("@@ -1,2 +1,2 @@", headers[0].text)
        assertEquals("@@ -50,2 +50,2 @@", headers[1].text)
        // ⚠️ Les numeros de ligne des blocs sont la seule information de position : ils doivent
        // survivre, c'est ce qui permet de retrouver le code dans le fichier.
        assertEquals(UnifiedDiff.Kind.Addition, parsed.lines.last().kind)
    }

    @Test
    fun `un patch vide est signale comme tel, pas affiche comme un fichier sans changement`() {
        // ⚠️ `patchMissing` distingue « le serveur n'a pas envoye de patch » (binaire, tronque) de
        // « le fichier n'a aucune ligne ». Les confondre afficherait un fichier vide pour un
        // changement binaire.
        val parsed = UnifiedDiff.parse(dto("", file = "logo.png"))

        assertTrue(parsed.patchMissing)
        assertTrue(!parsed.isDisplayable)
        assertEquals("logo.png", parsed.path)
    }

    @Test
    fun `les compteurs viennent du serveur, pas d un comptage local`() {
        // ⚠️ Le patch peut etre tronque par `context` : compter les `+` localement donnerait un
        // chiffre plus petit que le vrai. On garde ceux du serveur.
        val parsed = UnifiedDiff.parse(
            FileDiffDto(
                file = "f.kt",
                patch = "@@ -1,1 +1,2 @@\n+a\n",
                additions = 900,
                deletions = 12,
                status = "modified",
            ),
        )

        assertEquals(900, parsed.additions)
        assertEquals(12, parsed.deletions)
    }

    @Test
    fun `les trois statuts sont traduits, et un statut inconnu ne casse rien`() {
        assertEquals(
            UnifiedDiff.Status.Added,
            UnifiedDiff.parse(FileDiffDto(file = "a", status = "added")).status,
        )
        assertEquals(
            UnifiedDiff.Status.Deleted,
            UnifiedDiff.parse(FileDiffDto(file = "a", status = "deleted")).status,
        )
        // ⚠️ Un statut que le serveur ajouterait demain ne doit pas faire planter l'ecran.
        assertEquals(
            UnifiedDiff.Status.Modified,
            UnifiedDiff.parse(FileDiffDto(file = "a", status = "renamed")).status,
        )
    }
}
