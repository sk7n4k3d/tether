package sh.sk7.tether.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronUp
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
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherBackground
import sh.sk7.tether.ui.theme.TetherDimensions
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

    // ---------------------------------------------------------------- AUTO-SCROLL
    //
    // ⚠️ **DEUX bugs se cumulaient ici, et c'est ce qui faisait « remonter » l'ecran.**
    //
    // **Bug 1 — la detection du « colle en bas » se desactivait toute seule.**
    // La version precedente ecoutait `canScrollForward` **sans condition** : des que le contenu
    // grandissait (ce qui arrive a chaque token en streaming), `canScrollForward` passait a vrai
    // et `stickToBottom` devenait faux. L'auto-scroll se **coupait donc tout seul** au moment
    // precis ou il servait. On ne reagit desormais qu'a un scroll **en cours** (`isScrollInProgress`),
    // c'est-a-dire un geste de l'utilisateur : le contenu qui grandit n'est plus pris pour une
    // intention.
    //
    // **Bug 2 — « le bas » n'etait pas le bas.**
    // `scrollToItem(dernier)` colle le **HAUT** du dernier item en haut de l'ecran. Tant que la
    // reponse est courte ca marche par accident ; des qu'elle depasse un ecran (le cas normal),
    // on voit **son debut fige** et le texte qui arrive s'ecrit **hors de l'ecran**. Le remede
    // est de viser le vrai bas, et de le faire avec un decalage que Compose **borne** au maximum
    // reel (voir [scrollToBottom]).
    var stickToBottom by remember { mutableStateOf(true) }
    var firstScrollDone by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                // Seul un geste peut changer l'intention. Un scroll programmatique
                // (`animateScrollToItem`) passe aussi par ici, mais il nous ramene **vers** le
                // bas, donc il ne fait que confirmer `true`.
                if (scrolling) stickToBottom = !canScrollForward
            }
    }

    LaunchedEffect(itemCount, transientLength) {
        if (itemCount == 0) return@LaunchedEffect
        if (!firstScrollDone) {
            // Ouverture d'une session : on rejoint le bas **tout de suite**, sans animation.
            // Une animation d'ouverture fait defiler un historique qu'on n'a pas demande a voir.
            listState.scrollToBottom()
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
            listState.scrollToBottom()
        } else {
            // Nouveau message a l'arret : on descend en douceur, c'est un evenement, pas un flux.
            listState.animateScrollToBottom()
        }
    }

    // ---------------------------------------------------- REMONTER DANS L'HISTORIQUE
    //
    // ⚠️ On charge **avant** d'atteindre le sommet (marge de [ChatWindow.PREFETCH_THRESHOLD]
    // items) : au sommet pile, l'utilisateur voit un blanc le temps de l'aller-retour. Charger
    // une page un peu tot coute exactement la meme chose qu'au bon moment.
    //
    // ⚠️ On ne recharge PAS quand `loadingOlder` est vrai : sans ce garde-fou, chaque pixel de
    // scroll dans la zone de declenchement relancerait une requete.
    LaunchedEffect(listState, state.hasOlder, state.loadingOlder) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { first ->
                if (first <= ChatWindow.PREFETCH_THRESHOLD && state.hasOlder && !state.loadingOlder) {
                    viewModel.loadOlder()
                }
            }
    }

    Scaffold(
        // Insets par defaut du Scaffold (barre d'etat + barre de navigation). L'inset IME
        // est consomme **uniquement** par le `bottomBar` (voir plus bas), jamais ici :
        // sinon il serait compte deux fois et ecraserait l'ecran.
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // ⚠️ `Column` titre + en-tete, et non un `TopAppBar` seul : l'en-tete
            // d'instrument appartient a l'en-tete de l'ecran. Le mettre dans la liste le
            // ferait disparaitre au defilement — or c'est justement quand on lit une longue
            // reponse qu'on veut savoir ce qu'elle coute.
            Column {
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
                ChatInstrumentHeader(state = state.chat, meta = state.meta)
            }
        },
        bottomBar = {
            // ⚠️ Le composer NE VIT PAS ici, contrairement a la version precedente. Mesure et
            // recommandation (doc insets Compose + articles de reference) : `Scaffold` n'applique
            // **pas** l'inset IME par design, et son `bottomBar` applique deja les insets bas.
            // Mettre la saisie dedans oblige a bricoler `imePadding()` dessus et produit soit un
            // double padding, soit une barre qui flotte trop haut clavier ferme. La saisie est
            // traitee comme un element de **contenu**, pas comme une bottom bar Material.
            //
            // Ce que ce `bottomBar` porte encore : le message d'erreur, qui doit rester visible
            // au-dessus de la saisie.
            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherAlert,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        },
    ) { padding ->
        // ⚠️ Structure : la liste prend l'espace, la saisie est **posee dessous**, et le clavier
        // est gere ICI, une seule fois, par le conteneur. Ne PAS ajouter `imePadding()` ailleurs
        // (Scaffold, liste) : l'inset serait compte deux fois et ecraserait l'ecran.
        //
        // ⚠️ AUCUN `spacedBy` vertical dans la liste : comme sur la liste des sessions, un
        // espacement de liste creerait des trous que le rail ne traverserait pas, coupant le
        // fil. Le rythme vertical vit dans le padding interne de chaque bloc.
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // `union(ime, navigationBars)` : clavier **ferme**, l'inset IME vaut 0 et, sans
                // la barre de navigation, la saisie se dessinerait **sous** la pill de gestes.
                // L'union couvre les deux cas (mesure : Pixel 1080x2404, IME 986 px).
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.sm),
            ) {
                // ---------------------------------------------- LE BOUT DU FIL
                //
                // ⚠️ Sans ce marqueur, la fenetre glissante serait **invisible** : on ne saurait
                // pas si l'on voit le debut d'une conversation ou seulement ses 40 derniers
                // messages. Ce serait exactement le mensonge que le `design-soul.md` interdit —
                // une donnee technique masquee. Ici on dit ce qu'on charge, et on dit quand il
                // n'y a plus rien.
                item(key = "history-top") {
                    HistoryTopRow(
                        hasOlder = state.hasOlder,
                        loading = state.loadingOlder,
                        onLoadMore = viewModel::loadOlder,
                    )
                }
                itemsIndexed(state.chat.messages, key = { _, m -> m.id }) { _, message ->
                    MessageBlock(message)
                }
                item(key = "streaming") {
                    StreamingBlock(state.chat)
                }
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
    }
}

