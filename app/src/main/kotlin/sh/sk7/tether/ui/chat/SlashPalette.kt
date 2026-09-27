package sh.sk7.tether.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **La palette de commandes slash.**
 *
 * ### Pourquoi elle s'ouvre sur `/`
 * C'est le geste qui existe deja dans la TUI d'opencode, et l'attendre au meme endroit est la
 * seule facon qu'il soit naturel sur mobile. Mais il y a une raison plus dure : le serveur
 * **valide le nom des commandes** contre sa liste (28 mesurees). Taper `/review` comme du texte
 * ne declenche rien — l'agent le lirait comme une phrase. Sans cette palette, l'utilisateur
 * n'aurait aucun moyen de savoir quelles commandes existent, ni comment les invoquer.
 *
 * ⚠️ On affiche la **description** du serveur telle quelle : elle dit ce que fait la commande, et
 * la reformuler ferait prendre une paraphrase de l'app pour la documentation du serveur.
 *
 * ⚠️ Le filtrage est fait par l'appelant : la liste est deja reduite a ce qui correspond a la
 * frappe. Ce composant ne fait qu'afficher, ce qui le rend trivialement testable a l'oeil.
 */
@Composable
fun SlashPalette(
    commands: List<CommandDto>,
    onPick: (CommandDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd)),
    ) {
        Text(
            text = if (commands.isEmpty()) "AUCUNE COMMANDE" else "COMMANDES",
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
        )
        if (commands.isEmpty()) {
            // ⚠️ On dit POURQUOI c'est vide plutot que d'afficher une boite vide. Les commandes
            // viennent du serveur : si la liste est vide, c'est que le serveur n'en a pas annonce
            // ou que le chargement a echoue — dans les deux cas, ce n'est pas la faute de la frappe.
            Text(
                text = "Le serveur n'a pas annoncé de commande. " +
                    "Tu peux écrire ton message normalement.",
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
                modifier = Modifier.padding(
                    start = Spacing.md, end = Spacing.md, bottom = Spacing.md,
                ),
            )
        } else {
            LazyColumn(
                // ⚠️ Hauteur plafonnee : la palette ne doit pas recouvrir tout l'ecran. Elle
                // propose, elle ne remplace pas la conversation.
                modifier = Modifier.heightIn(max = 260.dp),
                contentPadding = PaddingValues(bottom = Spacing.sm),
            ) {
                items(commands, key = { it.name }) { command ->
                    CommandRow(command = command, onPick = { onPick(command) })
                }
            }
        }
    }
}

