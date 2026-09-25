package sh.sk7.tether.ui.chat

import com.composables.icons.lucide.Download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.GitCompare
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.PanelBottomOpen
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.Search
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
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherComposerBorder
import com.composables.icons.lucide.X
import sh.sk7.tether.ui.theme.TetherAccent
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherBackground
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherSurface
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherIconMuted
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
    /** Ouvre les diffs **de cette session** : ce que l'agent a reellement change. */
    onOpenDiff: () -> Unit = {},
    /** Ouvre le contexte **de cette session** : ce qui occupe la fenetre. */
    onOpenContext: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // ⚠️ L'activité vient du détenteur partagé, pas de la phase SSE : elle sait qu'un tour tourne
    // même si l'écran vient de s'ouvrir ou si le flux est coupé (voir `sessionActivity`).
    val sessionActivity by viewModel.sessionActivity.collectAsStateWithLifecycle()
    // ⚠️ Seuls les états où **un tour tourne** ouvrent l'action : `Running` (le serveur travaille)
    // et la phase SSE (`Sending`/`Streaming`). Pas `Queued` : un message en attente signifie
    // justement que rien ne tourne pour lui — proposer de « passer en arrière-plan » n'aurait
    // aucun objet.
    val canBackground = state.isBusy ||
        sessionActivity == sh.sk7.tether.domain.model.Activity.Running
    var draft by remember { mutableStateOf("") }

    /** Le selecteur modele/agent est-il ouvert ? */
    var pickerOpen by remember { mutableStateOf(false) }

    /**
     * La dictee est-elle en cours ?
     *
     * ⚠️ C'est un etat et pas seulement un lancement : il faut pouvoir **dire** a l'utilisateur que
     * l'app attend, sinon il appuie sur le micro et rien ne se passe a l'ecran pendant que le
     * dialogue systeme se prepare.
     */
    var dictating by remember { mutableStateOf(false) }

    /** Retour en arriere prepare, en attente de confirmation. */
    var revertTarget by remember { mutableStateOf<String?>(null) }

    /** Confirmation de copie : un retour visible, sinon le geste semble ignore. */
    var copiedNotice by remember { mutableStateOf(false) }

    /** La recherche dans la conversation. Vide = pas de recherche. */
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // ⚠️ On calcule le resultat a la composition, pas dans un `LaunchedEffect` : la recherche est
    // locale et instantanee, donc un aller-retour asynchrone introduirait un delai visible pour
    // une operation qui n'en a aucun besoin.
    val searchResult = remember(state.chat.messages, searchQuery) {
        ChatSearch.find(state.chat.messages, searchQuery)
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val listState = rememberLazyListState()
    // L'export produit un fichier puis ouvre la feuille de partage du systeme : l'utilisateur
    // choisit sa destination (fichiers, mail, depot, note…). On n'impose pas un chemin.
    var exporting by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    /**
     * La dictee est-elle possible sur cet appareil ?
     *
     * ⚠️ Verifie une fois a la composition : le `PackageManager` n'est pas consultable a chaque
     * frappe, et la reponse ne change pas — installer un moteur demande de quitter l'app.
     */
    val voiceAvailable = remember { isVoiceInputAvailable(context) }

    /**
     * **Le selecteur de fichiers du systeme.**
     *
     * ⚠️ `OpenDocument` et non `GetContent` : `OpenDocument` rend une URI stable et lisible par
     * `ContentResolver` directement dans le callback, sans permission de stockage. C'est le chemin
     * qui fonctionne sur GrapheneOS, ou l'acces au stockage est volontairement restreint.
     *
     * ⚠️ La lecture se fait dans le callback `onResult`, sur le dispatcher principal. C'est
     * volontaire : le fichier a ete choisi par l'utilisateur, sa taille est bornee par
     * [PromptAttachments.MAX_FILE_BYTES] avant d'etre encodee, et cette lecture n'a lieu qu'une
     * fois par fichier — monter un `LaunchedEffect` et un `withContext` pour cela ajouterait un
     * etat pour rien.
     */
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val resolver = context.contentResolver
            val name = queryDisplayName(context, uri) ?: "fichier"
            val mime = resolver.getType(uri)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
            viewModel.attachBytes(name = name, mime = mime, bytes = bytes)
        }.onFailure {
            android.util.Log.w("TetherChat", "fichier illisible", it)
        }
    }

    LaunchedEffect(exporting) {
        if (!exporting) return@LaunchedEffect
        val markdown = SessionExporter.toMarkdown(
            title = state.title ?: "Conversation",
            sessionID = state.sessionID,
            state = state.chat,
        )
        shareMarkdown(context, state.title ?: "conversation", markdown)
        exporting = false
    }

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
                    actions = {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Lucide.Search, contentDescription = "Rechercher dans la conversation")
                        }
                        // ⚠️ « Passer en arrière-plan » n'apparaît QUE quand quelque chose tourne.
                        // La route est un no-op quand rien ne bloque (doc serveur) : l'afficher au
                        // repos serait proposer une action sans effet, ce qui apprend à ne plus
                        // croire les boutons. On combine la phase SSE et l'état du serveur, car
                        // seule la seconde sait qu'un tour tourne déjà à l'ouverture de l'écran.
                        if (canBackground) {
                            IconButton(onClick = viewModel::backgroundTools) {
                                Icon(
                                    Lucide.PanelBottomOpen,
                                    contentDescription = "Déplacer les outils en arrière-plan",
                                )
                            }
                        }
                        // ⚠️ Deux actions de plus dans la barre, et elles sont justifiees : les
                        // diffs et le contexte sont les deux informations qu'un client d'agent a
                        // et qu'un client de chat n'a pas. Les enfouir reviendrait a les rendre
                        // invisibles — or c'est precisement ce qu'on vient chercher.
                        IconButton(onClick = onOpenDiff) {
                            Icon(Lucide.GitCompare, contentDescription = "Fichiers modifiés")
                        }
                        IconButton(onClick = onOpenContext) {
                            Icon(Lucide.Layers, contentDescription = "Fenêtre de contexte")
                        }
                        // ⚠️ L'export est une action d'ECRAN, pas de message : on exporte la
                        // conversation entiere. Le mettre dans le menu d'un message laisserait
                        // croire qu'on n'exporte que lui.
                        IconButton(onClick = { exporting = true }) {
                            Icon(Lucide.Download, contentDescription = "Exporter la conversation")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = TetherTextPrimary,
                    ),
                )
                ChatInstrumentHeader(state = state.chat, meta = state.meta)
                // ⚠️ La barre de recherche vit **sous** l'en-tete, pas dans la top bar : elle
                // apparait a la demande, et une top bar qui changerait de hauteur au clic ferait
                // sauter le contenu — donc perdre la position de lecture, precisement au moment
                // ou on cherche quelque part dans la conversation.
                if (searchOpen) {
                    ChatSearchBar(
                        query = searchQuery,
                        result = searchResult,
                        onQueryChange = { searchQuery = it },
                        onClose = {
                            searchOpen = false
                            searchQuery = ""
                        },
                    )
                }
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
            // Ce que ce `bottomBar` porte encore : la file d'attente et les messages, qui doivent
            // rester visibles au-dessus de la saisie.
            Column {
                // ⚠️ La file d'attente est au-dessus de la saisie, pas dans la liste : un message
                // en attente est une **intention en cours**, pas un tour de conversation. Le
                // noyer dans le fil le ferait lire comme deja envoye. Voir [QueuedBar].
                QueuedBar(
                    queued = state.chat.messages.filter { it.isQueued },
                    cancelling = state.cancelling,
                    onCancel = viewModel::cancelQueued,
                )
                state.notice?.let { notice ->
                    // ⚠️ Teinte neutre, jamais celle de l'erreur : « l'appel était sans effet » est
                    // un resultat normal, pas une panne (voir [ChatUiState.notice]).
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherTextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = viewModel::clearNotice)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                state.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = TetherAlert,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
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
                // ⚠️ **Les messages en file ne sont PAS rendus ici** : ils vivent dans [QueuedBar],
                // au-dessus de la saisie. Les afficher aux deux endroits serait un doublon, et les
                // noyer dans le fil les ferait lire comme deja envoyes — alors qu'ils attendent.
                // C'est une presentation differente parce que c'est un **etat different**.
                itemsIndexed(
                    state.chat.messages.filterNot { it.isQueued },
                    key = { _, m -> m.id },
                ) { _, message ->
                    MessageBlock(
                        message = message,
                        onCopy = { copied ->
                            // ⚠️ On copie le TEXTE, pas le markdown source : ce qu'on veut coller
                            // ailleurs est ce qu'on lit a l'ecran.
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(copied.text))
                            copiedNotice = true
                        },
                        // ⚠️ On retient le message vise, et le dialogue `RevertDialog` fait le
                        // `stage` lui-meme : il porte son propre ViewModel, donc c'est lui qui a
                        // acces a l'etat de retour en arriere.
                        onRevert = { target -> revertTarget = target.id },
                    )
                }
                item(key = "streaming") {
                    StreamingBlock(state.chat)
                }
            }
            val commands by viewModel.commands.collectAsStateWithLifecycle()
            val models by viewModel.models.collectAsStateWithLifecycle()
            val agents by viewModel.agents.collectAsStateWithLifecycle()
            val skills by viewModel.skills.collectAsStateWithLifecycle()

            // ⚠️ La palette n'apparait que si une commande est EN COURS DE FRAPPE (voir
            // `SlashInput`). La logique est testee a part parce qu'elle a trois faux positifs
            // reels — dont un chemin de fichier qui commence par `/`.
            val slash = SlashInput.parse(draft)
            if (slash.visible) {
                SlashPalette(
                    commands = SlashInput.filter(commands, slash.query),
                    onPick = { command -> draft = SlashInput.apply(command) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (pickerOpen) {
                ModelAgentPicker(
                    models = models.map { it.id },
                    agents = agents.map { it.id },
                    skills = skills.map { it.id },
                    currentModel = state.meta?.model,
                    currentAgent = state.meta?.agent,
                    onPickSkill = { id ->
                        pickerOpen = false
                        // ⚠️ Activer une competence est un **effet immediat** (le serveur reprend
                        // l'execution), pas un reglage stocke : on ferme la feuille pour que
                        // l'utilisateur voie le resultat dans la conversation.
                        viewModel.activateSkill(id)
                    },
                    onPickModel = { id ->
                        pickerOpen = false
                        models.firstOrNull { it.id == id }?.let { picked ->
                            viewModel.setModel(
                                sh.sk7.tether.data.api.ModelRef(
                                    id = picked.modelID ?: picked.id,
                                    providerID = picked.providerID.orEmpty(),
                                ),
                            )
                        }
                    },
                    onPickAgent = { id ->
                        pickerOpen = false
                        viewModel.setAgent(id)
                    },
                    onDismiss = { pickerOpen = false },
                )
            }

            Composer(
                value = draft,
                busy = state.isBusy,
                // ⚠️ Le micro n'est PAS dans la barre d'actions : il est dans le composer, a cote
                // du bouton d'envoi, parce que c'est une facon d'ECRIRE. Le mettre ailleurs en
                // ferait une fonction a part, alors que c'est la meme intention.
                // ⚠️ `null` masque le micro quand l'appareil ne peut pas dicter. Mesure sur le
                // Pixel de test : aucun moteur installe (GrapheneOS ne livre pas ceux de Google).
                // Un micro visible mais inerte serait un mensonge visuel.
                onVoice = if (voiceAvailable) {
                    { dictating = true }
                } else {
                    null
                },
                listening = dictating,
                onValueChange = { draft = it },
                // ⚠️ Le selecteur est accessible par le bouton d'envoi lui-meme quand le champ est
                // vide : choisir un modele ne demande rien d'ecrire, et c'est le moment ou on le
                // fait — avant de composer.
                onPickModelAgent = { pickerOpen = true },
                attachments = state.attachments,
                onRemoveAttachment = viewModel::removeAttachment,
                // ⚠️ Le trombone ouvre le selecteur **du systeme** : c'est lui qui a acces aux
                // documents, et l'app n'a aucune permission de stockage a demander. Lire le
                // contenu nous-memes (plutot que de passer un chemin au serveur) est le seul
                // choix possible : le telephone n'a pas acces au disque de la machine distante.
                onAttach = { filePicker.launch(arrayOf("*/*")) },
                onSend = {
                    val text = draft
                    // ⚠️ On verifie d'abord si c'est une COMMANDE valide. Le serveur valide le nom
                    // contre sa liste : envoyer `/review` comme texte de prompt ne declencherait
                    // rien du tout, l'agent le lirait comme une phrase.
                    val asCommand = SlashInput.toCommand(text, commands)
                    draft = ""
                    if (asCommand != null) {
                        viewModel.runCommand(asCommand.first, asCommand.second)
                    } else {
                        viewModel.send(text)
                    }
                },
                onStop = viewModel::stop,
            )
        }
    }

    // Le retour en arriere : le dialogue fait le `stage` puis attend confirmation. Il n'est monte
    // que lorsqu'un message a ete vise, donc aucun appel serveur n'a lieu sans intention.
    revertTarget?.let { target ->
        RevertDialog(
            sessionID = viewModel.sessionID,
            messageID = target,
            onDone = {
                revertTarget = null
                viewModel.resync()
            },
            onDismiss = { revertTarget = null },
        )
    }

    // ⚠️ La dictee est un effet, pas un composable : elle lance un intent systeme et rend son
    // resultat. Le `LaunchedEffect` est la seule facon d'avoir un lanceur dont la duree de vie
    // suit la composition.
    if (dictating) {
        VoiceInput(
            onResult = { spoken ->
                dictating = false
                // ⚠️ On AJOUTE a la saisie en cours au lieu de la remplacer : dicter au milieu
                // d'une phrase deja ecrite est le cas courant, et ecraser le travail deja fait
                // serait violent.
                if (spoken.isNotBlank()) {
                    draft = if (draft.isBlank()) spoken else "$draft $spoken"
                }
            },
            onCancel = { dictating = false },
        )
    }
}

