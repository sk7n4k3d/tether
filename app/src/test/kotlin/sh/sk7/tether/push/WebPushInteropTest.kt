package sh.sk7.tether.push

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **Interoperabilite** : un corps chiffre par le **plugin** se dechiffre ici.
 *
 * ## Pourquoi ce test existe, et pourquoi il n'est pas comme les autres
 *
 * Les deux cotes implementent la meme RFC 8291, mais **en des langages differents** et
 * **avec des bibliotheques de crypto differentes** : `node:crypto` d'un cote, le JDK et
 * `javax.crypto` de l'autre. Deux implementations d'un meme standard peuvent diverger sur
 * un detail — l'ordre de deux octets, une normalisation de courbe, une constante de
 * derivation — et tous les tests unitaires des deux cotes resteraient verts.
 *
 * Ce test referme la boucle. Le fixture est **produit par le code du plugin**, execute,
 * puis dechiffre par le code de l'app :
 *
 * ```
 * plugin/tether/webpush.ts  --chiffre-->  app/src/test/resources/webpush-interop.json
 *                                                            |
 *                                       WebPushDecrypt.kt <--dechiffre
 * ```
 *
 * ⚠️ Un fixture ecrit a la main ne prouverait rien : ce serait deux jeux de donnees
 * separes, dont on ne sait pas s'ils viennent de la meme formule. Ici, **la meme fonction
 * qui chiffre en production a produit le fichier teste**.
 *
 * ## Et la cle privee du « telephone », dans le fixture
 *
 * Elle y est parce qu'il n'y a pas le choix : sans le scalaire, l'app ne peut pas
 * dechiffrer, et aucun test d'interoperabilite n'existerait. Ce sont les cles de l'annexe
 * A de la RFC 8291, publiques depuis 2017. La version d'un appareil reel, elle, ne
 * quitte **jamais** le telephone : c'est le principe meme du Web Push.
 */
class WebPushInteropTest {

    @Serializable
    private data class InteropCase(
        val body: String,
        val text: String,
        val sessionID: String? = null,
        val progress: Boolean = false,
    )

    @Serializable
    private data class Interop(
        val p256dh: String,
        val privateKey: String,
        val auth: String,
        val cas: List<InteropCase>,
    )

    @BeforeTest
    fun silenceTheLog() {
        // `android.util.Log` n'existe pas en JVM unitaire. Meme reflexe que pour
        // `android.util.Base64` : on substitue plutot que d'ajouter Robolectric.
        WebPushDecrypt.log = WebPushDecrypt.PushLog.Silent
    }

    private fun fixture(): Interop {
        val raw = checkNotNull(javaClass.classLoader?.getResource("webpush-interop.json")) { "fixture absent" }
            .readText()

        // Les cles `_comment`, `_cles` et `_pourquoi_la_cle_privee` documentent la
        // provenance du fichier. Elles sont **conservees** — c'est ce qui dit d'ou il
        // vient et pourquoi il contient une cle privee — et simplement ignorees ici.
        val data = Json.parseToJsonElement(raw) as JsonObject
        val clean = JsonObject(data.filterKeys { !it.startsWith("_") })

        return Json.decodeFromJsonElement(Interop.serializer(), clean)
    }

    /** La subscription du « telephone ». */
    private fun keysFrom(data: Interop): WebPushDecrypt.SubscriptionKeys {
        val point = WebPushDecrypt.unb64u(data.p256dh)
        assertEquals(65, point.size, "un point P-256 non compresse fait 65 octets")
        assertEquals(0x04.toByte(), point[0], "prefixe 0x04")

        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)

        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            ECPrivateKeySpec(BigInteger(1, WebPushDecrypt.unb64u(data.privateKey)), params),
        )

        return WebPushDecrypt.SubscriptionKeys(publicKey = point, privateKey = privateKey)
    }

    private fun decode(text: String) = Json.parseToJsonElement(text) as JsonObject

    /** Le contenu texte d'un champ du JSON. */
    private fun JsonObject.string(key: String): String =
        (this[key] as kotlinx.serialization.json.JsonPrimitive).content

    // -----------------------------------------------------------------------

    @Test
    fun `le fixture existe et contient des cas`() {
        val data = fixture()

        assertTrue(data.cas.isNotEmpty(), "le fixture doit contenir au moins un cas")
        assertEquals(16, WebPushDecrypt.unb64u(data.auth).size, "le secret fait 16 octets")
    }

    @Test
    fun `un corps produit par le plugin se dechiffre, et donne la charge utile`() {
        val data = fixture()
        val keys = keysFrom(data)
        val auth = WebPushDecrypt.unb64u(data.auth)

        for (cas in data.cas) {
            val result = WebPushDecrypt.decrypt(WebPushDecrypt.unb64u(cas.body), keys, auth)

            assertNotNull(result, "le cas « ${cas.text.take(30)} » doit se dechiffrer")

            // Le plugin encode la charge utile **structuree** (protocol.ts) : JSON versionne,
            // pas le texte nu. Premier essai de ce test : on comparait le texte brut, et
            // l'echec ressemblait a une incompatibilite de chiffrement alors que les deux
            // cotes etaient d'accord.
            val payload = decode(result)

            assertEquals(cas.text, payload.string("text"), "le texte doit etre identique")
            assertEquals("1", payload.string("v"), "version du format")
            if (cas.sessionID != null) {
                assertEquals(cas.sessionID, payload.string("sessionID"))
            }
        }
    }

    @Test
    fun `le dechiffrement ne perd ni accents ni sauts de ligne`() {
        // Le troisieme cas contient un texte qui **ressemble** a un marqueur de l'ancien
        // format (`tether:progress=1` sur sa propre ligne). C'est le cas qui a fait
        // basculer le protocole vers du JSON structure : en texte brut, aucun parseur ne
        // pouvait dire si c'etait un marqueur ou du contenu.
        val data = fixture()
        val keys = keysFrom(data)

        val multiline = data.cas.last()
        val result = WebPushDecrypt.decrypt(
            WebPushDecrypt.unb64u(multiline.body),
            keys,
            WebPushDecrypt.unb64u(data.auth),
        )

        assertNotNull(result, "le cas multiligne doit se dechiffrer")
        val text = decode(result).string("text")

        assertEquals(multiline.text, text, "le saut de ligne et le contenu sont intacts")
        assertTrue(text.contains("\n"), "le vrai saut de ligne survit, pas la sequence \\n")
        assertTrue(text.contains("tether:progress=1"), "le faux marqueur est bien du texte")
    }

    @Test
    fun `le drapeau d'avancement est transmis, pas deduit`() {
        val data = fixture()
        val keys = keysFrom(data)
        val auth = WebPushDecrypt.unb64u(data.auth)

        for (cas in data.cas) {
            val result = WebPushDecrypt.decrypt(WebPushDecrypt.unb64u(cas.body), keys, auth)
            val payload = decode(result!!)
            // Le plugin **omet** le champ quand il est faux : un JSON de notification
            // reste court, et surtout un champ absent ne peut pas etre confondu avec un
            // champ a `false` par un lecteur qui confondrait les deux.
            val flag = (payload["progress"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false

            assertEquals(cas.progress, flag, "le drapeau « avancement » doit venir du serveur, pas etre devine")
            if (!cas.progress) {
                assertTrue("progress" !in payload, "un drapeau faux doit etre absent, pas present a false")
            }
        }
    }
}
