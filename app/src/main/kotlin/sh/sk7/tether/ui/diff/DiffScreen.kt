package sh.sk7.tether.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.FileDiff as FileDiffIcon
import com.composables.icons.lucide.Lucide
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherBackground
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **Les diffs : ce que l'agent a reellement change dans les fichiers.**
 *
 * ### Pourquoi cet ecran est le manque le plus gros d'un client d'agent
 * Un client de chat rend des phrases ; un agent de code ecrit des fichiers. Sans vue de diff, on
 * approuve des modifications **sans les voir** — c'est-a-dire qu'on fait confiance a un resume. La
 * vue de diff est citee comme la fonction qui distingue un vrai client opencode d'un simple miroir
 * de conversation (documentation des clients existants : « stacked/inline diff viewer »).
 *
 * ### Les choix de lecture
 *  - **une ligne par changement, coloree par son role**, pas de colonnes de numeros : sur un
 *    telephone, 4 caracteres de numeros par ligne consomment une largeur qui manque au code ;
 *  - **defilement horizontal conserve** : un diff ne se replie pas. Tronquer une ligne de code
 *    cacherait exactement le caractere qui a change, c'est-a-dire tout l'interet ;
 *  - **les fichiers sont replies par defaut et tries par ampleur** : sur une vraie session, il y a
 *    souvent dix fichiers de configuration a une ligne pour un fichier de code a deux cents ;
 *    commencer par le plus petit enterrerait le sujet ;
 *  - **l'ajout et la suppression sont distingues par un signe**, pas seulement par la couleur :
 *    une teinte seule ne se lit pas en contraste eleve ni pour un daltonien.
 *
 * ⚠️ La couleur de fond est un **melange avec le fond de l'app**, alpha faible : un vert plein
 * sur fond sombre produit un halo qui rend le code illisible. L'alpha est ici un choix de
 * lisibilite, pas d'esthetique.
 */
@Composable
fun DiffScreen(
    modifier: Modifier = Modifier,
    viewModel: DiffViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope by viewModel.diffScope.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        if (viewModel.availableScopes.size > 1) {
            ScopeSelector(
                scopes = viewModel.availableScopes,
                selected = scope,
                onSelect = viewModel::setScope,
            )
        }

        Box(Modifier.fillMaxSize()) {
            when (val current = state) {
                DiffUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = TetherAccent)
                }

                is DiffUiState.Error -> Centered(current.message, TetherAlert)
                is DiffUiState.Empty -> Centered(current.reason, TetherTextSecondary)

                is DiffUiState.Loaded -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item(key = "summary") {
                        Text(
                            text = summaryLine(current.files),
                            style = TetherDataStyle,
                            color = TetherTextSecondary,
                        )
                    }
                    items(current.files, key = { it.path }) { file ->
                        FileCard(file)
                    }
                }
            }
        }
    }
}

/**
 * « 3 fichiers · +412 / −87 ».
 *
 * ⚠️ Les totaux sont **la somme des compteurs du serveur**, jamais d'un comptage des lignes
 * affichees : le patch peut etre tronque par le parametre `context`, et un total recalcule
 * donnerait un chiffre plus petit que la realite — donc un chiffre faux.
 */
private fun summaryLine(files: List<UnifiedDiff.FileDiff>): String {
    val additions = files.sumOf { it.additions }
    val deletions = files.sumOf { it.deletions }
    val noun = if (files.size == 1) "fichier" else "fichiers"
    return "${files.size} $noun · +$additions / −$deletions"
}

