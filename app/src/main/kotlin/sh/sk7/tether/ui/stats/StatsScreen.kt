package sh.sk7.tether.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.sk7.tether.domain.model.DailyActivity
import sh.sk7.tether.domain.model.ModelUsage
import sh.sk7.tether.domain.model.UsageStats
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import java.util.Locale

/**
 * **La page Statistiques — ce que l'instrument a reellement fait.**
 *
 * ### Pourquoi ce n'est pas un gadget
 * Le `design-soul.md` dit : « Tu ne discutes pas avec un agent, tu pilotes un systeme qui
 * tourne ailleurs, avec ton argent. » Cette page est la seule qui reponde a la question qui
 * compte sur la duree : **est-ce que ca vaut ce que ca coute, et ou part l'argent ?**
 *
 * ### Ce qu'elle montre, et pourquoi chaque chiffre
 *  - **le cout et les tokens** — ce qu'on paie, en tete ;
 *  - **la repartition par modele, triee par cout** — pas par popularite : c'est la ligne qui
 *    permet de decider quel modele changer ;
 *  - **l'activite par jour** — la seule vue qui montre le rythme reel, trous compris ;
 *  - **le taux d'echec des outils** — un chiffre qu'aucun client ne montre, et qui dit si
 *    l'agent galere ;
 *  - **le cache** — mesure sur ce profil : **94 %** du volume est du cache lu. Le cacher serait
 *    mentir sur ce qui se passe vraiment.
 *
 * ⚠️ **Tout vient du serveur**, rien n'est recalcule depuis les sessions chargees : celles-ci
 * sont paginees, donc un total calcule cote app serait **faux** — et un chiffre faux est pire
 * qu'un chiffre absent.
 *
 * ⚠️ Les valeurs nulles ne s'affichent pas. Pas de « 0,00 $ » decoratif : si le serveur ne
 * compte rien, on ne dit rien.
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            StatsUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = TetherAccent)
            }
            is StatsUiState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TetherAlert,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
            is StatsUiState.Loaded -> StatsContent(current.stats, range, viewModel::setRange)
        }
    }
}

@Composable
private fun StatsContent(
    stats: UsageStats,
    selectedRange: StatsRange,
    onRangeSelect: (StatsRange) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "range") {
            RangeSelector(
                selected = selectedRange,
                onSelect = onRangeSelect,
            )
        }

        item(key = "cost") {
            Block(title = "Consommation") {
                CostRow(stats)
            }
        }

        item(key = "scale") {
            Block(title = "Volume") {
                ScaleRow(stats)
            }
        }

        if (stats.activity.isNotEmpty()) {
            item(key = "activity") {
                Block(title = "Activité par jour") {
                    ActivityChart(stats.activity)
                }
            }
        }

        item(key = "reliability") {
            Block(title = "Fiabilité") {
                ReliabilityRow(stats)
            }
        }

        if (stats.models.isNotEmpty()) {
            item(key = "models") {
                Block(title = "Par modèle") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        stats.models.forEach { ModelLine(it, stats.cost) }
                    }
                }
            }
        }

        if (stats.cacheReadTokens > 0) {
            item(key = "cache-note") {
                Text(
                    // ⚠️ On explique le cache au lieu de le cacher. Mesure sur ce profil :
                    // 5,5 Md de tokens de cache pour 249 M d'entree — le cache est **94 %** du
                    // volume. Sans cette note, « 249 M in » parait faux quand on voit la facture.
                    text = "Le cache représente ${percent(stats.tokenCacheRatio)} de l'entrée. " +
                        "C'est ce qui évite de renvoyer tout le contexte à chaque étape.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextMuted,
                )
            }
        }
    }
}

/** Sélecteur de plage. `null` = tout l'historique du serveur. */
@Composable
private fun RangeSelector(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        StatsRange.entries.forEach { range ->
            val active = range == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                    .background(
                        if (active) TetherAccent.copy(alpha = 0.18f) else Color.Transparent,
                    )
                    // ⚠️ 48 dp : une puce de filtre est une cible fréquente et sa hauteur visuelle
                    // est d'environ 24 dp. `heightIn` avant `clickable` porte la zone sensible à
                    // la taille exigée sans épaissir la puce.
                    .heightIn(min = TetherDimensions.touchTarget)
                    .clickable { onSelect(range) }
                    .padding(horizontal = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = range.label,
                    style = TetherDataStyle,
                    color = if (active) TetherAccent else TetherTextSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CostRow(stats: UsageStats) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BigFigure(
            value = money(stats.cost),
            label = "Coût sur la période",
            accent = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Figure(stats.sessions.toString(), "sessions")
            Figure(stats.subagents.toString(), "sous-agents")
            Figure(stats.prompts.toString(), "prompts")
            Figure(stats.steps.toString(), "étapes")
        }
    }
}

@Composable
private fun ScaleRow(stats: UsageStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Figure(count(stats.inputTokens), "tokens in")
        Figure(count(stats.outputTokens), "tokens out")
        // ⚠️ Le cache est compte a part, jamais additionne aux entrees : le confondre ferait
        // paraitre la consommation 20 fois plus grosse qu'elle n'est.
        Figure(count(stats.cacheReadTokens), "cache lu")
    }
}

