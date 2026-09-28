package sh.sk7.tether.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * Bloc **repliable** — la reponse au « bloc gris geant » qui noyait la conversation.
 *
 * L'information secondaire (raisonnement du modele, sortie d'outil brute) ne s'impose plus :
 * elle se resume sur **une ligne** avec ses chiffres, et s'ouvre d'un tap.
 *
 * C'est l'application directe de deux regles de `docs/design-soul.md` :
 *  - « dense en information, aere en espacement » ;
 *  - « pas de bloc gris massif : l'information secondaire se replie ».
 *
 * L'etat deplie/replie est **local a la composition** (pas de persistance serveur : l'API
 * n'expose aucun etat de ce genre, et on n'invente pas de donnee).
 */
@Composable
fun CollapsibleBlock(
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    summaryColor: Color = TetherTextSecondary,
    detailColor: Color = TetherTextSecondary,
    accent: Color = TetherTextSecondary,
    error: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    detailContent: (@Composable (String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = stringResource(R.string.chevron_fdac45),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 48 dp : le chevron de pliage fait 14 dp, mais c'est toute la ligne qui replie
                // et toute la ligne qui doit etre attrapable. Viser 14 dp au doigt est impossible.
                .heightIn(min = TetherDimensions.touchTarget)
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.sm)
                // ⚠️ `role = Button` : la ligne EST le contrôle de pliage. Sans le rôle, TalkBack
                // lit le résumé sans dire qu'on peut l'ouvrir — le contenu replié devient
                // inaccessible à un lecteur d'écran.
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Lucide.ChevronDown,
                contentDescription = if (expanded) "Replier" else "Deplier",
                tint = if (error) TetherAlert else accent,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotation),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) TetherAlert else summaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            if (detailContent != null) {
                detailContent(detail)
            } else {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor,
                    modifier = Modifier.padding(start = Spacing.xl, bottom = Spacing.sm),
                )
            }
        }
    }
}