/**
 * **Un tour de conversation, pose sur le fil.**
 *
 * ### La signature de Tether dans le chat
 * Le `design-soul.md` §5 dit : « Rail vertical a gauche des messages (le fil continue) ». Sans
 * lui, le chat serait un client de chat generique — exactement ce que §6 interdit. Le fil
 * **relie** les tours d'une meme conversation, et le nœud marque l'acteur (toi / l'agent).
 *
 * ### Pourquoi le fil est dessine sur CHAQUE bloc
 * Un `LazyColumn` recycle ses items : un fil dessine « par-dessus la liste » se decalerait des
 * qu'on defile. On le redessine donc localement, en pleine hauteur de chaque bloc — la
 * continuite visuelle vient de ce que les blocs sont jointifs (aucun espacement de liste).
 */
@Composable
private fun MessageBlock(message: ChatMessage) {
    val isUser = message.role == Role.User

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val railX = ChatRail.width.toPx() / 2f
                val color = if (isUser) {
                    TetherTextSecondary.copy(alpha = 0.18f)
                } else {
                    TetherAccent.copy(alpha = 0.22f)
                }
                // Le fil : toujours, sur toute la hauteur, quel que soit l'acteur.
                drawLine(
                    color = color,
                    start = Offset(railX, 0f),
                    end = Offset(railX, size.height),
                    strokeWidth = TetherDimensions.threadWidth.toPx(),
                )
                // Le nœud : teal si c'est l'agent (ce qui pense), gris si c'est toi.
                drawCircle(
                    color = if (isUser) TetherTextSecondary.copy(alpha = 0.5f) else TetherAccent,
                    radius = (if (isUser) 4.dp else 5.dp).toPx(),
                    center = Offset(railX, ChatRail.nodeY.toPx()),
                )
            },
        verticalAlignment = Alignment.Top,
    ) {
        // Reserve la largeur du rail : le contenu commence apres le fil, jamais dessous.
        androidx.compose.foundation.layout.Spacer(Modifier.width(ChatRail.width))

        if (isUser) {
            // ---------------------------------------------- TOI : a droite, teinte posée
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TetherTextPrimary,
                    modifier = Modifier
                        // ⚠️ 0.85 : une bulle utilisateur pleine largeur sur un ecran de
                        // lecture est un aplat qui ecrase le rythme. Elle doit se distinguer
                        // de la reponse, pas la dominer.
                        .fillMaxWidth(0.85f)
                        // Rayon asymetrique : la « queue » pointe cote rail, donc le coin
                        // proche du fil est plus ferme. C'est ce qui rattache la bulle au fil
                        // plutot que de la laisser flotter.
                        .background(
                            TetherAccent.copy(alpha = 0.14f),
                            RoundedCornerShape(
                                topStart = TetherDimensions.cornerMd,
                                topEnd = TetherDimensions.cornerMd,
                                bottomStart = TetherDimensions.cornerSm,
                                bottomEnd = TetherDimensions.cornerMd,
                            ),
                        )
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
            return@Row
        }

        // ---------------------------------------------- L'AGENT : pleine largeur, pas de bulle
        //
        // ⚠️ Une reponse d'agent n'est PAS une bulle : c'est du texte a lire, avec du code et
        // des tableaux. L'encadrer reduirait la largeur utile et casserait le markdown.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.sm, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            message.reasoning.takeIf { it.isNotBlank() }?.let { reasoning ->
                ReasoningBlock(reasoning, durationLabel = message.reasoningDurationLabel)
            }
            if (message.text.isNotBlank()) MarkdownBody(message.text)
            message.tools.forEach { ToolCard(it) }
            message.rawFallback?.takeIf { it.isNotBlank() }?.let { raw ->
                RawFallback(raw)
            }
        }
    }
}