@Composable
private fun ReliabilityRow(stats: UsageStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Figure(stats.toolCalls.toString(), "appels d'outil")
        Figure(
            value = percent(stats.toolFailureRate),
            label = "taux d'échec",
            // ⚠️ Seuils explicites : au-dela de 5 % d'echecs, l'agent a un vrai probleme et le
            // chiffre doit se voir. Un taux neutre ne declencherait aucune reaction.
            alert = (stats.toolFailureRate ?: 0.0) > 5.0,
        )
        Figure(
            value = stats.activeDays.toString(),
            label = "jours actifs",
        )
        if (stats.streak > 1) {
            Figure("${stats.streak} j", "d'affilée")
        }
    }
}

/**
 * Histogramme d'activité, **dessiné a la main**.
 *
 * ⚠️ Aucune dependance de graphiques : elles pesent des megaoctets pour un seul histogramme, et
 * Tether n'en a qu'un. `drawBehind` sur un `Canvas` de la taille voulue suffit, et ca reste dans
 * la regle « aucune nouvelle dependance hors necessite prouvee » du `design-soul.md`.
 *
 * ⚠️ Le maximum n'est **pas** normalisé a 100 % : la barre la plus haute represente le pic reel,
 * et le pic est **annote**. Sans l'annotation, un histogramme relatif ne dit pas l'echelle, donc
 * ne dit rien.
 */
@Composable
private fun ActivityChart(activity: List<DailyActivity>) {
    val max = activity.maxOfOrNull { it.steps }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .drawBehind {
                    val count = activity.size
                    if (count == 0) return@drawBehind
                    val gap = size.width * 0.02f
                    val barWidth = (size.width - gap * (count - 1)) / count
                    activity.forEachIndexed { index, day ->
                        // ⚠️ Les jours SANS activite gardent une barre minimale : le trou fait
                        // partie de l'information. Un histogramme qui saute les jours vides
                        // invente une continuite qui n'existe pas.
                        val ratio = day.steps.toFloat() / max
                        val h = (size.height * ratio).coerceAtLeast(if (day.steps == 0) 1.5f else 4f)
                        val left = index * (barWidth + gap)
                        drawRect(
                            color = if (day.steps == 0) {
                                TetherTextSecondary.copy(alpha = 0.18f)
                            } else {
                                TetherAccent.copy(alpha = 0.30f + 0.70f * ratio)
                            },
                            topLeft = Offset(left, size.height - h),
                            size = Size(barWidth, h),
                        )
                    }
                },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = activity.first().date,
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
            Text(
                text = "pic ${activity.maxOf { it.steps }} étapes",
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
            Text(
                text = activity.last().date,
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
        }
    }
}

/**
 * Une ligne par modele : cout, part du total, et volume.
 *
 * ⚠️ La **part** est aussi importante que le montant : « 54 $ » ne dit rien si on ne sait pas
 * que c'est 88 % de la facture. On montre les deux, et la barre rend la comparaison immediate.
 */
@Composable
private fun ModelLine(usage: ModelUsage, totalCost: Double) {
    val share = if (totalCost <= 0.0) 0.0 else usage.cost / totalCost
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = usage.model.ifBlank { "(modèle inconnu)" },
                style = TetherDataStyle,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = money(usage.cost),
                style = TetherDataStyle,
                color = TetherAccent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = usage.provider,
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
            Text(
                text = "${percent(share * 100)} · ${count(usage.inputTokens + usage.outputTokens)} tok",
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
        }
        // Barre de part : le rapport se voit sans lire les chiffres.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(TetherTextSecondary.copy(alpha = 0.14f), RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(TetherAccent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

// ------------------------------------------------------------------ primitives

@Composable
private fun BigFigure(value: String, label: String, accent: Boolean = false) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (accent) TetherAccent else TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = TetherDataStyle,
            color = TetherTextSecondary,
        )
    }
}

@Composable
private fun Figure(value: String, label: String, alert: Boolean = false) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = if (alert) TetherAlert else TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = TetherDataStyle,
            color = TetherTextSecondary,
        )
    }
}

// ------------------------------------------------------------------ formatage

private fun money(value: Double): String =
    String.format(Locale.FRANCE, "%.2f $", value)

/** `null` (pas de mesure) s'affiche « — », jamais « 0 % » : ce sont deux faits differents. */
private fun percent(value: Double?): String =
    value?.let { String.format(Locale.FRANCE, "%.1f %%", it) } ?: "—"

private fun count(value: Long): String = when {
    value >= 1_000_000_000 -> "%.1f Md".format(Locale.FRANCE, value / 1_000_000_000.0)
    value >= 1_000_000 -> "%.1f M".format(Locale.FRANCE, value / 1_000_000.0)
    value >= 1_000 -> "%.1f k".format(Locale.FRANCE, value / 1_000.0)
    else -> value.toString()
}
