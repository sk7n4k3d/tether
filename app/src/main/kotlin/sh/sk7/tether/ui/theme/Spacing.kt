package sh.sk7.tether.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Echelle d'espacement Tether — base **4 dp**.
 *
 * Regle du skill `material-design-3-ui` : « Use a coherent spacing scale; a 4dp rhythm ».
 * Tout espacement de l'app vient d'ici. Aucune valeur arbitraire ailleurs.
 */
object Spacing {
    /** 4 dp — separateur minimal, entre une icone et son libelle. */
    val xs = 4.dp

    /** 8 dp — espacement interne d'un composant. */
    val sm = 8.dp

    /** 12 dp — entre deux elements d'un meme groupe. */
    val md = 12.dp

    /** 16 dp — marge d'ecran standard, entre deux groupes. */
    val lg = 16.dp

    /** 24 dp — separation de sections. */
    val xl = 24.dp

    /** 32 dp — respiration forte (en-tete de page). */
    val xxl = 32.dp
}

/**
 * Dimensions de la signature visuelle Tether : **le fil et les nœuds**.
 *
 * Le rail est le trait continu qui relie toute une conversation ; les nœuds sont les
 * sessions, les brins les sous-agents. Ces valeurs sont la signature de l'app : les
 * modifier change son identite.
 */
object TetherDimensions {
    /** Largeur du rail vertical (le fil). */
    val railWidth = 20.dp

    /** Diametre d'un nœud de session (plein = actif, anneau = termine). */
    val nodeSize = 10.dp

    /** Diametre d'un nœud de sous-agent (plus petit : c'est un brin, pas un nœud). */
    val subNodeSize = 6.dp

    /** Epaisseur du fil principal. */
    val threadWidth = 2.dp

    /** Epaisseur d'un brin de sous-agent (plus fin). */
    val subThreadWidth = 1.dp

    /** Decalage horizontal d'une sous-session sous son parent. */
    val indent = 18.dp

    /** Rayon des surfaces — coherent avec M3 medium. */
    val cornerMd = 12.dp

    /** Rayon des petites surfaces (chips, badges). */
    val cornerSm = 8.dp
}
