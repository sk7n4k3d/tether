package sh.sk7.tether.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **La palette de commandes slash.**
 *
 * ### Pourquoi elle s'ouvre sur `/`
 * C'est le geste qui existe deja dans la TUI d'opencode, et l'attendre au meme endroit est la
 * seule facon qu'il soit naturel sur mobile. Mais il y a une raison plus dure : le serveur
 * **valide le nom des commandes** contre sa liste (28 mesurees). Taper `/review` comme du texte
 * ne declenche rien — l'agent le lirait comme une phrase. Sans cette palette, l'utilisateur
 * n'aurait aucun moyen de savoir quelles commandes existent, ni comment les invoquer.
 *
 * ⚠️ On affiche la **description** du serveur telle quelle : elle dit ce que fait la commande, et
 * la reformuler ferait prendre une paraphrase de l'app pour la documentation du serveur.
 *
 * ⚠️ Le filtrage est fait par l'appelant : la liste est deja reduite a ce qui correspond a la
 * frappe. Ce composant ne fait qu'afficher, ce qui le rend trivialement testable a l'oeil.
 */
@Composable
fun SlashPalette(
    commands: List<CommandDto>,
    onPick: (CommandDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd)),
    ) {
        Text(
            text = if (commands.isEmpty()) "AUCUNE COMMANDE" else "COMMANDES",
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
        if (commands.isEmpty()) {
            // ⚠️ On dit POURQUOI c'est vide plutot que d'afficher une boite vide. Les commandes
            // viennent du serveur : si la liste est vide, c'est que le serveur n'en a pas annonce
            // ou que le chargement a echoue — dans les deux cas, ce n'est pas la faute de la frappe.
            Text(
                text = "Le serveur n'a pas annoncé de commande. " +
                    "Tu peux écrire ton message normalement.",
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary.copy(alpha = 0.8f),
                modifier = Modifier.padding(
                    start = Spacing.md, end = Spacing.md, bottom = Spacing.md,
                ),
            )
        } else {
            LazyColumn(
                // ⚠️ Hauteur plafonnee : la palette ne doit pas recouvrir tout l'ecran. Elle
                // propose, elle ne remplace pas la conversation.
                modifier = Modifier.heightIn(max = 260.dp),
                contentPadding = PaddingValues(bottom = Spacing.sm),
            ) {
                items(commands, key = { it.name }) { command ->
                    CommandRow(command = command, onPick = { onPick(command) })
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: CommandDto, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Lucide.Terminal,
            contentDescription = null,
            tint = TetherAccent,
            modifier = Modifier.size(14.dp),
        )
        Column {
            Text(
                text = "/${command.name}",
                style = TetherDataStyle,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            command.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * **Le selecteur de modele et d'agent, au moment de l'envoi.**
 *
 * ### Pourquoi une feuille, et pas un reglage
 * Le choix du modele depend de la question qu'on pose : un modele rapide pour une reformulation,
 * un modele profond pour un refactor. Le mettre dans les reglages obligerait a y aller **avant**
 * chaque message, c'est-a-dire a chaque fois.
 *
 * ⚠️ On distingue visuellement le **choix pour cette session** de l'etat courant : le serveur
 * applique le changement pour les tours suivants, ce qui est different d'un choix ponctuel.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelAgentPicker(
    models: List<String>,
    agents: List<String>,
    currentModel: String?,
    currentAgent: String?,
    onPickModel: (String) -> Unit,
    onPickAgent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TetherComposerSurface,
    ) {
        Column(
            modifier = Modifier.padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PickerSection(
                title = "MODÈLE",
                items = models,
                current = currentModel,
                icon = Lucide.Blocks,
                onPick = onPickModel,
            )
            PickerSection(
                title = "AGENT",
                items = agents,
                current = currentAgent,
                icon = Lucide.Blocks,
                onPick = onPickAgent,
            )
        }
    }
}

@Composable
private fun PickerSection(
    title: String,
    items: List<String>,
    current: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "$title · ${items.size}",
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        if (items.isEmpty()) {
            // ⚠️ Un selecteur vide doit se dire. Sans ce message, l'utilisateur croirait a un
            // defaut d'affichage alors que le serveur n'a simplement rien annonce.
            Text(
                text = "Le serveur n'a rien annoncé dans cette catégorie.",
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(items, key = { it }) { item ->
                val active = item == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(item) }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (active) TetherAccent else TetherTextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = item,
                        style = TetherDataStyle,
                        color = if (active) TetherAccent else TetherTextPrimary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
