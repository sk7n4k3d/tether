package sh.sk7.tether.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.Lucide
import sh.sk7.tether.domain.model.SessionUiState
import sh.sk7.tether.domain.model.SessionStatus
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **L'en-tete d'instrument d'une conversation.**
 *
 * ### Pourquoi ce bandeau existe
 * Le `design-soul.md` §5 le demande : « En-tete de session : modele, agent, cout cumule en
 * direct, tokens, statut ». La liste des sessions le montrait deja ; le chat l'oubliait — et
 * c'est pourtant l'ecran ou l'on passe le plus de temps.
 *
 * ⚠️ Un cockpit qui ne dit **ce qu'il pilote** que sur sa page d'accueil n'est pas un cockpit.
 * Tu lis une reponse ici : tu dois savoir quel modele l'a ecrite, quel agent a ete mandate, et
 * ce que ca a coute, **sans quitter l'ecran**.
 *
 * ### Regles de construction
 *  - **Statut d'abord** : un point + un mot. Ce qui tourne se lit au premier regard.
 *  - **Chiffres en monospace** : cout et tokens dans la fonte des donnees, jamais celle du
 *    texte. C'est ce qui les rend comparables d'une session a l'autre.
 *  - **Provider separe du modele** : `ollama-cloud` et `deepseek-v4.1-flash` sont deux faits
 *    distincts ; les coller masque lequel des deux on paie.
 *  - Aucun champ absent n'affiche de zero : un modele inconnu est **absent**, pas « - ».
 */
@Composable
fun ChatInstrumentHeader(
    state: SessionUiState,
    meta: SessionMeta?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // ---------------------------------------------------- LIGNE 1 : l'etat
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StatusDot(state.status)
            Text(
                text = statusLabel(state.status),
                style = TetherDataStyle,
                color = statusColor(state.status),
                fontWeight = FontWeight.SemiBold,
            )
            // Le modele et son provider : deux faits, deux teintes.
            meta?.model?.let { model ->
                Text(
                    text = model,
                    style = TetherDataStyle,
                    color = TetherTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            meta?.provider?.let { provider ->
                Text(
                    text = provider,
                    style = TetherDataStyle,
                    color = TetherTextMuted,
                )
            }
        }

        // ---------------------------------------------------- LIGNE 2 : l'addition
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            meta?.agent?.let { agent ->
                Text(
                    text = agent,
                    style = TetherDataStyle,
                    color = TetherTextSecondary,
                )
            }
            // ⚠️ Le cout vient du FLUX (`session.usage.updated`), donc il monte en direct
            // pendant un tour. C'est ce qui en fait une jauge et pas une decoration.
            state.cost?.takeIf { it > 0.0 }?.let { cost ->
                Text(
                    text = formatCost(cost),
                    style = TetherDataStyle,
                    color = TetherAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            state.tokens?.let { tokens ->
                // ⚠️ **Le raisonnement compte.** Il etait exclu ici alors que `UsageStats.totalTokens`
                // l'inclut : deux definitions du meme « total » dans la meme app. Sur un modele qui
                // raisonne beaucoup, l'ecran affichait un volume tres inferieur a la realite — donc
                // un chiffre faux, ce qui est pire qu'un chiffre absent.
                val total = tokens.input + tokens.output + tokens.reasoning
                if (total > 0) {
                    Text(
                        text = formatTokens(tokens.input, tokens.output),
                        style = TetherDataStyle,
                        color = TetherTextSecondary,
                    )
                }
            }
        }
    }
}

/** Pastille d'etat : pleine et teal si ca tourne, anneau sinon. */
@Composable
private fun StatusDot(status: SessionStatus) {
    val color = statusColor(status)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape),
    )
}

private fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Running -> "en cours"
    SessionStatus.Succeeded -> "terminé"
    SessionStatus.Failed -> "échec"
    SessionStatus.Interrupted -> "interrompu"
    SessionStatus.Idle -> "prêt"
}

private fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.Running -> TetherAccent
    SessionStatus.Succeeded -> TetherTextPrimary
    SessionStatus.Failed, SessionStatus.Interrupted -> TetherAlert
    SessionStatus.Idle -> TetherTextSecondary
}

private fun formatCost(value: Double): String =
    String.format(java.util.Locale.FRANCE, "%.2f $", value)

private fun formatTokens(input: Long, output: Long): String =
    "${formatCount(input)} in · ${formatCount(output)} out"

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1f M".format(java.util.Locale.FRANCE, value / 1_000_000.0)
    value >= 1_000 -> "%.1f k".format(java.util.Locale.FRANCE, value / 1_000.0)
    else -> "$value"
}
