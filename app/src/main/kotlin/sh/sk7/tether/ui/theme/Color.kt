package sh.sk7.tether.ui.theme

import androidx.compose.ui.graphics.Color

val TetherBackground = Color(0xFF0B0E11)
val TetherSurface = Color(0xFF11151A)
val TetherTextPrimary = Color(0xFFE6EDF3)
val TetherTextSecondary = Color(0xFF8B98A5)
val TetherAccent = Color(0xFF2DD4BF)
val TetherAlert = Color(0xFFFFB020)

/**
 * **Texte secondaire attenue, au seuil WCAG AA.**
 *
 * ⚠️ C'est une **mesure**, pas un gout. Le seuil pour du texte normal est **4.5:1** (WCAG 2.1 AA,
 * et l'European Accessibility Act s'applique aux applications mobiles grand public depuis le
 * 28 juin 2025). Contraste calcule de `#8B98A5` attenue sur les trois fonds reels de l'app :
 *
 * ```
 *   alpha   sur #0B0E11   sur #11151A   sur #1F262E (surface de saisie)   verdict
 *   0.70        4.18         3.96         3.30                             ÉCHEC
 *   0.80        4.87         4.61         3.84                             ÉCHEC
 *   0.85        5.28         5.00         4.17                             ÉCHEC
 *   0.90        5.70         5.40         4.50                             PASSE
 *   1.00        6.57         6.22         5.19                             PASSE
 * ```
 *
 * ⚠️ **La surface de saisie est le couple contraignant** : c'est le fond le plus clair, donc celui
 * ou un texte attenue passe en dernier. 0.85 — la valeur la plus repandue avant cette mesure —
 * donnait **4.17**, sous le seuil, sur une apparence a priori correcte. C'est le mode d'echec le
 * plus vicieux : illisible pour qui en a besoin, invisible pour qui ne le remarque pas.
 *
 * ⚠️ Pour une **icone** ou un element graphique, le seuil est **3:1** et 0.7 suffit (`3.30`) :
 * [TetherIconMuted] existe pour ca, afin qu'on ne rabaisse pas le seuil du texte par confusion.
 */
val TetherTextMuted = Color(0xFF808D99)

/**
 * **Icone attenuee, au seuil des elements non textuels (3:1).**
 *
 * ⚠️ Contraste mesure de `#6B7681` sur `#1F262E` = **3.30**, sur `#0B0E11` = **4.18**. Le seuil
 * WCAG pour un composant d'interface ou un graphique est de **3:1** (1.4.11), pas 4.5 : une icone
 * n'est pas du texte. La distinguer du texte evite deux erreurs symetriques — eclaircir des icones
 * sans raison, ou garder du texte trop pale parce qu'une icone passait.
 */
val TetherIconMuted = Color(0xFF6B7681)

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
    val textMuted = TetherTextMuted
    val iconMuted = TetherIconMuted
}