@Composable
private fun ScopeSelector(
    scopes: List<DiffScope>,
    selected: DiffScope,
    onSelect: (DiffScope) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        scopes.forEach { scope ->
            val active = scope == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                    .background(if (active) TetherAccent.copy(alpha = 0.18f) else TetherComposerSurface)
                    .clickable { onSelect(scope) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            ) {
                Text(
                    text = scope.label,
                    style = TetherDataStyle,
                    color = if (active) TetherAccent else TetherTextSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * Un fichier : son en-tete cliquable, ses lignes quand il est ouvert.
 *
 * ⚠️ Etat d'expansion **local a la composition** (`remember`), comme les sous-agents : un etat
 * global obligerait a le nettoyer quand la liste change, et une liste qui se reouvre sur les
 * memes fichiers qu'avant serait une surprise.
 */
@Composable
private fun FileCard(file: UnifiedDiff.FileDiff) {
    var expanded by remember(file.path) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface),
    ) {
        FileHeader(file = file, expanded = expanded, onToggle = { expanded = !expanded })

        if (expanded) {
            if (file.isDisplayable) {
                DiffBody(file)
            } else {
                // ⚠️ Un fichier sans patch (binaire, ou tronque par le serveur) doit le DIRE. Un
                // corps vide laisserait croire a un fichier sans changement.
                Text(
                    text = "Le serveur n'a pas fourni de contenu pour ce fichier " +
                        "(binaire, ou diff trop volumineux).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextSecondary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(
                        start = Spacing.md, end = Spacing.md, bottom = Spacing.md,
                    ),
                )
            }
        }
    }
}

@Composable
private fun FileHeader(file: UnifiedDiff.FileDiff, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = Lucide.ChevronDown,
            contentDescription = if (expanded) "Replier" else "Déplier",
            tint = TetherTextSecondary,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.path,
                style = TetherDataStyle,
                color = TetherTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = file.status.label,
                style = TetherDataStyle,
                color = TetherTextSecondary.copy(alpha = 0.8f),
            )
        }
        // ⚠️ Les compteurs sont sur la ligne, toujours visibles meme replie : c'est ce qui permet
        // de choisir QUEL fichier ouvrir sans les ouvrir tous.
        Text(text = "+${file.additions}", style = TetherDataStyle, color = TetherAccent)
        Text(text = "−${file.deletions}", style = TetherDataStyle, color = TetherAlert)
    }
}

/**
 * Le corps du diff : les lignes, en defilement horizontal.
 *
 * ⚠️ On ne coupe **jamais** une ligne. Une troncature tomberait presque toujours sur la fin de la
 * ligne, qui est justement l'endroit ou le changement se voit.
 */
@Composable
private fun DiffBody(file: UnifiedDiff.FileDiff) {
    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll)
            .padding(bottom = Spacing.sm),
    ) {
        file.lines.forEach { line -> DiffLine(line) }
    }
}

/**
 * Une ligne, avec son role dit **par un signe et une couleur**.
 *
 * ⚠️ Le signe n'est pas redondant avec la couleur : c'est ce qui rend le diff lisible sans couleur
 * du tout. Un diff qui ne se comprend qu'en couleur est inutilisable en contraste eleve.
 */
@Composable
private fun DiffLine(line: UnifiedDiff.Line) {
    val (marker, color, background) = when (line.kind) {
        UnifiedDiff.Kind.Addition ->
            Triple("+", TetherAccent, TetherAccent.copy(alpha = 0.10f))
        UnifiedDiff.Kind.Deletion ->
            Triple("−", TetherAlert, TetherAlert.copy(alpha = 0.10f))
        UnifiedDiff.Kind.Context ->
            Triple(" ", TetherTextPrimary, TetherBackground.copy(alpha = 0.35f))
        UnifiedDiff.Kind.HunkHeader ->
            Triple("", TetherTextSecondary, TetherAccent.copy(alpha = 0.06f))
        UnifiedDiff.Kind.FileHeader ->
            Triple("", TetherTextSecondary.copy(alpha = 0.7f), TetherBackground.copy(alpha = 0.5f))
        UnifiedDiff.Kind.Meta ->
            Triple("", TetherTextSecondary.copy(alpha = 0.6f), TetherBackground.copy(alpha = 0.3f))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background),
    ) {
        // La gouttiere du signe a une largeur fixe pour que les lignes s'alignent verticalement.
        Text(
            text = marker,
            style = TetherDataStyle,
            color = color,
            modifier = Modifier
                .width(18.dp)
                .padding(start = Spacing.sm),
        )
        Text(
            // ⚠️ `softWrap = false` : indispensable pour que le defilement horizontal ait un sens.
            // Sans lui, Compose replierait la ligne et le defilement ne servirait a rien.
            text = line.text.ifEmpty { " " },
            style = TetherDataStyle,
            color = color,
            softWrap = false,
            modifier = Modifier.padding(end = Spacing.md, top = 1.dp, bottom = 1.dp),
        )
    }
}

@Composable
private fun Centered(message: String, color: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Lucide.FileDiffIcon,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}
