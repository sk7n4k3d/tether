package sh.sk7.tether.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.components.Block
import sh.sk7.tether.ui.i18n.Langues
import sh.sk7.tether.ui.theme.Accent
import sh.sk7.tether.ui.theme.ajusterPourContraste
import sh.sk7.tether.ui.theme.SEUIL_ELEMENT
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextPrimary

/**
 * Le choix d'accent.
 *
 * ## Un cercle, pas une pastille carree
 *
 * Le cercle montre la **teinte reelle, ajustee** — celle qui sera a l'ecran. C'est le
 * seul apercu honnete : une pastille qui montre la teinte brute mentirait sur la
 * luminosite, qui est precisement ce que l'ajustement change.
 *
 * ## Pourquoi le contraste est visible
 *
 * Chaque pastille porte un petit trait blanc ou noir, selon ce qui se lit dessus. Un
 * choix de couleur libre produit des pastilles invisibles les unes sur les autres ; ici
 * on **montre** le probleme plutot que de le resoudre silentlyeusement.
 */
@Composable
fun AccentPicker(
    choisi: Accent,
    onChoisir: (Accent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(Accent.entries.toList(), key = { it.cle }) { accent ->
            val affichee = ajusterPourContraste(accent.teinte, Color(0xFF0B0E11), SEUIL_ELEMENT)
            val selectionne = accent == choisi
            val trait = if (luminanceSuffisante(affichee)) Color(0xFF0B0E11) else Color.White

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    // 48 dp de cible + etat annonce : meme regle que le selecteur de langue.
                    .heightIn(min = TetherDimensions.touchTarget)
                    .clickable { onChoisir(accent) }
                    .padding(Spacing.xs)
                    .semantics {
                        role = Role.RadioButton
                        selected = selectionne
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(affichee)
                        .border(
                            width = if (selectionne) 3.dp else 1.dp,
                            color = if (selectionne) TetherTextPrimary else TetherTextMuted,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // Le trait de lisibilite : il dit si du texte se lirait sur la
                    // pastille. Un seul suffit : la lisibilite est le point.
                    Box(
                        modifier = Modifier
                            .size(width = 16.dp, height = 2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(trait),
                    )
                }
                Text(
                    text = accent.libelle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectionne) TetherTextPrimary else TetherTextMuted,
                    fontWeight = if (selectionne) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Une couleur est-elle assez claire pour porter un trait sombre ? Seuil : 3:1. */
private fun luminanceSuffisante(c: Color): Boolean {
    val luminance = 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
    return luminance > 0.5
}

/** La section « Apparence » des reglages : l'accent, et la langue. */
/**
 * Le choix de langue.
 *
 * ## « Suivre le systeme » est propose, et par defaut
 *
 * C'est le choix de la majorite des applications, et surtout le seul qui ne demande rien
 * a l'utilisateur qui veut simplement son telephone en francais. Le forcer dans une langue
 * impose une seconde decision pour eviter la premiere.
 *
 * L'option est **listee en premier** : c'est un choix, pas un oubli, et l'utilisateur doit
 * la voir pour ne pas croire que l'app n'en parle pas.
 */
@Composable
fun LanguePicker(
    choisi: String?,
    onChoisir: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options: List<String?> = listOf(null) + Langues.disponibles.map { it.first }
    val libelles: Map<String?, String> = buildMap {
        put(null, stringResource(R.string.suivre_systeme_2fc03a))
        Langues.disponibles.forEach { put(it.first, it.second) }
    }
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        items(options, key = { it ?: "systeme" }) { code ->
            val selectionne = code == choisi
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    // ⚠️ 48 dp AVANT `clickable` : un `labelSmall` + padding xs faisait ~22 dp
                    // de cible — sous le seuil tactile ET sous le plancher AA de 24 dp.
                    .heightIn(min = TetherDimensions.touchTarget)
                    .clickable { onChoisir(code) }
                    .padding(Spacing.xs)
                    // ⚠️ `selected` : sans lui, TalkBack annonce « bouton radio » mais jamais
                    // lequel est choisi — l'etat existe a l'ecran, pas pour le lecteur.
                    .semantics {
                        role = Role.RadioButton
                        selected = selectionne
                    },
            ) {
                Text(
                    text = libelles[code] ?: code.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selectionne) TetherTextPrimary else TetherTextMuted,
                    fontWeight = if (selectionne) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun AppearanceSection(
    accent: Accent,
    langue: String?,
    onAccent: (Accent) -> Unit,
    onLangue: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Block(title = stringResource(R.string.apparence_80b37d), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = stringResource(R.string.couleur_accent_67bc96),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherTextPrimary,
            )
            Text(
                // Le contraste est ajuste automatiquement : le dire evite que quelqu'un
                // se demande pourquoi sa teinte n'est pas exactement celle qu'il a vue.
                text = stringResource(R.string.luminosite_ajustee_a11cb4),
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
            AccentPicker(choisi = accent, onChoisir = onAccent)

            Text(
                text = stringResource(R.string.langue_661926),
                style = MaterialTheme.typography.bodyMedium,
                color = TetherTextPrimary,
            )
            Text(
                // Le choix se lit au moment ou il change, pas apres : c'est ce qui evite
                // l'ecran « l'app est en anglais alors que mon telephone est en francais ».
                text = stringResource(R.string.langue_suit_systeme_db8273),
                style = TetherDataStyle,
                color = TetherTextMuted,
            )
            LanguePicker(choisi = langue, onChoisir = onLangue)
        }
    }
}
