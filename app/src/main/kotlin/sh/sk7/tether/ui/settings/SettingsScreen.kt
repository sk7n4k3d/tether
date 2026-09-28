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
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Shuffle
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.ShieldAlert
import com.composables.icons.lucide.TriangleAlert
import org.unifiedpush.android.connector.UnifiedPush
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
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import sh.sk7.tether.ui.theme.Accent
import sh.sk7.tether.ui.findActivity
import sh.sk7.tether.ui.i18n.Langues
import sh.sk7.tether.ui.theme.AppearanceViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

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
    /** L'appairage d'un appareil : autoriser les notifications, en scannant un QR. */
    onOpenPairing: () -> Unit = {},
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
                title = { Text(stringResource(R.string.reglages_00d632)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Lucide.Server, contentDescription = stringResource(R.string.retour_e5befb))
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
                Block(title = stringResource(R.string.connexion_a33c58)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        InfoLine("Adresse", state.host.ifBlank { stringResource(R.string.non_renseignee_183c75) })
                        InfoLine("Répertoire", state.directory.ifBlank { stringResource(R.string.non_renseigne_bc09cb) })
                        StatusRow(
                            checking = state.checking,
                            reachable = state.reachable,
                            version = state.version,
                        )
                        ActionRow(
                            label = if (state.checking) stringResource(R.string.verification_30a679) else "Vérifier",
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
                Block(title = stringResource(R.string.explorer_8b3ee4)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ActionRow(
                            label = stringResource(R.string.inventaire_serveur_76b197),
                            detail = stringResource(R.string.mcp_skills_commandes_2129b1),
                            icon = Lucide.Server,
                            onClick = onOpenServer,
                        )
                        ActionRow(
                            label = stringResource(R.string.statistiques_fdce30),
                            detail = stringResource(R.string.cout_tokens_activite_6640f0),
                            icon = Lucide.Activity,
                            onClick = onOpenStats,
                        )
                        ActionRow(
                            label = stringResource(R.string.arbres_travail_e006dc),
                            detail = stringResource(R.string.essayer_sans_risquer_4515c3),
                            icon = Lucide.GitBranch,
                            onClick = onOpenWorktrees,
                        )
                        ActionRow(
                            label = stringResource(R.string.fichiers_23a9d9),
                            detail = stringResource(R.string.verifier_chemin_lire_e96d96),
                            icon = Lucide.FolderOpen,
                            onClick = onOpenFiles,
                        )
                        ActionRow(
                            label = stringResource(R.string.appairer_appareil_3c7d18),
                            detail = stringResource(R.string.appairer_appareil_detail_84f2ab),
                            icon = Lucide.QrCode,
                            onClick = onOpenPairing,
                        )
                        ActionRow(
                            label = stringResource(R.string.propos_5345ad),
                            detail = stringResource(R.string.version_licence_diagnostics_c0d170),
                            icon = Lucide.Info,
                            onClick = onOpenAbout,
                        )
                    }
                }
            }

            item(key = "notifications") {
                NotificationsSection()
            }

            item(key = "apparence") {
                val appearance = hiltViewModel<AppearanceViewModel>()
                val accent by appearance.store.accent.collectAsState(initial = Accent.parDefaut)
                val langue by appearance.store.langue.collectAsState(initial = null)
                val scope = rememberCoroutineScope()
                val contexte = LocalContext.current
                val activite = contexte.findActivity()
                AppearanceSection(
                    accent = accent,
                    langue = langue,
                    onAccent = { scope.launch { appearance.store.choisirAccent(it) } },
                    onLangue = { code ->
                        scope.launch {
                            appearance.store.choisirLangue(contexte, code)
                            // ⚠️ On dit aussi au systeme, pour qu'il sache : sans cela
                            // l'app n'apparait pas dans Reglages > Applications > Langues,
                            // et un changement fait depuis les reglages d'Android ne
                            // recharge rien.
                            Langues.declarerAuSysteme(contexte, code)
                            // La langue ne se remplace pas dans l'arbre : elle s'applique
                            // a la creation du contexte, donc il faut recreer l'activite.
                            activite?.recreate()
                        }
                    },
                )
            }

            item(key = "account") {
                Block(title = stringResource(R.string.compte_c45740)) {
                    ActionRow(
                        label = stringResource(R.string.deconnecter_ea36fa),
                        detail = stringResource(R.string.efface_adresse_mot_e2a9e9),
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
            title = { Text(stringResource(R.string.deconnecter_4ecfd5), color = TetherTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.adresse_mot_passe_1240c0),
                        color = TetherTextSecondary,
                    )
                    Text(
                        // ⚠️ On precise ce qui n'est PAS touche : la crainte naturelle est de
                        // perdre ses conversations. Les rassurer explicitement evite un refus
                        // par precaution.
                        text = stringResource(R.string.tes_sessions_restent_73b0e1),
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextMuted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    viewModel.disconnect()
                }) { Text(stringResource(R.string.deconnecter_ea36fa), color = TetherAlert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.annuler_49ba32), color = TetherTextSecondary)
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
                    color = LocalAccent.current,
                )
                Text(stringResource(R.string.verification_30a679), style = TetherDataStyle, color = TetherTextSecondary)
            }
            reachable == true -> {
                Text(
                    text = version?.let {
                        stringResource(R.string.connecte_opencode_04883d, it)
                    } ?: stringResource(R.string.t_connecte_75c661),
                    style = TetherDataStyle,
                    color = LocalAccent.current,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            reachable == false -> {
                Text(stringResource(R.string.injoignable_3ec16a),
                    style = TetherDataStyle,
                    color = TetherAlert,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            else -> Text(stringResource(R.string.non_verifie_22b05b), style = TetherDataStyle, color = TetherTextSecondary)
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
    // Le selecteur de distributeur est une boite de dialogue **systeme** : elle vit dans
    // une autre tache. `choosing` sert a ne pas etoffer le bouton pendant qu'elle est
    // ouverte — sinon un second tap la relancerait par-dessus la premiere.
    var choosing by remember { mutableStateOf(false) }

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
            Res.of(R.string.notifications_autorisees_31aaf5)
        } else {
            Res.of(R.string.permission_refusee_alertes_527909)
        }
    }

    // ⚠️ `getString` et non `stringResource` : le résolveur de `describePushStatus` est
    // un `(Int) -> String` **ordinaire**, volontairement, pour que la fonction reste
    // pure et appelable depuis un `Service` ou un test. Un lambda `@Composable` n'y
    // entrerait pas — et leVersions `stringResource` disponible ici donnerait un
    // résolveur non composable, ce qui ne compile pas.
    val verdict = describePushStatus(status) { id -> context.getString(id) }

    Block(title = stringResource(R.string.notifications_753a22)) {
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
                    label = stringResource(R.string.autoriser_notifications_e58324),
                    detail = stringResource(R.string.android_bloque_sans_4caf47),
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
                        color = LocalAccent.current,
                    )
                    Text(stringResource(R.string.enregistrement_e7d5f2), style = TetherDataStyle, color = TetherTextSecondary)
                }
            } else if (verdict.retryable) {
                ActionRow(
                    label = if (verdict.kind == PushStateKind.Ready) "Reconnecter" else stringResource(R.string.connecter_fedf24),
                    detail = stringResource(R.string.enregistre_app_aupres_b199ca),
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

            // ⚠️ Le choix du distributeur est propose meme quand tout fonctionne.
            // L'endpoint est une **capacite d'ecriture** : changer de distributeur, c'est
            // donner a un autre service le droit de pousser sur ce telephone. C'est
            // justement pour ca que ce n'est pas une action cachee dans un menu — et c'est
            // aussi pour ca qu'il faut pouvoir revenir en arriere si le nouveau est pire.
            //
            // Le selecteur est celui du **connecteur UnifiedPush** lui-meme : c'est lui qui
            // connait les distributeurs installes, et les-router soi-meme obligerait a
            // redescouvrir le systeme d'intents a chaque changement de version.
            ActionRow(
                label = stringResource(R.string.changer_distributeur_7f68b4),
                detail = status.distributor?.let { stringResource(R.string.actuel_5d605c, it) }
                    ?: stringResource(R.string.aucun_choisissez_recoit_637162),
                icon = Lucide.Shuffle,
                onClick = {
                    choosing = true
                    UnifiedPush.tryPickDistributor(context) { picked ->
                        choosing = false
                        // ⚠️ `false` signifie « l'utilisateur a referme sans choisir », pas
                        // « echec ». Les distinguer afficherait un avertissement sur un geste
                        // parfaitement normal.
                        status = pushStatus(context)
                        if (!picked) return@tryPickDistributor
                        // Changer de distributeur invalide l'ancien endpoint : il faut
                        // refaire l'enregistrement, sinon le serveur pousserait vers un
                        // point d'acces que personne n'ecoute plus.
                        requestPushRegistration(context) { result ->
                            messageIsError = result != PushRegistrationResult.Requested
                            message = registrationMessage(result)
                        }
                    }
                },
                enabled = !choosing,
            )

            if (choosing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = LocalAccent.current,
                    )
                    Text(stringResource(R.string.selection_d7eeed), style = TetherDataStyle, color = TetherTextSecondary)
                }
            }

            ActionRow(
                label = stringResource(R.string.tester_notification_952557),
                detail = stringResource(R.string.verifie_affichage_sans_cf880a),
                icon = Lucide.Send,
                onClick = {
                    // ⚠️ On affiche meme si l'app est au premier plan (c'est un test demande).
                    // ⚠️ `showTest` rend le fait : sans permission, `notify()` ne leve pas et rien
                    // ne s'affiche — on le dit, on ne laisse pas un bouton muet.
                    val shown = TetherNotifier.showTest(
                        context,
                        Res.of(R.string.notification_test_depuis_ef9f58),
                    )
                    messageIsError = !shown
                    message = if (shown) {
                        Res.of(R.string.notification_test_envoyee_527a82)
                    } else {
                        Res.of(R.string.rien_afficher_autorise_629e66)
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
                    Text(stringResource(R.string.aucune_application_distributrice_b51fea) +
                              " " + stringResource(R.string.notifications_peuvent_arriver_925684),
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
        PushTone.Ready -> LocalAccent.current
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