@Composable
private fun CommandRow(command: CommandDto, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 48 dp : une ligne de commande est une cible, pas une légende. Sans ce minimum,
            // une commande sans description ne fait que ~30 dp de haut et devient difficile à
            // choisir dans une palette qui en propose 28.
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(onClick = onPick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Lucide.Terminal,
            contentDescription = null,
            tint = TetherAccent,
            modifier = Modifier.size(14.dp),
        )
        Column {
            Text(
                text = "/${command.name}",
                style = TetherDataStyle,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            command.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * **Le selecteur de modele et d'agent, au moment de l'envoi.**
 *
 * ### Pourquoi une feuille, et pas un reglage
 * Le choix du modele depend de la question qu'on pose : un modele rapide pour une reformulation,
 * un modele profond pour un refactor. Le mettre dans les reglages obligerait a y aller **avant**
 * chaque message, c'est-a-dire a chaque fois.
 *
 * ⚠️ On distingue visuellement le **choix pour cette session** de l'etat courant : le serveur
 * applique le changement pour les tours suivants, ce qui est different d'un choix ponctuel.
 */
/**
 * Une ligne du selecteur : un libelle, et un **secondaire** qui explique le libelle.
 *
 * ⚠️ Le secondaire n'est pas decoratif. Un agent porte son **propre** modele (mesure du
 * 2026-09-26 : `build` -> `glm-5.3`, `plan` et `edit` -> `deepseek-v4.1-flash`), donc choisir un
 * agent sans afficher ce qu'il ameneOblige a deviner. Idem pour un modele : deux providers peuvent
 * servir le meme `id`, et n'afficher que l'`id` mentirait sur celui qu'on va payer.
 */
/**
 * ⚠️ **`value` n'est PAS `label`.** Le selecteur renvoie [value] au callback, jamais le libelle.
 *
 * Bug du 2026-09-27, **introduit par moi** en reprenant la mise en forme de Proton Lumo : les
 * modeles ont ete affiches en `provider/id` (le libelle juste et plus long), et le callback
 * cherchait toujours `models.first { it.id == picked }` — donc la comparaison se faisait entre
 * `"opencode/space-bunny-free"` et `"space-bunny-free"`, ne trouvait **rien**, et
 * `?.let` ne partait jamais. Symptome : le modele se choisissait, la feuille se fermait, et le
 * serveur retombait sur son defaut (`glm-5.3-flash`) sans la moindre erreur. Les agents
 * echappaient au piege parce que leur libelle **est** leur id.
 *
 * Separer les deux rend le piege impossible a reintroduire par megarde.
 */
data class PickerItem(
    val label: String,
    val secondary: String? = null,
    /** Ce que le callback recoit. Doit etre l'identite reelle, pas le libelle affiche. */
    val value: String = label,
)

/** Une note de section, non cliquable : ici, ce que le serveur choisit a notre place. */
data class PickerNote(val text: String)

/**
 * **Quelle feuille on ouvre.**
 *
 * ⚠️ Scinder la feuille suit Proton Lumo (2026-09-27) : le libelle de modele ouvre les
 * **modeles**, le libelle d'agents ouvre les **agents**. Une seule feuille avec tout dedans
 * obligeait a choisir entre un modele et un agent, alors que les deux sont des reglages de la
 * meme nature — et le selecteur etait alors le seul point d'entree du composer, ce qui rendait
 * le bouton d'envoi faire trois choses.
 *
 * ⚠️ **Les competences ont ete retirees d'ici** (demande du 2026-09-27) : meleees a des agents
 * dans une feuille qui ne s'appelait pas « Outils », elles etaient invisibles et jugees
 * inutiles. Consequence assumee : **activer une competence n'a plus de porte d'entree** — la
 * barre du haut n'en a pas (`rechercher / outils en arriere-plan / diff / contexte / export`).
 * La route reste cablee (`POST /experimental/session/{id}/skill`, [ChatViewModel.activateSkill])
 * pour qu'un retour en arriere soit un simple branchement, pas un developpement.
 */
enum class PickerTab {
    /** Les modeles du serveur, avec leur provider. */
    Models,

    /** Les agents selectionnables. */
    Agents,
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelAgentPicker(
    tab: PickerTab = PickerTab.Models,
    models: List<PickerItem>,
    agents: List<PickerItem>,
    currentModel: String?,
    currentAgent: String?,
    onPickModel: (String) -> Unit,
    onPickAgent: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Ce que le serveur fait de son cote quand on ne choisit rien.
     *
     * ⚠️ `null` = **aucune** mention. Une session fraiche a `agent` et `model` a `null` tant que le
     * premier tour n'a pas tourne (mesure), et `/api/model/default` est une valeur de
     * configuration, pas ce qu'on obtient. On n'affiche donc un defaut que pour l'agent, qui est
     * materiel dans la reponse — le seul en `mode: "all"`.
     */
    defaultNote: String? = null,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TetherComposerSurface,
    ) {
        Column(
            modifier = Modifier.padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (tab == PickerTab.Models) {
                PickerSection(
                    title = "MODÈLE",
                    items = models,
                    current = currentModel,
                    icon = Lucide.Blocks,
                    onPick = onPickModel,
                )
            }
            if (tab == PickerTab.Agents) {
                PickerSection(
                    title = "AGENT",
                    items = agents,
                    current = currentAgent,
                    icon = Lucide.Blocks,
                    onPick = onPickAgent,
                    note = defaultNote,
                )
            }
        }
    }
}

@Composable
private fun PickerSection(
    title: String,
    items: List<PickerItem>,
    current: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPick: (String) -> Unit,
    note: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = "$title · ${items.size}",
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        // ⚠️ La note vient **avant** la liste : elle dit ce que le serveur fera de son cote, donc
        // c'est le contexte de la liste, pas sa legende.
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        if (items.isEmpty()) {
            // ⚠️ Un selecteur vide doit se dire. Sans ce message, l'utilisateur croirait a un
            // defaut d'affichage alors que le serveur n'a simplement rien annonce.
            Text(
                text = "Le serveur n'a rien annoncé dans cette catégorie.",
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(items, key = { it.label }) { item ->
                val active = item.label == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // ⚠️ 48 dp : meme regle que les lignes de commande. Les deux listes de la
                        // feuille (modèles, agents, skills) doivent avoir la même hauteur de
                        // cible — sinon la feuille saute d'une section à l'autre au doigt.
                        .heightIn(min = TetherDimensions.touchTarget)
                        .clickable { onPick(item.value) }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (active) TetherAccent else TetherTextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = item.label,
                        style = TetherDataStyle,
                        color = if (active) TetherAccent else TetherTextPrimary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.secondary?.let {
                        Text(
                            text = it,
                            style = TetherDataStyle,
                            color = if (active) TetherAccent else TetherTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
