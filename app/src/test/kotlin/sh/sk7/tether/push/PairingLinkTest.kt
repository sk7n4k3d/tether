package sh.sk7.tether.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'analyse du lien d'appairage — la seule entree que l'utilisateur ne saisit pas.
 *
 * Le risque teste ici n'est pas une erreur de parsing : c'est un **QR forge** qui
 * pointerait l'app vers un serveur tiers, qui recupererait ensuite tout ce que l'agent
 * notifie. C'est pour cela que `s` est valide aussi strictement que possible, et que les
 * cas hostiles sont tous presents — y compris ceux qui paraissent anodins.
 */
class PairingLinkTest {

    // 16 octets reels en base64url : 22 caracteres. Un jeton de 128 bits, comme
    // [sh.sk7.tether.push.PairingLink.TOKEN_BYTES] cote serveur.
    private val jetonValide = "8z5U-NzHdByXsKPjyIZU1g"

    private fun requete(serveur: String, jeton: String) = mapOf("s" to serveur, "t" to jeton)

    @Test
    fun `un lien bien forme donne le serveur et le jeton`() {
        val demande = PairingLink.fromParts(
            "opencode",
            "pair",
            requete("https://opencode.exemple.fr:4096", jetonValide),
        )
        assertEquals("https://opencode.exemple.fr:4096", demande?.server)
        assertEquals(jetonValide, demande?.token)
    }

    @Test
    fun `le port fait partie du serveur`() {
        // C'est l'adresse complete que l'ecran de confirmation affiche : c'est elle que
        // l'utilisateur compare a ce qu'il croit avoir ouvert.
        val demande = PairingLink.fromParts("opencode", "pair", requete("https://exemple.fr:4096", jetonValide))
        assertEquals("https://exemple.fr:4096", demande?.server)
    }

    @Test
    fun `un serveur en clair sur le reseau est refuse`() {
        // Le cas central : un endpoint push en http, c'est une capacite d'ecriture qui
        // traverse le reseau en lisible.
        assertFalse(PairingLink.serveurAccepte("http://attaquant.fr"))
        assertFalse(PairingLink.serveurAccepte("http://opencode.lan:4096"))
        assertNull(PairingLink.fromParts("opencode", "pair", requete("http://attaquant.fr", jetonValide)))
    }

    @Test
    fun `seule la boucle locale passe en http`() {
        assertTrue(PairingLink.serveurAccepte("http://127.0.0.1:4096"))
        assertTrue(PairingLink.serveurAccepte("http://localhost:4096"))
        // Un nom qui *ressemble* a une boucle locale n'en est pas une : c'est resolvable
        // par DNS, donc joignable par le reseau.
        assertFalse(PairingLink.serveurAccepte("http://attaquant.fr.localtest.me"))
        assertFalse(PairingLink.serveurAccepte("http://127.0.0.1.attaquant.fr"))
        assertFalse(PairingLink.serveurAccepte("http://localhost.attaquant.fr"))
    }

    @Test
    fun `un prefixe piege ne passe pas`() {
        // La comparaison doit porter sur l'hote entier. `startsWith("localhost")`
        // accepterait `localhost.attaquant.fr` — exactement ce qu'on ne veut pas.
        val pieges = listOf(
            "http://127.0.0.1.attaquant.fr",
            "http://localhost.attaquant.fr",
            "http://127.0.0.1@attaquant.fr",
            "https://",
            "https:// ",
        )
        for (piege in pieges) {
            assertFalse(piege, PairingLink.serveurAccepte(piege))
        }
    }

    @Test
    fun `les autres schemas sont refuses`() {
        for (schema in listOf("http", "https", "javascript", "content", "file")) {
            assertNull(
                "schema $schema",
                PairingLink.fromParts(schema, "pair", requete("https://exemple.fr", jetonValide)),
            )
        }
    }

    @Test
    fun `un autre hote n'est pas un appairage`() {
        assertNull(PairingLink.fromParts("opencode", "session", mapOf("s" to "https://x.fr", "t" to jetonValide)))
        assertNull(PairingLink.fromParts("opencode", "pair", mapOf("autre" to "1")))
    }

    @Test
    fun `un jeton mal forme est refuse`() {
        val cas = mapOf(
            "trop court" to "abc",
            "trop long" to "a".repeat(23),
            "caractere interdit" to "a".repeat(21) + "+",
            "espace" to "a".repeat(21) + " ",
            "vide" to "",
        )
        for ((nom, jeton) in cas) {
            assertFalse(nom, PairingLink.jetonBienForme(jeton))
            assertNull(nom, PairingLink.fromParts("opencode", "pair", requete("https://exemple.fr", jeton)))
        }
    }

    @Test
    fun `un jeton ou un serveur absent est refuse`() {
        assertNull(PairingLink.fromParts("opencode", "pair", mapOf("s" to "https://exemple.fr")))
        assertNull(PairingLink.fromParts("opencode", "pair", mapOf("t" to jetonValide)))
        assertNull(PairingLink.fromParts("opencode", "pair", emptyMap()))
    }

    @Test
    fun `le jeton de reference fait bien la longueur attendue`() {
        // Le test du jeton « valide » ne vaut que si sa longueur est bien celle du
        // serveur. Un changement d'un des deux cotes casserait ce test, ce qui est voulu.
        assertEquals(PairingLink.TOKEN_LENGTH, jetonValide.length)
        assertTrue(PairingLink.jetonBienForme(jetonValide))
    }

    @Test
    fun `des parties absentes ne leve pas`() {
        assertNull(PairingLink.fromParts(null, null, emptyMap()))
        assertNull(PairingLink.fromParts(null, "pair", requete("https://exemple.fr", jetonValide)))
        assertNull(PairingLink.fromParts("opencode", null, requete("https://exemple.fr", jetonValide)))
    }

    @Test
    fun `les espaces autour des valeurs sont ignores`() {
        // Un QR recopie a la main peut avoir un espace en trop ; un espace **interne** au
        // jeton, non — c'est couvert par le test des caracteres interdits.
        val demande = PairingLink.fromParts("opencode", "pair", requete("  https://exemple.fr  ", "  $jetonValide  "))
        assertEquals("https://exemple.fr", demande?.server)
        assertEquals(jetonValide, demande?.token)
    }
}
