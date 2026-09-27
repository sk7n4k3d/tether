package sh.sk7.tether.push

import android.util.Log
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Le dechiffrement d'un message Web Push — **RFC 8291**, cote recepteur.
 *
 * ## Symetrie avec le serveur
 *
 * Le serveur chiffre, l'app dechiffre. C'est exactement l'inverse de le plugin serveur :
 * meme ECDH P-256, meme HKDF, meme AES-128-GCM. La seule asymetrie est **qui** fournit
 * la cle publique de l'autre, et c'est la que le role change :
 *
 * | etape | emetteur (serveur) | recepteur (app) |
 * |---|---|---|
 * | sel | tire au hasard | lu dans l'en-tete du corps |
 * | cle publique de l'autre | `ua_public` (envoye a l'app) | `as_public`, lu dans le `keyid` |
 * | nonce | derive, **non transmis** | derive, **non transmis** |
 *
 * 🔴 **Le nonce n'est pas dans le corps.** Mesure sur le vecteur de l'annexe A de la RFC
 * 8291 : le corps fait **144** octets, alors que la RFC annonce `Content-Length: 145` —
 * l'exemple de la RFC est faux d'un octet. Les 58 octets apres l'en-tete de 86 valent
 * exactement `41 (texte) + 1 (delimiteur 0x02) + 16 (tag AEAD)`. Aucun nonce dedans.
 *
 * Il est **rederive** des deux cotes : le recepteur connait le sel (en-tete) et sait
 * recalculer le `keyid`, donc il refait la derivation. Un nonce dans le corps donnerait
 * 156 octets, que le recepteur echouerait a dechiffrer — **sans emettre d'erreur**.
 *
 * ## Zero dependance
 *
 * `KeyAgreement`, `Mac` et `Cipher` sont dans le JDK. C'est le meme argument que pour
 * le serveur : ajouter une bibliotheque de crypto serait la premiere dependance du
 * projet, donc il n'y en a pas.
 */
object WebPushDecrypt {

    private const val TAG = "TetherPush"

    /**
     * Le journal, isole derriere une interface.
     *
     * ⚠️ `android.util.Log` **n'existe pas** en JVM unitaire : l'appeler leve
     * `RuntimeException: Method d in android.util.Log not mocked`. Un simple `Log.d` dans
     * un chemin d'erreur rend donc **toute la classe intestable** — c'est arrive, et sept
     * tests ont casse d'un coup.
     *
     * Meme raison que [b64u] : c'est le SDK Android qui manque, pas la logique. On
     * isole donc le journal plutot que d'ajouter Robolectric, qui serait une
     * dependance de plus pour un besoin de rien.
     */
    fun interface PushLog {
        fun debug(message: String)

        companion object {
            /** Le journal reel, en production. */
            val Android = PushLog { message -> Log.d(TAG, message) }

            /** Le journal du test : rien, et surtout pas d'exception. */
            val Silent = PushLog { }
        }
    }

    /** Remplacable par un test. Par defaut, [PushLog.Android]. */
    @Volatile
    var log: PushLog = PushLog.Android

    /** En-tete `aes128gcm` d'un seul record : `salt(16) | rs(4) | idlen(1) | keyid(65)`. */
    const val HEADER_BYTES = 86

    /** Delimiteur de remplissage. Toute autre valeur doit faire rejeter le message. */
    private const val PADDING_DELIMITER = 0x02