/**
 * **La file d'attente : ce que tu as écrit et qui n'est pas encore parti.**
 *
 * ### Pourquoi une barre au-dessus de la saisie, et pas dans le fil
 * Un message en file n'est **pas** un tour de conversation : c'est une **intention en cours**.
 * Le rendre comme une bulle ordinaire ferait croire qu'il a été envoyé — et c'est exactement ce
 * que l'app faisait : elle ajoutait l'item d'inbox dans le fil **sans son mode**, donc impossible
 * de savoir s'il allait corriger le tour (`steer`) ou attendre son tour (`queue`).
 *
 * ⚠️ C'est la distinction que les utilisateurs d'opencode réclamaient (issue #32157, 84 👍) :
 * « en file » ne dit rien tant qu'on ignore si le message **interrompt** ou **patiente**.
 *
 * ### Pourquoi l'annulation est ici
 * ⚠️ Réponse directe à l'issue #4821 (126 👍) : un message soumis pendant que l'agent tourne part
 * en file et, sans cette route, **ne peut plus être annulé**. Le bouton est sur la ligne du
 * message concerné — on annule celui qu'on voit, pas « la file ».
 */
@Composable
private fun QueuedBar(
    queued: List<ChatMessage>,
    cancelling: Set<String>,
    onCancel: (String) -> Unit,
) {
    if (queued.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = if (queued.size == 1) "1 message en attente" else "${queued.size} messages en attente",
            style = TetherDataStyle,
            color = TetherTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        queued.forEach { message ->
            QueuedRow(
                message = message,
                busy = message.id in cancelling,
                onCancel = { onCancel(message.id) },
            )
        }
    }
}

