package sh.sk7.tether.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **A propos : ce qu'est cette app, et ou trouver quoi.**
 *
 * ### Pourquoi ce n'est pas une page decorative
 * Deux informations y sont reellement utiles :
 *  - **la version de l'app ET celle du serveur** cote a cote. C'est ce qui permet de diagnostiquer
 *    une incompatibilite : un client qui parle a un serveur plus vieux doit le **dire**, pas
 *    echouer bizarrement ;
 *  - **la licence MIT**, parce que Tether est un logiciel libre et que le dire fait partie du
 *    contrat.
 *
 * ⚠️ On affiche une **marque** discrete — le fil et le nœud, dessines a la main. C'est la
 * signature de l'app : elle appartient a cet ecran, qui est le seul ou l'on parle de l'app
 * elle-meme.
 */
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "mark") { TetherMark() }

        item(key = "versions") {
            Block(title = "Versions") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    VersionLine("Tether", APP_VERSION)
                    VersionLine(
                        label = "opencode",
                        value = state.version ?: "injoignable",
                        ok = state.version != null,
                    )
                }
            }
        }

        item(key = "principe") {
            Block(title = "Principe") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = "Tether pilote ton serveur opencode depuis ce téléphone. " +
                            "Tout reste chez toi : l'app ne parle qu'à ton serveur, et tes " +
                            "conversations ne quittent pas ta machine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TetherTextSecondary,
                    )
                    Text(
                        text = "Licence MIT — logiciel libre, sans dépendance aux services " +
                            "Google. Conçu pour fonctionner sans Play Services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextSecondary.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/**
 * La marque : **le fil et le nœud**, la signature de Tether.
 *
 * ⚠️ Dessinee, pas un asset. `drawBehind` evite d'embarquer un fichier image pour deux traits,
 * et surtout elle **reste nette** a n'importe quelle densite d'ecran.
 */
@Composable
private fun TetherMark() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(vertical = Spacing.sm)
                .drawBehind {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val span = size.width * 0.28f
                    // Le fil : un trait continu.
                    drawLine(
                        color = TetherAccent.copy(alpha = 0.55f),
                        start = Offset(cx - span, cy),
                        end = Offset(cx + span, cy),
                        strokeWidth = TetherDimensions.threadWidth.toPx(),
                    )
                    // Le nœud : plein, sur le fil.
                    drawCircle(
                        color = TetherAccent,
                        radius = 5.dp.toPx(),
                        center = Offset(cx, cy),
                    )
                },
        ) {}

        Text(
            text = "Tether",
            style = MaterialTheme.typography.titleMedium,
            color = TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "le fil qui relie ton agent",
            style = TetherDataStyle,
            color = TetherTextSecondary,
        )
    }
}

@Composable
private fun VersionLine(label: String, value: String, ok: Boolean = true) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = TetherDataStyle, color = TetherTextSecondary)
        Text(
            text = value,
            style = TetherDataStyle,
            color = if (ok) TetherAccent else TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Version de l'app.
 *
 * ⚠️ En dur plutot que `BuildConfig.VERSION_NAME` : `buildConfig` n'est pas active dans ce
 * module, et l'activer pour une seule chaine ajouterait une etape de build et une classe
 * generee. La valeur est celle de `app/build.gradle.kts` (`versionName = "0.1.0"`), et elle ne
 * bouge qu'a une release — un ecran « A propos » n'a pas besoin d'une precision a la seconde.
 */
private const val APP_VERSION = "0.1.0"
