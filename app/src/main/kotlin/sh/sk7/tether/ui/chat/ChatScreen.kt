package sh.sk7.tether.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.Square
import com.composables.icons.lucide.Terminal
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherBackground
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary

/**
 * Ecran de conversation d'une session opencode, avec streaming.
 *
 * - bulle utilisateur a droite, reponse assistant pleine largeur ;
 * - le markdown est rendu par `multiplatform-markdown-renderer` (blocs de code colores) ;
 * - chaque appel d'outil est une [ToolCard] depliable ;
 * - auto-scroll pendant le stream, bouton stop tant qu'un tour est en cours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll pendant le stream.
    //
    // ⚠️ La cle doit couvrir TOUT le contenu transitoire, pas seulement le texte : le mode
    // reel de ce modele est le **raisonnement** (et les outils). Se limiter a `streamingText`
    // laissait la vue figee pendant un long `reasoning` (constate sur le Pixel).
    val transientLength = (state.chat.streamingText?.length ?: 0) +
        (state.chat.streamingReasoning?.length ?: 0) +
        state.chat.streamingTools.size
    val itemCount = state.chat.messages.size + (if (transientLength > 0) 1 else 0)

    // On ne recentre **ensuite** que si l'utilisateur etait deja en bas : sinon, relire
    // l'historique pendant un stream serait impossible (le scroll serait ramene de force).
    var stickToBottom by remember { mutableStateOf(true) }
    var firstScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScroll ->
            // Tant qu'on n'a pas fait le premier saut, on ne se fie pas a la position
            // initiale (haut de liste = « peut descendre » = faux negatif).
            if (firstScrollDone) stickToBottom = !canScroll
        }
    }
    LaunchedEffect(itemCount, transientLength) {
        if (itemCount == 0) return@LaunchedEffect
        if (!firstScrollDone) {
            // Ouverture d'une session : on rejoint le bas, en douceur.
            listState.animateScrollToItem(itemCount - 1)
            firstScrollDone = true
            return@LaunchedEffect
        }
        if (!stickToBottom) return@LaunchedEffect
        val streaming = state.chat.streamingText != null ||
            state.chat.streamingReasoning != null ||
            state.chat.streamingTools.isNotEmpty()
        if (streaming) {
            // ⚠️ `scrollToItem` **instantané** a chaque token : `animateScrollToItem` serait
            // annule puis relance en continu (clignotement, et O(n) par delta).
            listState.scrollToItem(itemCount - 1)
        } else {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Scaffold(
        // Insets par defaut du Scaffold (barre d'etat + barre de navigation). L'inset IME
        // est consomme **uniquement** par le `bottomBar` (voir plus bas), jamais ici :
        // sinon il serait compte deux fois et ecraserait l'ecran.
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title ?: "Session",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Lucide.ArrowLeft, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = TetherTextPrimary,
                ),
            )
        },
        bottomBar = {
            // ⚠️ targetSdk 37, edge-to-edge. Le systeme ne redimensionne pas la fenetre et
            // `adjustResize` est ignore : c'est **nous** qui remontons la barre de saisie,
            // en consommant l'inset IME ici et nulle part ailleurs. `adjustNothing` dans le
            // manifest empeche le systeme de **panoramiquer** la fenetre vers le haut (ce qui
            // faisait disparaitre la barre de titre). Mesure : Pixel 1080x2404, IME 986 px.
            //
            // `union(ime, navigationBars)` : clavier **ferme**, l'inset IME vaut 0 et, sans
            // la barre de navigation, le champ se dessinait **sous** la pill de gestes
            // (visible sur la capture « avant envoi »). L'union couvre les deux cas.
            Column(
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars),
                ),
            ) {
                state.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherAlert,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Composer(
                    value = draft,
                    busy = state.isBusy,
                    onValueChange = { draft = it },
                    onSend = {
                        val text = draft
                        draft = ""
                        viewModel.send(text)
                    },
                    onStop = viewModel::stop,
                )
            }
        },
    ) { padding ->
        // ⚠️ `adjustResize` + Scaffold : le champ de saisie vit dans `bottomBar` et suit
        // l'inset clavier du Scaffold. Ne PAS ajouter `imePadding()` ici, qui compterait
        // l'inset une seconde fois et ecraserait la liste.
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(state.chat.messages, key = { _, m -> m.id }) { _, message ->
                MessageBubble(message)
            }
            item(key = "streaming") {
                StreamingBlock(state.chat)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    if (message.role == Role.User) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = TetherTextPrimary,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(TetherAccent.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.reasoning.takeIf { it.isNotBlank() }?.let { reasoning ->
            ReasoningBlock(reasoning)
        }
        if (message.text.isNotBlank()) MarkdownBody(message.text)
        message.tools.forEach { ToolCard(it) }
        message.rawFallback?.takeIf { it.isNotBlank() }?.let { raw ->
            RawFallback(raw)
        }
    }
}

/** Bloc transitoire du tour en cours : raisonnement, texte, outils. */
@Composable
private fun StreamingBlock(chat: sh.sk7.tether.domain.model.SessionUiState) {
    val hasReasoning = !chat.streamingReasoning.isNullOrBlank()
    val hasText = !chat.streamingText.isNullOrBlank()
    val hasTools = chat.streamingTools.isNotEmpty()
    if (!hasReasoning && !hasText && !hasTools) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Le raisonnement se replie : c'est de l'information secondaire, elle ne doit pas
        // noyer la reponse (constat sur le Pixel : jusqu'a 2 ecrans de bloc gris).
        chat.streamingReasoning?.takeIf { it.isNotBlank() }?.let {
            ReasoningBlock(text = it, durationLabel = chat.reasoningDurationLabel)
        }
        chat.streamingTools.forEach { call ->
            ToolCard(call = call, durationLabel = chat.toolDurations[call.id])
        }
        chat.streamingText?.takeIf { it.isNotBlank() }?.let { MarkdownBody(it) }
    }
}

