package sh.sk7.tether.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import sh.sk7.tether.domain.model.ToolCall
import sh.sk7.tether.domain.model.ToolStatus
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherCodeStyle
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **Le raisonnement du modele, REPLIE par defaut.**
 *
 * ### Pourquoi ce composant existe
 *
 * Avant : le raisonnement brut s'affichait en bloc gris plein, occupant jusqu'a deux ecrans
 * et noyant la reponse. Constat de Bastien, confirme par la recherche sur l'etat de l'art :
 * ChatGPT, Claude, Grok et Cursor affichent tous le raisonnement **replie**, resume sur une
 * ligne. Le libelle doit etre **honnete** (« Raisonnement », pas « Travail en cours »), et la
 * ligne repliee doit porter **la duree** — sinon on ne sait pas si le modele a reflechi 2 s
 * ou 2 min. DeepSeek R1 est le seul a tout montrer, et c'est precisement ce que les
 * utilisateurs de Cursor demandent a faire disparaitre.
 *
 * ### Regles appliquees
 *  - replie par defaut, un tap pour ouvrir (defaut de tous les produits de reference) ;
 *  - la ligne porte le **temps de raisonnement** et le volume, jamais un libelle vague ;
 *  - surface **translucide** + bordure fine, jamais un aplat plein (regle anti-slop :
 *    « never solid card backgrounds ») ;
 *  - le fond ne « brille » pas : c'est de l'information secondaire, elle doit se retirer.
 */
@Composable
fun ReasoningBlock(
    text: String,
    durationLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "reasoning-chevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TetherSurfaceTranslucent, RoundedCornerShape(TetherDimensions.cornerSm))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Lucide.Brain,
                contentDescription = null,
                tint = TetherTextSecondary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                // Libelle HONNETE + duree. « Raisonne 12 s », pas « Travail en cours ».
                text = buildString {
                    append("Raisonnement")
                    durationLabel?.let { append(" · $it") }
                },
                style = TetherDataStyle,
                color = TetherTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Lucide.ChevronDown,
                contentDescription = if (expanded) "Replier le raisonnement" else "Deplier le raisonnement",
                tint = TetherTextSecondary.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotation),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
            )
        }
    }
}

/**
 * **Carte d'appel d'outil**, repliee, avec **duree**.
 *
 * Regles tirees de l'etat de l'art :
 *  - la carte porte le **nom de l'outil ET sa duree** : un `shell` de 40 s sans duree passe
 *    pour de la reflexion du modele, ce qui est faux et trompeur ;
 *  - hauteur par defaut visee **sous 120 px** pour qu'un run de 10 etapes tienne a l'ecran ;
 *  - le statut est dit par **forme + couleur**, jamais par la couleur seule ;
 *  - la sortie se deplie **dans** la carte, on ne l'impose pas.
 */
@Composable
fun ToolCard(call: ToolCall, durationLabel: String? = null, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "tool-chevron",
    )
    val statusColor = when (call.status) {
        ToolStatus.Running -> TetherAccent
        ToolStatus.Succeeded -> TetherTextSecondary
        ToolStatus.Failed -> TetherAlert
    }
    // ⚠️ Duree : celle du REST (`time.ran` -> `time.completed`) prime sur le calcul local du
    // reducer. Le REST l'a mesuree meme si le flux SSE n'a rien vu (rechargement d'historique).
    val effectiveDuration = call.durationLabel ?: durationLabel
    // On ne deplie que s'il y a quelque chose a montrer. Un chevron qui n'ouvre que
    // « (aucune sortie) » est une promesse non tenue.
    val body = call.output?.takeIf { it.isNotBlank() }
        ?: call.raw.takeIf { it.isNotBlank() && call.output == null }
    val expandable = body != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TetherSurfaceTranslucent, RoundedCornerShape(TetherDimensions.cornerSm))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = toolIcon(call.name),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = call.name.ifBlank { "outil" },
                style = TetherDataStyle,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            // Statut en TOUTES LETTRES : la couleur seule n'est pas accessible.
            Text(
                text = when (call.status) {
                    ToolStatus.Running -> "en cours"
                    ToolStatus.Succeeded -> "ok"
                    ToolStatus.Failed -> "echec"
                },
                style = TetherDataStyle,
                color = statusColor,
            )
            // ⚠️ **CE QUE L'OUTIL A RECU** — c'est ce qui manquait : « read ok » trois fois ne
            // dit rien, `read …/memory/user_sebastien.md` dit tout. Le serveur l'envoyait deja
            // (`state.input`), l'app le jetait.
            //
            // ⚠️ Sur sa PROPRE ligne, pas dans celle du titre : comprime entre le statut et la
            // duree, il ne restait que ~13 caracteres et trois chemins distincts s'affichaient
            // tous en `…/memory/inf…`. Ici il a toute la largeur.
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            effectiveDuration?.let {
                Text(text = it, style = TetherDataStyle, color = TetherTextSecondary)
            }
            if (expandable) {
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = if (expanded) "Replier la sortie" else "Deplier la sortie",
                    tint = TetherTextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(rotation),
                )
            }
        }

        call.summary?.let {
            Text(
                text = it,
                style = TetherCodeStyle,
                color = TetherTextSecondary.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md + 13.dp - Spacing.sm, bottom = Spacing.xs),
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = expanded && expandable,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
        ) {
            Text(
                // Le RESULTAT de l'outil (`state.content[].text`), pas le JSON du flux.
                text = body.orEmpty(),
                style = TetherCodeStyle,
                color = TetherTextSecondary,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm),
            )
        }
    }
}

/**
 * Icone de l'outil, choisie sur son **nom reel**.
 *
 * ⚠️ Volontairement pauvre : quatre formes couvrent les outils effectivement utilises
 * (`shell`, `read`, `grep`/`glob`, les autres). Inventer quinze icones pour des outils qu'on
 * n'a jamais vus serait de la decoration, pas de l'information.
 */
private fun toolIcon(name: String) = when (name) {
    "shell", "bash" -> Lucide.Terminal
    "read", "write", "edit" -> Lucide.Terminal
    "grep", "glob" -> Lucide.Terminal
    else -> Lucide.Terminal
}

/** Surface translucide : jamais un aplat plein (regle anti-slop). */
private val TetherSurfaceTranslucent = androidx.compose.ui.graphics.Color(0x14FFFFFF)
