package sh.sk7.tether.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.LogOut
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.TriangleAlert
import sh.sk7.tether.push.PushRegistrationResult
import sh.sk7.tether.push.PushStateKind
import sh.sk7.tether.push.PushTone
import sh.sk7.tether.push.TetherNotifier
import sh.sk7.tether.push.describePushStatus
import sh.sk7.tether.push.endpointHint
import sh.sk7.tether.push.pushStatus
import sh.sk7.tether.push.registrationMessage
import sh.sk7.tether.push.requestPushRegistration
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

            item(key = "notifications") {
                NotificationsSection()
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

/**
 * **La section Notifications : l'etat reel, puis les deux actions.**
 *
 * ### Pourquoi cette section existe
 * La chaine UnifiedPush peut echouer a **trois** endroits distincts (pas de distributeur, permission
 * refusee, pas d'endpoint), et sans cette section l'utilisateur ne le decouvre qu'en constatant
 * qu'il ne recoit rien — c'est-a-dire jamais, puisqu'il ne sait pas ce qui devrait arriver.
 *
 * ### Ce que cette section NE fait PAS
 * ⚠️ Elle n'affiche **jamais** « connecté » sur la seule presence d'un distributeur. L'etat vient
 * de [describePushStatus], qui exige les trois faits. C'est la regle du projet : un `null` ne
 * s'affiche pas comme un `0`, et un distributeur retenu n'est pas un abonnement reussi.
 *
 * ### L'activation de la permission
 * ⚠️ Sur Android 13+, sans `POST_NOTIFICATIONS`, `notify()` **ne leve pas** et rien ne s'affiche.
 * On ne demande pas la permission d'office ici : on l'explique, et le bouton propose de demander
 * quand elle manque. C'est le seul cas ou un bouton ouvre une demande systeme.
 */
@Composable
private fun NotificationsSection() {
    val context = LocalContext.current
    // ⚠️ On relit l'etat localement (`remember` + relecture), pas via le ViewModel : ces faits
    // viennent du systeme (permission, distributeur, SharedPreferences), pas du serveur opencode.
    // Les melanger au `SettingsViewModel` ferait croire qu'ils se rafraichissent avec la connexion.
    var status by remember { mutableStateOf(pushStatus(context)) }
    var registering by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // ⚠️ On retient si le message dit une erreur, pour le teinter : un « enregistrement demandé »
    // en ambre ferait croire a un echec.
    var messageIsError by remember { mutableStateOf(false) }

    // ⚠️ L'enregistrement et l'octroi de permission sont **asynchrones** : on relit l'etat au
    // retour dans l'ecran (la permission se donne dans une boite systeme, l'endpoint arrive d'un
    // service). Sans cette relecture, l'ecran afficherait un etat perime juste apres une action.
    LifecycleResumeEffect(Unit) {
        status = pushStatus(context)
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = pushStatus(context)
        messageIsError = !granted
        message = if (granted) {
            "Notifications autorisées."
        } else {
            "Permission refusée : les alertes ne s'afficheront pas."
        }
    }

    val verdict = describePushStatus(status)

    Block(title = "Notifications") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PushStateRow(
                tone = verdict.tone,
                label = verdict.label,
                detail = verdict.detail,
            )

            InfoLine("Distributeur", status.distributor ?: "aucun")
            InfoLine("Endpoint", status.endpointHint() ?: "aucun")
            InfoLine(
                "Autorisation",
                if (status.notificationsAllowed) "accordée" else "refusée",
            )

            // ⚠️ La demande de permission n'apparait QUE quand elle manque. L'afficher toujours
            // proposerait une action sans effet sur un telephone ou elle est deja accordee.
            if (!status.notificationsAllowed) {
                ActionRow(
                    label = "Autoriser les notifications",
                    detail = "Android les bloque : sans ça, rien ne s'affiche",
                    icon = Lucide.Bell,
                    tint = TetherAlert,
                    enabled = true,
                    onClick = { permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
                )
            }

            // ⚠️ « Reconnecter » n'est propose que si l'action peut aboutir
            // ([PushStateKind.NoDistributor] ne le permet pas) : un bouton qui ne peut pas marcher
            // est pire que pas de bouton.
            if (registering) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = TetherAccent,
                    )
                    Text("Enregistrement…", style = TetherDataStyle, color = TetherTextSecondary)
                }
            } else if (verdict.retryable) {
                ActionRow(
                    label = if (verdict.kind == PushStateKind.Ready) "Reconnecter" else "Se connecter",
                    detail = "Enregistre l'app auprès du distributeur",
                    icon = Lucide.RefreshCw,
                    onClick = {
                        registering = true
                        message = null
                        // ⚠️ Le callback est asynchrone : on ne suppose rien entre le clic et le
                        // resultat. C'est exactement le point que le brief signale.
                        requestPushRegistration(context) { result ->
                            registering = false
                            status = pushStatus(context)
                            messageIsError = result != PushRegistrationResult.Requested
                            message = registrationMessage(result)
                        }
                    },
                )
            }

            ActionRow(
                label = "Tester la notification",
                detail = "Vérifie l'affichage sans attendre un événement",
                icon = Lucide.Send,
                onClick = {
                    // ⚠️ On affiche meme si l'app est au premier plan (c'est un test demande).
                    // ⚠️ `showTest` rend le fait : sans permission, `notify()` ne leve pas et rien
                    // ne s'affiche — on le dit, on ne laisse pas un bouton muet.
                    val shown = TetherNotifier.showTest(
                        context,
                        "Notification de test depuis les Réglages.",
                    )
                    messageIsError = !shown
                    message = if (shown) {
                        "Notification de test envoyée."
                    } else {
                        "Rien n'a pu s'afficher : autorise les notifications."
                    }
                },
            )

            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageIsError) TetherAlert else TetherTextSecondary,
                )
            }

            // ⚠️ Avertissement explicite quand rien ne peut marcher : on explique POURQUOI plutot
            // que de laisser l'utilisateur cliquer sur des boutons sans effet.
            if (verdict.kind == PushStateKind.NoDistributor) {
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
                        imageVector = Lucide.TriangleAlert,
                        contentDescription = null,
                        tint = TetherAlert,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Aucune application distributrice (ntfy) n'est installée : " +
                            "les notifications ne peuvent pas arriver.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherAlert,
                    )
                }
            }
        }
    }
}

/**
 * **L'etat des notifications, en un mot et une phrase.**
 *
 * ⚠️ Le libelle **et** la teinte : une pastille de couleur seule ne se lit ni par un daltonien, ni
 * en contraste eleve. Le mot porte l'information, la couleur ne fait que la rendre trouvable —
 * meme regle que les statuts de serveur MCP.
 */
@Composable
private fun PushStateRow(tone: PushTone, label: String, detail: String) {
    val tint = when (tone) {
        PushTone.Ready -> TetherAccent
        PushTone.Blocked -> TetherAlert
        PushTone.Pending -> TetherTextSecondary
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = TetherDataStyle,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
        )
    }
}
