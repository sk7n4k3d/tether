package sh.sk7.tether.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Blocks
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.X
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.animationsAllowed
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherIconMuted
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **La barre de saisie — une piece d'instrument, pas un formulaire.**
 *
 * ### Ce qui n'allait pas (et pourquoi ca faisait « annee 2000 »)
 * L'ancienne version etait un `OutlinedTextField` Material avec **label flottant**, **bordure
 * dure sur les 4 cotes**, **placeholder indenté** et un `IconButton` gris **a cote** du champ.
 * C'est le formulaire d'inscription de 2014 : la bordure crie « remplis-moi », le bouton
 * flotte hors du champ, et rien ne dit que l'app attend ou qu'elle travaille.
 *
 * ### Ce qui remplace
 * Une **surface unique** qui contient tout : le texte, et l'action **a l'interieur**, a droite.
 * Trois principes non negociables :
 *
 * 1. **Aucune bordure, aucun label.** Le champ est delimite par sa **teinte de surface**, pas
 *    par un trait. C'est ce que font ChatGPT, Claude et Cursor : on n'encadre pas une zone de
 *    saisie, on la **pose**.
 * 2. **Le bouton EST un etat, pas un controle separe.** Rouge et carre quand une execution
 *    tourne (appuyer = interrompre un processus vivant), teal et fleche quand il y a du texte.
 *    Il **grandit** et change de couleur — la difference se voit sans lire.
 * 3. **Il grandit avec le texte**, jusqu'a 6 lignes, puis defile **a l'interieur** : au-dela,
 *    la barre de saisie ne doit plus manger l'ecran de lecture.
 *
 * ### La barre est aussi un **temoin**
 * ⚠️ Un lisere teal pulse sous la barre quand un tour est en cours. C'est le fil du
 * `design-soul.md` qui passe par la : l'instrument **dit** qu'il travaille, il ne l'espere pas.
 *
 * ⚠️ `BasicTextField` et non `TextField` : le composant Material impose son propre
 * `contentPadding`, son label et son conteneur. Pour une surface vraiment nue, il faut la
 * primitive. Le prix a payer est explicite : **c'est a nous** de gerer le placeholder, le curseur
 * et la selection.
 */