/**
 * Une entrée de la file, **avec son mode dit en clair**.
 *
 * ⚠️ Le libellé est **traduit du mode**, jamais deviné : `steer` -> « corrige le tour en cours »,
 * `queue` -> « attend son tour ». Afficher « en file » pour les deux jetterait précisément
 * l'information qui explique ce qui va se passer.
 */
@Composable
private fun QueuedRow(
    message: ChatMessage,
    busy: Boolean,
    onCancel: () -> Unit,
) {
    val modeLabel = if (message.isSteering) "corrige le tour en cours" else "attend son tour"
    val tint = if (message.isSteering) TetherAccent else TetherTextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.Hourglass,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.text.ifBlank { "Message sans texte" },
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = modeLabel, style = TetherDataStyle, color = tint)
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = TetherAccent,
            )
        } else {
            // ⚠️ `X` est une icône **universelle** d'annulation, mais sa description dit l'objet
            // précis : « Annuler ce message », pas « Fermer » — un lecteur d'écran doit savoir ce
            // qu'il annule.
            IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = "Annuler ce message en attente",
                    tint = TetherTextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
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
private fun MessageBlock(
    message: ChatMessage,
    onCopy: (ChatMessage) -> Unit,
    onRevert: ((ChatMessage) -> Unit)?,
) {
    val isUser = message.role == Role.User

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .thread(isUser),
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
                MessageActions(
                    message = message,
                    onCopy = onCopy,
                    onRevert = onRevert,
                    alignEnd = true,
                )
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
            MessageActions(
                message = message,
                onCopy = onCopy,
                onRevert = onRevert,
                alignEnd = false,
            )
        }
    }
}


