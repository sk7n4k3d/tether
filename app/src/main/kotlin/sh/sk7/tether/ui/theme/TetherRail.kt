package sh.sk7.tether.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import sh.sk7.tether.ui.theme.LocalAccent

/**
 * **LE FIL** — composant signature de Tether.
 *
 * Un trait vertical continu, avec un **nœud** a hauteur de chaque element. C'est la
 * representation visuelle du nom de l'app : *tether* = la corde qui relie.
 *
 * Etats de nœud, immediatement lisibles :
 *  - [NodeState.Active]  : disque plein + halo — la session tourne **maintenant** ;
 *  - [NodeState.Done]    : anneau — termine ;
 *  - [NodeState.Failed]  : anneau ambre — termine en erreur ;
 *  - [NodeState.Idle]    : petit disque gris — sans activite.
 *
 * ⚠️ **Le fil est continu de bout en bout.** Chaque appel dessine le trait sur **toute** la
 * hauteur disponible, avec les nœuds par-dessus : c'est ce qui fait que deux lignes voisines
 * se raccordent visuellement et donnent l'impression d'une seule corde. Une premiere version
 * ne tracait le trait qu'entre le premier et le dernier nœud — avec un seul nœud par ligne,
 * aucun trait n'apparaissait, et il ne restait que des points isoles.
 */
enum class NodeState { Active, Done, Failed, Idle }

/**
 * Dessine le fil vertical sur toute la hauteur, avec les nœuds demandes.
 *
 * @param nodes positions verticales des nœuds, en fraction 0..1 de la hauteur du composant.
 * @param states etat de chaque nœud, meme ordre que [nodes].
 * @param isSub brin de sous-agent : fil plus fin, nœuds plus petits.
 * @param leading si vrai, le fil part du **bord haut** (raccord avec la ligne precedente).
 * @param trailing si vrai, le fil va jusqu'au **bord bas** (raccord avec la ligne suivante).
 */
@Composable
fun TetherRail(
    nodes: List<Float>,
    states: List<NodeState>,
    modifier: Modifier = Modifier,
    accent: Color = LocalAccent.current,
    idle: Color = TetherTextSecondary,
    alert: Color = TetherAlert,
    isSub: Boolean = false,
    leading: Boolean = true,
    trailing: Boolean = true,
) {
    val threadWidth: Dp = if (isSub) TetherDimensions.subThreadWidth else TetherDimensions.threadWidth
    val nodeSize: Dp = if (isSub) TetherDimensions.subNodeSize else TetherDimensions.nodeSize

    val anyActive = states.any { it == NodeState.Active }
    val anyFailed = states.any { it == NodeState.Failed }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val tw = threadWidth.toPx()
        val ns = nodeSize.toPx()

        // La teinte du fil est un ETAT, pas une decoration : ambre si une erreur existe,
        // teal si quelque chose tourne, gris sinon.
        val threadColor = when {
            anyFailed -> alert.copy(alpha = 0.5f)
            anyActive -> accent.copy(alpha = 0.42f)
            else -> idle.copy(alpha = 0.20f)
        }

        val startY = if (leading) 0f else (nodes.firstOrNull() ?: 0f) * h
        val endY = if (trailing) h else (nodes.lastOrNull() ?: 1f) * h
        drawLine(
            color = threadColor,
            start = Offset(cx, startY),
            end = Offset(cx, endY),
            strokeWidth = tw,
        )

        nodes.forEachIndexed { i, fraction ->
            val cy = fraction * h
            when (states.getOrNull(i) ?: NodeState.Idle) {
                NodeState.Active -> {
                    // Halo : la seule « respiration » de l'app.
                    drawCircle(color = accent.copy(alpha = 0.16f), radius = ns * 2.0f, center = Offset(cx, cy))
                    drawCircle(color = accent, radius = ns / 2f, center = Offset(cx, cy))
                }
                NodeState.Done -> {
                    drawCircle(
                        color = idle.copy(alpha = 0.80f),
                        radius = ns / 2f,
                        center = Offset(cx, cy),
                        style = Stroke(width = tw),
                    )
                }
                NodeState.Failed -> {
                    drawCircle(
                        color = alert,
                        radius = ns / 2f,
                        center = Offset(cx, cy),
                        style = Stroke(width = tw * 1.5f),
                    )
                }
                NodeState.Idle -> {
                    drawCircle(color = idle.copy(alpha = 0.42f), radius = ns / 2.8f, center = Offset(cx, cy))
                }
            }
        }
    }
}
