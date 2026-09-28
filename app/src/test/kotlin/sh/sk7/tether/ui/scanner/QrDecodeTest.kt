package sh.sk7.tether.ui.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import sh.sk7.tether.push.PairingLink

/**
 * Le decodage du QR, **sur une vraie image**.
 *
 * Le test **encode** un QR avec ZXing puis le **decode** : la boucle est fermee sur une
 * image reelle, pas sur une forme inventee. C'est la lecon du projet — un test qui encode
 * une forme supposee valide le bug qu'il devrait attraper.
 *
 * Ce qui n'est **pas** teste ici, et ne peut pas l'etre en JVM : la camera. L'ouverture du
 * capteur, la permission et l'apercu se verifient sur l'appareil.
 */
class QrDecodeTest {

    private val lien =
        "opencode://pair?s=https%3A%2F%2Fopencode.exemple.fr&t=DJANf6aeJ-eTf2JL63buPw"

    /**
     * Rend un QR en luminance, avec un remplissage de fin de ligne optionnel.
     *
     * ⚠️ C'est exactement ce que fait CameraX : `rowStride` peut depasser la largeur, et
     * les octets de remplissage ne font pas partie de l'image. Les laisser en place
     * decale toutes les lignes suivantes — le cas que [QrDecode.compacterLignes] existe
     * pour traiter, et que ce test doit voir echouer si on le retire.
     */
    private fun planY(contenu: String, cote: Int = 360, remplissage: Int = 0): Pair<ByteArray, Int> {
        val matrix = QRCodeWriter().encode(
            contenu,
            BarcodeFormat.QR_CODE,
            cote,
            cote,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val largeur = matrix.width
        val hauteur = matrix.height
        val rowStride = largeur + remplissage
        val pixels = ByteArray(rowStride * hauteur) { 0x80.toByte() } // gris neutre dans le remplissage
        for (y in 0 until hauteur) {
            for (x in 0 until largeur) {
                // ZXing : `true` = noir. En luminance, noir = 0, blanc = 255.
                pixels[y * rowStride + x] = if (matrix.get(x, y)) 0 else 0xFF.toByte()
            }
        }
        return pixels to rowStride
    }

    @Test
    fun `un QR d appairage se decode`() {
        val (pixels, rowStride) = planY(lien)
        assertEquals(lien, QrDecode.depuisPlanY(pixels, rowStride, rowStride, pixels.size / rowStride))
    }

    @Test
    fun `le remplissage de fin de ligne est retire avant le decodage`() {
        // Sans la compaction, l'image est decalee et le QR devient illisible. C'est la
        // panne la plus probable du scanner, et elle est silencieuse.
        val (pixels, rowStride) = planY(lien, remplissage = 24)
        val largeur = rowStride - 24
        val hauteur = pixels.size / rowStride
        assertEquals(lien, QrDecode.depuisPlanY(pixels, rowStride, largeur, hauteur))
    }

    @Test
    fun `compacterLignes enleve exactement le remplissage`() {
        // Plan de 2 lignes de 3 octets utiles, 2 octets de remplissage : [1 2 3 _ _ 4 5 6 _ _]
        val plan = byteArrayOf(1, 2, 3, 9, 9, 4, 5, 6, 9, 9)
        val attendu = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(attendu.toList(), QrDecode.compacterLignes(plan, rowStride = 5, largeur = 3, hauteur = 2).toList())
    }

    @Test
    fun `un plan trop court est refuse plutot que lu de travers`() {
        // Une lecture partielle produirait une image fausse : mieux vaut ne rien decoder.
        assertNull(QrDecode.depuisPlanY(ByteArray(10), rowStride = 8, largeur = 8, hauteur = 4))
        assertNull(QrDecode.depuisPlanY(ByteArray(100), rowStride = 4, largeur = 8, hauteur = 4))
    }

    @Test
    fun `une image sans QR ne rend rien`() {
        // Une frame blanche : rien a lire, et surtout pas d'exception qui tuerait l'analyse.
        val blanc = ByteArray(200 * 200) { 0xFF.toByte() }
        assertNull(QrDecode.depuisLuminance(blanc, 200, 200))
    }

    @Test
    fun `ce que le scanner decode est accepte par l analyse d appairage`() {
        // La jointure entre les deux modules : le scanner rend un texte, `PairingLink`
        // decide. Un QR d'appairage vrai doit passer de l'un a l'autre.
        val (pixels, rowStride) = planY(lien)
        val decode = QrDecode.depuisPlanY(pixels, rowStride, rowStride, pixels.size / rowStride)
        val demande = decode?.let { PairingLink.depuisTexte(it) }
        assertEquals("https://opencode.exemple.fr", demande?.server)
        assertEquals("DJANf6aeJ-eTf2JL63buPw", demande?.token)
    }

    @Test
    fun `un QR d un autre format est decode mais refuse par l appairage`() {
        // Le scanner decode n'importe quel QR — c'est le role de `PairingLink` de refuser.
        // On l'exerce sur le QR de credentials, qui est le cas reel avec lequel on peut
        // confondre les deux bouts de papier.
        val credentials = """{"urls":["https://exemple.fr"],"username":"opencode","password":"secret"}"""
        val (pixels, rowStride) = planY(credentials)
        val decode = QrDecode.depuisPlanY(pixels, rowStride, rowStride, pixels.size / rowStride)
        assertEquals(credentials, decode)
        assertNull(PairingLink.depuisTexte(decode!!))
    }
}
