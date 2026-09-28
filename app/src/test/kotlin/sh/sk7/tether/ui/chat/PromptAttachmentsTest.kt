package sh.sk7.tether.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **La fabrication d'une piece jointe, testee sur ses pieges mesures.**
 *
 * ### Pourquoi ces tests existent
 * Les seules formes que le serveur accepte ont ete mesurees : `data:` inline et `file://` absolu.
 * Un chemin relatif, une URL `https`, ou un `file://` relatif sont **refuses** — et le refus
 * n'arrive qu'a l'envoi, apres que l'utilisateur a appuye sur Envoyer. C'est exactement le genre
 * d'erreur qu'on ne voit pas a la compilation, donc on la fige ici.
 *
 * ⚠️ Le base64 est produit par `java.util.Base64` et non `android.util.Base64` : c'est ce qui rend
 * cette classe testable **sans Robolectric**, donc sans dependance ajoutee.
 */
class PromptAttachmentsTest {

    // ------------------------------------------------------------------
    // Fichiers du telephone : encodage inline
    // ------------------------------------------------------------------

    @Test
    fun `un fichier du telephone devient un data URI base64 sans saut de ligne`() {
        val attachment = PromptAttachments.fromBytes("note.txt", "text/plain", "hello".toByteArray())
        requireNotNull(attachment)
        // "hello" en base64 = aGVsbG8=. C'est la forme que le serveur a acceptee en mesure.
        assertEquals("data:text/plain;base64,aGVsbG8=", attachment.uri)
        assertEquals("note.txt", attachment.name)
        // ⚠️ Aucun saut de ligne : un `data:` URI qui en contient un n'est pas un URI valide.
        assertTrue(!attachment.uri.contains("\n"), attachment.uri)
    }

    @Test
    fun `le type MIME absent retombe sur octet-stream, jamais sur du texte`() {
        val attachment = PromptAttachments.fromBytes("binaire", null, byteArrayOf(0, 1, 2))
        requireNotNull(attachment)
        // ⚠️ `text/plain` par defaut serait un mensonge : le serveur decoderait des octets
        // arbitraires comme du texte. `application/octet-stream` dit ce qu'on ne sait pas.
        assertTrue(attachment.uri.startsWith("data:application/octet-stream;base64,"), attachment.uri)
    }

    @Test
    fun `un MIME vide est traite comme absent`() {
        val attachment = PromptAttachments.fromBytes("x", "   ", byteArrayOf(1))
        requireNotNull(attachment)
        assertTrue(attachment.uri.startsWith("data:application/octet-stream;base64,"), attachment.uri)
    }

    @Test
    fun `un fichier trop gros est refuse plutot qu'envoye`() {
        val tooBig = ByteArray(PromptAttachments.MAX_FILE_BYTES + 1)
        assertNull(PromptAttachments.fromBytes("gros.bin", null, tooBig))
    }

    @Test
    fun `un fichier exactement a la borne passe`() {
        val atLimit = ByteArray(PromptAttachments.MAX_FILE_BYTES)
        // ⚠️ La borne est inclusive : un fichier de 4 Mo pile doit passer. Un `>` transforme en
        // `>=` refuserait un fichier que le serveur accepte — un refus qu'on ne saurait expliquer.
        requireNotNull(PromptAttachments.fromBytes("gros.bin", null, atLimit))
    }

    // ------------------------------------------------------------------
    // Fichiers du serveur : chemin absolu
    // ------------------------------------------------------------------

    @Test
    fun `un chemin absolu devient une URI file avec son nom`() {
        val attachment = PromptAttachments.fromServerPath("/home/user/Projects/tether/settings.gradle.kts")
        requireNotNull(attachment)
        assertEquals("file:///home/user/Projects/tether/settings.gradle.kts", attachment.uri)
        assertEquals("settings.gradle.kts", attachment.name)
    }

    @Test
    fun `un chemin relatif est refuse, jamais prefixe en silence`() {
        // ⚠️ Mesure : `Projects/tether/README.md` rend `400 Invalid attachment URI`. Le prefixer
        // en `file://` donnerait `file://Projects/...` -> `400 Invalid file URI`. Les deux
        // echouent, donc on refuse tot et on le dit, plutot que d'envoyer une URI cassee.
        assertNull(PromptAttachments.fromServerPath("Projects/tether/README.md"))
        assertNull(PromptAttachments.fromServerPath("README.md"))
        assertNull(PromptAttachments.fromServerPath(""))
    }

    @Test
    fun `une URI https n est jamais acceptee comme fichier`() {
        // ⚠️ Mesure : `https://example.com/x.md` rend `400 Unsupported attachment URI`. Ce n'est
        // pas un chemin serveur, donc `fromServerPath` doit le refuser (il ne commence pas par /).
        assertNull(PromptAttachments.fromServerPath("https://example.com/x.md"))
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------

    @Test
    fun `les tailles se lisent en unites utiles`() {
        assertEquals("512 o", PromptAttachments.formatSize(512))
        assertEquals("2 Ko", PromptAttachments.formatSize(2048))
        assertEquals("1.5 Mo", PromptAttachments.formatSize(1024 * 1024 * 3 / 2))
    }
}
