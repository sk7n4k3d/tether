package sh.sk7.tether.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TetherColorScheme = darkColorScheme(
    primary = TetherAccent,
    onPrimary = TetherBackground,
    secondary = TetherTextSecondary,
    onSecondary = TetherBackground,
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

@Composable
fun TetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TetherColorScheme,
        typography = TetherTypography,
        content = content,
    )
}
