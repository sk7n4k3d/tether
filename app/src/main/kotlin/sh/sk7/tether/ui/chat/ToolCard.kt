package sh.sk7.tether.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleCheckBig
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Loader
import com.composables.icons.lucide.Terminal
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherBackground
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * Carte depliable d'un appel d'outil.
 *
 * ⚠️ La forme d'un evenement `session.tool.*` n'a **jamais ete capturee** : la carte
 * n'interprete donc que [ToolCall.name] et [ToolCall.status], et affiche [ToolCall.raw]
 * tel quel quand on la deplie. Aucune structure de charge n'est supposee.
 */
@Composable
fun ToolCard(tool: ToolCall, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TetherBackground, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Lucide.Terminal,
                contentDescription = null,
                tint = TetherTextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = tool.name.ifBlank { "outil" },
                style = MaterialTheme.typography.labelLarge,
                color = TetherTextPrimary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            ToolStatusBadge(tool.status)
            Icon(
                if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Replier" else "Déplier",
                tint = TetherTextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(visible = expanded && tool.raw.isNotBlank()) {
            Text(
                text = tool.raw,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToolStatusBadge(status: ToolStatus) {
    when (status) {
        ToolStatus.Running -> Icon(
            Lucide.Loader,
            contentDescription = "En cours",
            tint = TetherAccent,
            modifier = Modifier.size(15.dp),
        )
        ToolStatus.Succeeded -> Icon(
            Lucide.CircleCheckBig,
            contentDescription = "Terminé",
            tint = TetherAccent,
            modifier = Modifier.size(15.dp),
        )
        ToolStatus.Failed -> Icon(
            Lucide.CircleX,
            contentDescription = "Échec",
            tint = TetherAlert,
            modifier = Modifier.size(15.dp),
        )
    }
}
