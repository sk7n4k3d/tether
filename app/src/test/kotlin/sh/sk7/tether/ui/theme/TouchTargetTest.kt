package sh.sk7.tether.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **La cible tactile minimale est une constante, et elle vaut 48 dp.**
 *
 * ### Pourquoi ce test minuscule
 * ⚠️ Un seuil d'accessibilité qui vit dans un commentaire **dérive**. Quelqu'un trouve une ligne
 * trop haute, descend la constante à 40 « pour densifier », et la régresse silencieusement dans
 * tout l'écran d'un coup puisqu'elle est partagée. Ce test fige la valeur contre la source de
 * vérité externe :
 *
 *  - **WCAG 2.2, 2.5.8 « Target Size (Minimum) »** : 24 × 24 CSS px est le plancher absolu, mais
 *    c'est une exigence de niveau AA assortie de dérogations ;
 *  - **Material 3** recommande **48 dp** pour une cible tactile, et c'est cette valeur que la
 *    plateforme Android respecte (`minimumInteractiveComponentSize`) ;
 *  - l'**European Accessibility Act** s'applique aux applications mobiles grand public depuis le
 *    **28 juin 2025**.
 *
 * ⚠️ On vise 48 et pas 24 : sur un téléphone tenu à une main, une cible de 24 dp est ratée une
 * fois sur quelques essais, et l'app la plus gênée par une cible ratée est justement celle-ci —
 * un cockpit d'agent qu'on ouvre en marchant.
 */
class TouchTargetTest {

    @Test
    fun `la cible tactile minimale fait 48 dp`() {
        assertEquals(
            48f,
            TetherDimensions.touchTarget.value,
            "48 dp est le seuil Material 3, et la valeur sur laquelle les écrans sont audités",
        )
    }

    @Test
    fun `la cible est plus grande que le rail et le noeud, qui sont la signature visuelle`() {
        // ⚠️ Le test encode la distinction entre **ce qu'on voit** et **ce qu'on touche** : le
        // rail (20 dp) et le nœud (10 dp) sont la signature de l'app et ne doivent **pas** grossir
        // pour atteindre le seuil. C'est la zone sensible qui s'étend autour d'eux. Ce test
        // empêche les deux erreurs symétriques — épaissir le fil pour « corriger » la cible, ou
        // accepter une cible de 20 dp parce que le fil est visible.
        assertTrue(
            TetherDimensions.touchTarget > TetherDimensions.railWidth,
            "la cible ne doit pas être confondue avec la largeur du rail",
        )
        assertTrue(
            TetherDimensions.touchTarget > TetherDimensions.nodeSize,
            "la cible ne doit pas être confondue avec le diamètre du nœud",
        )
    }
}
