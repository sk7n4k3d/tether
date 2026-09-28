package sh.sk7.tether.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Lucide
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import java.util.Locale
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res
import androidx.compose.ui.res.pluralStringResource

/**
 * **Ce qui occupe la fenetre de contexte, et ce que ca coute.**
 *
 * ### Le probleme que cet ecran resout
 * C'est la demande la mieux votee de tout le suivi d'opencode (issue #6152, 145 👍) :
 * *« what is eating my context window »*. Sans cette vue, on ne comprend ni pourquoi une session
 * « oublie » des choses, ni pourquoi la facture monte.
 *
 * ⚠️ **Ce n'est pas l'historique.** La route liste les messages **envoyes au prochain tour**.
 * Mesure du 2026-09-25 sur une session reelle : 445 messages echanges, **96 dans la fenetre** —
 * dont un seul resume de compaction tenant lieu de tout ce qui a precede. Afficher « 96 » la ou
 * la conversation en compte 445 est correct, et c'est precisement le fait interessant.
 *
 * ### Pourquoi grupper par type plutot que lister
 * Parce que la reponse est la : sur cette session, `assistant` = **68 % du cout** et `compaction`
 * = **32 % pour un seul message**. Une liste a plat de 96 lignes noierait ce fait sous le detail.
 */
@Composable
fun SessionContextScreen(
    modifier: Modifier = Modifier,
    viewModel: ContextViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            ContextUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current)
            }

            ContextUiState.Empty -> Centered(
                title = stringResource(R.string.rien_fenetre_344f52),
                body = "Cette session n'a encore rien envoyé à l'agent. " +
                    stringResource(R.string.fenetre_remplira_premier_9569e8),
                alert = false,
            )

            is ContextUiState.Error -> Centered(
                title = stringResource(R.string.contexte_indisponible_58230a),
                body = current.message,
                alert = true,
            )

            is ContextUiState.Loaded -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item(key = "totals") {
                    Block(title = stringResource(R.string.fenetre_3d6916)) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(
                                text = pluralStringResource(R.plurals.message, current.entryCount, current.entryCount) +
                                    (if (current.entryCount > 1) "s" else "") +
                                    " partiront au prochain tour",
                                style = MaterialTheme.typography.titleSmall,
                                color = TetherTextPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                                Figure(count(current.totalInput), stringResource(R.string.tokens_f0ab08))
                                Figure(count(current.totalOutput), stringResource(R.string.tokens_out_332354))
                                Figure(money(current.totalCost), "coût")
                            }
                        }
                    }
                }

                // ⚠️ Le message le plus lourd est mis en avant AVANT le detail par type. Mesure :
                // un seul resume pesait un tiers de la facture. Sans ce reperage, il faut
                // additionner 96 lignes pour le voir.
                current.heaviest?.let { heavy ->
                    item(key = "heaviest") {
                        Block(title = stringResource(R.string.lourd_dc257e)) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = labelFor(heavy.type),
                                        style = TetherDataStyle,
                                        color = TetherAlert,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = money(heavy.cost),
                                        style = TetherDataStyle,
                                        color = TetherAlert,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (heavy.summarySize > 0) {
                                    // ⚠️ On dit la TAILLE du resume, pas son contenu : c'est la
                                    // taille qui explique ou est passe le contexte. Afficher
                                    // 10 000 caracteres noierait l'ecran sans rien apprendre.
                                    Text(
                                        text = stringResource(R.string.resume_count_heavy_9f1c77, count(heavy.summarySize.toLong())) +
                                            " caractères — il tient lieu de tout ce qui précède.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TetherTextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "by-type-title") {
                    Text(stringResource(R.string.part_cout_728b35),
                        style = MaterialTheme.typography.titleSmall,
                        color = TetherTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                items(current.groups, key = { it.type }) { group ->
                    GroupRow(group)
                }
            }
        }
    }
}

/**
 * Une categorie : son nom, son compte, son cout, sa part.
 *
 * ⚠️ On affiche le **compte** a cote du nom : « Résumés · 1 » contre « Réponses · 95 » explique
 * pourquoi le premier pese autant malgre sa solitude. Sans le compte, la comparaison des montants
 * semblerait arbitraire.
 */
@Composable
private fun GroupRow(group: ContextGroup) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TetherTextPrimary,
                )
                Text(
                    text = "· ${group.count}",
                    style = TetherDataStyle,
                    color = TetherTextSecondary,
                )
            }
            Text(
                text = if (group.cost > 0.0) money(group.cost) else "—",
                style = TetherDataStyle,
                color = if (group.costShare > 0.3f) TetherAlert else TetherTextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (group.inputTokens > 0 || group.outputTokens > 0) {
            Text(
                text = "${count(group.inputTokens)} in · ${count(group.outputTokens)} out",
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
        }

        // Barre de part : le rapport se voit sans calcul mental.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(TetherTextSecondary.copy(alpha = 0.14f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(group.costShare.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(LocalAccent.current, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Libelle lisible d'un type serveur. Le type brut est la cle ; ceci n'est qu'un affichage. */
private fun labelFor(type: String): String = when (type) {
    "assistant" -> Res.of(R.string.reponse_agent_6c6352)
    "user" -> Res.of(R.string.ton_message_1677cf)
    "compaction" -> Res.of(R.string.resume_compaction_98e00d)
    "synthetic" -> Res.of(R.string.message_synthetique_a75874)
    "system" -> Res.of(R.string.message_systeme_11d7e5)
    "idle" -> Res.of(R.string.fin_tour_173454)
    else -> type
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = label, style = TetherDataStyle, color = TetherTextSecondary)
    }
}

@Composable
private fun Centered(title: String, body: String, alert: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Lucide.Layers,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (alert) TetherAlert else TetherTextPrimary,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

private fun money(value: Double): String =
    if (value <= 0.0) "—" else String.format(Locale.FRANCE, "%.3f $", value)

private fun count(value: Long): String = when {
    value >= 1_000_000 -> "%.1f M".format(Locale.FRANCE, value / 1_000_000.0)
    value >= 1_000 -> "%.1f k".format(Locale.FRANCE, value / 1_000.0)
    else -> value.toString()
}
