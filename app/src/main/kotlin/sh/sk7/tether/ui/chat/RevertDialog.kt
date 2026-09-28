package sh.sk7.tether.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Undo2
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import androidx.compose.ui.res.pluralStringResource

/**
 * **Confirmer un retour en arrière, en voyant ce qu'il touche.**
 *
 * ### Pourquoi cette boite existe
 * Le reproche le plus vif fait aux agents de code est d'avoir **defait du travail** sans qu'on l'ait
 * vu venir. Un `revert` modifie des fichiers sur la machine : c'est la seule action de Tether qui
 * peut detruire du travail, et elle n'a aucun retour.
 *
 * ⚠️ On affiche la **liste des fichiers** qui seraient touches, pas un compte. « 3 fichiers » ne
 * permet pas de decider ; `src/auth/session.kt` le permet. C'est la meme regle que pour les
 * approbations d'outils : montrer ce sur quoi porte la decision, sans resumer.
 *
 * ⚠️ Le bouton de confirmation dit **« Revenir ici »**, pas « OK ». Un libelle generique ferait
 * perdre de vue ce qu'on s'apprete a faire — et c'est exactement le moment ou il faut le savoir.
 */
@Composable
fun RevertDialog(
    sessionID: String,
    messageID: String,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: RevertViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ⚠️ La preparation se lance **ici**, pas a l'appel : c'est le seul moment ou l'on sait quel
    // message est vise. La cle est l'identifiant du message, donc un changement de cible relance
    // la verification au lieu de reutiliser le resultat precedent.
    androidx.compose.runtime.LaunchedEffect(sessionID, messageID) {
        viewModel.stage(sessionID, messageID)
    }

    when (val current = state) {
        // ⚠️ `Idle` ferme la boite : apres un abandon ou un `commit` reussi, il n'y a plus rien a
        // confirmer. Sans ce rappel de l'appelant, la boite resterait montee sur un etat vide.
        RevertUiState.Idle -> onDismiss()

        RevertUiState.Staging -> AlertDialog(
            onDismissRequest = {
                // ⚠️ Renoncer pendant la verification doit aussi abandonner cote serveur : un
                // snapshot prepare puis ignore resterait en place.
                viewModel.discard(sessionID)
            },
            title = { Text(stringResource(R.string.verification_30a679), color = TetherTextPrimary) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LocalAccent.current,
                    )
                    Text(stringResource(R.string.regarde_quels_fichiers_6bf1df),
                        color = TetherTextSecondary,
                    )
                }
            },
            confirmButton = {},
            containerColor = TetherSurface,
        )

        is RevertUiState.Ready -> AlertDialog(
            onDismissRequest = {
                // ⚠️ Renoncer doit **explicitement** abandonner cote serveur : un snapshot laisse
                // en attente serait un etat intermediaire sans sortie depuis l'app.
                viewModel.discard(sessionID)
            },
            icon = {
                Icon(Lucide.Undo2, contentDescription = null, tint = TetherAlert)
            },
            title = {
                Text(
                    text = if (current.preview.isEmpty) {
                        stringResource(R.string.revenir_ici_1a6293)
                    } else {
                        "Revenir ici modifiera " + pluralStringResource(
                            R.plurals.fichier,
                            current.preview.fileCount,
                            current.preview.fileCount,
                        ) +
                            (if (current.preview.fileCount > 1) "s" else "")
                    },
                    color = TetherTextPrimary,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.tes_messages_apres_cde32f) +
                              " " + stringResource(R.string.fichiers_reviendront_leur_7beb48),
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextSecondary,
                    )

                    if (current.preview.isEmpty) {
                        // ⚠️ Cas reel et important : un revert qui ne touche aucun fichier
                        // n'annule **que** la conversation. Le dire evite de croire qu'on ne fait
                        // rien du tout.
                        Text(stringResource(R.string.aucun_fichier_ete_b344b3) +
                                  " " + stringResource(R.string.seule_conversation_sera_fdbe62),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalAccent.current,
                        )
                    } else {
                        // ⚠️ La liste des chemins, pas un compte. C'est ce qui permet de decider.
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(current.preview.files, key = { it.file }) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(TetherAlert.copy(alpha = 0.08f))
                                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = file.file,
                                        style = TetherDataStyle,
                                        color = TetherTextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "+${file.additions} −${file.deletions}",
                                        style = TetherDataStyle,
                                        color = TetherTextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.commit(sessionID, onDone) }) {
                    Text(stringResource(R.string.revenir_ici_aed777),
                        color = TetherAlert,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.discard(sessionID) }) {
                    Text(stringResource(R.string.annuler_49ba32), color = TetherTextSecondary)
                }
            },
            containerColor = TetherSurface,
        )

        is RevertUiState.Failed -> AlertDialog(
            onDismissRequest = { viewModel.dismiss() },
            title = { Text(stringResource(R.string.retour_impossible_c203fa), color = TetherTextPrimary) },
            text = { Text(current.message, color = TetherTextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismiss() }) {
                    Text(stringResource(R.string.fermer_5ab4ec), color = LocalAccent.current)
                }
            },
            containerColor = TetherSurface,
        )
    }
}
