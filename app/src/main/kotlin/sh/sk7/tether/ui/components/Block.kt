package sh.sk7.tether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions

/**
 * **Un bloc d'information titré** — la primitive de mise en page des écrans d'inventaire.
 *
 * ### Pourquoi une primitive et pas du style répété
 * Les écrans Statistiques, Serveur et Réglages affichent tous des listes de faits groupés par
 * thème. Répéter le fond, la bordure, le rayon et le titre dans chacun garantit qu'ils
 * divergeront au premier ajustement — et une app dont les blocs n'ont pas exactement la même
 * forme **a l'air inachevée**, même quand chaque bloc est correct isolément.
 *
 * ### Le choix de la surface
 * ⚠️ Surface **tonale solide** (`TetherComposerSurface`) + liseré 1 dp, comme la barre de saisie,
 * et pas un aplat translucide. Raison : ces blocs portent des **données à lire** — ils sont le
 * contenu de la page, pas une information secondaire repliée. La translucidité est réservée à ce
 * qui doit se retirer (raisonnement, sortie d'outil), pas à ce qu'on est venu consulter.
 *
 * ⚠️ Titre en capitales espacées et dans la fonte des **données** : c'est une étiquette de
 * section, pas une phrase. Elle doit se lire comme un repère de navigation, pas comme du contenu.
 */
@Composable
fun Block(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TetherComposerSurface, RoundedCornerShape(TetherDimensions.cornerMd))
            .border(
                width = 1.dp,
                color = TetherComposerBorder,
                shape = RoundedCornerShape(TetherDimensions.cornerMd),
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = title.uppercase(),
            style = TetherDataStyle,
            color = sh.sk7.tether.ui.theme.TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}
