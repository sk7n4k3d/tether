package sh.sk7.tether.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.GitFork
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Scissors
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Trash2
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary
import sh.sk7.tether.ui.theme.TetherTypography

/**
 * **Les options d'une session**, derriere un seul point de menu.
 *
 * ### Pourquoi un menu et pas cinq boutons
 * Cinq actions sur chaque ligne transformeraient la liste en tableau de bord : avec 440
 * sessions, la densite est la premiere qualite de cet ecran. Un seul point d'entree garde la
 * ligne calme, et les actions restent a un tap.
 *
 * ### Pourquoi ces cinq-la, et pas d'autres
 * Elles viennent du **serveur**, pas de l'imagination : `PATCH` (renommer), `POST /fork`,
 * `POST /interrupt`, `POST /compact`, `DELETE`. Chacune ouvre une route qui existe reellement.
 * On n'affiche pas une action qu'on ne peut pas executer.
 *
 * ### Regles
 *  - chaque action est **nommee en clair** (« Renommer », pas « Modifier » : le perimetre exact
 *    doit etre lisible) ;
 *  - [onDelete] est **rouge** : c'est la seule action irreversible de la liste ;
 *  - une action indisponible est **absente**, jamais grisee : un menu grise fait perdre du
 *    temps a chercher pourquoi.
 */
@Composable
fun SessionOptionsMenu(
    onPin: (() -> Unit)? = null,
    pinned: Boolean = false,
    onRename: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    onInterrupt: (() -> Unit)? = null,
    onCompact: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val actions = listOfNotNull(
        // ⚠️ « Epingler » est en PREMIER : c'est le geste le plus frequent, et le libelle dit
        // l'etat courant (« Epingler » / « Détacher ») plutot qu'un verbe ambigu comme
        // « Favori ». Un utilisateur doit savoir ce que le tap va faire, pas ce qu'il a fait.
        onPin?.let {
            SessionAction(
                label = if (pinned) "Détacher" else "Épingler",
                icon = Lucide.Pin,
                onClick = it,
            )
        },
        onRename?.let { SessionAction("Renommer", Lucide.Pencil, it) },
        onFork?.let { SessionAction("Dupliquer (fork)", Lucide.GitFork, it) },
        onInterrupt?.let { SessionAction("Interrompre", Lucide.Square, it) },
        onCompact?.let { SessionAction("Compacter le contexte", Lucide.Scissors, it) },
        onDelete?.let { SessionAction("Supprimer", Lucide.Trash2, it, destructive = true) },
    )
    // Aucune action disponible : aucun controle. On n'affiche pas un menu vide.
    if (actions.isEmpty()) return

    Box(modifier = modifier) {
        var expanded by remember { mutableStateOf(false) }
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Lucide.EllipsisVertical,
                contentDescription = "Options de la session",
                tint = TetherTextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            style = TetherTypography.bodyMedium,
                            color = if (action.destructive) {
                                TetherAlert
                            } else {
                                TetherTextPrimary
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            tint = if (action.destructive) {
                                TetherAlert
                            } else {
                                TetherTextSecondary
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

private data class SessionAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)
