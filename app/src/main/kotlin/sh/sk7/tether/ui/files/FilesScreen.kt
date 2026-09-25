package sh.sk7.tether.ui.files

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import sh.sk7.tether.data.api.FsEntryDto
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **L'explorateur de fichiers du serveur, consultable depuis le telephone.**
 *
 * ### Pourquoi cet ecran, et pas un simple champ de saisie de chemin
 * ⚠️ Verifier un chemin **avant** de l'envoyer est le geste qui evite un aller-retour de
 * conversation pour une faute de frappe. Un champ de saisie ne dit pas si le chemin existe ; cet
 * ecran le montre. De meme, lire un fichier permet de voir **ce que l'agent verra** — pas ce qu'on
 * croit avoir ecrit.
 *
 * ### Les deux modes, et pourquoi ils ne se melangent pas
 *  - **parcourir** : les enfants directs d'un dossier, avec le chemin courant toujours visible ;
 *  - **chercher** : une recherche recursive par nom.
 *
 * ⚠️ Quand une recherche est en cours, on **cache l'arborescence**. Afficher les deux reviendrait
 * a demander a l'utilisateur de deviner lesquels des fichiers listes sont le dossier courant et
 * lesquels viennent de la recherche — deux questions differentes dans la meme colonne.
 */
@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    // ⚠️ Deux recherches, deux intentions, et l'utilisateur doit pouvoir les distinguer :
    //  - le **filtre** agit en temps reel sur le dossier affiche (aucun appel reseau) ;
    //  - la **recherche recursive** est un geste explicite (bouton), parce que `find` balaie tout
    //    le repertoire cote serveur — la declencher par touche ferait un balayage par caractere.
    val term = query.trim()
    val filtered = remember(state.entries, term) {
        if (term.isBlank()) state.entries
        else state.entries.filter { displayName(it.path).contains(term, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ------------------------------------------------ CHEMIN COURANT + RECHERCHE
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CurrentPathBar(
                path = state.path,
                canGoUp = !state.isRoot,
                onUp = viewModel::up,
            )
            SearchField(value = query, onValueChange = { query = it })
        }

        if (term.isNotBlank()) {
            RecursiveSearchSection(
                query = term,
                localCount = filtered.size,
                onOpen = { path -> viewModel.load(path.substringBeforeLast('/', "")) },
                viewModel = viewModel,
            )
        }

        BrowseSection(state = state, entries = filtered, term = term, viewModel = viewModel)
    }
}

/**
 * La barre du chemin courant, avec le bouton de remontee.
 *
 * ⚠️ Le chemin affiche est **relatif au repertoire configure** : c'est ce que le serveur attend,
 * et afficher un absolu laisserait croire qu'on peut en saisir un — or un chemin absolu est refuse
 * par le serveur (`404 FileNotFoundError`, mesure).
 */
