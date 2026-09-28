package sh.sk7.tether.ui.background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import sh.sk7.tether.domain.model.Activity
import sh.sk7.tether.domain.model.FleetState
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * **Le travail de fond : ce qui tourne sans qu'une session ne l'attende.**
 *
 * ### La question à laquelle cette section répond
 * « Qu'est-ce qui tourne encore ? » — posée quand on veut éteindre, ou qu'on se demande pourquoi la
 * machine est lente. Aucune session ne l'annonce, parce qu'aucune ne l'attend.
 *
 * ### La distinction premier plan / arrière-plan, et son honnêteté
 * ⚠️ **Le serveur n'expose PAS cette distinction.** Il y a un `metadata.sessionID` sur un shell, et
 * rien de plus. La règle appliquée est donc une **déduction de l'app** :
 *
 *  - un shell dont la session est **encore active** (`running`) → **premier plan** ;
 *  - un shell dont la session n'est **plus active** → **arrière-plan** — il a survécu à la fin du
 *    tour, ce qui est exactement le cas qui inquiète ;
 *  - un shell **sans** session, ou un terminal → **arrière-plan** (rien ne l'attend).
 *
 * ⚠️ Cette déduction est **présentée comme telle** : la ligne dit « session terminée » et non
 * « arrière-plan, certifié par le serveur ». Inventer une autorité qu'on n'a pas serait le
 * mensonge qu'on s'interdit.
 *
 * ### Pourquoi les terminaux sont là aussi
 * ⚠️ Mesure du 2026-09-25 : `POST /api/session/{id}/shell` rend **500** sur ce serveur (bug de
 * plugin `cc-safety-net`), alors que `POST /api/pty` rend **200**. Ne montrer que les shells
 * donnerait une image incomplète du travail réel — un terminal ouvert est du travail de fond.
 */
@Composable
fun BackgroundSection(
    fleet: FleetState,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val liveShells = fleet.liveShells
    val liveTerminals = fleet.liveTerminals

    if (liveShells.isEmpty() && liveTerminals.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.travail_fond_e44975),
                style = TetherDataStyle,
                color = TetherTextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "· ${liveShells.size + liveTerminals.size}",
                style = TetherDataStyle,
                color = LocalAccent.current,
            )
        }

        liveTerminals.forEach { terminal ->
            BackgroundRow(
                id = terminal.id,
                label = terminal.label,
                detail = buildString {
                    append("terminal · pid ${terminal.pid ?: "?"}")
                    terminal.cwd?.let { append(" · $it") }
                },
                // ⚠️ Un terminal n'annonce pas de session : il est donc en arrière-plan par
                // construction, sans deduction a faire.
                foreground = null,
                now = now,
                startedAt = null,
            )
        }

        liveShells.forEach { shell ->
            val sessionActive = fleet.bySession[shell.sessionID]?.activity
                ?.let { it == Activity.Running || it == Activity.Waiting }

            BackgroundRow(
                id = shell.id,
                label = shell.firstLine,
                detail = buildString {
                    append("shell · pid ${shell.pid ?: "?"}")
                    shell.sessionID?.let { append(" · ${it.take(12)}…") }
                },
                // `true` = la session est encore active ; `false` = elle ne l'est plus ;
                // `null` = aucune session connue.
                foreground = when {
                    shell.sessionID == null -> false
                    sessionActive == true -> true
                    else -> false
                },
                now = now,
                startedAt = shell.startedAt,
            )
        }
    }
}

/**
 * Une ligne de travail de fond.
 *
 * ⚠️ La durée est affichée **quand on peut la calculer seulement**. Un shell vivant a un début ;
 * un terminal n'en a pas dans l'API. Ne rien afficher vaut mieux qu'un « 0 s » qui serait faux.
 */
@Composable
private fun BackgroundRow(
    id: String,
    label: String,
    detail: String,
    foreground: Boolean?,
    now: Long,
    startedAt: Long?,
) {
    // ⚠️ La formulation dit la SOURCE du jugement : « la session tourne encore » est un fait,
    // « en arrière-plan » est notre deduction. Sur une ligne courte, on nomme le fait.
    val (stateLabel, stateTint) = when (foreground) {
        true -> stringResource(R.string.session_tourne_0424da) to LocalAccent.current
        false -> stringResource(R.string.session_finie_6cb0e6) to TetherAlert
        null -> stringResource(R.string.aucune_session_15daed) to TetherTextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Lucide.Terminal,
            contentDescription = null,
            tint = stateTint,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.ifBlank { id },
                style = TetherDataStyle,
                color = TetherTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = TetherDataStyle,
                color = TetherTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // ⚠️ Le délai de garde : on dit depuis combien de temps ça tourne, mais **seulement**
            // si on le sait. Un shell de 4 minutes et un shell d'une heure ne veulent pas dire la
            // même chose quand on cherche pourquoi la machine chauffe.
            startedAt?.let { start ->
                val minutes = (now - start) / 60_000
                if (minutes > 0) {
                    Text(stringResource(R.string.depuis_minutes_min_1b885f),
                        style = TetherDataStyle,
                        color = TetherTextMuted,
                    )
                }
            }
        }
        Text(
            text = stateLabel,
            style = TetherDataStyle,
            color = stateTint,
            maxLines = 2,
        )
    }
}
