package sh.sk7.tether.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.sk7.tether.ui.theme.NodeState
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **Une session, posee sur le fil** — la signature visuelle de Tether.
 *
 * ### Pourquoi le dessin est fait avec `drawBehind` et non un composant `Canvas` fils
 *
 * Une premiere version placait un `Canvas` avec `fillMaxHeight()` a cote du contenu. Ca ne
 * marche pas dans une `LazyColumn` : les items y sont mesures avec une **contrainte de
 * hauteur non bornee**, donc `fillMaxHeight()` ne resout rien et le fil n'apparait jamais
 * (constate sur le Pixel : des nœuds isoles, aucune corde).
 *
 * `drawBehind` dessine sur le canvas **de la ligne elle-meme**, dont la taille est connue
 * apres coup. C'est deterministe, sans mesure d'intrinsèque, et le fil couvre exactement la
 * hauteur reelle de chaque element — donc il se raccorde d'une ligne a l'autre.
 *
 * ### La grammaire visuelle
 *  - **le fil** : trait vertical continu, meme abscisse sur toutes les lignes ;
 *  - **un nœud plein + halo** : la session tourne maintenant ;
 *  - **un anneau** : terminee ; **un anneau ambre** : en erreur ;
 *  - **un petit disque gris** : sans activite ;
 *  - **un brin** (sous-agent) : pas de fil vertical, mais un **crochet horizontal** partant
 *    du fil principal — un embranchement, pas une session de second rang.
 */
@Composable
fun SessionRow(
    item: SessionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSub = item.isSub
    val state = item.nodeState
    // ⚠️ Le fil porte la couleur de SA BRANCHE : si ce parent a un enfant actif (sous-agent
    // en cours), le fil du parent s'allume en teal meme si le parent lui-meme est termine.
    // Sans ca, une delegation en cours reste invisible depuis la liste.
    val branchActive = item.branchActive || state == NodeState.Active

    // Positions : le fil est a abscisse FIXE (donc continu), le contenu se decale.
    val railX: Dp = TetherDimensions.railWidth / 2
    val nodeX: Dp = if (isSub) railX + TetherDimensions.indent else railX
    val contentStart: Dp = TetherDimensions.railWidth + if (isSub) TetherDimensions.indent else 0.dp

    // Ordonnee du nœud : centre de la premiere ligne de titre (padding haut + demi-interligne).
    val nodeY: Dp = Spacing.md + 10.dp

    val accent = TetherAccent
    val idle = TetherTextSecondary
    val alert = TetherAlert

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val railXpx = railX.toPx()
                val nodeXpx = nodeX.toPx()
                val nodeYpx = nodeY.toPx()
                val tw = TetherDimensions.threadWidth.toPx()
                val nodeR = (if (isSub) TetherDimensions.subNodeSize else TetherDimensions.nodeSize).toPx() / 2f

                // --- LE FIL : TOUJOURS, sur toute la hauteur, y compris sur une sous-session ---
                // ⚠️ C'est le point qui fait qu'on voit UNE corde et pas des traits coupes.
                // Une premiere version ne tracait pas le fil sur les sous-agents : le fil
                // s'interrompait a chaque delegation, et l'effet « fil tendu » disparaissait.
                val threadColor = when {
                    state == NodeState.Failed -> alert.copy(alpha = 0.45f)
                    branchActive -> accent.copy(alpha = 0.40f)
                    else -> idle.copy(alpha = 0.16f)
                }
                drawLine(
                    color = threadColor,
                    start = Offset(railXpx, 0f),
                    end = Offset(railXpx, size.height),
                    strokeWidth = tw,
                )

                // --- LE BRIN : crochet horizontal qui rattache le nœud du sous-agent au fil ---
                if (isSub) {
                    drawLine(
                        color = idle.copy(alpha = 0.30f),
                        start = Offset(railXpx, nodeYpx),
                        end = Offset(nodeXpx, nodeYpx),
                        strokeWidth = TetherDimensions.subThreadWidth.toPx(),
                    )
                }

                // --- LE NŒUD ---
                when (state) {
                    NodeState.Active -> {
                        drawCircle(
                            color = accent.copy(alpha = 0.16f),
                            radius = nodeR * 2.0f,
                            center = Offset(nodeXpx, nodeYpx),
                        )
                        drawCircle(color = accent, radius = nodeR, center = Offset(nodeXpx, nodeYpx))
                    }
                    NodeState.Done -> drawCircle(
                        color = idle.copy(alpha = 0.78f),
                        radius = nodeR,
                        center = Offset(nodeXpx, nodeYpx),
                        style = Stroke(width = tw),
                    )
                    NodeState.Failed -> drawCircle(
                        color = alert,
                        radius = nodeR,
                        center = Offset(nodeXpx, nodeYpx),
                        style = Stroke(width = tw * 1.5f),
                    )
                    NodeState.Idle -> drawCircle(
                        color = idle.copy(alpha = 0.40f),
                        radius = nodeR * 0.72f,
                        center = Offset(nodeXpx, nodeYpx),
                    )
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = contentStart)
                .clickable(onClick = onClick)
                // C'est ce padding qui aere, pendant que le fil reste continu.
                .padding(vertical = Spacing.md, horizontal = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = item.title,
                style = if (isSub) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.titleSmall
                },
                color = if (isSub) TetherTextSecondary else TetherTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = RelativeTime.format(System.currentTimeMillis(), item.timestamp),
                    style = TetherDataStyle,
                    color = TetherTextSecondary,
                )
                item.agent?.let {
                    Text(text = it, style = TetherDataStyle, color = TetherTextSecondary)
                }
                item.costLabel?.let {
                    Text(
                        text = it,
                        style = TetherDataStyle,
                        color = TetherAccent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item.outcome?.takeIf { it != "succeeded" }?.let {
                    Text(
                        text = it,
                        style = TetherDataStyle,
                        color = if (state == NodeState.Failed) TetherAlert else TetherTextSecondary,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.modelLabel?.let {
                    Text(
                        text = it,
                        style = TetherDataStyle,
                        color = TetherTextSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                item.tokensLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = TetherDataStyle,
                        color = TetherTextSecondary.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}