/** Rayon/position du fil du chat. Une seule source de verite pour les trois blocs. */
private object ChatRail {
    val width = 22.dp
    val nodeY = 22.dp
}

/**
 * **Le bout du fil** : ce qui est charge, et ce qui reste.
 *
 * ### Pourquoi cette ligne existe
 * La fenetre glissante ([ChatWindow]) ne charge que les 40 derniers messages et remonte au
 * scroll. Sans marqueur, l'utilisateur ne peut pas savoir **s'il voit le debut de la
 * conversation ou seulement sa fin** — et croire avoir lu un echange entier quand on n'en a vu
 * que la derniere tranche est exactement le genre de mensonge que le `design-soul.md` interdit.
 *
 * ### Deux etats, deux formulations
 *  - **il reste des messages** : un bouton « Remonter dans l'historique », avec le geste
 *    principal qui est le scroll (ce bouton est un raccourci, pas la seule voie) ;
 *  - **on est au debut** : un trait fin et « Début de la conversation ». Un fait, pas une
 *    decoration.
 *
 * ⚠️ Le trait n'est pas qu'esthetique : il **termine le fil**. Le rail se dessine sur chaque
 * bloc ; sans cette ligne, le fil s'arreterait net dans le vide au-dessus du premier message.
 */
@Composable
private fun HistoryTopRow(
    hasOlder: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasOlder) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                    .clickable(enabled = !loading, onClick = onLoadMore)
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        color = TetherAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    Icon(
                        imageVector = Lucide.ChevronUp,
                        contentDescription = null,
                        tint = TetherTextSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = if (loading) "Chargement…" else "Remonter dans l'historique",
                    style = TetherDataStyle,
                    color = TetherTextSecondary,
                )
            }
        } else {
            Text(
                text = "DÉBUT DE LA CONVERSATION",
                style = TetherDataStyle,
                color = TetherTextSecondary.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * Descend au **vrai bas** de la liste, sans animation.
 *
 * ### Pourquoi pas `scrollToItem(dernier)`
 * `scrollToItem` place le **HAUT** de l'item demande en haut du viewport. Sur une reponse courte,
 * ca tombe bien par accident ; sur une reponse de plusieurs ecrans — le cas normal — ca fige le
 * **debut** de la reponse et le texte qui arrive s'ecrit **sous l'ecran**. C'est exactement ce
 * qui donnait l'impression que « l'ecran remonte » : il ne remontait pas, il ne descendait
 * jamais.
 *
 * ### Pourquoi un decalage borne plutot qu'une valeur calculee
 * `scrollToItem(index, offset)` accepte un decalage en pixels qu'il **borne lui-meme** au
 * maximum scrollable : demander « 100 000 px plus bas » sur une liste de 3 items ne casse rien,
 * ca descend simplement tout en bas. On evite ainsi d'avoir a mesurer la hauteur reelle des
 * items, qui est inconnue ici (markdown, cartes d'outils, images…).
 *
 * ⚠️ Volontairement **sans animation** : c'est appele a chaque token pendant le streaming, et
 * une animation serait annulee puis relancee en continu (clignotement garanti, et couteux).
 */
private suspend fun LazyListState.scrollToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last, SCROLL_TO_BOTTOM_OFFSET_PX)
}

/**
 * Meme chose, mais **en douceur** — reserve aux evenements, jamais au flux de tokens.
 *
 * ⚠️ Un token n'est pas un evenement : il y en a des dizaines par seconde. Animer chacun d'eux
 * revient a n'animer aucun d'eux (chaque nouvelle animation annule la precedente).
 */
private suspend fun LazyListState.animateScrollToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    animateScrollToItem(last, SCROLL_TO_BOTTOM_OFFSET_PX)
}

/**
 * Decalage suffisant pour depasser n'importe quel dernier item.
 *
 * ⚠️ Valeur **volontairement enorme** : elle est bornee par Compose au maximum reellement
 * scrollable. Chercher a la calculer exactement demanderait de mesurer chaque item, ce que
 * `LazyColumn` ne permet pas (les items hors ecran n'existent pas).
 */
private const val SCROLL_TO_BOTTOM_OFFSET_PX = 100_000

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
