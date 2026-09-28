package sh.sk7.tether.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Le contraste, et ce que l'app fait pour ne jamais en dependre du gout.
 *
 * ## Le probleme qu'un choix libre introduit
 *
 * Donner un choix de couleur, c'est donner la possibilite de produire un ecran illisible.
 * Un accent trop pale sur fond sombre, ou une teinte proche du fond : le texte du bouton
 * disparait. Personne ne le remarkera chez lui, et l'utilisateur qui, lui, ne lira rien.
 *
 * La reponse n'est pas d'interdire le choix, mais de **calculer** la partie du choix qui
 * ne peut pas etre laissee a l'aveugle : la luminosite. La teinte est libre, la luminosite
 * est derivee pour tenir le seuil.
 */

/** Le fond le plus clair que l'app utilise : la surface de saisie (`#1F262E`). */
val FOND_LE_PLUS_CLAIR = Color(0xFF1F262E)

/** Le fond le plus sombre, pour le texte pose dessus. */
val FOND_LE_PLUS_SOMBRE = Color(0xFF0B0E11)

/**
 * Le seuil WCAG pour un **element non textuel** (icone, bordure, bouton) : **3:1**.
 *
 * ⚠️ Ce n'est pas 4.5. Le texte normal demande 4.5 (1.4.3) ; un composant d'interface ou
 * un graphique demande 3 (1.4.11). Un accent est un element graphique : c'est 3:1 qui
 * s'applique. Utiliser 4.5 ici rendrait la moitie des teintes inutilisables sur fond
 * sombre, sans aucun gain reel.
 */
const val SEUIL_ELEMENT = 3.0

/** Le seuil WCAG pour du **texte** : 4.5:1. */
const val SEUIL_TEXTE = 4.5

/**
 * La luminance relative d'une couleur, selon WCAG 2.1.
 *
 * La formule est linearisee sur les composantes : sans cette correction, un bleu fonce
 * parait notablement plus sombre qu'il ne l'est, et le contraste calcule ment — c'est
 * l'erreur classique des implementations de contraste maison.
 */
fun luminance(c: Color): Double {
    fun composante(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * composante(c.red) + 0.7152 * composante(c.green) + 0.0722 * composante(c.blue)
}

/** Le rapport de contraste entre deux couleurs, de 1 a 21 (WCAG 2.1). */
fun contraste(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * Ajuste la luminosite d'une couleur jusqu'a ce qu'elle tienne le seuil demande.
 *
 * C est le coeur du choix de couleur : l utilisateur choisit une teinte, et la luminosite
 * est ramenee dans la plage lisible. Deux directions sont tentees — eclaircir puis
 * assombrir — parce qu'un fond sombre demande d'eclaircir, un fond clair d'assombrir.
 *
 * Si aucune direction n'atteint le seuil — ce qui est impossible avec un noir et un blanc
 * aux deux extremites, mais pas avec un fond seul — on renvoie la couleur inchangee : une
 * valeur qui echoue silencieusement serait pire qu'une valeur qu'on sait non conforme.
 */
fun ajusterPourContraste(couleur: Color, fond: Color, seuil: Double = SEUIL_ELEMENT): Color {
    if (contraste(couleur, fond) >= seuil) return couleur

    // Vers le blanc.
    var clair = couleur
    for (pas in 0..40) {
        clair = lerp(couleur, Color.White, pas / 40f)
        if (contraste(clair, fond) >= seuil) return clair
    }
    // Vers le noir.
    var sombre = couleur
    for (pas in 0..40) {
        sombre = lerp(couleur, Color.Black, pas / 40f)
        if (contraste(sombre, fond) >= seuil) return sombre
    }
    return couleur
}

/** Melange lineaire de deux couleurs, `t` dans `[0, 1]`. */
fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** Un accent accentue : la couleur posee sur un fond sombre. */
fun Color.accentue(seuil: Double = SEUIL_ELEMENT): Color =
    ajusterPourContraste(this, FOND_LE_PLUS_SOMBRE, seuil)