    /** La cle ECDH de l'app, generee une fois et conservee sur l'appareil. */
    data class SubscriptionKeys(
        /** P-256, forme non compressee (65 octets), base64url — c'est le `p256dh`. */
        val publicKey: ByteArray,
        /** Le scalaire. Ne quitte **jamais** l'appareil. */
        val privateKey: PrivateKey,
    ) {
        // `data class` avec un `PrivateKey` : l'egalite par structure n'a pas de sens
        // ici, et la comparaison de cles n'est jamais un besoin. On l'identite.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Genere une paire P-256 pour cet appareil.
     *
     * La cle est **par installation** : la regeneree si l'app est reinstallee, ce qui
     * est correct — l'ancien endpoint devient alors inutile, et le distributeur le
     *Signale par un 4xx (regle de desabonnement, §2.1 brevet ③).
     */
    fun generateKeys(): SubscriptionKeys {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair: KeyPair = generator.generateKeyPair()
        return SubscriptionKeys(publicKey = encodeUncompressed(pair), privateKey = pair.private)
    }

    /**
     * Le point non compresse (65 octets, `0x04 || X || Y`) d'une cle publique.
     *
     * ⚠️ `publicKey.encoded` sur Android donne le format **X.509** (SubjectPublicKeyInfo),
     * pas le point brut — les 65 derniers octets. Lire les 65 premiers octet comme
     * certains exemples le font produit un point **faux**, qui ne correspond pas a la
     * cle privee : l'ECDH donne un secret partage different, et le message ne se
     * dechiffre pas.
     */
    fun encodeUncompressed(pair: KeyPair): ByteArray {
        val x509 = pair.public.encoded
        val point = x509.copyOfRange(x509.size - 65, x509.size)
        require(point[0] == 0x04.toByte()) { "point non compresse attendu (prefixe 0x04)" }
        return point
    }

    /** Le scalaire prive, depuis un `PrivateKey` EC deja charge. */
    fun encodePrivate(privateKey: PrivateKey): ByteArray {
        val pkcs8 = privateKey.encoded
        // PKCS#8 d'une cle EC : le scalaire est le dernier bloc de l'"ECPrivateKey".
        // 32 octets pour P-256, precedes de la racine publique.
        return pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
    }

    /**
     * Dechiffre un corps de push, en expliquant un echec.
     *
     * Variante **interne**, exposee pour les tests : elle rend la cause plutot qu'un
     * `null`, ce qui est la seule facon de diagnostiquer un desaccord entre le serveur et
     * l'app. Un message qui ne se dechiffre pas est *normal* en production (mauvaise
     * cle, tag abime, message d'un autre abonnement) — c'est justement pour ca que
     * [decrypt] renvoie `null` sans rien lever, et que ce chemin-ci n'existe que pour
     * une panne reelle d'interoperabilite.
     */
    internal fun decryptOrExplain(body: ByteArray, keys: SubscriptionKeys, authSecret: ByteArray): Result<String> {
        if (body.size < HEADER_BYTES + 16 + 1) return Result.failure(IllegalArgumentException("corps trop court : ${body.size} octets"))
        if (authSecret.size != 16) return Result.failure(IllegalArgumentException("secret d'authentification : ${authSecret.size} octets, 16 attendu"))

        val keyIdLength = body[20].toInt()
        if (keyIdLength != 65) return Result.failure(IllegalArgumentException("keyid : $keyIdLength octets, 65 attendu"))
        val asPublic = body.copyOfRange(21, 21 + keyIdLength)
        if (asPublic[0] != 0x04.toByte()) return Result.failure(IllegalArgumentException("keyid sans prefixe 0x04"))

        val salt = body.copyOfRange(0, 16)
        val record = body.copyOfRange(HEADER_BYTES, body.size)
        if (record.size < 17) return Result.failure(IllegalArgumentException("record trop court : ${record.size} octets"))
        val ciphertext = record.copyOfRange(0, record.size - 16)
        val tag = record.copyOfRange(record.size - 16, record.size)

        val (cek, nonce) = deriveKeys(keys, authSecret, asPublic, salt)

        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(ByteArray(0))
            val plain = cipher.doFinal(ciphertext + tag)

            // Le delimiteur est le dernier octet. Sa valeur **doit** etre 0x02 : autre
            // chose, le message est rejete plutot que tronque (RFC 8291 §4).
            val delimiter = plain[plain.size - 1].toInt()
            if (delimiter != PADDING_DELIMITER) {
                throw IllegalStateException("delimiteur 0x%02x, 0x%02x attendu".format(delimiter, PADDING_DELIMITER))
            }
            String(plain, 0, plain.size - 1, Charsets.UTF_8)
        }
    }

    /**
     * Dechiffre un corps de push.
     *
     * @return le texte, delimiteur de remplissage retire. **`null` si le message est
     *   rejete** — mauvais tag, mauvais delimiteur, mauvaise taille. Jamais d'exception :
     *   un message corrompu ne doit pas faire tomber le service de push.
     */
    fun decrypt(body: ByteArray, keys: SubscriptionKeys, authSecret: ByteArray): String? =
        decryptOrExplain(body, keys, authSecret).fold(
            onSuccess = { it },
            onFailure = { error ->
                // Un message qui ne se dechiffre pas est **normal** : mauvaise cle, tag
                // abime, message d'un autre abonnement. Ce n'est pas une anomalie, et
                // remonter une exception ferait tomber le service de push.
                //
                // Le journal est donc volontairement terse, et n'est jamais coupe par
                // defaut : on ne veut pas remplir logcat avec le bruit d'un distributeur
                // mal configure. Il ne contient **jamais** de cle ni de contenu.
                log.debug("push non dechiffrable : ${error.javaClass.simpleName} (${error.message?.take(80)})")
                null
            },
        )

