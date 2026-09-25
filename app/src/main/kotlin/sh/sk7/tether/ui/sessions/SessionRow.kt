package sh.sk7.tether.ui.sessions

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * ### Le nœud EST le controle
 *
 * Le rond a gauche n'est pas decoratif : **c'est lui qui deplie les sous-agents**. Pas de
 * chevron separe, pas de fleche en plus — le nœud porte deja la semantique du fil (c'est le
 * point d'attache), donc c'est naturel qu'il ouvre la branche. Ca evite d'ajouter un controle
 * encombrant a cote, et ca garde le nœud comme seul point d'interet visuel de la ligne.
 *
 * ### Trois signaux dans un seul rond
 *  - **la forme** : plein = tourne, anneau = termine, anneau ambre = erreur, petit point = rien ;
 *  - **la pulsation** : uniquement quand la session tourne — c'est la seule « respiration » de
 *    l'app, et elle dit « vivant » sans afficher une roue qui tourne ;
 *  - **le remplissage** : un nœud deplie est plein et teal (la branche est ouverte), un nœud
 *    replie reste dans la teinte de son etat. On voit donc d'un coup d'œil quels parents
 *    cachent des enfants.
 *
 * ### Sous-agents replies par defaut
 * Les enfants ne s'affichent qu'a la demande : avec 297 sous-agents sur 437 sessions, les
 * afficher tous noie litteralement les sessions principales.
 */
@Composable
fun SessionRow(
    item: SessionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** `null` = pas de sous-agents : le nœud n'est pas cliquable (rien a ouvrir). */
    onToggleSubs: (() -> Unit)? = null,
    subsExpanded: Boolean = false,
) {
    val isSub = item.isSub
    val state = item.nodeState
    val branchActive = item.branchActive || state == NodeState.Active
    val expandable = onToggleSubs != null && item.childCount > 0

    // Positions : le fil est a abscisse FIXE (donc continu), le contenu se decale.
    val railX: Dp = TetherDimensions.railWidth / 2
    val nodeX: Dp = if (isSub) railX + TetherDimensions.indent else railX
    val contentStart: Dp = TetherDimensions.railWidth + if (isSub) TetherDimensions.indent else 0.dp
    val nodeY: Dp = Spacing.md + 10.dp

    val accent = TetherAccent
    val idle = TetherTextSecondary
    val alert = TetherAlert

    // ---------------------------------------------------------------
    // PULSATION DU NŒUD
    //
    // Le nœud est le seul endroit de la ligne ou il y a quelque chose a faire, mais un anneau
    // gris immobile ne le dit pas. Deux raisons de pulser, deux sémantiques distinctes :
    //   - `branchActive` : la session **tourne** -> halo teal. C'est une respiration, elle dit
    //     « vivant » sans afficher une roue qui tourne ;
    //   - `expandable && !subsExpanded` : la session **cache des sous-agents** -> halo gris.
    //     C'est une invitation : « il y a quelque chose dessous, appuie ».
    //
    // ⚠️ `rememberInfiniteTransition` anime en PERMANENCE. On ne la déclenche donc jamais pour
    // rien : seuls les parents et les sessions actives pulsent, pas les 440 lignes.
    // ⚠️ Respect de « réduire les animations » (ANIMATOR_DURATION_SCALE = 0) : sinon on impose
    // un mouvement continu a quelqu'un qui l'a explicitement desactive.
    // ---------------------------------------------------------------
    val context = LocalContext.current
    val animationsAllowed = remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
    val invitesToExpand = expandable && !subsExpanded
    val pulseOn = animationsAllowed && (branchActive || invitesToExpand)

    val pulse by rememberInfiniteTransition(label = "node-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (branchActive) 1600 else 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "node-pulse-value",
    )
    // Le halo d'invitation reste plus discret que celui d'activite : deux informations
    // differentes ne doivent pas crier aussi fort.
    val pulseAlpha = when {
        !pulseOn -> 0f
        branchActive -> 0.30f * pulse
        else -> 0.16f * pulse
    }
    val pulseColor = if (branchActive) accent else idle

    // Le nœud est plus visible quand il a quelque chose a ouvrir : c'est un controle.
    val nodeSizeBase: Dp = when {
        isSub -> TetherDimensions.subNodeSize
        expandable -> TetherDimensions.nodeSize + 3.dp
        else -> TetherDimensions.nodeSize
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val railXpx = railX.toPx()
                val nodeXpx = nodeX.toPx()
                val nodeYpx = nodeY.toPx()
                val tw = TetherDimensions.threadWidth.toPx()
                val nodeR = nodeSizeBase.toPx() / 2f

                // --- LE FIL : TOUJOURS, sur toute la hauteur, y compris sur une sous-session ---
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

                // --- LE NŒUD (le controle) ---
                // Halo de pulsation d'abord, sous le nœud.
                if (pulseAlpha > 0f) {
                    drawCircle(
                        color = pulseColor.copy(alpha = pulseAlpha),
                        radius = nodeR * (2.2f + pulse * 0.8f),
                        center = Offset(nodeXpx, nodeYpx),
                    )
                }

                when {
                    // Deplie : plein teal — la branche est ouverte, c'est visible.
                    expandable && subsExpanded -> {
                        drawCircle(color = accent, radius = nodeR, center = Offset(nodeXpx, nodeYpx))
                    }
                    state == NodeState.Active -> {
                        drawCircle(color = accent, radius = nodeR, center = Offset(nodeXpx, nodeYpx))
                    }
                    state == NodeState.Failed -> drawCircle(
                        color = alert, radius = nodeR, center = Offset(nodeXpx, nodeYpx),
                        style = Stroke(width = tw * 1.5f),
                    )
                    // Replie : anneau plus epais — il y a quelque chose dedans, ca se voit.
                    expandable -> drawCircle(
                        color = idle.copy(alpha = 0.9f), radius = nodeR, center = Offset(nodeXpx, nodeYpx),
                        style = Stroke(width = tw * 1.6f),
                    )
                    state == NodeState.Done -> drawCircle(
                        color = idle.copy(alpha = 0.72f), radius = nodeR, center = Offset(nodeXpx, nodeYpx),
                        style = Stroke(width = tw),
                    )
                    else -> drawCircle(
                        color = idle.copy(alpha = 0.40f), radius = nodeR * 0.72f,
                        center = Offset(nodeXpx, nodeYpx),
                    )
                }
            },
        verticalAlignment = Alignment.Top,
    ) {
        // ---------------------------------------------------------------
        // LA ZONE CLIQUABLE DU NŒUD — couvre toute la hauteur du rail
        // ---------------------------------------------------------------
        val toggleModifier = Modifier
            .size(width = TetherDimensions.railWidth + if (isSub) TetherDimensions.indent else 0.dp, height = 64.dp)
            .then(
                if (expandable) {
                    Modifier
                        .clickable(onClick = onToggleSubs!!)
                        .semantics {
                            contentDescription = if (subsExpanded) {
                                "Replier les ${item.childCount} sous-agents de ${item.title}"
                            } else {
                                "Deplier les ${item.childCount} sous-agents de ${item.title}"
                            }
                        }
                } else {
                    Modifier
                },
            )
        androidx.compose.foundation.layout.Spacer(toggleModifier)

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
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
                // 💰 UN SEUL PRIX : le **total de la branche** (session + sous-agents).
                // Afficher cote a cote « prix session » et « prix total » obligeait a faire
                // l'addition mentalement — et le premier chiffre etait de toute facon faux
                // (il ignorait les delegations). Un seul chiffre, le vrai.
                (item.branchCostLabel ?: item.costLabel)?.let {
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
