package sh.sk7.tether.ui.welcome

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.QrCode
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.Terminal
import kotlinx.coroutines.launch
import sh.sk7.tether.R
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **L'accueil : ce que l'app fait, et ce qu'il faut avant de s'en servir.**
 *
 * ### Pourquoi un carrousel, et pas un ecran
 *
 * Avant, le premier lancement ouvrait directement sur un formulaire : adresse, mot de
 * passe, repertoire, bouton. Trois champs et pas une phrase pour dire a quoi ils servent.
 * Quelqu'un qui installe Tether pour la voir n'a aucune raison de connaitre le mot de
 * passe de son serveur opencode — et rien ne lui dit qu'il en existe un, ni ou le
 * trouver.
 *
 * ### Ce que les trois pages repondent, dans l'ordre
 *
 *  1. **Ce que c'est** : un client opencode, pas une application autonome.
 *  2. **Pourquoi elle existe** : une session bloquee sur une question que personne ne
 *     voit. C'est la raison d'etre de Tether, et elle n'apparait nulle part ailleurs.
 *  3. **Ce qu'il faut** : un serveur joignable, le plugin dessus, des identifiants — ou
 *     un QR. Les prerequis, enumeres avant qu'on les demande.
 *
 * ### Les trois sorties sont toujours a l'ecran
 *
 * « Passer » en haut, « Scanner le QR » et « Configurer a la main » sur la derniere page.
 * Un carrousel qui oblige a le parcourir entierement pour atteindre le bouton est une
 * interception, pas une presentation : celui qui sait deja ce qu'il vient faire doit
 * pouvoir le dire en un geste.
 *
 * ⚠️ **Le carrousel ne se revoit pas tout seul.** Il est marque comme vu des qu'on le
 * quitte, par « Passer » comme par la derniere page. Sans cette marque, l'app le
 * reafficherait a chaque premier lancement non configure — c'est-a-dire a chaque fois
 * qu'une connexion echoue, ce qui est exactement le moment ou l'utilisateur veut voir
 * le formulaire, pas une presentation.
 */
@Composable
fun WelcomeScreen(
    onTerminer: () -> Unit,
    onScanner: () -> Unit,
    onManuel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(pageCount = { PAGES.size })
    val portee = rememberCoroutineScope()
    val derniere = pager.currentPage == PAGES.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ `targetSdk 37` impose l'edge-to-edge, et cet ecran ne passe pas par un
            // `Scaffold`. Sans ces insets, le titre passe sous la barre d'etat et le
            // dernier bouton sous la pilule de gestes.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        // ---------------------------------------------------------------- EN-TETE
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tether",
                style = MaterialTheme.typography.titleMedium,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // ⚠️ Toujours visible, y compris sur la derniere page : c'est la sortie de
            // secours de quelqu'un qui connait deja l'app et ne veut rien lire.
            TextButton(onClick = onTerminer) {
                Text(
                    text = stringResource(R.string.passer_5dc188),
                    color = TetherTextSecondary,
                )
            }
        }

        // ---------------------------------------------------------------- LES PAGES
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { rang ->
            PageAccueil(PAGES[rang])
        }

        // ------------------------------------------------------- L'INDICATEUR
        // ⚠️ Les points sont **muets** pour un lecteur d'ecran : trois rectangles sans
        // texte. On annonce donc la position en clair sur la ligne qui les porte, sinon
        // le seul repere de progression disparait pour qui ne voit pas la forme.
        val position = stringResource(R.string.page_sur_83099c, pager.currentPage + 1, PAGES.size)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md)
                .semantics { contentDescription = position },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PAGES.indices.forEach { rang ->
                val actif = rang == pager.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.xs)
                        .height(6.dp)
                        .width(if (actif) 20.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (actif) LocalAccent.current
                            else TetherTextMuted.copy(alpha = 0.35f)
                        ),
                )
            }
        }

        // ---------------------------------------------------------------- L'ACTION
        if (derniere) {
            Button(
                onClick = onScanner,
                shape = RoundedCornerShape(TetherDimensions.cornerMd),
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ `heightIn(min = ...)` et non `height(...)` : a 200 % de taille de
                    // police, un libelle qui double de hauteur dans une hauteur fixe est
                    // coupe. Le minimum garde l'epaisseur, sans plafonner la croissance.
                    .heightIn(min = 52.dp),
            ) {
                Icon(Lucide.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.scanner_qr_1f4e1d))
            }
            TextButton(
                onClick = onManuel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.configurer_main_f6bbdd),
                    color = TetherTextSecondary,
                )
            }
        } else {
            Button(
                onClick = {
                    portee.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                shape = RoundedCornerShape(TetherDimensions.cornerMd),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.suivant_596d29))
                Spacer(Modifier.width(Spacing.sm))
                Icon(Lucide.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Une page du carrousel.
 *
 * `corps` est nullable parce que la troisieme page ne raconte rien : elle **enonce**.
 * Un paragraphe suivi de trois puces qui repetent le paragraphe est le defaut de la
 * plupart des ecrans d'accueil.
 */
private data class PageAccueil(
    @StringRes val titre: Int,
    @StringRes val corps: Int?,
    val icone: ImageVector,
    val puces: List<Int> = emptyList(),
)

/**
 * ⚠️ L'ordre n'est pas cosmetique : l'application d'abord, la raison ensuite, les
 * prerequis en dernier. Mettre les prerequis en premier demanderait a l'utilisateur
 * d'accepter une contrainte avant de savoir pourquoi.
 */
private val PAGES = listOf(
    PageAccueil(
        titre = R.string.ton_serveur_opencode_poche_e3024a,
        corps = R.string.lis_session_reponds_question_cf78cb,
        icone = Lucide.Terminal,
    ),
    PageAccueil(
        titre = R.string.session_attendre_heures_c4ad39,
        corps = R.string.opencode_demande_permission_896635,
        icone = Lucide.Hourglass,
    ),
    PageAccueil(
        titre = R.string.ce_qu_il_te_faut_008c8f,
        corps = null,
        icone = Lucide.ShieldCheck,
        puces = listOf(
            R.string.serveur_opencode_joignable_60677a,
            R.string.plugin_tether_installe_88354f,
            R.string.adresse_mot_passe_qr_145129,
        ),
    ),
)

@Composable
private fun PageAccueil(page: PageAccueil) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ Chaque page defile seule, verticalement. A 200 % de taille de police, le
            // texte du deuxieme ecran depasse la hauteur d'un petit telephone : sans ce
            // defilement, la fin du paragraphe est inaccessible, et le bouton « Suivant »
            // reste visible pendant qu'on ne peut pas lire ce qu'on valide.
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(TetherDimensions.cornerMd))
                .background(TetherComposerSurface)
                .border(
                    1.dp,
                    TetherComposerBorder,
                    RoundedCornerShape(TetherDimensions.cornerMd),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icone,
                contentDescription = null,
                tint = LocalAccent.current,
                modifier = Modifier.size(30.dp),
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = stringResource(page.titre),
            style = MaterialTheme.typography.headlineSmall,
            color = TetherTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        page.corps?.let { corps ->
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(corps),
                style = MaterialTheme.typography.bodyLarge,
                color = TetherTextSecondary,
            )
        }

        if (page.puces.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            page.puces.forEach { puce ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = null,
                        tint = LocalAccent.current,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(puce),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TetherTextSecondary,
                    )
                }
            }
        }
    }
}
