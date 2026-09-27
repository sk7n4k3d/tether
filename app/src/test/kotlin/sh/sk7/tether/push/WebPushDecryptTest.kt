package sh.sk7.tether.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le dechiffrement cote app, contre le **vecteur de l'annexe A de la RFC 8291**.
 *
 * Meme vecteur que le test du plugin. C'est delibere : si les deux cotes sont ancres
 * sur la meme source, un ecart entre eux ne peut venir que de l'un des deux, jamais
 * d'un vecteur faux recopie deux fois.
 *
 * ⚠️ Ces vecteurs sont la seule source acceptable. Le projet a eu cinq bugs sur cinq
 * dus a des formes inventees.
 */
class WebPushDecryptTest {

    // --- Vecteur de la RFC 8291, annexe A (espaces de presentation supprimeses) ---

    private val plaintext = "When I grow up, I want to be a watermelon"

    private val asPublicB64 = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val uaPublicB64 = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"

    /** La cle privee du **recepteur** — annexe A. C'est celle-la qu'il faut pour dechiffrer. */
    private val uaPrivateB64 = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"

    private val authB64 = "BTBZMqHH6r4Tts7J_aSIgg"

    /** Le corps de la RFC 8291 §5. */
    private val bodyB64 =
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    init {
        // `android.util.Log` n'existe pas en JVM unitaire. On substitue un journal muet
        // plutot que d'ajouter Robolectric : meme reflexe que pour `android.util.Base64`.
        WebPushDecrypt.log = WebPushDecrypt.PushLog.Silent
    }

    private fun bytes(base64url: String): ByteArray = WebPushDecrypt.unb64u(base64url)

    /**
     * Construit une subscription a partir des cles **en clair** de la RFC.
     *
     * On recharge le scalaire dans un `PrivateKey` pour que le test exerce le vrai
     * chemin `KeyAgreement`, pas un raccourci.
     */
    private fun subscriptionFromVector(): WebPushDecrypt.SubscriptionKeys {
        val point = bytes(uaPublicB64)
        val params = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)

        // 🔴 La **vraie** cle privee du vecteur, donnee en annexe A de la RFC.
        //
        // Une premiere version de ce test en fabriquait une au hasard, en.se fiant a une
        // phrase de la RFC (« knowledge of just one of the private keys is necessary »)
        // qu'on avait lue de travers : c'est vrai pour l'**expediteur**, mais ici l'on
        // dechiffre, donc il nous faut celle du **recepteur**. Avec une cle fantaisiste,
        // l'ECDH derive, la CEK est fausse, et le dechiffrement echoue — ce qui ressemble
        // a un bug de production alors que le code etait correct.
        //
        // Verification : avec cette cle, le secret partage vaut exactement
        // `kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs`, comme la RFC.
        val privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(
            java.security.spec.ECPrivateKeySpec(java.math.BigInteger(1, bytes(uaPrivateB64)), params),
        )

