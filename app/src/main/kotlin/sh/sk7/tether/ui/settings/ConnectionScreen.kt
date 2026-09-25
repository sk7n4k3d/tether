package sh.sk7.tether.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.WifiOff
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * **Connexion au serveur — la porte d'entree, pas un formulaire de reglages.**
 *
 * ### Ce qui n'allait pas
 * L'ancien ecran s'appelait « Connexion serveur », avait une flèche « retour » vers nulle part
 * a la premiere ouverture, et empilait trois `OutlinedTextField` avec **label flottant** —
 * l'esthetique d'un panneau d'administration. Rien ne disait ce qu'on attendait de l'utilisateur,
 * ni que le bouton enregistrait *et* testait.
 *
 * ### Ce qui remplace
 * Un ecran qui **explique** puis **verifie** :
 *  - un en-tete qui dit a quoi sert ce qu'on demande, avant de le demander ;
 *  - des champs **sans label flottant** (le libelle est au-dessus, fixe : il ne bouge pas quand
 *    on tape, et il reste lisible quand le champ est rempli) ;
 *  - un **etat de test explicite** : on ne dit jamais « ok » sans avoir parle au serveur ;
 *  - un bouton dont le libelle dit ce qu'il fait vraiment (« Se connecter »), pas « Tester ».
 *
 * ### Le mode premiere ouverture
 * ⚠️ `firstRun = true` change trois choses, et c'est important : pas de flèche retour (il n'y a
 * rien derriere), un titre d'accueil au lieu d'un titre de reglage, et un rappel du geste a
 * faire sur le serveur si la connexion echoue. Un utilisateur qui ouvre l'app pour la premiere
 * fois n'a pas de contexte ; l'ecran doit le lui donner.
 */
@Composable
fun ConnectionScreen(
    onBack: () -> Unit = {},
    firstRun: Boolean = false,
    onConnected: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    // ⚠️ Quand la connexion reussit, on **quitte l'ecran nous-memes** en premiere ouverture.
    // Sans ca, l'utilisateur reste bloque sur un formulaire qui vient de dire « connecte » et
    // doit deviner qu'il peut revenir en arriere — un classique des apps inachevees.
    LaunchedEffect(state.result, firstRun) {
        if (firstRun && state.result is ConnectionTestResult.Success) onConnected()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ `targetSdk 37` impose l'edge-to-edge, et cet écran ne passe pas par un
            // `Scaffold` (qui applique ses insets tout seul). Sans ce padding, le contenu se
            // dessine **sous** la barre d'état et sous la barre de navigation : le titre peut être
            // masqué et le dernier bouton tomber sur la pilule de gestes.
            // `safeDrawing` couvre les deux (et les découpes d'écran), clavier exclu — cet écran
            // n'a pas de champ de saisie, le clavier ne s'y ouvre pas.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // ------------------------------------------------------------ EN-TETE
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!firstRun) {
                IconButton(onClick = onBack) {
                    Icon(Lucide.ArrowLeft, contentDescription = "Retour")
                }
            }
            Text(
                text = if (firstRun) "Tether" else "Connexion",
                style = MaterialTheme.typography.titleMedium,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (firstRun) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = "Relier ton serveur opencode",
                    style = MaterialTheme.typography.titleMedium,
                    color = TetherTextPrimary,
                )
                Text(
                    text = "Tether pilote le serveur opencode qui tourne sur ta machine. " +
                        "Renseigne son adresse et son mot de passe : ils restent sur ce " +
                        "telephone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TetherTextSecondary,
                )
            }
        }

        // ------------------------------------------------------------ LES CHAMPS
        Field(
            label = "Adresse du serveur",
            hint = "http://192.0.2.10:4096",
            value = state.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )

        Field(
            label = "Mot de passe",
            hint = "celui du serveur opencode",
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
            secret = !passwordVisible,
            trailing = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Lucide.EyeOff else Lucide.Eye,
                        contentDescription = if (passwordVisible) {
                            "Masquer le mot de passe"
                        } else {
                            "Afficher le mot de passe"
                        },
                        tint = TetherTextSecondary,
                    )
                }
            },
        )

        Field(
            label = "Repertoire de travail",
            hint = "/home/utilisateur",
            value = state.directory,
            onValueChange = viewModel::onDirectoryChange,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            // ⚠️ Aide contextuelle : sans elle, « repertoire » ne veut rien dire pour quelqu'un
            // qui n'a pas ecrit l'API. On dit ce que ca change, pas ce que ca contient.
            help = "Le dossier ou opencode travaille. Il determine quelles sessions tu vois.",
        )

        // ------------------------------------------------------------ L'ACTION
        ConnectButton(
            result = state.result,
            onClick = viewModel::testConnection,
        )

        TestOutcome(state.result, firstRun = firstRun)

        // ------------------------------------------------------------ LA NOTE DE SECURITE
        if (firstRun) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                    .background(TetherComposerSurface)
                    .border(
                        1.dp,
                        TetherComposerBorder,
                        RoundedCornerShape(TetherDimensions.cornerSm),
                    )
                    .padding(Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Lucide.ShieldCheck,
                    contentDescription = null,
                    tint = TetherAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Le mot de passe est conserve dans l'espace prive de l'application. " +
                        "Aucun envoi vers un tiers : Tether ne parle qu'a ton serveur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextSecondary,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xl))
    }
}

