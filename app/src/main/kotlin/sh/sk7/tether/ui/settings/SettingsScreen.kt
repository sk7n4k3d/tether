package sh.sk7.tether.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.ShieldAlert
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **Les reglages : l'etat de l'app d'abord, les actions ensuite.**
 *
 * ### La logique de cet ecran
 * Il repond dans l'ordre aux trois questions qu'on se pose en ouvrant des reglages :
 *  1. **A quoi suis-je connecte ?** — l'adresse et le repertoire, en clair ;
 *  2. **Est-ce que ca marche ?** — un etat verifie, avec la version du serveur ;
 *  3. **Que puis-je faire ?** — verifier a nouveau, voir l'inventaire, exporter, se deconnecter.
 *
 * ⚠️ Ce n'est **pas** un formulaire : il n'y a pas de champ a remplir ici. Modifier la connexion
 * se fait via l'ecran de connexion (que la deconnexion ramene). Un formulaire permanent dans les
 * reglages invite a modifier une adresse qui marche, ce qui est le contraire de ce qu'on veut.
 *
 * ⚠️ **On distingue « jamais configure » de « injoignable »** : ce ne sont pas les memes
 * situations et elles n'appellent pas la meme action. Les confondre ferait chercher un probleme
 * reseau a quelqu'un qui n'a simplement rien renseigne.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    /** Les arbres de travail isoles : essayer sans risquer le depot. */
    onOpenWorktrees: () -> Unit = {},
    /** L'explorateur de fichiers : verifier un chemin avant de l'envoyer. */
    onOpenFiles: () -> Unit = {},
    onDisconnected: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }

    // ⚠️ La deconnexion **quitte l'ecran** : rester sur une page de reglages qui n'a plus de
    // serveur ne veut rien dire. C'est l'appelant qui decide ou aller (l'ecran de connexion).
    // ⚠️ On attend `loaded` : sans cette condition, `hasPassword` vaut `false` au premier
    // passage et l'ecran redirigeait vers la connexion **avant d'avoir lu le disque**. Bug
    // attrape en testant l'ecran : les Reglages ne s'affichaient jamais.
    androidx.compose.runtime.LaunchedEffect(state.loaded, state.hasPassword) {
        if (state.loaded && !state.hasPassword) onDisconnected()
    }

    androidx.compose.material3.Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Lucide.Server, contentDescription = "Retour")
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = TetherTextPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "connexion") {
                Block(title = "Connexion") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        InfoLine("Adresse", state.host.ifBlank { "non renseignée" })
                        InfoLine("Répertoire", state.directory.ifBlank { "non renseigné" })
                        StatusRow(
                            checking = state.checking,
                            reachable = state.reachable,
                            version = state.version,
                        )
                        ActionRow(
                            label = if (state.checking) "Vérification…" else "Vérifier",
                            icon = Lucide.RefreshCw,
                            enabled = !state.checking,
                            onClick = viewModel::check,
                        )
                    }
                }
            }

            state.error?.let { message ->
                item(key = "error") {
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
                        Text(message, style = MaterialTheme.typography.bodySmall, color = TetherAlert)
                    }
                }
            }

            item(key = "explorer") {
                Block(title = "Explorer") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ActionRow(
                            label = "Inventaire du serveur",
                            detail = "MCP, skills, commandes, plugins",
                            icon = Lucide.Server,
                            onClick = onOpenServer,
                        )
                        ActionRow(
                            label = "Statistiques",
                            detail = "Coût, tokens, activité, modèles",
                            icon = Lucide.Activity,
                            onClick = onOpenStats,
                        )
                        ActionRow(
                            label = "Arbres de travail",
                            detail = "Essayer sans risquer ton dépôt",
                            icon = Lucide.GitBranch,
                            onClick = onOpenWorktrees,
                        )
                        ActionRow(
                            label = "Fichiers",
                            detail = "Vérifier un chemin, lire un fichier",
                            icon = Lucide.FolderOpen,
                            onClick = onOpenFiles,
                        )
                        ActionRow(
                            label = "À propos",
                            detail = "Version, licence, diagnostics",
                            icon = Lucide.Info,
                            onClick = onOpenAbout,
                        )
                    }
                }
            }

            item(key = "account") {
                Block(title = "Compte") {
                    ActionRow(
                        label = "Se déconnecter",
                        detail = "Efface l'adresse et le mot de passe de ce téléphone",
                        icon = Lucide.LogOut,
                        tint = TetherAlert,
                        onClick = { confirmDisconnect = true },
                    )
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Se déconnecter ?", color = TetherTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = "L'adresse et le mot de passe seront effacés de ce téléphone.",
                        color = TetherTextSecondary,
                    )
                    Text(
                        // ⚠️ On precise ce qui n'est PAS touche : la crainte naturelle est de
                        // perdre ses conversations. Les rassurer explicitement evite un refus
                        // par precaution.
                        text = "Tes sessions restent sur le serveur opencode : rien n'est supprimé.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    viewModel.disconnect()
                }) { Text("Se déconnecter", color = TetherAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text("Annuler", color = TetherTextSecondary)
                }
            },
            containerColor = TetherSurface,
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = TetherDataStyle, color = TetherTextSecondary)
        Text(
            text = value,
            style = TetherDataStyle,
            color = TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * L'etat verifie, dit **en mots**.
 *
 * ⚠️ Trois cas distincts : en cours, joignable (avec la version — la preuve qu'on a vraiment
 * parle au serveur), injoignable. Un simple voyant de couleur ne dirait pas laquelle des trois,
 * et l'utilisateur ne saurait pas s'il doit attendre ou agir.
 */
@Composable
private fun StatusRow(checking: Boolean, reachable: Boolean?, version: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        when {
            checking -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = TetherAccent,
                )
                Text("Vérification…", style = TetherDataStyle, color = TetherTextSecondary)
            }
            reachable == true -> {
                Text(
                    text = version?.let { "connecté · opencode $it" } ?: "connecté",
                    style = TetherDataStyle,
                    color = TetherAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            reachable == false -> {
                Text(
                    text = "injoignable",
                    style = TetherDataStyle,
                    color = TetherAlert,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            else -> Text("non vérifié", style = TetherDataStyle, color = TetherTextSecondary)
        }
    }
}

/**
 * Une action de reglages : libelle, detail optionnel, chevron implicite par la forme.
 *
 * ⚠️ Le **detail** n'est pas decoratif : « Statistiques » seul ne dit pas ce qu'on y trouve.
 * Dans un ecran qui ne contient que des listes, chaque ligne doit annoncer sa destination.
 */
@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    detail: String? = null,
    tint: androidx.compose.ui.graphics.Color = TetherTextPrimary,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            // ⚠️ 48 dp : c'est la cible de navigation des réglages, présente une douzaine de fois.
            // Une hauteur de ~40 dp serait juste sous le seuil, donc systématiquement ratée au
            // pouce — et rien ne le signalerait.
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                fontWeight = FontWeight.Medium,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextMuted,
                )
            }
        }
    }
}