/** Rendu markdown de la reponse, avec coloration syntaxique des blocs de code. */
@Composable
private fun MarkdownBody(text: String) {
    // ⚠️ Sans theme explicite, le theme par defaut de `highlights` rend la ponctuation
    // presque invisible sur fond sombre (`(` `)` `+` disparaissent). Constaté sur le Pixel.
    val highlightsBuilder = remember {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))
    }
    Markdown(
        content = text,
        colors = markdownColor(
            text = TetherTextPrimary,
            codeText = TetherTextPrimary,
            inlineCodeText = TetherAccent,
            linkText = TetherAccent,
            codeBackground = TetherSurface,
            inlineCodeBackground = TetherBackground,
            dividerColor = TetherTextSecondary,
            tableText = TetherTextPrimary,
            tableBackground = TetherSurface,
        ),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyLarge,
            code = MaterialTheme.typography.bodySmall,
            inlineCode = MaterialTheme.typography.bodySmall,
            paragraph = MaterialTheme.typography.bodyLarge,
            h1 = MaterialTheme.typography.titleLarge,
            h2 = MaterialTheme.typography.titleMedium,
            h3 = MaterialTheme.typography.titleSmall,
            quote = MaterialTheme.typography.bodyMedium,
            bullet = MaterialTheme.typography.bodyLarge,
            ordered = MaterialTheme.typography.bodyLarge,
        ),
        components = markdownComponents(
            codeBlock = {
                MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlights = highlightsBuilder,
                )
            },
            codeFence = {
                MarkdownHighlightedCodeFence(
                    content = it.content,
                    node = it.node,
                    highlights = highlightsBuilder,
                )
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Raisonnement du modele : discret, repliable mentalement (texte secondaire, italique). */

/** Contenu de forme inconnue : affiche brut, jamais jete (Review Focus n°4). */
@Composable
private fun RawFallback(raw: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TetherBackground, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Lucide.Terminal,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = raw,
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Composer(
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TetherSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message…", color = TetherTextSecondary) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(Lucide.Square, contentDescription = "Arrêter", tint = TetherAlert)
            }
        } else {
            IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Icon(Lucide.Send, contentDescription = "Envoyer", tint = TetherAccent)
            }
        }
    }
}
