package sh.sk7.tether.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plug
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.ShieldAlert
import sh.sk7.tether.data.api.SavedPermissionDto
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * **L'inventaire du serveur : ce avec quoi l'agent travaille.**
 *
 * ### Pourquoi cet ecran existe
 * Mesure du 2026-09-25 : le serveur expose **21 serveurs MCP, 59 skills, 28 commandes,
 * 90 plugins, 4 providers, 232 integrations** et ses permissions memorisees. L'app n'en montrait
 * **rien**. Un compagnon qui ignore ce que l'autre porte n'est pas un compagnon.
 *
 * ### Ce qui le rend utile plutot que decoratif
 * Les **permissions memorisees** sont revoquables ici, et c'est une action de securite : c'est le
 * seul endroit ou l'on peut retirer un droit accorde puis oublie. Un droit qu'on ne peut plus
 * retirer n'est pas une fonctionnalite manquante, c'est un risque.
 *
 * ⚠️ Chaque nature d'objet est **groupée dans un bloc titré** avec son compte : la question
 * « combien de serveurs MCP sont connectes ? » se lit sans compter. Un statut se dit par un mot
 * **et** une couleur, jamais par la couleur seule.
 */
@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    viewModel: ServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var revoking by remember { mutableStateOf<SavedPermissionDto?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "identity") {
                    Block(title = stringResource(R.string.serveur_970701)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Icon(
                                imageVector = Lucide.Server,
                                contentDescription = null,
                                tint = LocalAccent.current,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = state.version?.let { stringResource(R.string.opencode_f119e9) } ?: "opencode",
                                style = MaterialTheme.typography.titleSmall,
                                color = TetherTextPrimary,
                            )
                        }
                    }
                }

                // ⚠️ L'erreur est un bandeau **au-dessus** du contenu, jamais un remplacement.
                // Un inventaire partiel doit rester consultable : voir 18 serveurs sur 21 est
                // plus utile que ne rien voir du tout.
                state.error?.let { message ->
                    item(key = "partial-error") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                                .background(TetherAlert.copy(alpha = 0.12f))
                                .padding(Spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Lucide.ShieldAlert,
                                contentDescription = null,
                                tint = TetherAlert,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TetherAlert,
                            )
                        }
                    }
                }

                if (state.mcp.isNotEmpty()) {
                    item(key = "mcp") {
                        Block(title = "Serveurs MCP · ${state.mcpConnected}/${state.mcp.size} connectés") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.mcp.forEach { server ->
                                    StatusLine(
                                        name = server.name,
                                        status = server.status?.status,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.providers.isNotEmpty()) {
                    item(key = "providers") {
                        Block(title = stringResource(R.string.fournisseurs_06b6d8)) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.providers.forEach { provider ->
                                    StatusLine(
                                        name = provider.name ?: provider.id,
                                        status = provider.activation,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.permissions.isNotEmpty()) {
                    item(key = "permissions") {
                        Block(title = "Autorisations mémorisées (${state.permissions.size})") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.permissions.forEach { permission ->
                                    PermissionLine(permission) { revoking = permission }
                                }
                            }
                        }
                    }
                }

                if (state.skills.isNotEmpty()) {
                    item(key = "skills") {
                        Block(title = "Skills (${state.skills.size})") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.skills.take(SKILLS_SHOWN).forEach { skill ->
                                    NamedLine(
                                        icon = Lucide.Blocks,
                                        name = skill.name ?: skill.id,
                                        detail = skill.description,
                                    )
                                }
                                if (state.skills.size > SKILLS_SHOWN) {
                                    MoreLine(state.skills.size - SKILLS_SHOWN, "skills")
                                }
                            }
                        }
                    }
                }

                if (state.commands.isNotEmpty()) {
                    item(key = "commands") {
                        Block(title = "Commandes (${state.commands.size})") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.commands.take(COMMANDS_SHOWN).forEach { command ->
                                    NamedLine(
                                        icon = Lucide.Plug,
                                        name = "/${command.name}",
                                        detail = command.description,
                                    )
                                }
                                if (state.commands.size > COMMANDS_SHOWN) {
                                    MoreLine(state.commands.size - COMMANDS_SHOWN, "commandes")
                                }
                            }
                        }
                    }
                }

                if (state.plugins.isNotEmpty()) {
                    item(key = "plugins") {
                        Block(title = "Plugins (${state.plugins.size})") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.plugins.take(PLUGINS_SHOWN).forEach { plugin ->
                                    NamedLine(
                                        icon = Lucide.Plug,
                                        name = plugin.id,
                                        detail = plugin.state?.status,
                                    )
                                }
                                if (state.plugins.size > PLUGINS_SHOWN) {
                                    MoreLine(state.plugins.size - PLUGINS_SHOWN, "plugins")
                                }
                            }
                        }
                    }
                }

                if (state.projects.isNotEmpty()) {
                    item(key = "projects") {
                        Block(title = "Projets (${state.projects.size})") {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                state.projects.forEach { project ->
                                    // ⚠️ On affiche le CHEMIN (`canonical`), jamais l'id : un
                                    // hash de 40 caracteres ne dit rien a personne.
                                    NamedLine(
                                        icon = Lucide.Server,
                                        name = project.canonical ?: project.id,
                                        detail = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    revoking?.let { permission ->
        RevokeDialog(
            permission = permission,
            onConfirm = {
                viewModel.revoke(permission.id)
                revoking = null
            },
            onDismiss = { revoking = null },
        )
    }
}

/**
 * Ligne d'etat : nom + statut, le statut dit par un **mot** et une couleur.
 *
 * ⚠️ Le mot est indispensable : « connecté » / « absent » se lisent, une pastille verte ne se lit
 * pas (et disparait en mode contraste eleve ou pour un daltonien).
 */
@Composable
private fun StatusLine(name: String, status: String?) {
    val connected = status in setOf("connected", "enabled", "active")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = TetherDataStyle,
            color = TetherTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = status?.let { statusText(it) } ?: "inconnu",
            style = TetherDataStyle,
            color = if (connected) LocalAccent.current else TetherTextSecondary,
        )
    }
}

private fun statusText(raw: String): String = when (raw) {
    "connected" -> "connecté"
    "enabled" -> "actif"
    "active" -> "actif"
    "disabled" -> "désactivé"
    "error", "failed" -> "erreur"
    else -> raw
}

@Composable
private fun NamedLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    detail: String?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Column {
            Text(
                text = name,
                style = TetherDataStyle,
                color = TetherTextPrimary,
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Dit **combien** d'elements sont masques — un « … » seul laisse croire qu'il n'y en a pas plus. */
@Composable
private fun MoreLine(remaining: Int, what: String) {
    Text(
        text = stringResource(R.string.remaining_autre_remaining_57e09b, if (remaining > 1) "s" else "", what),
        style = TetherDataStyle,
        color = TetherTextMuted,
    )
}

/**
 * Une autorisation memorisee, avec son bouton de revocation.
 *
 * ⚠️ L'**action** et la **ressource** sont affichees cote a cote : « external_directory » seul ne
 * dit pas sur quoi porte le droit. C'est le couple qui permet de decider s'il faut le retirer.
 */
@Composable
private fun PermissionLine(permission: SavedPermissionDto, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = permission.action ?: stringResource(R.string.action_inconnue_ea7b67),
                style = TetherDataStyle,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            permission.resource?.let {
                Text(
                    text = it,
                    style = TetherDataStyle,
                    color = TetherTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(stringResource(R.string.revoquer_9c67aa),
            style = TetherDataStyle,
            color = TetherAlert,
            modifier = Modifier
                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                // ⚠️ 48 dp : action de sécurité, elle doit être immanquable.
                .heightIn(min = TetherDimensions.touchTarget)
                .clickable(onClick = onRevoke)
                .padding(horizontal = Spacing.sm)
                // ⚠️ `role = Button` : sans lui, TalkBack annonce le texte sans dire qu'il est
                // actionnable — l'utilisateur entend « Révoquer » mais ne sait pas qu'il peut
                // appuyer. C'est la différence entre un lecteur d'écran utilisable et décoratif.
                .semantics { role = Role.Button },
        )
    }
}

/**
 * Confirmation de revocation.
 *
 * ⚠️ On confirme parce que c'est **irreversible depuis l'app** : une fois retire, le droit ne
 * peut etre re-accorde que depuis le serveur. Le corps nomme l'action concernee — on ne revoque
 * pas « une permission » en general.
 */
@Composable
private fun RevokeDialog(
    permission: SavedPermissionDto,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.revoquer_autorisation_7dc1d5), color = TetherTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = permission.action ?: "",
                    style = TetherDataStyle,
                    color = TetherTextPrimary,
                )
                permission.resource?.let {
                    Text(
                        text = it,
                        style = TetherDataStyle,
                        color = TetherTextSecondary,
                    )
                }
                Text(stringResource(R.string.agent_devra_redemander_a0ffc8) +
                          " " + stringResource(R.string.pourras_accorder_depuis_fac7df),
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherAlert,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.revoquer_9c67aa), color = TetherAlert) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.annuler_49ba32), color = TetherTextSecondary) }
        },
        containerColor = TetherSurface,
    )
}

/** Combien d'elements on montre par section avant de resumer. */
private const val SKILLS_SHOWN = 8
private const val COMMANDS_SHOWN = 8
private const val PLUGINS_SHOWN = 6
