package sh.sk7.tether.ui.worktree

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import sh.sk7.tether.data.api.WorktreeDirDto
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * **Les arbres de travail isolés.**
 *
 * ### Ce que ça change concrètement
 * Un agent qui écrit des fichiers travaille dans le dépôt. Un **arbre de travail** lui donne une
 * copie à part : on essaie une approche risquée sans que l'arbre principal soit touché. C'est la
 * réponse à « je veux essayer sans risquer mon dépôt », et ça n'existe pas dans une interface de
 * chat.
 *
 * ⚠️ **On dit d'abord si le répertoire est versionné.** Un arbre de travail repose sur git : dans
 * un répertoire sans gestion de version, la création échouerait avec un message obscur. On le dit
 * avant d'essayer, au lieu de laisser l'utilisateur buter sur l'erreur du serveur.
 *
 * ⚠️ La suppression demande une confirmation qui **nomme `force`**. Un arbre avec des
 * modifications non commitées ne se retire pas sans forcer : forcer en silence serait détruire du
 * travail sans le dire.
 */
@Composable
fun WorktreeScreen(
    modifier: Modifier = Modifier,
    viewModel: WorktreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                item(key = "explain") {
                    Block(title = stringResource(R.string.quoi_sert_d7aac6)) {
                        Text(stringResource(R.string.arbre_travail_copie_65c59a) +
                                "travailler sans toucher à l'arbre principal. Tu essaies une " +
                                "approche risquée, et ton dépôt reste intact.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TetherTextSecondary,
                        )
                    }
                }

                if (!state.versioned) {
                    item(key = "not-versioned") {
                        Notice(
                            text = "Ce répertoire n'est pas versionné. Un arbre de travail " +
                                "repose sur git : il n'y a rien à isoler ici.",
                        )
                    }
                }

                state.error?.let { message ->
                    item(key = "error") {
                        Notice(text = message, onClick = viewModel::dismissError)
                    }
                }

                if (state.versioned) {
                    item(key = "create") {
                        Block(title = stringResource(R.string.nouvel_arbre_01ef8f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(TetherDimensions.cornerMd))
                                        .background(TetherComposerSurface)
                                        .border(
                                            width = 1.dp,
                                            color = TetherComposerBorder,
                                            shape = RoundedCornerShape(TetherDimensions.cornerMd),
                                        )
                                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (state.draftName.isEmpty()) {
                                            Text(stringResource(R.string.nom_arbre_5ff8cf),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TetherTextSecondary,
                                            )
                                        }
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = state.draftName,
                                            onValueChange = viewModel::onNameChange,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = TetherTextPrimary,
                                            ),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                                LocalAccent.current,
                                            ),
                                            // ⚠️ `fillMaxWidth` obligatoire, comme les deux autres
                                            // champs de l'app : sans lui, le tap tombe sur le
                                            // placeholder et le champ ne prend jamais le focus.
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                                        .background(LocalAccent.current.copy(alpha = 0.14f))
                                        // ⚠️ 48 dp : c'est le bouton qui crée l'arbre. Sa
                                        // hauteur naturelle est d'environ 36 dp (icône + padding),
                                        // sous le seuil.
                                        .heightIn(min = TetherDimensions.touchTarget)
                                        .clickable(
                                            enabled = state.draftName.isNotBlank() && !state.creating,
                                            onClick = viewModel::create,
                                        )
                                        .padding(horizontal = Spacing.md)
                                        .semantics { role = Role.Button },
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (state.creating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = LocalAccent.current,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Lucide.Plus,
                                            contentDescription = null,
                                            tint = LocalAccent.current,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                    Text(
                                        text = if (state.creating) "Création…" else "Créer",
                                        style = TetherDataStyle,
                                        color = LocalAccent.current,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.items.isNotEmpty()) {
                    item(key = "list-title") {
                        Text(
                            text = "${state.items.size} arbre" +
                                (if (state.items.size > 1) "s" else "") + " de travail",
                            style = MaterialTheme.typography.titleSmall,
                            color = TetherTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(state.items, key = { it.directory }) { item ->
                        WorktreeRow(item = item, onRemove = { viewModel.askRemove(item) })
                    }
                }
            }
        }
    }

    state.removing?.let { item ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRemove,
            icon = { Icon(Lucide.Trash2, contentDescription = null, tint = TetherAlert) },
            title = { Text(stringResource(R.string.retirer_cet_arbre_3f60d5), color = TetherTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(item.directory, style = TetherDataStyle, color = TetherTextSecondary)
                    Text(
                        // ⚠️ On dit ce qui peut être perdu AVANT de proposer de forcer. Un arbre
                        // avec des modifications non commitées contient du travail qui n'existe
                        // nulle part ailleurs.
                        text = "Les modifications non commitées dans cet arbre seront perdues. " +
                            "Le reste du dépôt n'est pas touché.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherAlert,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.remove(force = true) }) {
                    // ⚠️ Le libellé nomme ce qu'on fait : forcer. Un simple « Supprimer » cacherait
                    // que l'opération passe outre une protection du serveur.
                    Text(stringResource(R.string.retirer_force_0f1681), color = TetherAlert, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRemove) {
                    Text(stringResource(R.string.annuler_49ba32), color = TetherTextSecondary)
                }
            },
            containerColor = TetherSurface,
        )
    }
}

@Composable
private fun WorktreeRow(item: WorktreeDirDto, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Lucide.GitBranch,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = item.directory,
                style = TetherDataStyle,
                color = TetherTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // ⚠️ Boîte de 48 dp explicite : un `padding` avant `.size(15).clickable` met la zone
        // sensible sur l'icône seule, pas sur le padding. Sur une action **destructive**, cette
        // erreur est doublement grave : on rate le bouton, et le voisin devient cliquable par
        // débordement supposé.
            // La description est lue ici : `semantics` s'execute hors composition.
            val descRetirer_cet_arbre = stringResource(R.string.retirer_cet_arbre_d42936)
        Box(
            modifier = Modifier
                .size(TetherDimensions.touchTarget)
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onRemove)
                .semantics {
                    role = Role.Button
                    contentDescription = descRetirer_cet_arbre
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Trash2,
                contentDescription = null,
                tint = TetherAlert,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(TetherAlert.copy(alpha = 0.12f))
            // ⚠️ 48 dp **quand c'est cliquable** : un bandeau d'alerte qu'on peut écarter doit
            // pouvoir être visé. `heightIn` avant `clickable`, pour que la zone sensible en tienne
            // compte — l'ordre inverse le rendrait sans effet.
            .heightIn(min = TetherDimensions.touchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .padding(Spacing.md),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = TetherAlert)
    }
}
