package sh.sk7.tether.ui.sessions

import sh.sk7.tether.domain.model.FleetState

import sh.sk7.tether.domain.model.Activity
import sh.sk7.tether.ui.background.BackgroundSection

import sh.sk7.tether.ui.theme.TetherDataStyle

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDimensions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import sh.sk7.tether.ui.theme.Spacing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.WifiOff
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onOpenSettings: () -> Unit,
    onOpenSession: (String) -> Unit,
    /** Statistiques d'usage. */
    onOpenStats: () -> Unit = {},
    /** Inventaire du serveur (MCP, skills, permissions). */
    onOpenServer: () -> Unit = {},
    /** Approbations en attente. */
    onOpenPermissions: () -> Unit = {},
    /**
     * L'ecran hors-connexion : il explique la panne et propose d'agir.
     *
     * ⚠️ On y mene depuis l'erreur de la liste, parce que c'est **la** que la panne se decouvre.
     * L'erreur de liste dit le fait ; l'ecran dedie dit quoi faire. Un simple bouton « reessayer »
     * laisserait l'utilisateur boucler sans jamais pouvoir corriger son adresse.
     */
    onOpenOffline: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val create by viewModel.create.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val pendingApprovals by viewModel.pendingApprovals.collectAsStateWithLifecycle()
    val pinnedIds by viewModel.pinnedIds.collectAsStateWithLifecycle()
    val fleet by viewModel.fleet.collectAsStateWithLifecycle()
    // ⚠️ La requete vit **hors** de la branche `Loaded` : si elle mourait au passage a l'etat
    // d'erreur ou de chargement, un rafraichissement rate effacerait la recherche en cours.
    var query by remember { mutableStateOf("") }
    // Dialogues d'action : la session visee, ou null. L'etat vit ici (et non dans la branche
    // `Loaded`) parce que les boites sont affichees **hors** du `when` : si la liste passe par
    // un etat transitoire pendant l'action, le dialogue ne doit pas disparaitre sous le doigt.
    // ⚠️ La teinte du bouton d'approbations dit s'il y a quelque chose a faire. Un bouton
    // toujours identique obligerait a l'ouvrir pour savoir — c'est-a-dire a faire le travail
    // que le badge est cense epargner.
    val pendingTint = if (pendingApprovals > 0) TetherAlert else TetherTextPrimary
    var renaming by remember { mutableStateOf<SessionItem?>(null) }
    var deleting by remember { mutableStateOf<SessionItem?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = TetherTextPrimary,
                ),
                actions = {
                    IconButton(onClick = onOpenStats) {
                        Icon(Lucide.Activity, contentDescription = "Statistiques")
                    }
                    // ⚠️ L'acces aux approbations est dans la barre principale, pas enfoui dans
                    // les reglages : c'est **la** raison d'etre d'une app compagne (une session
                    // peut rester bloquee des heures sur une demande non vue). Un ecran qu'il
                    // faut aller chercher ne repond pas a une urgence.
                    IconButton(onClick = onOpenPermissions) {
                        Icon(
                            imageVector = Lucide.ShieldCheck,
                            contentDescription = "Approbations en attente",
                            tint = pendingTint,
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Lucide.RefreshCw, contentDescription = "Recharger")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Lucide.Settings, contentDescription = "Réglages")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state is SessionListUiState.Loaded) {
                Button(onClick = viewModel::openCreate) {
                    Icon(Lucide.Plus, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Session")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // La recherche vit **au-dessus** de la liste, pas dans les reglages : retrouver une
            // session est un geste du quotidien, pas une configuration.
            SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
            // ⚠️ L'en-tête de flotte répond à la question qu'on se pose en ouvrant l'app :
            // « est-ce que c'est calme ? ». Il est placé AVANT la liste, parce que c'est la
            // première chose qu'on veut savoir — et qu'il tient en un mot.
            if (fleet.hasAnything || fleet.polledAt != null) {
                FleetHeader(fleet = fleet)
            }
            // ⚠️ Le travail de fond est **au-dessus** de la liste : c'est ce qu'on vient chercher
            // quand on se demande pourquoi la machine est lente, et ça ne doit pas demander de
            // défiler 450 sessions pour le trouver.
            BackgroundSection(fleet = fleet)
        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                SessionListUiState.Loading -> Centered {
                    CircularProgressIndicator(color = TetherAccent)
                }
                SessionListUiState.NeedsSetup -> Centered {
                    InfoBlock(
                        icon = { Icon(Lucide.Server, contentDescription = null, tint = TetherAccent) },
                        title = "Aucun serveur configuré",
                        body = "Renseigne l'adresse du serveur opencode et le mot de passe.",
                        action = "Ouvrir les réglages",
                        onAction = onOpenSettings,
                    )
                }
                is SessionListUiState.Empty -> Centered {
                    InfoBlock(
                        icon = { Icon(Lucide.Server, contentDescription = null, tint = TetherTextSecondary) },
                        title = "Aucune session",
                        body = "Aucune session pour ${current.directory}.",
                    )
                }
                is SessionListUiState.Error -> Centered {
                    InfoBlock(
                        icon = { Icon(Lucide.WifiOff, contentDescription = null, tint = TetherAlert) },
                        // ⚠️ Le titre **qualifie la panne** au lieu de dire « connexion
                        // impossible » a tout le monde : « refusés » et « injoignable » n'appellent
                        // pas le meme geste, et les confondre envoie chercher au mauvais endroit.
                        title = if (isUnauthorized(current.message)) {
                            "Identifiants refusés"
                        } else {
                            "Serveur injoignable"
                        },
                        body = current.message,
                        action = "Ouvrir l'écran hors connexion",
                        onAction = onOpenOffline,
                    )
                }
                is SessionListUiState.Loaded -> {
                    val items = current.items
                    val refreshing = current.refreshing
                    // ⚠️ Sous-agents **REPLIES PAR DEFAUT** : avec 297 sous-agents sur 437
                    // sessions, les afficher tous noie les sessions principales. On retient
                    // donc l'ensemble des parents OUVERTS (et non l'inverse), pour que le
                    // defaut soit « replie » sans avoir a pre-remplir la liste.
                    var expandedParents by remember { mutableStateOf(emptySet<String>()) }
                    // ⚠️ ORDRE : on filtre par RECHERCHE **avant** de replier les sous-agents.
                    // L'inverse perdrait les enfants d'un parent qui ne matche pas mais dont un
                    // enfant matche — exactement le cas « je cherche le nom d'un sous-agent ».
                    val searched = remember(items, query) { SessionSearch.filter(items, query) }
                    // Liste rendue : un parent replie masque ses enfants.
                    val folded = remember(searched, expandedParents) {
                        searched.filter { item ->
                            item.parentID == null || item.parentID in expandedParents
                        }
                    }
                    // ⚠️ Les epinglees remontent EN TETE. Sans ce tri, epingler ne servirait qu'a
                    // afficher une punaise : l'interet est de retrouver vite une session qu'on
                    // suit. Le tri est **stable** (les non-epinglees gardent leur ordre par date)
                    // et ne separe jamais un parent de ses enfants ouverts.
                    val visible = remember(folded, pinnedIds) {
                        folded.sortedByDescending { it.id in pinnedIds }
                    }

                    // Rafraichissement au **geste** : tirer vers le bas. C'est le geste naturel
                    // sur mobile, et il ne remplace rien — le bouton de la barre reste, pour
                    // ceux qui ne connaissent pas le geste.
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = viewModel::startRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // ⚠️ **Etat vide de recherche**, distinct de « aucune session » : dire
                        // « aucune session » alors qu'on vient de taper un filtre est un
                        // mensonge, et l'utilisateur chercherait pourquoi ses sessions ont
                        // disparu. On dit sur quoi on a cherche, et on propose d'effacer.
                        if (visible.isEmpty() && query.isNotBlank()) {
                            NoSearchResult(query = query, onClear = { query = "" })
                        } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = 96.dp,
                            ),
                            // ⚠️ AUCUN espacement vertical ici : un `spacedBy` creerait des trous
                            // que le rail ne traverserait pas, coupant le fil entre les lignes.
                            // L'aeration vit dans le padding interne de chaque SessionRow.
                        ) {
                            // En-tete : ce que l'app consomme. Toujours visible, jamais demande.
                            current.usage?.let { usage ->
                                item(key = "usage-header") { UsageHeader(usage) }
                            }

                            items(visible, key = { it.id }) { item ->
                                SessionRow(
                                    item = item,
                                    // ⚠️ L'état vient du détenteur partagé, jamais recalculé ici :
                                    // c'est ce qui garantit que la liste et le chat ne diront pas
                                    // deux choses différentes de la même session.
                                    activity = fleet.bySession[item.id]?.activity,
                                    // Chevron : ouvre/ferme les sous-agents. Present seulement
                                    // si la session en a (sinon aucun controle inutile).
                                    onToggleSubs = if (item.childCount > 0) {
                                        {
                                            expandedParents = if (item.id in expandedParents) {
                                                expandedParents - item.id
                                            } else {
                                                expandedParents + item.id
                                            }
                                        }
                                    } else null,
                                    subsExpanded = item.id in expandedParents,
                                    onClick = { onOpenSession(item.id) },
                                    // Options de session : chaque action ouvre une route qui
                                    // existe cote serveur (PATCH, fork, interrupt, compact,
                                    // DELETE).
                                    onPin = { viewModel.togglePin(item.id) },
                                    pinned = item.id in pinnedIds,
                                    onRename = { renaming = item },
                                    onFork = { viewModel.forkSession(item.id) },
                                    onInterrupt = { viewModel.interruptSession(item.id) },
                                    onCompact = { viewModel.compactSession(item.id) },
                                    onDelete = { deleting = item },
                                    // ⚠️ Animation de depliage : les enfants apparaissent
                                    // avec un glissement, pas d'un coup. C'est ce qui rend un
                                    // arbre lisible plutot que brutal.
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
        }
}
    }

    if (create.visible && state is SessionListUiState.Loaded) {
        val loaded = state as SessionListUiState.Loaded
        CreateSessionDialog(
            form = create,
            models = loaded.models,
            agents = loaded.agents,
            onTitleChange = viewModel::onCreateTitleChange,
            onModelChange = viewModel::onCreateModelChange,
            onAgentChange = viewModel::onCreateAgentChange,
            onConfirm = viewModel::createSession,
            onDismiss = viewModel::dismissCreate,
        )
    }

    renaming?.let { item ->
        RenameSessionDialog(
            item = item,
            onConfirm = { title ->
                renaming = null
                viewModel.renameSession(item.id, title)
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { item ->
        DeleteSessionDialog(
            item = item,
            onConfirm = {
                deleting = null
                viewModel.deleteSession(item.id)
            },
            onDismiss = { deleting = null },
        )
    }

    sessionError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearSessionError,
            title = { Text("Action impossible", color = TetherTextPrimary) },
            text = { Text(message, color = TetherTextSecondary) },
            confirmButton = {
                TextButton(onClick = viewModel::clearSessionError) {
                    Text("Fermer", color = TetherAccent)
                }
            },
            containerColor = TetherSurface,
        )
    }
}

/**
 * Renommer : une boite d'une seule ligne, pre-remplie avec le titre actuel.
 *
 * ⚠️ L'action n'est **pas** appliquee a la volee : le titre est une donnee serveur, on attend
 * la validation. Valider avec un titre inchange ne fait rien (aucun appel reseau inutile).
 */
@Composable
private fun RenameSessionDialog(
    item: SessionItem,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(item.id) { mutableStateOf(item.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer la session", color = TetherTextPrimary) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Titre") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                enabled = draft.isNotBlank() && draft.trim() != item.title,
            ) {
                Text("Renommer", color = TetherAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = TetherTextSecondary) }
        },
        containerColor = TetherSurface,
    )
}

