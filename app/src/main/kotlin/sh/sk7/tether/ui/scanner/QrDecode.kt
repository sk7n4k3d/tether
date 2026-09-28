package sh.sk7.tether.ui.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Le decodage d'un QR, **sans camera et sans Android**.
 *
 * ## Pourquoi ce fichier existe separement de l'ecran
 *
 * La camera ne se teste pas en JVM : il n'y a ni appareil, ni capteur, ni permission.
 * Le **decodage**, lui, est du calcul pur sur des octets — il n'a besoin ni de `Context`
 * ni d'ecran. En isolant cette partie, la regle la plus fragile du scanner (lire les
 * bons octets au bon endroit) devient verifiable en JVM, et le test **encode un vrai QR
 * avec ZXing puis le decode**, ce qui ferme la boucle sur une image reelle plutot que
 * sur une forme inventee.
 *
 * ## Le piege de CameraX : `rowStride`
 *
 * Le plan de luminance (`Y`) que livre `ImageAnalysis` est **rembourre** : chaque ligne
 * fait `rowStride` octets alors que l'image en fait `largeur`. Les octets de remplissage
 * ne font pas partie de l'image, et les laisser decale **toutes** les lignes suivantes —
 * le QR devient illisible alors que la camera voit parfaitement. C'est exactement le
 * genre de faute qui ne se voit qu'a l'execution, d'ou [depuisPlanY] et son test.
 */
object QrDecode {

    /**
     * Decode un QR depuis une image de luminance **compacte** : `largeur * hauteur`
     * octets, ligne par ligne, sans remplissage. `null` si aucun QR n'est lisible.
     */
    fun depuisLuminance(pixels: ByteArray, largeur: Int, hauteur: Int): String? {
        if (largeur <= 0 || hauteur <= 0) return null
        if (pixels.size < largeur * hauteur) return null

        val source = PlanarYUVLuminanceSource(pixels, largeur, hauteur, 0, 0, largeur, hauteur, false)
        val lecteur = MultiFormatReader().apply {
            setHints(
                mapOf(
                    // Un seul format est attendu : chercher les autres ne ferait que
                    // ralentir l'analyse d'un flux continu.
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        return try {
            lecteur.decode(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            // Un format inattendu ne doit pas tuer l'analyse : la frame suivante peut
            // tres bien contenir le QR.
            null
        }
    }

    /**
     * Decode un QR depuis le plan `Y` tel que CameraX le fournit, remplissage compris.
     *
     * On ne compacte que si c'est necessaire : quand `rowStride == largeur` (frequent),
     * la copie serait une allocation gratuite en moins.
     */
    fun depuisPlanY(plan: ByteArray, rowStride: Int, largeur: Int, hauteur: Int): String? {
        if (largeur <= 0 || hauteur <= 0) return null
        if (rowStride < largeur || plan.size < rowStride * hauteur) return null
        val compact = if (rowStride == largeur) plan else compacterLignes(plan, rowStride, largeur, hauteur)
        return depuisLuminance(compact, largeur, hauteur)
    }

    /**
     * Retire le remplissage de fin de ligne d'un plan de luminance.
     *
     * Fonction pure, et c'est volontaire : c'est la seule ligne de code du scanner dont
     * une erreur donne un resultat **silencieusement faux** (un decodage qui echoue sans
     * rien dire) au lieu d'une exception.
     */
    fun compacterLignes(plan: ByteArray, rowStride: Int, largeur: Int, hauteur: Int): ByteArray {
        val sortie = ByteArray(largeur * hauteur)
        for (ligne in 0 until hauteur) {
            System.arraycopy(plan, ligne * rowStride, sortie, ligne * largeur, largeur)
        }
        return sortie
    }
}