@Composable
fun Composer(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Lance la dictee vocale. `null` masque le micro (aucun moteur, ou contexte sans dictee). */
    onVoice: (() -> Unit)? = null,
    /** Dictee en cours : le micro le dit, sinon le geste semble ignore. */
    listening: Boolean = false,
    /**
     * Ouvre le selecteur modele/agent. Attache au bouton d'envoi quand le champ est vide, **et a
     * la pastille de modele** (voir [currentModelLabel]).
     *
     * ⚠️ Sur le bouton d'envoi, et pas une icone a part : choisir un modele ne demande rien a
     * ecrire, c'est une action **au repos**. Une icone dediee prendrait une place permanente dans
     * une barre ou chaque pixel coute au champ de saisie.
     */
    onPickModelAgent: (() -> Unit)? = null,
    /** Ouvre la feuille **agents**. `null` masque le bouton. */
    onOpenAgents: (() -> Unit)? = null,
    /**
     * **Le modele en cours, en permanence** — et la pastille qui permet d'en changer.
     *
     * ⚠️ `null` = **le serveur n'a rien resolu** (session fraiche, aucun tour lance : mesure du
     * 2026-09-26, `POST /api/session` laisse `model` a `null`). On affiche alors un tiret et
     * **rien d'autre** : ni `/api/model/default` (une valeur de configuration, pas ce qu'on
     * obtient), ni le modele de la derniere session (un autre choix, presente comme un fait).
     *
     * ⚠️ **La pastille survit au `Stop`.** Pendant un tour, le bouton d'envoi est un arret : c'est
     * le seul endroit ou le composer pouvait etre atteint, donc le modele devenait
     * **inchangeable en cours d'execution** — alors que le TUI le permet. Elle est ici hors du
     * `Row` du bouton, donc cliquable dans les deux etats.
     */
    currentModelLabel: String? = null,
    /**
     * Les fichiers joints au prochain envoi.
     *
     * ⚠️ On recoit la liste deja construite et on **remonte** les retraits : le Composer ne lit ni
     * n'ecrit le disque, il montre et il signale. Toute la logique d'URI et de taille vit dans le
     * ViewModel, ou elle est testee.
     */
    attachments: List<PendingAttachment> = emptyList(),
    /** Retire une piece jointe (par son nom). */
    onRemoveAttachment: (String) -> Unit = {},
    /** Ouvre le selecteur de fichiers. `null` masque le trombone. */
    onAttach: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasText = value.isNotBlank()
    // ⚠️ Un fichier joint EST un message : « regarde ceci » se dit sans phrase. Le bouton d'envoi
    // doit donc s'allumer et envoyer sur cette seule base — sinon on ne pourrait rien joindre
    // sans accompagner le fichier d'un texte, ce qui n'est pas ce qu'on veut faire la plupart du
    // temps.
    val hasContent = hasText || attachments.isNotEmpty()

    // Le bouton change de sens : « interrompre » pendant un tour, « envoyer » sinon.
    // ⚠️ Des que du contenu est present, ENVOYER prime — sinon on ne pourrait pas repondre a un
    // agent qui tourne (le cas `steer` de l'API), ce qui est precisement ce qu'on veut faire.
    val showStop = busy && !hasContent

    // Couleur du bouton : ambre (interrompre) > teal (envoyer) > muet (vide).
    val buttonColor by animateColorAsState(
        targetValue = when {
            showStop -> TetherAlert
            hasContent -> LocalAccent.current
            // ⚠️ 0.55 et non 0.25 : a 0.25 la fleche disparait et l'app perd son
            // point d'entree principal. Lumo la garde visible et ternit — c'est un controle
            // visiblement inactif, pas un controle absent.
            else -> TetherTextSecondary.copy(alpha = 0.55f)
        },
        label = Res.of(R.string.composer_button_color_c4b0b5),
    )
    val buttonSize by animateDpAsState(
        // Il grandit quand il devient actif : l'etat se lit a la silhouette, pas seulement
        // a la couleur (regle d'accessibilite : jamais la couleur seule).
        //
        // ⚠️ 40 dp et non 30. Mesure : Grok utilise un cercle 36x36 (`h-9 w-9 rounded-full`) et
        // c'est le plus petit des composers etudies. En dessous, le bouton devient une cible
        // qu'on rate — or c'est le controle qu'on presse le plus dans l'app. 40 dp reste sous
        // les 48 dp Material, mais c'est un choix de densite **conscient**, compense par
        // `minimumInteractiveComponentSize()` qui etend la zone sensible a 48 dp.
        targetValue = if (showStop || hasContent) 40.dp else 36.dp,
        label = Res.of(R.string.composer_button_size_5674e1),
    )
    // Icone : rotation douce entre la fleche et le carre (morph visuel).
    val arrowAlpha by animateFloatAsState(
        targetValue = if (showStop) 0f else 1f,
        label = Res.of(R.string.composer_arrow_e319e7),
    )
    val stopAlpha by animateFloatAsState(
        targetValue = if (showStop) 1f else 0f,
        label = Res.of(R.string.composer_stop_f8a665),
    )

    // ⚠️ La couleur se lit **avant** le `remember` : dans un `remember { }` sans cle,
    // le bloc n'est evalue qu'une fois, et un changement d'accent ne serait jamais
    // repris. En la passant par cle, le `remember` se recalcule au changement.
    val accent = LocalAccent.current
    val selectionColors = remember(accent) {
        TextSelectionColors(
            handleColor = accent,
            backgroundColor = accent.copy(alpha = 0.30f),
        )
    }

    // Scroll interne du champ : c'est lui qui rend le plafond de hauteur utilisable. Sans lui,
    // `heightIn` coupe le texte et le curseur sort du cadre.
    val scrollState = rememberScrollState()

    // --- Le lisere d'activite : un fil teal sous la barre quand un tour tourne ---
    //
    // ⚠️ **Plus de respiration : le lisere est fixe.** Le 2026-09-29, sur le Pixel, l'app
    // prenait **37 % d'un cœur en permanence** (RenderThread 27 %, 616 images en 10 s) sur un
    // simple ecran Sessions ; la meme mesure avec les animations systeme coupees donnait
    // **0,9 %** et **2 images en 10 s**. Le composer etant sur tous les ecrans de chat, une
    // pulsation infinie ici recurrait ce cout partout — et l'information « un tour tourne »
    // tient dans un fil teal continu.
    //
    // Le lisere reste soumis a « reduire les animations » : coupe, il disparait — c'est le
    // comportement attendu d'un mouvement, meme fige, et la couleur ne porte pas seule
    // l'information quand l'utilisateur a demande moins de stimuli (WCAG 2.3.3).
    val lisereVisible = busy && animationsAllowed()
    val lisereCouleur = LocalAccent.current

    Column(modifier = modifier.fillMaxWidth()) {
        // ------------------------------------------------ LES PIECES JOINTES EN ATTENTE
        //
        // ⚠️ Elles sont **au-dessus** de la barre, pas dedans : la barre de saisie doit rester
        // sur une ligne fine, et un fichier joint n'est pas du texte a editer. Les mettre dans le
        // champ melangerait deux choses qui n'ont pas la meme duree de vie.
        //
        // ⚠️ Chaque puce porte la **taille** et un bouton de retrait : joindre un fichier par
        // erreur doit pouvoir se defaire d'un geste, sinon on renvoie un message qu'on ne voulait
        // pas. Le retrait a un `contentDescription` propre — TalkBack ne peut pas « voir » la croix.
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                attachments.forEach { attachment ->
                    AttachmentChip(attachment = attachment, onRemove = { onRemoveAttachment(attachment.name) })
                }
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // LA SURFACE : champ en pleine largeur, barre d'outils DANS la surface.
        // ════════════════════════════════════════════════════════════════════
        //
        // ⚠️ **Reprise de Proton Lumo, relevee le 2026-09-27 sur son composer.** Trois
        // corrections, mesurees :
        //
        //  1. **Le champ prend la largeur entiere.** Avant, il etait entre le trombone a gauche
        //     et un bouton circulaire de 40 dp a droite : il ne restait que ~55 % de la barre
        //     pour ecrire. Or ecrire un prompt long est le cas NORMAL dans un cockpit (le
        //     plafond est deja a 10 lignes, 240 dp — on ne fait pas semblant que c'est court).
        //  2. **La barre d'outils est DANS la surface, en bas.** Plus rien ne vit a cote du
        //     champ, donc plus dePressed-glisseur possible ni de collision entre le micro et
        //     l'envoi — le reproche que la documentation du fichier portait sur l'ancien
        //     dessin, justement parce qu'il etait vrai.
        //  3. **L'envoi est une icone, pas un disque.** Le disque de 40 dp ne se justifiait
        //     que parce que le bouton portait **trois** fonctions (envoyer / arreter / ouvrir le
        //     selecteur quand le champ est vide). Le selecteur ayant sa propre place dans la
        //     barre d'outils, il n'en reste que deux — et l'icone suffit. Le cercle disparait,
        //     l'espace qu'il occupait revient au champ.
        //
        // ⚠️ **L'etat ne passe plus par la silhouette mais par la teinte** (ambre = arreter,
        // teal = envoyer, muet = rien a envoyer). C'est un recul d'accessibilite — la regle
        // dit « jamais la couleur seule » — et il est **compense** : l'icone elle-meme change
        // (fleche contre carre d'arret) et le libelle pour lecteur d'ecran dit l'action reelle.
        // On ne peut pas avoir les trois, et la silhouette d'un disque est ce qui rendait
        // l'ancien bouton illisible des que la barre gagnait un element.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
                .clip(RoundedCornerShape(ComposerRadius))
                .background(TetherComposerSurface)
                .border(1.dp, TetherComposerBorder, RoundedCornerShape(ComposerRadius)),
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                // ─────────────────────────────────────────── LE CHAMP, PLEINE LARGEUR
                Box {
                    if (value.isEmpty()) {
                        // ⚠️ Placeholder **plein**, surtout pas un texte secondaire attenue.
                        // Mesure : mon ancien placeholder a 60 % d'opacite donnait **2.96** de
                        // contraste, sous le seuil de 4.5 — illisible. Mon `#8B98A5` plein
                        // donne **5.19** : au-dessus de Claude, sobre.
                        Text(Res.of(R.string.ecrire_agent_146209),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TetherTextSecondary,
                        )
                    }
                    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                // ⚠️ `heightIn` ET `verticalScroll`, les deux : `heightIn` seul
                                // plafonne la hauteur mais **coupe le texte** au-dela — le curseur
                                // sort du cadre et on ecrit dans le vide. Le scroll interne est ce
                                // qui rend le plafond utilisable.
                                //
                                // ⚠️ Plafond a **10 lignes** (240 dp), pas 6 : ecrire un prompt
                                // long est le cas NORMAL dans un cockpit.
                                .heightIn(min = 24.dp, max = 240.dp)
                                .verticalScroll(scrollState),
                            // ⚠️ **16 sp — c'est LE bug de fond.** Le composer etait en `bodyMedium`
                            // (14 sp) alors que la reponse s'affiche en `bodyLarge` (16 sp, defaut
                            // M3). Le champ ou l'on ecrit etait donc **plus petit que le texte
                            // qu'il produit**.
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = TetherTextPrimary,
                            ),
                            cursorBrush = SolidColor(LocalAccent.current),
                            // ⚠️ `Default` et NON `Send` : sur un clavier mobile, `ImeAction.Send`
                            // remplace le retour a la ligne par une touche d'envoi. Dans un
                            // cockpit, ecrire un prompt multi-lignes est le cas NORMAL.
                            // On envoie par le bouton, jamais par le clavier.
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(),
                        )
                    }
                }

                // ─────────────────────────────────────────── LA BARRE D'OUTILS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    // `+` — joindre. A gauche, comme sur les quatre references (ChatGPT, Claude,
                    // Gemini, Perplexity) : un controle unique a gauche du champ.
                    if (onAttach != null) {
                        ComposerIcon(
                            icon = Lucide.Paperclip,
                            label = Res.of(R.string.joindre_fichier_d0f75a),
                            onClick = onAttach,
                        )
                    }
                    // `Agents` — la feuille des 4 agents selectionnables. Le libelle porte le
                    // contenu (« Agents »), pas un mot generique : c'etait la faute du bouton
                    // « Outils », qui nommait une categorie et cachait ce qu'il ouvrait.
                    if (onOpenAgents != null) {
                        ComposerLabelIcon(
                            icon = Lucide.Bot,
                            label = Res.of(R.string.agents_64acf7),
                            onClick = onOpenAgents,
                            emphasised = false,
                        )
                    }
                    // L'espace elastique : tout ce qui est « a moi » colle a gauche, tout ce
                    // qui est « a moi aussi » colle a droite. Pas de `Spacer` fixe — la
                    // separation suit la largeur, donc elle ne laisse jamais un trou bizarre.
                    Spacer(modifier = Modifier.weight(1f))

                    // Le modele, la, a portee de pouce. Une seule ligne, ellipsis : c'est un
                    // coup d'oeil, l'audit se fait dans la feuille.
                    if (onPickModelAgent != null) {
                        ComposerLabelIcon(
                            icon = Lucide.Blocks,
                            label = currentModelLabel ?: "—",
                            onClick = onPickModelAgent,
                            contentDescription = Res.of(R.string.modele_courant_changer_b2b5db),
                            // ⚠️ **Emphase** : le modele est un **etat**, l'agent une
                            // action. Le design-soul (§4) demande une hierarchie par
                            // *quatre* moyens — taille, poids, **couleur**, position — et
                            // interdit la taille seule. Ici la couleur fait le travail : le
                            // modele est en texte primaire, l'action en secondaire.
                            emphasised = true,
                        )
                    }
                    // Le micro, juste avant l'envoi mais **pas colle** : l'icone est nu, sans
                    // disque, et la distance au champ reste celle d'un controle ordinary.
                    if (onVoice != null) {
                        ComposerIcon(
                            icon = Lucide.Mic,
                            label = if (listening) Res.of(R.string.dictee_cours_fdda57) else Res.of(R.string.dicter_message_ed4e18),
                            onClick = onVoice,
                            enabled = !listening,
                            tint = if (listening) LocalAccent.current else TetherTextSecondary,
                        )
                    }
                    // L'envoi. Deux seuls etats utiles : fleche (envoyer) et carre d'arret.
                    // ⚠️ **48 dp, la cible tactile d'Android — et pas 36.** Mesure du 2026-09-29 :
                    // le bouton faisait 36 dp avec un glyphe de 20 dp. La zone sensible etait bien
                    // a 48 (`minimumInteractiveComponentSize`), mais ce qui SE VOIT restait 36 :
                    // c'est le controle qu'on presse le plus dans l'app, et le seul dont le
                    // dessin etait plus petit que sa propre cible. Le rond vaut desormais la
                    // cible, donc ce qu'on vise a l'oeil est ce qu'on touche.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(percent = 50))
                            .clickable(
                                enabled = showStop || hasContent,
                                onClick = { if (showStop) onStop() else onSend() },
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = when {
                                    showStop -> Res.of(R.string.arreter_execution_8a52ed)
                                    hasContent -> Res.of(R.string.envoyer_message_649908)
                                    else -> Res.of(R.string.envoyer_aucun_texte_dc0718)
                                }
                                // ⚠️ L'etat change sans que le focus bouge : sans `liveRegion`,
                                // c'est un changement **silencieux** pour un lecteur d'ecran.
                                liveRegion = LiveRegionMode.Polite
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (arrowAlpha > 0f) {
                            Icon(
                                imageVector = Lucide.ArrowUp,
                                contentDescription = null,
                                tint = buttonColor.copy(alpha = arrowAlpha),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        if (stopAlpha > 0f) {
                            Icon(
                                imageVector = Lucide.CircleStop,
                                contentDescription = null,
                                tint = buttonColor.copy(alpha = stopAlpha),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        // Le lisere d'activite : 2 dp sous la barre, invisible au repos.
        //
        // ⚠️ `drawBehind` et non `background(edgeColor)` : c'est la **lecture différée** qui fait
        // tout. `pulse` est lu dans cette lambda, donc chaque frame ne redessine que cette bande —
        // aucune recomposition, aucune re-mesure, aucun passage par le main thread.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    if (!lisereVisible) return@drawBehind
                    drawRect(color = lisereCouleur.copy(alpha = 0.40f))
                },
        ) {}
    }
}

/**
 * **Une icone seule dans la barre d'outils.**
 *
 * ⚠️ La cible tactile est de 48 dp grace a `minimumInteractiveComponentSize`, sur une icone de
 * 20 dp : l'icone est ce qu'on voit, la cible est ce qu'on rate pas. Les deux ne peuvent pas
 * etre la meme chose sur une barre dense.
 */
@Composable
private fun ComposerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = TetherTextSecondary,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(32.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * **Une icone **accompagnee de son libelle** — c'est ce qui distingue un bouton d'action
 * (« Outils ») d'un etat (« Max »).
 *
 * ⚠️ Le libelle est **d'une seule ligne avec ellipsis** : au-dela, il tronque, et un libelle
 * tronque qui signifie « autre chose » est pire que pas de libelle. Le modele courant tombe
 * donc sur son nom court quand le serveur en donne un, et sur son id sinon.
 */
@Composable
private fun ComposerLabelIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    contentDescription: String? = null,
    /** `true` pour un **etat** (le modele), `false` pour une **action** (les agents). */
    emphasised: Boolean = false,
) {
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription ?: label
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TetherIconMuted,
            modifier = Modifier.size(14.dp),
        )
        // ⚠️ **`bodyMedium`, pas `TetherDataStyle`.** Le style des donnees est du
        // monospace 11 sp : c'est fait pour des chiffres alignes en colonne (cout, tokens), et
        // mettre un libelle dedans melangeait deux systemes typographiques dans le meme
        // composer — le champ en proportionnel 16 sp juste au-dessus. Le modele, lui, est bien
        // une donnee, mais il est ici en **etat** et pas en colonne : la couleur porte la
        // hierarchie, pas la police.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasised) TetherTextPrimary else TetherTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Encre sur l'accent : sombre, pour rester lisible sur le teal clair. */
private val ComposerOnAccent = Color(0xFF0B0E11)

/**
 * **Une piece jointe en attente, sous forme de puce.**
 *
 * ⚠️ La puce porte le **nom** et la **taille** : le nom seul ne dit pas si l'on a joint une note
 * de trois lignes ou un binaire de 3 Mo, et c'est pourtant ce qui change la decision d'envoyer.
 *
 * ⚠️ Le bouton de retrait a une cible tactile **rembourrée** (padding 8 dp autour d'une icone de
 * 12 dp = 28 dp de contenu, plus `minimumInteractiveComponentSize` qui etend la zone sensible a
 * 48 dp) : viser une croix de 12 dp au doigt est impossible, et une cible ratee ici laisse un
 * fichier qu'on ne veut pas envoyer.
 */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(LocalAccent.current.copy(alpha = 0.14f))
            .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.Paperclip,
            contentDescription = null,
            tint = LocalAccent.current,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = attachment.name,
            style = TetherDataStyle,
            color = TetherTextPrimary,
            maxLines = 1,
        )
        if (attachment.sizeBytes > 0) {
            Text(
                text = attachment.sizeLabel,
                style = TetherDataStyle,
                color = TetherTextSecondary,
            )
        }
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(20.dp)
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onRemove)
                .semantics {
                    role = Role.Button
                    contentDescription = "Retirer ${attachment.name}"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.X,
                contentDescription = null,
                tint = TetherTextSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * Rayon de la barre de saisie : **24 dp**.
 *
 * ⚠️ Valeur mesuree contre les composers reels, pas choisie a l'œil : Claude ~16, ChatGPT ~28,
 * Gemini ~32, Grok ~32. Aucun n'est carre, aucun n'est sous 16. 24 est le point d'equilibre,
 * et il correspond au token M3 `extra large` (28) arrondi vers le bas pour rester coherent avec
 * `TetherDimensions.cornerMd` (12) deja utilise ailleurs.
 *
 * ⚠️ Pourquoi pas la pilule pleine (`percent = 50`) : sur un ecran de lecture, une pilule
 * complete « avale » la barre — elle devient une forme, plus un contenant.
 */
private val ComposerRadius = 24.dp
