package sh.sk7.tether.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import androidx.compose.ui.res.pluralStringResource

/**
 * **En-tete de consommation** — toujours visible en haut de la liste, jamais demande.
 *
 * `docs/design-soul.md` §3 (principe de non-mensonge) : Bastien ne voyait ni son cout ni ses
 * tokens alors que l'API les expose. Ce bloc les rend visibles sans qu'on ait a les chercher.
 *
 * Deux regles de presentation :
 *  - **on dit sur quoi on calcule** (« sur 438 sessions ») — un chiffre sans perimetre est un
 *    chiffre malhonnete ;
 *  - **le cache a sa place** a cote de in/out, parce qu'il represente 94,7 % du volume reel
 *    du profil. Le cacher donnerait une fausse idee du trafic.
 */
@Composable
fun UsageHeader(usage: UsageInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(stringResource(R.string.consommation_fa3cc9),
                    style = MaterialTheme.typography.labelSmall,
                    color = TetherTextSecondary,
                )
                Text(
                    text = formatTotal(usage.costTotal),
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalAccent.current,
                )
            }
            Text(
                text = "sur " + pluralStringResource(R.plurals.sessions, usage.sessions, usage.sessions),
                style = TetherDataStyle,
                color = TetherTextSecondary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            DataPoint(label = stringResource(R.string.in_af10ef), value = formatCount(usage.tokensIn))
            DataPoint(label = stringResource(R.string.out_f4800d), value = formatCount(usage.tokensOut))
            DataPoint(label = stringResource(R.string.cache_b03592), value = formatCount(usage.cacheRead), accent = true)
        }
    }
}

@Composable
private fun DataPoint(label: String, value: String, accent: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = TetherDataStyle, color = TetherTextSecondary)
        Text(
            text = value,
            style = TetherDataStyle,
            color = if (accent) LocalAccent.current else TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatTotal(value: Double): String =
    String.format(java.util.Locale.FRANCE, "%.2f $", value)

private fun formatCount(value: Long): String = when {
    value >= 1_000_000_000L -> String.format(java.util.Locale.FRANCE, "%.1f Md", value / 1_000_000_000.0)
    value >= 1_000_000L -> String.format(java.util.Locale.FRANCE, "%.1f M", value / 1_000_000.0)
    value >= 1_000L -> String.format(java.util.Locale.FRANCE, "%.1f k", value / 1_000.0)
    else -> value.toString()
}
