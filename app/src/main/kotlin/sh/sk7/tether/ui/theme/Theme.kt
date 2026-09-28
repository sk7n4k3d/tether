package sh.sk7.tether.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * L'accent **choisi**, accessible par le `CompositionLocal`.
 *
 * ## Pourquoi un `CompositionLocal` et pas une variable globale
 *
 * 137 usages de `TetherAccent` dans 26 fichiers. Les remplacer un par un serait une
 * faute a 137 endroits, et le suivant en ajouterait un 138. Un `var` global serait
 * pire : il survivrait au changement de configuration, et une preview compose affiche le
 * theme d'un autre.
 *
 * Le `CompositionLocal` est le canal de Compose pour « une valeur qui depend de l'arbre
 * » : il change avec le theme, il meurt avec lui, et les lectures sont gratuites.
 */
val LocalAccent = staticCompositionLocalOf { Accent.parDefaut.teinte.accentue() }

/**
 * Le scheme Material, construit autour de l'accent choisi.
 *
 * La couleur est passee par [accent] et non lue globalement : c'est ce qui permet a une
 * preview — ou a un futur mode clair — de construire un theme different sans toucher au
 * reste de l'app.
 */
@Composable
fun TetherTheme(accent: Accent = Accent.parDefaut, content: @Composable () -> Unit) {
    val couleur = accent.teinte.accentue()
    // Le texte pose sur l'accent : le fond le plus sombre de l'app. Sur une teinte
    // claire (le gris, par exemple) c'est le seul qui tienne 4.5:1 — verifie par
    // `ContrasteTest`.
    val surAccent = FOND_LE_PLUS_SOMBRE

    val scheme = darkColorScheme(
        primary = couleur,
        onPrimary = surAccent,
        secondary = couleur,
        onSecondary = surAccent,
        tertiary = couleur,
        onTertiary = surAccent,
        background = TetherBackground,
        onBackground = TetherTextPrimary,
        surface = TetherSurface,
        onSurface = TetherTextPrimary,
        surfaceVariant = TetherSurface,
        onSurfaceVariant = TetherTextSecondary,
        error = TetherAlert,
        onError = TetherBackground,
        outline = TetherTextSecondary,
    )

    CompositionLocalProvider(LocalAccent provides couleur) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TetherTypography,
            content = content,
        )
    }
}
