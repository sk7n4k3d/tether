package sh.sk7.tether.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// ---------------------------------------------------------------------------
// Le chemin reellement emprunte : la query **brute**, telle qu'elle arrive d'un
// `android.net.Uri`. C'est la que se joue le percent-encoding, et donc le seul
// endroit ou une erreur pourrait passer inapercue — une adresse mal decodee
// donne soit un refus visible, soit un serveur bricole et invisible.
// ---------------------------------------------------------------------------

class PairingLinkQueryTest {

    private val jeton = "8z5U-NzHdByXsKPjyIZU1g"

    @Test
    fun `une query encodee comme un encodeur de lien donne le bon serveur`() {
        // C'est exactement la forme que produit le TUI : le `:` et le `/` d'une URL
        // sont percent-encodes dans la query.
        val demande = PairingLink.depuisUri(
            "opencode",
            "pair",
            "s=https%3A%2F%2Fexemple.fr%3A4096&t=$jeton",
        )
        assertEquals("https://exemple.fr:4096", demande?.server)
        assertEquals(jeton, demande?.token)
    }

    @Test
    fun `l ordre des parametres n importe pas`() {
        val demande = PairingLink.depuisUri("opencode", "pair", "t=$jeton&s=https%3A%2F%2Fexemple.fr")
        assertEquals("https://exemple.fr", demande?.server)
    }

    @Test
    fun `une query sans percent-encoding est acceptee`() {
        // Un encodeur maladroit laisse ':' et '/' en clair. C'est legal, et refuser
        // ferait echouer un QR legitime.
        val demande = PairingLink.depuisUri("opencode", "pair", "s=https://exemple.fr&t=$jeton")
        assertEquals("https://exemple.fr", demande?.server)
    }

    @Test
    fun `le plus n est pas un espace`() {
        // ⚠️ `java.net.URLDecoder` (semantique formulaire) donnerait "a b" ici et
        // changerait l'adresse. En query URI, `+` est litteral.
        //
        // Le `+` est dans le **chemin** : c'est la que le decodeur est en jeu, et
        // l'hote reste analysable (les hotes exotiques sont refuses, voir plus bas).
        val demande = PairingLink.depuisUri("opencode", "pair", "s=https%3A%2F%2Fexemple.fr%2Fa+b&t=$jeton")
        assertEquals("https://exemple.fr/a+b", demande?.server)
    }

    @Test
    fun `un echappement mal forme refuse le lien entier`() {
        // Plutot que d'ignorer le parametre, ce qui laisserait passer une adresse
        // a moitie decodee — que personne n'a lue.
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=https%3A%2F%2Fexemple.fr&t=%zz"))
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=%2&t=$jeton"))
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=abc%&t=$jeton"))
    }

    @Test
    fun `un hexa non numerique est refuse`() {
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=https%3A%2F%2Fexemple.fr&t=%GG"))
    }

    @Test
    fun `une query vide ou absente ne donne aucune demande`() {
        assertNull(PairingLink.depuisUri("opencode", "pair", null))
        assertNull(PairingLink.depuisUri("opencode", "pair", ""))
    }

    @Test
    fun `un parametre sans valeur est ignore`() {
        // `?debug&s=…` est une query valide : on ignore le drapeau, on garde la demande.
        val demande = PairingLink.depuisUri("opencode", "pair", "debug&s=https%3A%2F%2Fexemple.fr&t=$jeton")
        assertEquals("https://exemple.fr", demande?.server)
    }

    @Test
    fun `un parametre en double ne prend que le premier`() {
        // Deux `s=` dans une query sont anormaux. Prendre le premier evite qu'un
        // encodeur maladroit en ajoute un second qui decale la destination.
        val demande = PairingLink.depuisUri(
            "opencode",
            "pair",
            "s=https%3A%2F%2Fexemple.fr&s=https%3A%2F%2Fattaquant.fr&t=$jeton",
        )
        assertEquals("https://exemple.fr", demande?.server)
    }

    @Test
    fun `une cle vide est ignoree`() {
        val demande = PairingLink.depuisUri("opencode", "pair", "=1&s=https%3A%2F%2Fexemple.fr&t=$jeton")
        assertEquals("https://exemple.fr", demande?.server)
    }

    @Test
    fun `un accent non encode survit`() {
        // Un encodeur qui encode tout sauf les non-ASCII laisse des octets UTF-8.
        val demande = PairingLink.depuisUri("opencode", "pair", "s=https%3A%2F%2Fexemple.fr%2Fcaf%C3%A9&t=$jeton")
        assertEquals("https://exemple.fr/café", demande?.server)
    }

    /**
     * Un hote que la JVM ne sait pas lire est refuse.
     *
     * ⚠️ Le decodage est correct — c'est `java.net.URI` qui refuse ensuite un hote non
     * ASCII (`café.fr` est du punycode en clair) ou contenant un `+`, qui n'est pas un
     * caractere d'hote valide. On **eleve ce refus en decision** plutot que de le subir :
     * une adresse que la JVM ne sait pas decrire est une adresse qu'on n'affichera pas
     * correctement a l'utilisateur pour verification — or c'est justement l'affichage
     * qui fait la securite de cet ecran.
     */
    @Test
    fun `un hote non ASCII ou ponctue est refuse`() {
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=https%3A%2F%2Fcaf%C3%A9.fr&t=$jeton"))
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=https%3A%2F%2Fa+b.fr&t=$jeton"))
    }

    @Test
    fun `un serveur encode en piege est toujours refuse`() {
        // Le decodage ne doit pas pouvoir servir a contourner la validation.
        assertNull(PairingLink.depuisUri("opencode", "pair", "s=http%3A%2F%2Fattaquant.fr&t=$jeton"))
        assertNull(
            PairingLink.depuisUri("opencode", "pair", "s=http%3A%2F%2Flocalhost.attaquant.fr&t=$jeton"),
        )
    }
}