    /**
     * Derive la cle de contenu et le nonce — le meme calcul que le serveur, avec les
     * roles inverses.
     */
    private fun deriveKeys(
        keys: SubscriptionKeys,
        authSecret: ByteArray,
        asPublic: ByteArray,
        salt: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val params = ellipticCurveParameters()

        val keyFactory = KeyFactory.getInstance("EC")
        val serverPublic = keyFactory.generatePublic(ECPublicKeySpec(pointToECPoint(asPublic), params))

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keys.privateKey)
        agreement.doPhase(serverPublic, true)
        val sharedSecret = agreement.generateSecret()

        val keyInfo = "WebPush: info".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) + keys.publicKey + asPublic

        // RFC 8291 §3.3 : le secret d'authentification est le SALT de l'extraction.
        val ikm = hkdf(authSecret, sharedSecret, keyInfo, 32)

        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00), 12)

        return cek to nonce
    }

    /** HKDF (RFC 5869) avec SHA-256, extract puis expand. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(ikm)

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1

        while (offset < length) {
            previous = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
                .doFinal(previous + info + counter.toByte())
            val take = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, take)
            offset += take
            counter += 1
        }
        return output
    }

    /** Les parametres de la courbe P-256. */
    private fun ellipticCurveParameters(): ECParameterSpec {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        return parameters.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Un point non compresse (65 octets) en [ECPoint].
     *
     * ⚠️ [ECPoint] attend des [BigInteger], pas des octets : passer un `ByteArray`
     * directement ne compile pas. Et surtout, l'interpretation doit etre **big-endian**
     * et non signee — un `BigInteger(1, octets)` se lirait a l'envers, ce qui donnerait
     * un point faux, donc un secret partage faux, donc un dechiffrement qui echoue
     * sans raison apparente.
     */
    private fun pointToECPoint(uncompressed: ByteArray): ECPoint {
        require(uncompressed.size == 65 && uncompressed[0] == 0x04.toByte()) {
            "point P-256 non compresse attendu (65 octets, prefixe 0x04)"
        }
        return ECPoint(
            java.math.BigInteger(1, uncompressed.copyOfRange(1, 33)),
            java.math.BigInteger(1, uncompressed.copyOfRange(33, 65)),
        )
    }

    /**
     * base64url, la forme qu'UnifiedPush et le serveur utilisent.
     *
     * ⚠️ `java.util.Base64` et **non** `android.util.Base64` : c'est ce qui rend cette
     * classe testable **sans Robolectric**, donc sans dependance ajoutee. Le SDK Android
     * n'existe pas en JVM unitaire, et `android.util.Base64` y renvoie `null` — ce qui
     * faisait echouer les 11 tests sur une `RuntimeException`, tres loin du dechiffrement.
     * Convention deja posee par `PromptAttachmentsTest`.
     */
    fun b64u(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * L'inverse de [b64u]. Accepte aussi le base64 **standard**, que certains langages
     * envoient : on tente l'URL-safe d'abord, puis le standard.
     */
    fun unb64u(text: String): ByteArray {
        val compact = text.trim()
        return runCatching { java.util.Base64.getUrlDecoder().decode(compact) }
            .recoverCatching { java.util.Base64.getDecoder().decode(compact) }
            .getOrElse { ByteArray(0) }
    }

    /**
     * Charge une cle publique EC depuis son point non compresse.
     *
     * Expose pour les tests, qui ont besoin de fabriquer une subscription a partir d'un
     * point. Dupliquer la conversion dans un test, c'est dupliquer le lieu d'un bug : le
     * meme code doit etre verifie des deux cotes.
     */
    fun publicKeyFromPoint(uncompressed: ByteArray): java.security.PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ECPublicKeySpec(pointToECPoint(uncompressed), ellipticCurveParameters()))
    }
}