/**
 * **Les actions d'un message : copier, et revenir a ce point.**
 *
 * ### Ce qui n'est PAS la, et pourquoi
 * Pas de « regenerer » au sens d'un client de chat (reecrire la meme reponse). L'API d'opencode
 * n'a pas cette notion : on ne rejoue pas un tour, on **revient** a un point et on relance.
 * Inventer un bouton « regenerer » qui ferait autre chose que ce qu'il annonce serait exactement
 * le mensonge qu'on s'interdit.
 *
 * ⚠️ « Revenir ici » n'apparait que sur les messages de l'**agent**, et **jamais** sur un message
 * encore optimiste : son identifiant n'existe pas cote serveur, donc l'action echouerait a coup
 * sur. Une action qui ne peut pas marcher est pire que pas d'action.
 *
 * ⚠️ Pas de 👍/👎 : les utilisateurs ne savent pas ce que fait ce bouton, et la doc d'OpenAI
 * indique que la conversation peut servir a l'entrainement. Ici, un bouton de satisfaction qui ne
 * ferait rien de verifiable serait un mensonge de plus.
 */
@Composable
private fun MessageActions(
    message: ChatMessage,
    onCopy: (ChatMessage) -> Unit,
    onRevert: ((ChatMessage) -> Unit)?,
    alignEnd: Boolean,
) {
    Row(
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessageAction("Copier", Lucide.Copy) { onCopy(message) }
        if (onRevert != null && message.role == Role.Assistant && !message.id.startsWith("local_")) {
            MessageAction("Revenir ici", Lucide.Undo2) { onRevert(message) }
        }
    }
}

