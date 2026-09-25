package sh.sk7.tether.ui.permissions

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.X
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **Approuver ou refuser les actions de l'agent, depuis le telephone.**
 *
 * ### Ce que cet ecran repare
 * Documentation d'opencode et rapports d'utilisateurs convergent : une session peut rester
 * **bloquee des heures** parce qu'une demande d'autorisation n'a ete vue par personne. Le
 * reproche recurrent est d'ailleurs de **devoir** approuver — et la seule reponse possible etant
 * « oui », on finit par approuver sans lire. D'ou deux choix de conception ici :
 *
 *  1. **On montre tout ce qui permet de decider** : l'action, la ressource exacte, le message du
 *     serveur. Pas de resume. Un utilisateur qui approuve doit reconnaitre ce qu'il approuve.
 *  2. **Trois reponses, pas deux** : `once` (cette fois), `always` (et mémoriser), `reject`.
 *     Pouvoir approuver **sans** accorder un droit permanent est ce qui evite le clic reflexe.
 *
 * ⚠️ `always` est visuellement **different des deux autres** : c'est le seul choix qui survit a
 * la session. Le distinguer n'est pas cosmetique, c'est la seule facon d'eviter qu'on l'accorde
 * par habitude.
 *
 * ### L'etat vide n'est pas une perte de place
 * Quand il n'y a rien a approuver, on le **dit** et on explique ce que ca signifie (l'agent
 * travaille sans rien demander). Un ecran blanc laisserait croire a une panne de chargement.
 */
@Composable
fun PermissionsScreen(
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading && !state.hasAny -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = TetherAccent)
            }
            !state.hasAny -> EmptyApprovals()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                state.error?.let { message ->
                    item(key = "error") {
                        Notice(text = message, tone = Tone.Alert)
                    }
                }
                item(key = "count") {
                    Text(
                        text = if (state.pending.size == 1) {
                            "1 action attend une réponse"
                        } else {
                            "${state.pending.size} actions attendent une réponse"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = TetherTextPrimary,
                    )
                }
                items(state.pending, key = { it.id }) { request ->
                    PermissionCard(
                        request = request,
                        busy = request.id in state.replying,
                        onDecide = { decision -> viewModel.reply(request, decision) },
                    )
                }
            }
        }
    }
}

/**
 * Le cas « rien a approuver ».
 *
 * ⚠️ On **explique** au lieu de laisser un vide : l'utilisateur doit comprendre que l'absence de
 * demande est un etat normal (l'agent travaille avec les droits qu'il a), pas un chargement qui
 * n'aboutit pas.
 */
@Composable
private fun EmptyApprovals() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Lucide.ShieldCheck,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = "Rien à approuver",
            style = MaterialTheme.typography.titleSmall,
            color = TetherTextPrimary,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = "L'agent travaille avec les droits qu'il a déjà. " +
                "Une demande apparaîtra ici dès qu'il aura besoin d'autre chose.",
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * Une demande et ses trois reponses.
 *
 * ⚠️ L'**action** est le titre, en grand, dans la fonte des donnees : c'est le mot que
 * l'utilisateur doit reconnaitre. `bash` ou `edit` ne se traduisent pas — les reformuler ferait
 * perdre precisement l'information qui permet de decider.
 */
@Composable
private fun PermissionCard(
    request: PermissionRequest,
    busy: Boolean,
    onDecide: (PermissionDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Lucide.Hourglass,
                contentDescription = null,
                tint = TetherAlert,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = request.action.ifBlank { "action" },
                style = MaterialTheme.typography.titleSmall,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Ce sur quoi porte l'action : le fait decisif, donc jamais tronque a l'exces.
        if (request.resources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                request.resources.forEach { resource ->
                    Text(
                        text = resource,
                        style = TetherDataStyle,
                        color = TetherTextSecondary,
                    )
                }
            }
        }

        // Message du serveur, s'il en donne un : c'est lui qui explique le POURQUOI.
        request.message?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary.copy(alpha = 0.9f),
            )
        }

        if (request.save.isNotEmpty()) {
            Text(
                text = "« Toujours » mémoriserait : ${request.save.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
            )
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = TetherAccent,
                )
                Text("Envoi de la réponse…", style = TetherDataStyle, color = TetherTextSecondary)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DecisionButton(
                    label = "Refuser",
                    icon = Lucide.X,
                    tint = TetherAlert,
                    onClick = { onDecide(PermissionDecision.Reject) },
                )
                DecisionButton(
                    label = "Une fois",
                    icon = Lucide.Check,
                    tint = TetherAccent,
                    // ⚠️ « Une fois » est propose en PREMIER dans la lecture (apres Refuser, qui
                    // doit rester accessible sans chercher) : c'est le choix qui n'engage rien,
                    // et c'est celui qu'on veut rendre le plus facile.
                    onClick = { onDecide(PermissionDecision.Once) },
                )
                DecisionButton(
                    label = "Toujours",
                    icon = Lucide.ShieldCheck,
                    tint = TetherTextSecondary,
                    // Visuellement plus discret : c'est le seul choix qui survit a la session.
                    subtle = true,
                    onClick = { onDecide(PermissionDecision.Always) },
                )
            }
        }
    }
}

@Composable
private fun DecisionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    subtle: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(tint.copy(alpha = if (subtle) 0.06f else 0.12f))
            // ⚠️ 48 dp : approuver ou refuser une action d'agent est LA décision de l'app. Une
            // cible de 30 dp sur ces boutons serait le pire endroit pour rater son geste.
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md)
            // ⚠️ `role = Button` : TalkBack doit dire « bouton » et pas seulement lire le
            // libellé — sur une décision d'autorisation, la nature de l'élément compte autant que
            // son texte.
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            style = TetherDataStyle,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class Tone { Alert }

@Composable
private fun Notice(text: String, tone: Tone) {
    val color = when (tone) {
        Tone.Alert -> TetherAlert
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(color.copy(alpha = 0.12f))
            .padding(Spacing.md),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
