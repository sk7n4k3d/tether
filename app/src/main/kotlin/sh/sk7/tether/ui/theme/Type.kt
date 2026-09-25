package sh.sk7.tether.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Echelle typographique Tether.
 *
 * Regles appliquees (skill `material-design-3-ui`) :
 *  - la hierarchie ne repose **jamais sur la taille seule** — d'ou les ecarts de poids
 *    marques entre `title` et `body` ;
 *  - **trois tailles suffisent** sur un ecran dense : title, body, label ;
 *  - les surfaces de productivite denses (liste de sessions, chat) restent balayables :
 *    on ne descend pas sous 11 sp, et le `lineHeight` est genereux sur le corps.
 *
 * Le monospace est reserve aux **donnees** (couts, tokens, ids, commandes) : c'est ce qui
 * rend les chiffres alignes et lisibles dans un cockpit.
 */
val TetherTypography = Typography(
    // Titre d'ecran : « Sessions », titre de session.
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    // Titre de composant : carte, section.
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // Corps de lecture : la reponse du modele. C'est le seul texte qu'on lit vraiment.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    // Corps secondaire : raisonnement replie, sortie d'outil.
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    // Metadonnees : agent, age, statut. Petit mais jamais illisible.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
)

/**
 * Style des **donnees chiffrees** (cout, tokens, provider, ids).
 *
 * Monospace + tabular figures : indispensable dans un cockpit, sinon les colonnes de
 * chiffres dansent d'une ligne a l'autre.
 */
val TetherDataStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.sp,
)

/** Style du code et des commandes (sortie d'outil, chemins). */
val TetherCodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 18.sp,
    letterSpacing = 0.sp,
)