@Composable
private fun MessageAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            // ⚠️ Cible tactile : la ligne visible fait ~28 dp, sous les 48 dp exiges (WCAG 2.5.8,
            // et le European Accessibility Act s'applique depuis le 28 juin 2025). Le
            // `heightIn` est place AVANT `clickable` : c'est la seule position ou Compose en
            // tient compte pour calculer la zone sensible.
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm)
            // ⚠️ `role = Button` : TalkBack doit annoncer « bouton », pas seulement lire le
            // libellé. Sans lui, l'utilisateur entend « Copier » sans savoir qu'il peut appuyer.
            .semantics { role = androidx.compose.ui.semantics.Role.Button },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TetherIconMuted,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            style = TetherDataStyle,
            color = TetherTextMuted,
        )
    }
}

/** Rayon/position du fil du chat. Une seule source de verite pour les trois blocs. */
private object ChatRail {
    val width = 22.dp
    val nodeY = 22.dp
}

/**
 * **Dessine le fil et son nœud**, sur toute la hauteur de l'item qui l'applique.
 *
 * ⚠️ **Pourquoi c'est un helper et plus un `drawBehind` en ligne.** Le direct
 * ([StreamingBlock]) et l'historique ([MessageBlock]) sont deux items voisins de la meme
 * liste : si un seul porte le fil, le fil **s'interrompt** au milieu de la conversation. Et
 * c'est exactement ce qui se voyait : pendant un tour, le bloc en cours s'affichait colle au
 * bord gauche, sans rail, sans marge — « tout condense, moche, il manque des informations »
 * (constat de Bastien sur le Pixel, 2026-09-25).
 *
 * ⚠️ Declaree au **niveau du fichier** et non dans [ChatRail] : une extension membre d'un objet
 * ne se resout pas implicitement chez ses voisins, il faudrait un `with(ChatRail) { … }` a
 * chaque appel — trois sites a se rappeler, donc trois occasions d'en oublier un.
 */
