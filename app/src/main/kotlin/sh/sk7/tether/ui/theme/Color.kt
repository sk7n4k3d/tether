package sh.sk7.tether.ui.theme

import androidx.compose.ui.graphics.Color

val TetherBackground = Color(0xFF0B0E11)
val TetherSurface = Color(0xFF11151A)
val TetherTextPrimary = Color(0xFFE6EDF3)
val TetherTextSecondary = Color(0xFF8B98A5)
val TetherAccent = Color(0xFF2DD4BF)
val TetherAlert = Color(0xFFFFB020)

/**
 * Surface de **saisie** : plus presente que les blocs d'information.
 *
 * ⚠️ Choix assume contre le reste de l'app. Les blocs de contenu (raisonnement, outils) sont
 * translucides, parce qu'ils doivent **se retirer** : on les lit une fois. La barre de saisie
 * est l'inverse — c'est le seul element avec lequel on **agit**, en permanence. Une surface qui
 * s'efface serait un contresens : elle doit se voir et se viser.
 *
 * ⚠️ Valeur **mesuree**, pas choisie a l'œil. La translucidite floutee est la signature
 * d'iMessage / Liquid Glass, **pas** des composers d'IA : ChatGPT, Claude, Gemini et Grok
 * utilisent tous une surface **tonale solide**, sans ombre. Mesure des ecarts reels au fond :
 *
 *   ChatGPT `#212121` sur `#000000` -> 1.30
 *   Grok    `#212121` sur `#141414` -> 1.14
 *   Gemini  `#1e1f20` sur fond noir -> 1.27
 *
 * Ma premiere version (`#161B21`) donnait **1.12** : indistinguable du fond sur un ecran aussi
 * sombre que le mien, donc la barre avait l'air « posee dans le vide ». `#1F262E` donne
 * **1.27**, soit exactement la reference Gemini — la barre se detache sans devenir un aplat.
 * Le texte y garde 12.93 de contraste (seuil 4.5).
 */
val TetherComposerSurface = Color(0xFF1F262E)

/**
 * Liseré de la barre de saisie : **1 dp, jamais plus**.
 *
 * ⚠️ **Mesure live des references** (DevTools sur chatgpt.com et claude.ai, 25/09/2026) :
 *  - ChatGPT : `1px` de `rgba(255,255,255,0.2)` sur `#212121` → ecart **1.90** ;
 *  - Claude  : ring inset `1px` de `rgba(255,255,255,0.1)` sur `#20201f`.
 *
 * ⚠️ Les deux publient aussi une **ombre tres faible** (ChatGPT mobile
 * `0 4px 16px rgba(0,0,0,0.05)`, Claude `0 4px 20px rgba(0,0,0,0.035)`) — j'avais donc tort de
 * dire « aucune ombre ». Mais sur mon fond `#0B0E11`, une ombre **noire** a 5 % est
 * litteralement invisible (noir sur quasi-noir) : elle ne coute rien et ne sert a rien. La
 * bordure d'1 dp est ici le seul outil qui delimite reellement la surface, et je la cale sur
 * l'ecart de ChatGPT (**~1.9**), pas en dessous.
 */
val TetherComposerBorder = Color(0xFF49535F)

object TetherColors {
    val background = TetherBackground
    val surface = TetherSurface
    val textPrimary = TetherTextPrimary
    val textSecondary = TetherTextSecondary
    val accent = TetherAccent
    val alert = TetherAlert
    val composerSurface = TetherComposerSurface
    val composerBorder = TetherComposerBorder
}
