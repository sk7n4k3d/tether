package sh.sk7.tether.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * **« Réduire les animations » est-il actif ?**
 *
 * ### Pourquoi c'est une obligation, pas un confort
 * Android expose ce réglage via `ANIMATOR_DURATION_SCALE` (0 quand l'utilisateur a demandé à
 * réduire les animations, dans les options développeur ou via l'accessibilité). Les WCAG 2.2
 * (2.3.3, « Animation from Interactions ») demandent que le mouvement puisse être désactivé, et
 * l'European Accessibility Act s'applique aux applications mobiles grand public depuis le
 * 28 juin 2025.
 *
 * ⚠️ Ce n'est pas qu'une question de préférence esthétique : un mouvement continu peut provoquer
 * un **malaise vestibulaire** réel chez certaines personnes. Une pulsation « discrète » pour qui
 * ne la remarque pas peut être invalidante pour qui la subit.
 *
 * ### Ce qui est conditionné, et ce qui ne l'est pas
 * ⚠️ On ne coupe **que** les **animations en boucle infinie** (la pulsation d'un nœud, le liséré
 * d'activité). Les transitions courtes et ponctuelles — une couleur qui change, un chevron qui
 * pivote à l'ouverture — **restent** : elles durent moins de 300 ms, elles répondent à un geste,
 * et les supprimer retirerait le retour visuel qui dit que l'action a été prise en compte. La
 * recommandation porte sur le mouvement **automatique et prolongé**, pas sur toute transition.
 *
 * ⚠️ Lecture **défensive** (`runCatching` + défaut `true`) : `Settings.Global` est une lecture
 * système qui peut échouer ; refuser d'animer parce qu'une lecture a échoué serait un échec pire
 * que le problème qu'on traite.
 */
@Composable
fun animationsAllowed(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
}
