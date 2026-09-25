package sh.sk7.tether.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Mic
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

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
     * Ouvre le selecteur modele/agent. Attache au bouton d'envoi quand le champ est vide.
     *
     * ⚠️ Sur le bouton d'envoi, et pas une icone a part : choisir un modele ne demande rien a
     * ecrire, c'est une action **au repos**. Une icone dediee prendrait une place permanente dans
     * une barre ou chaque pixel coute au champ de saisie.
     */
    onPickModelAgent: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasText = value.isNotBlank()

    // Le bouton change de sens : « interrompre » pendant un tour, « envoyer » sinon.
    // ⚠️ Des que du texte est present, ENVOYER prime — sinon on ne pourrait pas repondre a un
    // agent qui tourne (le cas `steer` de l'API), ce qui est precisement ce qu'on veut faire.
    val showStop = busy && !hasText

    // Couleur du bouton : ambre (interrompre) > teal (envoyer) > muet (vide).
    val buttonColor by animateColorAsState(
        targetValue = when {
            showStop -> TetherAlert
            hasText -> TetherAccent
            else -> TetherTextSecondary.copy(alpha = 0.25f)
        },
        label = "composer-button-color",
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
        targetValue = if (showStop || hasText) 40.dp else 36.dp,
        label = "composer-button-size",
    )
    // Icone : rotation douce entre la fleche et le carre (morph visuel).
    val arrowAlpha by animateFloatAsState(
        targetValue = if (showStop) 0f else 1f,
        label = "composer-arrow",
    )
    val stopAlpha by animateFloatAsState(
        targetValue = if (showStop) 1f else 0f,
        label = "composer-stop",
    )

    val selectionColors = remember {
        TextSelectionColors(
            handleColor = TetherAccent,
            backgroundColor = TetherAccent.copy(alpha = 0.30f),
        )
    }

    // Scroll interne du champ : c'est lui qui rend le plafond de hauteur utilisable. Sans lui,
    // `heightIn` coupe le texte et le curseur sort du cadre.
    val scrollState = rememberScrollState()

    // --- Le lisere d'activite : un fil teal qui respire quand un tour tourne ---
    val pulseTransition = rememberInfiniteTransition(label = "composer")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "composer-pulse",
    )
    // Lisere teal sous la barre : la version du fil qui passe par la zone de saisie.
    val edgeColor = if (busy) TetherAccent.copy(alpha = pulse) else Color.Transparent

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // ------------------------------------------------ LA SURFACE DE SAISIE
            Box(
                modifier = Modifier
                    .weight(1f)
                    // ⚠️ Rayon 24 dp. **Mesures live** des references : Claude **14 px**,
                    // ChatGPT **28 px** — et Grok/Gemini sont sur des pilules ~32. Aucun n'est
                    // carre, aucun n'est sous 14. 24 tombe entre les deux archetypes
                    // dominants (carte moderee / pilule), au token M3 `extra large` (28)
                    // legerement adouci pour rester coherent avec `cornerMd` (12) deja utilise.
                    .clip(RoundedCornerShape(ComposerRadius))
                    .background(TetherComposerSurface)
                    // ⚠️ Liseré 1 dp cale sur l'ecart **mesure** de ChatGPT (~1.9 vs surface).
                    // Pas d'ombre : elle est invisible sur mon fond quasi-noir, donc elle
                    // n'ajouterait que du cout de rendu. Le lisere est le seul outil qui
                    // delimite reellement la surface ici.
                    .border(1.dp, TetherComposerBorder, RoundedCornerShape(ComposerRadius))
                    // ⚠️ Padding interne : les references mesurent **7px 10px** (ChatGPT) et
                    // **8px** (Claude) — tres serre, volontairement : la barre doit rester
                    // fine. Mon `Spacing.md` (12) + `Spacing.sm` (8) reste plus genereux, ce
                    // qui est correct ici : ma police d'ecran est plus petite que celle de
                    // ChatGPT (17px) et la densite globale de l'app est plus faible que la
                    // leur. Serrer davantage nuirait a la lisibilite des prompts longs.
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            ) {
                if (value.isEmpty()) {
                    // ⚠️ Placeholder **plein**, surtout pas un texte secondaire attenue.
                    // Mesure : mon ancien placeholder a 60 % d'opacite donnait **2.96** de
                    // contraste, sous le seuil de 4.5 — illisible. Comparaison avec les
                    // references mesurees : ChatGPT `#cdcdcd` sur `#212121` = **10.13**,
                    // Claude `#898781` sur `#20201f` = **4.54** (juste au seuil).
                    // Mon `#8B98A5` plein donne **5.19** : au-dessus de Claude, sobre.
                    Text(
                        text = "Écrire à l'agent…",
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
                            // ⚠️ Plafond a **10 lignes** (240 dp), pas 6. Mesure : la plainte
                            // la plus documentee chez Grok est justement un plafond trop bas
                            // (~6 lignes), au point qu'un fork entier existe pour le monter a
                            // 15-16. Ecrire un prompt long est le cas NORMAL dans un cockpit.
                            .heightIn(min = 24.dp, max = 240.dp)
                            .verticalScroll(scrollState),
                        // ⚠️ **16 sp — c'est LE bug de fond.** Le composer etait en `bodyMedium`
                        // (14 sp) alors que la reponse s'affiche en `bodyLarge` (16 sp, defaut M3).
                        // Le champ ou l'on ecrit etait donc **plus petit que le texte qu'il
                        // produit** : on tape petit, on lit gros, et la barre parait chétive.
                        // Ecrire et lire doivent partager la meme echelle.
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = TetherTextPrimary,
                        ),
                        // Curseur teal : le seul accent de la zone, et il marque l'insertion.
                        cursorBrush = SolidColor(TetherAccent),
                        keyboardOptions = KeyboardOptions(
                            // ⚠️ `Default` et NON `Send` : sur un clavier mobile, `ImeAction.Send`
                            // remplace le retour a la ligne par une touche d'envoi. Dans un
                            // cockpit, ecrire un prompt multi-lignes est le cas NORMAL.
                            // On envoie par le bouton, jamais par le clavier.
                            imeAction = ImeAction.Default,
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        keyboardActions = KeyboardActions(),
                    )
                }
            }

            // ------------------------------------------------ LE MICRO, DANS LA MEME SURFACE
          //
          // ⚠️ Le micro vit **dans** la surface de saisie, a cote du bouton d'envoi, parce que
          // dicter est une facon d'ECRIRE. Le mettre dans une barre d'outils en ferait une
          // fonction a part, alors que c'est la meme intention.
          if (onVoice != null) {
              Box(
                  modifier = Modifier
                      .minimumInteractiveComponentSize()
                      .size(30.dp)
                      .clip(RoundedCornerShape(percent = 50))
                      // ⚠️ La teinte dit l'etat d'ecoute : sans elle, appuyer sur le micro ne
                      // produirait aucun retour local, et l'utilisateur ne saurait pas si le geste
                      // a ete pris en compte avant l'ouverture du dialogue systeme.
                      .background(
                          if (listening) TetherAccent.copy(alpha = 0.18f) else Color.Transparent,
                      )
                      .clickable(enabled = !listening, onClick = onVoice)
                      .semantics {
                          role = Role.Button
                          contentDescription =
                              if (listening) "Dictée en cours" else "Dicter le message"
                      },
                  contentAlignment = Alignment.Center,
              ) {
                  Icon(
                      imageVector = Lucide.Mic,
                      contentDescription = null,
                      tint = if (listening) TetherAccent else TetherTextSecondary,
                      modifier = Modifier.size(16.dp),
                  )
              }
          }

          // ------------------------------------------------ L'ACTION, DANS LA MEME SURFACE
            //
            // ⚠️ `minimumInteractiveComponentSize` : le bouton fait 30-34 dp a l'ecran, mais
            // Material garantit une **cible tactile de 48 dp** en etirant la zone sensible
            // autour. Sans lui, on rate le bouton sur une barre fine — et c'est le bouton qu'on
            // presse le plus dans l'app.
            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(buttonSize)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(buttonColor)
                    .clickable(
                        enabled = showStop || hasText || onPickModelAgent != null,
                    ) {
                        when {
                            showStop -> onStop()
                            hasText -> onSend()
                            // ⚠️ Champ vide et rien en cours : le bouton n'a rien a envoyer, donc
                            // il propose le reglage qui sert **avant** d'ecrire — le modele et
                            // l'agent. Sans ce cas, le bouton resterait inerte et l'utilisateur
                            // n'aurait aucun point d'entree vers le selecteur.
                            onPickModelAgent != null -> onPickModelAgent.invoke()
                            else -> Unit
                        }
                    }
                    .semantics {
                        // ⚠️ `role` : sans lui, TalkBack ne dit pas « double-tap pour activer ».
                        role = Role.Button
                        // ⚠️ Une seule description, celle de l'action REELLE. Les icones
                        // internes portent `contentDescription = null` : sinon TalkBack lit deux
                        // fois.
                        contentDescription = when {
                            showStop -> "Arrêter l'exécution"
                            hasText -> "Envoyer le message"
                            onPickModelAgent != null -> "Modèle et agent"
                            else -> "Envoyer (aucun texte)"
                        }
                        // ⚠️ `liveRegion` : l'etat change sans que le focus bouge, donc sans
                        // annonce c'est un changement **silencieux** pour un lecteur d'ecran.
                        liveRegion = LiveRegionMode.Polite
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (arrowAlpha > 0f) {
                    Icon(
                        imageVector = Lucide.ArrowUp,
                        contentDescription = null,
                        // Sur le teal, l'icone doit etre SOMBRE : un blanc sur teal clair est
                        // illisible (contraste 2:1). Le fond sombre de l'app donne le contraste.
                        tint = ComposerOnAccent.copy(alpha = arrowAlpha),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (stopAlpha > 0f) {
                    Icon(
                        imageVector = Lucide.CircleStop,
                        contentDescription = null,
                        tint = ComposerOnAccent.copy(alpha = stopAlpha),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Le lisere d'activite : 2 dp sous la barre, invisible au repos.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(edgeColor),
        ) {}
    }
}

/** Encre sur l'accent : sombre, pour rester lisible sur le teal clair. */
private val ComposerOnAccent = Color(0xFF0B0E11)

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