private fun Modifier.thread(isUser: Boolean): Modifier = drawBehind {
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
                    // ⚠️ 48 dp : « Remonter dans l'historique » est une cible, pas une légende.
                    .heightIn(min = TetherDimensions.touchTarget)
                    .clickable(enabled = !loading, onClick = onLoadMore)
                    .padding(horizontal = Spacing.md),
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
                color = TetherTextMuted,
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

/**
 * Bloc transitoire du tour en cours : raisonnement, texte, outils.
 *
 * ⚠️ **Il doit se dessiner comme un message d'agent, parce que c'en est un.**
 *
 * Il ne l'etait pas : pas de rail, pas de marge, contenu colle au bord gauche, alors que le
 * message juste au-dessus (une fois le tour termine) est indente et porte le fil. Resultat sur
 * le Pixel : des qu'un tour tournait, la conversation changeait de mise en page — « tout
 * condense, moche » — et le fil s'interrompait. Le meme tour **sautait** visuellement a la fin,
 * quand son contenu passait de [StreamingBlock] a un message normal.
 *
 * ⚠️ Le `Spacer(ChatRail.width)` et les marges reprennent **les memes valeurs** que la branche
 * agent de [MessageBlock] : toute divergence recreerait le saut a la fin du tour.
 */
@Composable
private fun StreamingBlock(chat: sh.sk7.tether.domain.model.SessionUiState) {
    val hasReasoning = !chat.streamingReasoning.isNullOrBlank()
    val hasText = !chat.streamingText.isNullOrBlank()
    val hasTools = chat.streamingTools.isNotEmpty()
    if (!hasReasoning && !hasText && !hasTools) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ Meme fil que les messages : sans lui, la ligne se couperait pendant le tour.
            .thread(isUser = false),
        verticalAlignment = Alignment.Top,
    ) {
        // Reserve la largeur du rail : le contenu commence apres le fil, jamais dessous.
        Spacer(Modifier.width(ChatRail.width))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.sm, end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
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

/**
 * Nom affichable d'un document choisi par le selecteur du systeme.
 *
 * ⚠️ `OpenDocument` rend souvent un nom opaque (`content://.../1234`). Le nom lisible vit dans
 * la colonne `DISPLAY_NAME` du `ContentResolver` : sans cette requete, on joindrait un fichier
 * nomme « 1234 » et l'utilisateur ne reconnaitrait pas ce qu'il a joint.
 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

/**
 * **Partage d'un export Markdown** via la feuille du systeme.
 *
 * ### Pourquoi un fichier et pas juste du texte
 * ⚠️ Un long export depose dans le presse-papiers est inutilisable sur mobile : on ne peut pas
 * le coller utilement, et il disparait au redemarrage. Un **fichier** se range, s'envoie et
 * s'ouvre — c'est ce que l'utilisateur veut faire d'un export.
 *
 * ⚠️ `Intent.createChooser` est obligatoire : sans lui, Android prend l'application par defaut
 * et l'utilisateur ne choisit rien. Le partage doit toujours passer par la feuille de choix.
 *
 * ⚠️ Le fichier est ecrit dans le **cache** de l'app : c'est le seul repertoire partageable sans
 * permission de stockage, et le systeme le nettoie de lui-meme.
 */
private fun shareMarkdown(
    context: android.content.Context,
    title: String,
    markdown: String,
) {
    runCatching {
        // Nom de fichier assaini : une barre oblique dans un titre ferait echouer l'ecriture.
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40)
            .ifBlank { "conversation" }
        val file = java.io.File(context.cacheDir, "$safe.md")
        file.writeText(markdown)

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Partager la conversation"),
        )
    }.onFailure {
        android.util.Log.w("TetherChat", "export impossible", it)
    }
}

