package sh.sk7.tether.ui.sessions

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    modifier: Modifier = Modifier,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val create by viewModel.create.collectAsStateWithLifecycle()

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
        Box(Modifier.padding(padding).fillMaxSize()) {
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
                        title = "Connexion impossible",
                        body = current.message,
                        action = "Réessayer",
                        onAction = viewModel::refresh,
                    )
                }
                is SessionListUiState.Loaded -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(current.items, key = { it.id }) { item ->
                        SessionCard(item = item, onClick = { onOpenSession(item.id) })
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