        return WebPushDecrypt.SubscriptionKeys(publicKey = point, privateKey = privateKey)
    }

    // -----------------------------------------------------------------------

    @Test
    fun `la cle publique generee est un point non compresse de 65 octets`() {
        val keys = WebPushDecrypt.generateKeys()

        assertEquals(65, keys.publicKey.size, "un point P-256 non compresse")
        assertEquals(0x04.toByte(), keys.publicKey[0], "prefixe 0x04")
    }

    @Test
    fun `la cle publique encodee correspond a la cle`() {
        // Le piege Android : `publicKey.encoded` est en X.509, pas en point brut. Lire
        // les 65 premiers octets au lieu des 65 derniers produit un point FAUX, qui ne
        // correspond pas a la cle privee : l'ECDH diverge et rien ne se dechiffre.
        val generator = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val pair = generator.generateKeyPair()
        val point = WebPushDecrypt.encodeUncompressed(pair)

        assertEquals(65, point.size)
        assertEquals(0x04.toByte(), point[0])

        // La preuve : le point encode doit se recharger en une cle publique identique.
        val reloaded = WebPushDecrypt.publicKeyFromPoint(point)
        assertEquals(pair.public, reloaded, "le point encode est bien la cle de la paire")
    }

    @Test
    fun `le corps de la RFC se dechiffre et rend exactement le texte annonce`() {
        val result = WebPushDecrypt.decrypt(bytes(bodyB64), subscriptionFromVector(), bytes(authB64))

        assertNotNull(result, "le corps de la RFC doit se dechiffrer")
        assertEquals(plaintext, result, "le texte doit etre exact, delimiteur de remplissage retire")
    }

    @Test
    fun `le secret partage vaut celui de la RFC`() {
        // La preuve intermoyenne. Sans elle, un dechiffrement qui reussit par hasard
        // passerait ; avec elle, on sait que l'ECDH est correct et que c'est bien ce
        // couple de cles qui a produit le message.
        val keys = subscriptionFromVector()
        val asPublic = bytes(asPublicB64)

        val params = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val serverPublic = WebPushDecrypt.publicKeyFromPoint(asPublic)

        val agreement = javax.crypto.KeyAgreement.getInstance("ECDH").apply { init(keys.privateKey) }
        agreement.doPhase(serverPublic, true)
        val shared = agreement.generateSecret()

        assertEquals(32, shared.size)
        assertEquals("kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs", WebPushDecrypt.b64u(shared), "secret ECDH de la RFC")
    }

    @Test
    fun `le corps de la RFC fait 144 octets, et non 145 comme annonce`() {
        // Mesure : l'exemple de la RFC 8291 §5 annonce `Content-Length: 145`, mais le
        // corps qu'il donne en fait **144**. L'en-tete de 86 + 41 (texte) + 1 (delimiteur)
        // + 16 (tag) = 144. Aucun nonce n'y figure : il est derive des deux cotes.
        val body = bytes(bodyB64)

        assertEquals(144, body.size, "la longueur reelle, et non celle annoncee par la RFC")
        assertEquals(86 + plaintext.length + 1 + 16, body.size, "en-tete + texte + delimiteur + tag")
        assertEquals(65, body[20].toInt(), "le keyid fait 65 octets")
    }

    @Test
    fun `un corps de la RFC avec de mauvaise cles echoue proprement, sans exception`() {
        // Le service de push ne doit **jamais** tomber sur un message pourri.
        val other = WebPushDecrypt.generateKeys()

        val result = runCatching {
            WebPushDecrypt.decrypt(bytes(bodyB64), other, ByteArray(16) { 0x42 })
        }
        assertTrue(result.isSuccess, "une mauvaise cle doit rendre null, pas lever")
        assertNull(result.getOrNull())
    }

    @Test
    fun `un corps trop court rend null`() {
        val keys = WebPushDecrypt.generateKeys()
        assertNull(WebPushDecrypt.decrypt(ByteArray(10), keys, ByteArray(16)))
    }

    @Test
    fun `un secret d'authentification de mauvaise longueur rend null`() {
        // 16 octets, RFC 8291 §3.2. Un secret plus court weaken la construction.
        val keys = WebPushDecrypt.generateKeys()
        assertNull(WebPushDecrypt.decrypt(bytes(bodyB64), keys, ByteArray(8)))
    }

    @Test
    fun `un keyid qui n'est pas un point non compresse rend null`() {
        val keys = WebPushDecrypt.generateKeys()
        val body = bytes(bodyB64).copyOf()
        body[20] = 64 // idlen au lieu de 65
        assertNull(WebPushDecrypt.decrypt(body, keys, bytes(authB64)))
    }

    @Test
    fun `un prefixe de keyid incorrect rend null`() {
        val keys = WebPushDecrypt.generateKeys()
        val body = bytes(bodyB64).copyOf()
        body[21] = 0x02 // 0x02 au lieu de 0x04 : ce n'est pas un point compresse
        assertNull(WebPushDecrypt.decrypt(body, keys, bytes(authB64)))
    }

    @Test
    fun `un tag AEAD invalide rend null`() {
        // Un octet change dans le tag : GCM doit refuser. C'est ce qui protege contre
        // un distributeur qui altere un message.
        val keys = WebPushDecrypt.generateKeys()
        val body = bytes(bodyB64).copyOf()
        body[body.size - 1] = (body[body.size - 1] + 1).toByte()

        assertNull(WebPushDecrypt.decrypt(body, keys, bytes(authB64)))
    }

    @Test
    fun `un corps duplique ne rend pas le texte deux fois`() {
        // Garde-fou : un `keyid` plus long que 65 doit etre refuse, sinon on lirait un
        // enregistrement dans le mauvais decalage et le tag ne correspondrait plus.
        val keys = WebPushDecrypt.generateKeys()
        val body = bytes(bodyB64).copyOf()
        body[20] = 0x7F

        assertNull(WebPushDecrypt.decrypt(body, keys, bytes(authB64)))
    }

    @Test
    fun `base64url et son inverse sont coherents`() {
        val original = ByteArray(32) { (it * 7).toByte() }
        val round = WebPushDecrypt.unb64u(WebPushDecrypt.b64u(original))

        assertTrue(original.contentEquals(round), "aller-retour base64url")
    }

    @Test
    fun `le point encode par b64u fait 65 octets apres decodage`() {
        val keys = WebPushDecrypt.generateKeys()
        val decoded = WebPushDecrypt.unb64u(WebPushDecrypt.b64u(keys.publicKey))

        assertEquals(65, decoded.size)
        assertTrue(decoded.contentEquals(keys.publicKey))
    }

    @Test
    fun `un scalaire prive fait 32 octets`() {
        val keys = WebPushDecrypt.generateKeys()
        val scalar = WebPushDecrypt.encodePrivate(keys.privateKey)

        assertEquals(32, scalar.size, "le scalaire d'une cle P-256 fait 32 octets")
    }
}