/**
 * **La barre de recherche dans la conversation.**
 *
 * ### Pourquoi elle vit sous l'en-tete et non dans la top bar
 * ⚠️ Elle **apparait a la demande**. Une top bar dont la hauteur change au clic ferait sauter tout
 * le contenu sous elle, et la position de lecture serait perdue — precisement au moment ou on
 * cherche quelque part dans la conversation.
 *
 * ### Ce que le compteur dit, et pourquoi il est precis
 * On affiche **« 3 messages · 7 occurrences »** et pas seulement un total : un message peut
 * contenir le mot cherche sept fois, et « 7 » tout seul laisserait croire a sept messages. Les
 * deux chiffres repondent a deux questions differentes (« combien d'endroits ? » et « combien de
 * messages a ouvrir ? »).
 *
 * ⚠️ Le compteur est **neutre tant que rien n'est cherche** : afficher « 0 » sur une barre qu'on
 * vient d'ouvrir ferait croire qu'il n'y a rien, alors qu'on n'a rien demande.
 */
@Composable
private fun ChatSearchBar(
    query: String,
    result: ChatSearch.Result,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TetherDimensions.cornerMd))
                .background(TetherComposerSurface)
                .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                tint = TetherTextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Rechercher dans la conversation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TetherTextSecondary,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TetherTextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(TetherAccent),
                    // ⚠️ `fillMaxWidth` obligatoire : sans lui, le champ n'occupe que la largeur de
                    // son texte — vide au depart — et le tap tombe sur le placeholder. C'est le
                    // bug deja rencontre sur la recherche de sessions.
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // ⚠️ Boîte de 48 dp **explicite**, et non un padding autour de l'icône : dans
            // `padding(12).size(15).clickable`, c'est le `clickable` qui est le plus interne et il
            // ne couvre que les 15 dp de l'icône — le padding est *hors* de la zone sensible. Une
            // `Box` de 48 dp avec l'icône centrée ne laisse aucune ambiguïté, et l'ordre des
            // modificateurs ne peut plus l'inverser.
            Box(
                modifier = Modifier
                    .size(TetherDimensions.touchTarget)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClick = onClose)
                    .semantics {
                        role = androidx.compose.ui.semantics.Role.Button
                        contentDescription = "Fermer la recherche"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = null,
                    tint = TetherTextSecondary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        if (query.isNotBlank()) {
            Text(
                text = if (result.isEmpty) {
                    "Aucun résultat pour « $query »"
                } else {
                    val msgs = result.messageCount
                    val total = result.total
                    "$msgs message${if (msgs > 1) "s" else ""} · " +
                        "$total occurrence${if (total > 1) "s" else ""}"
                },
                style = TetherDataStyle,
                color = if (result.isEmpty) TetherAlert else TetherTextSecondary,
            )
        }
    }
}
