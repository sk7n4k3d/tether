package sh.sk7.tether.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * Les accents que l'utilisateur peut choisir, et **comment** les qualiifier.
 *
 * ## Pourquoi la teinte n'est pas le choix
 *
 * Choisir une couleur libre — un sélecteur HSL, un champ « #rrggbb » — donnerait des
 * valeurs qu'on ne peut pas evaluer. Un accent clair sur fond clair rend les boutons
 * illisibles ; un accent proche du fond disparaît. Il n'y a pas d'erreur visible tant que
 * l'utilisateur ne l'a pas choisie.
 *
 * Donc le choix porte sur une **teinte**, et la **luminosité** est calculée pour tenir le
 * contraste, quelle que soit la teinte. C'est la seule façon de donner un choix libre
 * qui ne produit jamais un écran illisible.
 *
 * ## Les valeurs de depart sont mesurees, pas hueurs
 *
 * Chaque teinte est verifiee a l'aide contre le fond le plus clair que l'app utilise
 * (`#1F262E`, la surface de saisie), pour un element d'interface : **3:1**, le seuil WCAG
 * 1.4.11. Le detail est dans `AccentContrastTest`, qui echoue si une valeur passe sous.
 */
enum class Accent(val cle: String, val teinte: Color, val libelle: String) {
    /** Le vert d'origine. Surprenant : c'est la couleur la plus lue par le monde. */
    Sarcelle("sarcelle", Color(0xFF2DD4BF), "Sarcelle"),

    /** Bleu : la couleur la plus attendue pour un outil technique. */
    Azur("azur", Color(0xFF60A5FA), "Bleu"),

    /** Violet, pour ceux qui sortent du theme par defaut. */
    Amethyste("amethyste", Color(0xFFA78BFA), "Violet"),

    /** Orange : chaud, et le plus lisible sur fond tres sombre. */
    Ambre("ambre", Color(0xFFFB923C), "Orange"),

    /** Rose. */
    Magenta("magenta", Color(0xFFF472B6), "Rose"),

    /** Blanc casse : la seule qui fonctionne en mode clair, quand il y en aura un. */
    Craie("craie", Color(0xFFD4D4D8), Res.of(R.string.gris_clair_62e9ce)),
    ;

    companion object {
        val parDefaut = Sarcelle

        /** L'accent inconnu retombe sur le defaut — une cle de reglages ne doit rien casser. */
        fun depuisCle(cle: String?): Accent =
            entries.firstOrNull { it.cle == cle } ?: parDefaut
    }
}
