package sh.sk7.tether.ui.sessions

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.background

import sh.sk7.tether.domain.model.Activity

import androidx.compose.material3.Icon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pin

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
import androidx.compose.foundation.layout.heightIn
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
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import sh.sk7.tether.ui.theme.animationsAllowed

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
    onPin: (() -> Unit)? = null,
    pinned: Boolean = false,
    onRename: (() -> Unit)? = null,
    onFork: (() -> Unit)? = null,
    onInterrupt: (() -> Unit)? = null,
    onCompact: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /**
     * L'etat vivant de cette session, tel que le serveur le voit.
     *
     * ⚠️ `null` = on ne sait pas encore (premiere interrogation en cours). On n'affiche alors
     * **rien** plutot qu'un statut par defaut : afficher « calme » avant d'avoir demande serait
     * une affirmation qu'on n'a pas les moyens de faire.
     */
    activity: Activity? = null,
) {
    val isSub = item.isSub
    val state = item.nodeState
    val branchActive = item.branchActive || state == NodeState.Active
    val expandable = onToggleSubs != null && item.childCount > 0

    // Positions : le fil est a abscisse FIXE (donc continu), le contenu se decale.
    val railX: Dp = TetherDimensions.railWidth / 2
    val nodeX: Dp = if (isSub) railX + TetherDimensions.indent else railX
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
    // ⚠️ Respect de « réduire les animations » : la lecture vit dans [animationsAllowed], commune
    // a tous les écrans — une seule implementation, donc un seul comportement a verifier.
    // ---------------------------------------------------------------
    val animationsOn = animationsAllowed()
    val invitesToExpand = expandable && !subsExpanded
    val pulseOn = animationsOn && (branchActive || invitesToExpand)

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
        // ⚠️ La zone fait la hauteur d'une ligne (64 dp, au-dessus du seuil) et la largeur du rail
        // élargie à la cible tactile. Le `semantics` porte le libellé : sans lui, replier un arbre
        // de sous-agents serait **invisible** à un lecteur d'écran, alors que c'est le seul moyen
        // de le faire autrement qu'à l'œil.
        val toggleModifier = Modifier
            // ⚠️ Largeur portée à la cible tactile (48 dp) : le rail ne fait que **20 dp** de
            // large, et le nœud qui le contrôle 10 à 13 dp. C'est la cible la plus petite de
            // l'app, sur un geste (replier un arbre de sous-agents) sans équivalent clavier.
            //
            // ⚠️ Ce qui change est **la gouttière**, pas le fil : le trait reste dessiné à
            // `railX` (10 dp) et le nœud à `nodeX`, inchangés. Seul le contenu commence 28 dp
            // plus loin. On paie un peu de largeur pour une cible atteignable — le contraire
            // (garder 20 dp et rater le geste) coûte bien plus cher qu'un peu d'air.
            .size(
                width = TetherDimensions.touchTarget +
                    if (isSub) TetherDimensions.indent else 0.dp,
                height = 64.dp,
            )
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
                // ⚠️ 48 dp : une ligne de session réduite (titre court, pas de métadonnées) peut
                // tomber sous le seuil. Le minimum ne change rien à hauteur nominale — il garantit
                // seulement qu'on ne descend jamais sous la cible.
                .heightIn(min = TetherDimensions.touchTarget)
                .clickable(onClick = onClick)
                .padding(vertical = Spacing.md, horizontal = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // Titre + menu sur la MEME ligne. Le menu se cale a droite, et suit le titre
            // (donc il se decale avec lui sur une sous-session).
            Row(verticalAlignment = Alignment.Top) {
                if (pinned) {
                    Icon(
                        imageVector = Lucide.Pin,
                        contentDescription = "Épinglée",
                        tint = TetherTextSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.xs))
                }
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
                    modifier = Modifier.weight(1f),
                )
                // ⚠️ Le statut est place AVANT le menu, donc toujours visible sans ouvrir quoi que
                // ce soit. C'est la reponse directe a « je ne sais pas si tu tournes ».
                activity?.let { ActivityBadge(it) }
                SessionOptionsMenu(
                    onPin = onPin,
                    pinned = pinned,
                    onRename = onRename,
                    onFork = onFork,
                    onInterrupt = onInterrupt,
                    onCompact = onCompact,
                    onDelete = onDelete,
                )
            }

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
                        color = TetherTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                item.tokensLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = TetherDataStyle,
                        color = TetherTextMuted,
                    )
                }
            }

        }
    }
}

/**
 * **La pastille d'état d'une session.**
 *
 * ### Pourquoi un mot ET une couleur
 * Une pastille de couleur seule ne se lit pas : ni en contraste élevé, ni pour un daltonien, ni
 * d'un coup d'œil sur une ligne dense. Le mot est ce qui rend l'état lisible ; la couleur ne fait
 * que le rendre trouvable. C'est la même règle que pour les statuts de serveur MCP.
 *
 * ⚠️ **« t'attend » est le seul état qui porte une forme pleine.** C'est le seul qui demande une
 * action de l'utilisateur : il doit se distinguer des autres même flouté, même en noir et blanc.
 *
 * ⚠️ `Idle` n'affiche **rien**. Marquer « calme » sur 440 lignes remplirait l'écran d'une
 * information qui est l'absence d'information — et noierait les quelques lignes qui comptent.
 */
@Composable
private fun ActivityBadge(activity: Activity) {
    // ⚠️ `Idle` et `Unseen` sont traites a part : le premier ne s'affiche pas, le second est
    // deja porte par sa propre information (« termine »). Tout afficher rendrait le tout illisible.
    val (label, tint) = when (activity) {
        Activity.Waiting -> "t'attend" to TetherAlert
        Activity.Running -> "en cours" to TetherAccent
        Activity.Unseen -> "terminé" to TetherTextPrimary
        Activity.Queued -> "en file" to TetherTextSecondary
        Activity.Failed -> "échec" to TetherAlert
        Activity.Idle -> return
    }
    val emphasis = activity == Activity.Waiting

    Text(
        text = label,
        style = sh.sk7.tether.ui.theme.TetherDataStyle,
        color = tint,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(tint.copy(alpha = if (emphasis) 0.16f else 0.08f))
            .padding(horizontal = Spacing.xs, vertical = 1.dp),
    )
}