@Composable
private fun CurrentPathBar(path: String, canGoUp: Boolean, onUp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(start = Spacing.xs, end = Spacing.md, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // ⚠️ `IconButton` : Material garantit une cible de **48 dp** meme si l'icone fait 18 dp.
        // C'est le seul controle de navigation de cet ecran, il doit etre immanquable.
        IconButton(onClick = onUp, enabled = canGoUp) {
            Icon(
                imageVector = Lucide.ArrowLeft,
                contentDescription = if (canGoUp) "Dossier parent" else "Déjà à la racine",
                tint = if (canGoUp) TetherTextPrimary else TetherTextSecondary.copy(alpha = 0.9f),
            )
        }
        Text(
            text = path.ifBlank { "racine du répertoire" },
            style = TetherDataStyle,
            color = if (path.isBlank()) TetherTextSecondary else TetherTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrowseSection(
    state: FilesUiState,
    entries: List<FsEntryDto>,
    term: String,
    viewModel: FilesViewModel,
) {
    when {
        state.loadingFile -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = TetherAccent)
        }
        state.fileTooBig != null -> TooBigNotice(state.fileTooBig, onClose = viewModel::closeFile)
        state.openFile != null -> FileViewer(state.openFile, onClose = viewModel::closeFile)
        state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator(color = TetherAccent)
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            state.error?.let { message ->
                item(key = "error") { ErrorLine(message) }
            }
            if (entries.isEmpty()) {
                item(key = "empty") {
                    // ⚠️ Un dossier vide et un filtre sans resultat sont deux faits **differents**,
                    // et la phrase doit le dire : « ce dossier est vide » n'est pas la meme chose
                    // que « aucun fichier ne correspond a ta frappe ». Les confondre ferait croire
                    // a un dossier vide alors qu'un fichier existe sous un autre nom.
                    Text(
                        text = if (term.isBlank()) {
                            "Ce dossier est vide."
                        } else {
                            "Aucun fichier de ce dossier ne contient « $term »."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextSecondary,
                    )
                }
            }
            items(entries, key = { it.path }) { entry ->
                EntryRow(entry = entry, onClick = { viewModel.open(entry) })
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FsEntryDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entryIcon(entry),
            contentDescription = null,
            tint = if (entry.isDirectory) TetherAccent else TetherTextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = displayName(entry.path),
            style = TetherDataStyle,
            color = TetherTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * L'icone d'une entree — **file** et **dossier** se distinguent par la forme, pas seulement la
 * couleur.
 *
 * ⚠️ Une difference uniquement coloree serait invisible pour un daltonien **et** en mode contraste
 * eleve. La forme reste.
 */
private fun entryIcon(entry: FsEntryDto): ImageVector = when {
    entry.isDirectory -> Lucide.Folder
    // ⚠️ Heuristique de code par extension : elle ne decide **que** de l'icone. Elle n'a aucune
    // consequence sur ce qu'on envoie ni sur ce qu'on lit — se tromper donne une icone de moins,
    // ce qui est un echec benin.
    CODE_EXTENSIONS.any { displayName(entry.path).endsWith(it, ignoreCase = true) } -> Lucide.FileCode
    else -> Lucide.FileText
}

private val CODE_EXTENSIONS = listOf(
    ".kt", ".kts", ".java", ".ts", ".tsx", ".js", ".py", ".rs", ".go", ".c", ".cpp", ".h",
    ".sh", ".json", ".yaml", ".yml", ".toml", ".xml", ".html", ".css", ".sql", ".md",
)

/** Le nom seul, sans le chemin complet (le serveur rend le chemin relatif). */
private fun displayName(path: String): String = path.trimEnd('/').substringAfterLast('/')

/**
 * Le contenu d'un fichier, **en texte selectionnable et monospace**.
 *
 * ⚠️ Un fichier tronque le **dit**. Afficher 512 Ko puis laisser croire que c'est tout le fichier
 * serait un mensonge silencieux — et l'utilisateur prendrait une decision (modifier, envoyer) sur
 * une vue partielle.
 */
@Composable
private fun FileViewer(file: OpenFile, onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = file.path,
                style = TetherDataStyle,
                color = TetherTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // ⚠️ Cible tactile : `minimumInteractiveComponentSize` etendu par le `padding` ; on
            // vise une croix au doigt, donc on ne compte pas sur la taille de l'icone.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClose)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Fermer le fichier"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    tint = TetherTextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (file.binary) {
            Text(
                text = "Ce fichier n'est pas du texte (binaire ou encodage non UTF-8). " +
                    "Son contenu ne peut pas être affiché ici.",
                style = MaterialTheme.typography.bodySmall,
                color = TetherAlert,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        } else {
            if (file.truncated) {
                Text(
                    text = "Affichage tronqué (fichier volumineux).",
                    style = TetherDataStyle,
                    color = TetherAlert,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            Text(
                text = file.text,
                style = TetherDataStyle.copy(fontFamily = FontFamily.Monospace),
                color = TetherTextPrimary,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
    }
}

@Composable
private fun TooBigNotice(tooBig: FileTooBig, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = "Fichier trop volumineux pour l'affichage",
            style = MaterialTheme.typography.titleSmall,
            color = TetherAlert,
        )
        Text(
            text = "${tooBig.path} fait ${tooBig.bytes / 1024} Ko. " +
                "La limite d'affichage est de ${FilesViewModel.MAX_OPEN_BYTES / 1024} Ko.",
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
        )
        Text(
            text = "Fermer",
            style = TetherDataStyle,
            color = TetherAccent,
            modifier = Modifier
                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                // ⚠️ 48 dp : seul point de sortie de cet état.
                .heightIn(min = TetherDimensions.touchTarget)
                .clickable(onClick = onClose)
                .padding(horizontal = Spacing.sm)
                .semantics { role = Role.Button },
        )
    }
}

@Composable
private fun ErrorLine(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(TetherAlert.copy(alpha = 0.12f))
            .padding(Spacing.md),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = TetherAlert)
    }
}

/**
 * **La recherche recursive** (`GET /api/fs/find`), declenchee explicitement.
 *
 * ⚠️ Elle n'est lancee qu'a la **demande** (« Chercher partout »), pas a chaque frappe. Mesure :
 * `find` balaie tout le repertoire cote serveur ; la declencher par touche ferait un balayage
 * complet a chaque caractere, pour un resultat qu'on n'a pas encore fini de taper.
 */
@Composable
private fun RecursiveSearchSection(
    query: String,
    localCount: Int,
    onOpen: (String) -> Unit,
    viewModel: FilesViewModel,
) {
    var results by remember { mutableStateOf<List<FsEntryDto>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                .background(TetherAccent.copy(alpha = 0.16f))
                .heightIn(min = TetherDimensions.touchTarget)
                .clickable(enabled = !searching) {
                    searching = true
                    error = null
                    viewModel.search(
                        query = query,
                        onResult = {
                            results = it
                            searching = false
                        },
                        onError = {
                            error = it
                            searching = false
                        },
                    )
                }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .semantics { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (searching) {
                CircularProgressIndicator(
                    color = TetherAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Icon(
                    Lucide.Search,
                    contentDescription = null,
                    tint = TetherAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = "Chercher « $query » partout ($localCount ici)",
                style = TetherDataStyle,
                color = TetherAccent,
            )
        }

        error?.let { ErrorLine(it) }

        results?.let { found ->
            if (found.isEmpty()) {
                Text(
                    text = "Aucun fichier pour « $query » dans tout le répertoire.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextSecondary,
                )
            } else {
                Block(title = "${found.size} résultat${if (found.size > 1) "s" else ""}") {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(found, key = { it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onOpen(entry.path) }
                                    .padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    entryIcon(entry),
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) TetherAccent else TetherTextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = entry.path,
                                    style = TetherDataStyle,
                                    color = TetherTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = Spacing.sm),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Le champ de recherche de l'explorateur.
 *
 * ⚠️ Le placeholder dit **dans quoi** on cherche (le dossier courant) : « Rechercher » tout court
 * laisserait croire a une recherche globale, alors que le filtrage local ne porte que sur le
 * dossier affiche. La recherche globale est un second geste, explicite.
 */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    Row(
        modifier = Modifier
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
                    text = "Filtrer ce dossier, ou chercher partout",
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
                // ⚠️ `fillMaxWidth` obligatoire : sans lui, le champ vide n'occupe que la largeur
                // de son texte et le tap tombe sur le placeholder non cliquable. Bug deja
                // rencontre sur la recherche de sessions et de conversation.
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(TetherDimensions.touchTarget)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable { onValueChange("") }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Effacer la recherche"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    tint = TetherTextSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