/**
 * Un champ avec son libelle **au-dessus**, fixe.
 *
 * ⚠️ **Pas de label flottant**, et c'est un choix argumente : le libelle flottant disparait dans
 * la bordure des qu'on tape, donc il faut le relire avant de remplir le champ suivant — et il ne
 * sert plus a rien une fois le champ rempli. Un libelle fixe au-dessus est toujours lisible, et
 * c'est ce que font les interfaces qu'on ne trouve pas datees.
 *
 * ⚠️ L'aide est sous le champ, jamais dans le placeholder : elle doit rester visible quand on a
 * commence a taper, sinon on perd l'information au moment ou on en a le plus besoin.
 */
@Composable
private fun Field(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    secret: Boolean = false,
    help: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = {
                // ⚠️ Un placeholder EST du texte : il doit tenir le seuil de 4.5:1 comme le reste.
                // Mesure : l'ancien `alpha = 0.6` donnait **2.78** sur la surface de saisie —
                // illisible. C'est la couleur du texte secondaire plein qui passe (5.19).
                Text(hint, color = TetherTextSecondary)
            },
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                // ⚠️ Pas d'auto-correction sur une URL ou un mot de passe : le clavier
                // « corrigerait » une adresse IP ou un secret, ce qui est un bug silencieux.
                autoCorrectEnabled = false,
            ),
            trailingIcon = trailing,
            shape = RoundedCornerShape(TetherDimensions.cornerSm),
            modifier = Modifier.fillMaxWidth(),
        )
        help?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
            )
        }
    }
}

/** Le bouton d'action : son libelle dit **exactement** ce qui va se passer. */
@Composable
private fun ConnectButton(result: ConnectionTestResult, onClick: () -> Unit) {
    val testing = result == ConnectionTestResult.Testing
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = !testing,
        shape = RoundedCornerShape(TetherDimensions.cornerMd),
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ `heightIn(min = 52.dp)` et non `height(52.dp)`. Mesure : avec une taille de
            // police système à 200 %, un texte qui double de hauteur dans une hauteur **fixe** de
            // 52 dp est **coupé** — le libellé disparaît en partie, et le bouton principal de
            // l'écran de connexion devient illisible. Le minimum garde l'épaisseur voulue au
            // repos, sans plafonner la croissance.
            .heightIn(min = 52.dp),
    ) {
        if (testing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = "Connexion en cours" },
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Spacing.sm))
            Text("Connexion…")
        } else {
            Icon(Lucide.Server, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(if (result is ConnectionTestResult.Failure) "Réessayer" else "Se connecter")
        }
    }
}

/**
 * Le resultat du test, dit **en clair**.
 *
 * ⚠️ Trois cas distincts, jamais confondus :
 *  - **succes** : ce qu'on a trouve (la version), pour prouver qu'on a vraiment parle au serveur ;
 *  - **identifiants refuses** : le probleme est le mot de passe, pas le reseau — on le dit ;
 *  - **injoignable** : et on donne la **cause probable**, pas juste « erreur ».
 *
 * Un message d'erreur qui ne dit pas quoi faire oblige a chercher, et c'est precisement ce qui
 * fait qu'une app parait incomplete.
 */
@Composable
private fun TestOutcome(result: ConnectionTestResult, firstRun: Boolean) {
    AnimatedVisibility(visible = result !is ConnectionTestResult.Idle) {
        when (result) {
            ConnectionTestResult.Idle, ConnectionTestResult.Testing -> Unit
            is ConnectionTestResult.Success -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = null,
                    tint = TetherAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Connecté — opencode ${result.version}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TetherAccent,
                )
            }
            is ConnectionTestResult.Failure -> Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(
                        imageVector = Lucide.WifiOff,
                        contentDescription = null,
                        tint = TetherAlert,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TetherAlert,
                    )
                }
                if (firstRun) {
                    Text(
                        // ⚠️ On nomme les deux causes les plus frequentes au lieu de laisser
                        // l'utilisateur deviner. C'est la difference entre un message d'erreur
                        // et une aide.
                        text = "Vérifie que le serveur tourne, que le téléphone est sur le " +
                            "même réseau, et que l'adresse est la bonne.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextSecondary,
                    )
                }
            }
        }
    }
}