/**
 * Supprimer : la **seule** action irreversible de la liste, donc la seule a demander confirmation.
 *
 * ⚠️ Le titre de la session est affiche dans le corps : on supprime une session precise, pas
 * « une session ». Le libelle du bouton dit ce qui va se passer (« Supprimer »), il ne dit pas
 * « OK ».
 */
@Composable
private fun DeleteSessionDialog(
    item: SessionItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer cette session ?", color = TetherTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(item.title, color = TetherTextPrimary)
                Text(
                    text = "Cette action est définitive. La conversation et son historique " +
                        "seront perdus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherAlert,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Supprimer", color = TetherAlert) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler", color = TetherTextSecondary) }
        },
        containerColor = TetherSurface,
    )
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SessionCard(item: SessionItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TetherSurface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = TetherTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetaChip(text = RelativeTime.format(System.currentTimeMillis(), item.timestamp))
            item.agent?.let { MetaChip(text = it) }
            item.costLabel?.let { MetaChip(text = it, color = TetherAccent) }
        }
        item.modelLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetaChip(text: String, color: Color = TetherTextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun InfoBlock(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Text(title, style = MaterialTheme.typography.titleMedium, color = TetherTextPrimary)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = TetherTextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSessionDialog(
    form: CreateSessionState,
    models: List<Model>,
    agents: List<Agent>,
    onTitleChange: (String) -> Unit,
    onModelChange: (ModelRef) -> Unit,
    onAgentChange: (String?) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = onTitleChange,
                    label = { Text("Titre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EnumDropdown(
                    label = "Modèle (obligatoire)",
                    options = models.map { it.displayName() },
                    selectedIndex = models.indexOfFirst { it.toRef() == form.model },
                    onSelect = { index -> onModelChange(models[index].toRef()) },
                )
                EnumDropdown(
                    label = "Agent",
                    options = agents.map { it.name ?: it.id },
                    selectedIndex = agents.indexOfFirst { it.id == form.agent },
                    onSelect = { index -> onAgentChange(agents[index].id) },
                )
                form.error?.let {
                    Text(it, color = TetherAlert, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = form.canSubmit) {
                if (form.creating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Créer")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val expandedState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val expanded = expandedState.value
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expandedState.value = !expandedState.value },
    ) {
        OutlinedTextField(
            value = options.getOrNull(selectedIndex).orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expandedState.value = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        expandedState.value = false
                    },
                )
            }
        }
    }
}

private fun Model.displayName(): String = name ?: "${providerID.orEmpty()}/$id"

private fun Model.toRef(): ModelRef = ModelRef(id = modelID ?: id, providerID = providerID.orEmpty())

/**
 * **Le champ de recherche.**
 *
 * ### Pourquoi il reprend la surface de la barre de saisie du chat
 * ⚠️ C'est un choix de coherence : dans Tether, **une zone ou l'on ecrit a toujours la meme
 * forme** (surface posee, liseré 1 dp, pas de bordure Material, pas de label flottant). Un
 * `OutlinedTextField` ici et une surface nue dans le chat donneraient deux langages visuels pour
 * la meme action. Une app dont les composants ne se ressemblent pas parait inachevee.
 *
 * ⚠️ Le bouton d'effacement n'apparait **que s'il y a du texte** : un controle qui ne fait rien
 * est un piege a tapes.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(15.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "Rechercher une session",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TetherTextSecondary,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TetherTextPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(TetherAccent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    // ⚠️ `Search` affiche la touche « rechercher » au lieu d'« entrer » : la
                    // recherche est locale et instantanee, il n'y a rien a valider.
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                // ⚠️ **NECESSAIRE, et c'est un bug attrape en testant** : sans ce modificateur,
                // le `BasicTextField` n'occupe que la largeur de son texte — qui est vide au
                // depart. Le tap tombait alors sur le placeholder (un `Text` non cliquable), et
                // le champ ne recevait **jamais** le focus : impossible de taper quoi que ce soit.
                // `fillMaxWidth` lui donne la surface entiere a capturer.
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                imageVector = Lucide.X,
                contentDescription = "Effacer la recherche",
                tint = TetherTextSecondary,
                modifier = Modifier
                    .size(15.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

/**
 * **Aucun resultat — un etat a part entiere, pas un vide.**
 *
 * ⚠️ C'est le point que les analyses d'interfaces generiques designent comme le premier
 * marqueur d'une app « inachevee » : 92 % des interfaces generees n'ont **pas d'etat vide
 * designe**. Afficher une liste vide sans explication laisse croire a un bug de chargement.
 *
 * ⚠️ On repete la **requete** dans le message : c'est ce qui permet de reperer une faute de
 * frappe. « Aucun resultat » seul oblige a relire son propre champ.
 */
@Composable
private fun NoSearchResult(query: String, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = "Aucune session pour « $query »",
            style = MaterialTheme.typography.titleSmall,
            color = TetherTextPrimary,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = "La recherche porte sur le titre, le modèle et l'agent.",
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Text(
            text = "Effacer la recherche",
            style = TetherDataStyle,
            color = TetherAccent,
            modifier = Modifier
                .padding(top = Spacing.md)
                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                .clickable(onClick = onClear)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
    }
}

/**
 * La panne est-elle un refus d'identifiants ?
 *
 * ⚠️ On regarde les **codes** et non le texte : le message est produit par `ConnectionErrors` et
 * peut changer de formulation sans que la cause change. Un 401/403 signifie « mot de passe », le
 * reste signifie « reseau ou adresse ».
 */
private fun isUnauthorized(message: String): Boolean =
    message.contains("401") || message.contains("403")

/**
 * **L'en-tête de flotte : l'état de toute l'installation, en un mot.**
 *
 * ### Ce qu'il répond, et pourquoi il est en haut
 * La question qu'on se pose en ouvrant l'app n'est pas « quelle est la première session ? » mais
 * **« est-ce que c'est calme ? »**. Y répondre demande de parcourir mentalement 440 lignes, ce
 * que personne ne fait. Un mot en haut de l'écran le fait à notre place.
 *
 * ### Les compteurs, et pourquoi ils sont distincts
 * « 2 t'attendent » et « 3 tournent » ne se traitent pas pareil : le premier demande un geste,
 * le second demande de la patience. Les additionner en « 5 sessions » perdrait exactement
 * l'information qui décide de ce qu'on fait ensuite.
 *
 * ⚠️ **Rien n'est affiché quand tout est calme et qu'on n'a jamais interrogé le serveur.** Un
 * en-tête « calme » à l'ouverture serait une affirmation qu'on n'a pas encore les moyens de
 * faire — on ne sait pas, on n'a pas demandé.
 */
@Composable
private fun FleetHeader(fleet: FleetState) {
    // ⚠️ Instant de reference : l'ouverture de l'ecran. « Qu'est-ce qui s'est passe pendant que
    // j'etais ailleurs ? » est une question utile ; « 110 sessions jamais rouvertes » n'en est pas
    // une. La verite par session reste dans la pastille de chaque ligne.
    //
    // ⚠️ HYPOTHESE ASSUMEE : cette borne vient de l'horloge du **telephone**, alors que `idleAt`
    // vient de celle du **serveur**. Les deux machines sont synchronisees par NTP, donc l'ecart est
    // de l'ordre de la seconde. Si un jour il ne l'etait plus, l'effet serait de decaler la fenetre
    // de quelques secondes — jamais d'afficher un fait faux, puisqu'aucune donnee n'est inventee.
    // La comparaison « termine / pas vu » par session, elle, reste **entierement serveur** et ne
    // depend d'aucune horloge locale.
    val openedAt = remember { System.currentTimeMillis() }
    val since = fleet.unseenSince(openedAt)

    val (label, tint) = when (fleet.summarySince(openedAt)) {
        Activity.Waiting -> "Des décisions t'attendent" to TetherAlert
        Activity.Unseen -> "Du travail vient de se terminer" to TetherTextPrimary
        Activity.Running -> "En cours d'exécution" to TetherAccent
        Activity.Queued -> "Messages en file" to TetherTextSecondary
        Activity.Failed -> "Des tours ont échoué" to TetherAlert
        Activity.Idle -> "Calme" to TetherTextSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            fontWeight = if (fleet.summarySince(openedAt) == Activity.Waiting) {
            FontWeight.SemiBold
        } else {
            FontWeight.Normal
        },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // ⚠️ Chaque compteur n'apparaît que s'il est non nul : une ligne de « 0 » partout
            // serait du bruit, et noierait le seul chiffre qui compte.
            if (fleet.waiting.isNotEmpty()) Counter("${fleet.waiting.size} t'attend", TetherAlert)
            // ⚠️ On compte les termines **depuis l'ouverture**, pas le total : 110 « pas vu »
            // serait exact et inutilisable. Et on dit le mot « terminé », pas « pas vu » — ce
            // qu'on annonce est un evenement, pas un manquement.
            if (since.isNotEmpty()) Counter("${since.size} terminé" + if (since.size > 1) "s" else "", TetherTextPrimary)
            if (fleet.running.isNotEmpty()) Counter("${fleet.running.size} en cours", TetherAccent)
            if (fleet.queued.isNotEmpty()) Counter("${fleet.queued.size} en file", TetherTextSecondary)
            if (fleet.liveShells.isNotEmpty()) Counter("${fleet.liveShells.size} shell", TetherTextSecondary)
        }

        // ⚠️ On dit l'erreur d'interrogation au lieu de la taire : un état figé qui a l'air à jour
        // est pire qu'un état qu'on sait périmé.
        fleet.error?.let { message ->
            Text(
                text = "État non rafraîchi : $message",
                style = sh.sk7.tether.ui.theme.TetherDataStyle,
                color = TetherAlert.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Counter(label: String, tint: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = sh.sk7.tether.ui.theme.TetherDataStyle,
        color = tint,
    )
}
