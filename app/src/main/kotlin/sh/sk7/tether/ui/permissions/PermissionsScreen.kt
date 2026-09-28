package sh.sk7.tether.ui.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ShieldCheck
import com.composables.icons.lucide.X
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.ui.forms.FormsScreen
import sh.sk7.tether.ui.forms.FormsViewModel
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * **Les deux files d'attente qui bloquent un agent.**
 *
 * ⚠️ Une permission et un formulaire immobilisent la session **de la meme facon** : tant que
 * personne ne repond, l'agent ne bouge plus. Les separer en deux ecrans obligeait a savoir lequel
 * consulter — et un blocage qu'on ne sait pas chercher est un blocage invisible, exactement le
 * defaut que l'ecran d'approbations corrige.
 */
enum class ApprovalTab { Permissions, Forms }

/**
 * Intervalle de relecture de la file des formulaires quand elle n'est pas affichee.
 *
 * ⚠️ Aligne sur le polling des autorisations (6 s) : les deux files doivent signaler une demande
 * avec la meme reactivite, sinon l'onglet d'a cote paraitrait fige alors que l'autre bouge.
 */
private const val FORMS_POLL_INTERVAL_MS = 6_000L

/**
 * **Quel onglet ouvrir en premier, et pourquoi.**
 *
 * ⚠️ C'est une fonction **pure**, donc testable : la regle decide qu'une demande **ne sera pas
 * manquee**. Ouvrir sur un onglet vide quand l'autre contient un agent bloque obligerait
 * l'utilisateur a deviner de quel cote regarder — exactement le defaut que cet ecran corrige.
 *
 * ⚠️ Priorite aux permissions : elles seules portent une alerte persistante, et une autorisation
 * accordee « toujours » change les droits pour de bon. Les formulaires viennent ensuite.
 */
fun initialApprovalTab(permissionsPending: Int, formsPending: Int): ApprovalTab = when {
    permissionsPending > 0 -> ApprovalTab.Permissions
    formsPending > 0 -> ApprovalTab.Forms
    else -> ApprovalTab.Permissions
}

/**
 * **Peut-on relire la file des formulaires maintenant ?**
 *
 * ⚠️ Non pendant qu'un formulaire est ouvert : `FormsViewModel.load()` remplace `forms` mais garde
 * `openIndex`, donc le formulaire affiche serait **remplace par un autre** si la liste changeait —
 * c'est-a-dire sous les doigts de quelqu'un en train de le remplir. La veille periodique doit se
 * taire tant qu'une saisie est en cours.
 */
fun canReloadForms(hasOpenForm: Boolean): Boolean = !hasOpenForm

/**
 * **Ce qui attend une reponse, en un seul endroit.**
 *
 * ### Ce que cet ecran repare
 * Documentation d'opencode et rapports d'utilisateurs convergent : une session peut rester
 * **bloquee des heures** parce qu'une demande n'a ete vue par personne. Le reproche recurrent est
 * d'ailleurs de **devoir** approuver — et la seule reponse possible etant « oui », on finit par
 * approuver sans lire. D'ou deux choix de conception ici :
 *
 *  1. **On montre tout ce qui permet de decider** : l'action, la ressource exacte, le message du
 *     serveur. Pas de resume. Un utilisateur qui approuve doit reconnaitre ce qu'il approuve.
 *  2. **Trois reponses, pas deux** : `once` (cette fois), `always` (et mémoriser), `reject`.
 *     Pouvoir approuver **sans** accorder un droit permanent est ce qui evite le clic reflexe.
 *
 * ### Pourquoi les formulaires sont ICI, et pas sur un ecran a part
 * Un formulaire bloque l'agent **exactement comme une permission** (mesure : la session reste
 * immobile tant que personne ne repond). Un ecran separe obligerait l'utilisateur a savoir qu'il
 * existe, puis a penser a le consulter — c'est-a-dire a faire le travail que l'app doit faire.
 * Ici, **un seul endroit** repond a « qu'est-ce qui m'attend ? », et l'onglet porte le compte.
 *
 * ⚠️ Les deux files ont **leur propre ViewModel** ([PermissionsViewModel] et [FormsViewModel]) :
 * on ne replie pas le rendu des formulaires (brouillon, conditions `when`, acquittement des
 * `external`, validation locale), on le **reutilise** via [FormsScreen]. Un second rendu aurait
 * diverge du premier a la premiere regle ajoutee.
 *
 * ### L'etat vide n'est pas une perte de place
 * Quand il n'y a rien a approuver, on le **dit** et on explique ce que ca signifie (l'agent
 * travaille sans rien demander). Un ecran blanc laisserait croire a une panne de chargement.
 */
@Composable
fun PermissionsScreen(
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = hiltViewModel(),
    formsViewModel: FormsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formsState by formsViewModel.state.collectAsStateWithLifecycle()

    // ⚠️ Quand la file des autorisations se vide, l'alerte persistante n'a plus d'objet. On la
    // retire **ici** et non dans le ViewModel : cela evite d'y injecter un `Context` (donc de
    // rendre le ViewModel dependant d'Android et intestable), et l'ecran est precisement celui ou
    // l'utilisateur vient de traiter la demande.
    //
    // ⚠️ La notification persistante est posee par le push sur les **permissions** seules (voir
    // `TetherNotifier.pendingDecisions`) : on la conditionne donc ici au meme signal. Un formulaire
    // en attente n'a pas d'alerte persistante propre a retirer.
    //
    // ⚠️ On n'agit **que** sur la transition « il y avait quelque chose -> il n'y a plus rien » :
    // retirer la notification a chaque recomposition, ou au premier chargement sans rien a
    // approuver, annulerait une alerte legitime postee alors que l'ecran n'etait pas ouvert.
    val context = androidx.compose.ui.platform.LocalContext.current
    var hadPending by remember { mutableStateOf(false) }
    LaunchedEffect(state.hasAny) {
        if (state.hasAny) {
            hadPending = true
        } else if (hadPending) {
            sh.sk7.tether.push.TetherNotifier.clearOngoing(context)
            hadPending = false
        }
    }

    // ⚠️ Onglet initial : celui qui porte une demande. On l'arrete **une fois les deux files
    // chargees**, et on ne le recalcule plus — sinon un formulaire qui arrive pendant qu'on lit
    // les autorisations ferait sauter l'onglet sous les yeux de l'utilisateur.
    var chosenTab by rememberSaveable { mutableStateOf<String?>(null) }
    var initialised by rememberSaveable { mutableStateOf(false) }
    val bothLoaded = !state.loading && !formsState.loading
    LaunchedEffect(bothLoaded, state.hasAny, formsState.forms.size) {
        if (!initialised && bothLoaded) {
            chosenTab = initialApprovalTab(
                permissionsPending = state.pending.size,
                formsPending = formsState.forms.size,
            ).name
            initialised = true
        }
    }
    val selected = chosenTab?.let { name ->
        ApprovalTab.entries.firstOrNull { it.name == name }
    } ?: ApprovalTab.Permissions

    // ⚠️ **La file des formulaires n'a pas de polling propre.**
    // `PermissionsViewModel` interroge le serveur toutes les 6 s ; `FormsViewModel`, lui, ne lit
    // qu'a sa creation — suffisant quand il etait le seul ecran, mais pas ici : un formulaire pose
    // pendant qu'on lit les autorisations resterait invisible jusqu'a la prochaine navigation.
    // Une demande qui attend et qu'on ne montre pas est exactement le blocage qu'on corrige.
    //
    // ⚠️ **On ne veille que sur la file cachee**, et on lit **a l'entree** sur l'onglet affiche :
    //  - veiller sur l'onglet visible ferait passer `FormsViewModel.load()` par `loading=true`,
    //    donc clignoter le spinner de [FormsScreen] toutes les 6 s ;
    //  - lire a l'entree suffit : on voit toujours la derniere reponse du serveur quand on ouvre.
    //
    // ⚠️ **Jamais pendant qu'un formulaire est ouvert.** `load()` relit `forms` mais laisse
    // `openIndex` : un formulaire ouvert a l'index 0 serait **remplace** par le suivant si la liste
    // changeait, c'est-a-dire sous les doigts de quelqu'un en train de le remplir. On lit donc
    // `state.value` (l'etat **vivant**) et non la valeur capturee par la composition.
    LaunchedEffect(selected) {
        if (canReloadForms(formsViewModel.state.value.openForm != null)) formsViewModel.load()
        if (selected == ApprovalTab.Forms) return@LaunchedEffect
        while (isActive) {
            delay(FORMS_POLL_INTERVAL_MS)
            if (canReloadForms(formsViewModel.state.value.openForm != null)) formsViewModel.load()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ApprovalTabs(
            selected = selected,
            permissions = state.pending.size,
            forms = formsState.forms.size,
            onSelect = { chosenTab = it.name },
        )
        Box(Modifier.fillMaxSize()) {
            when (selected) {
                ApprovalTab.Permissions -> PermissionQueue(
                    state = state,
                    formsWaiting = formsState.forms.size,
                    onDecide = { request, decision -> viewModel.reply(request, decision) },
                )
                ApprovalTab.Forms -> Column(Modifier.fillMaxSize()) {
                    // ⚠️ **Le retour vers la liste des formulaires.**
                    // [FormsScreen] n'expose aucun retour quand un formulaire est ouvert : il part
                    // du principe qu'un envoi reussi ramene a la liste (le formulaire disparait).
                    // Mais un envoi **refuse** (409 « deja regle », 400) laisse le formulaire
                    // ouvert — et sans ce chemin, l'utilisateur serait bloque sur un formulaire
                    // qu'il ne peut plus ni envoyer ni quitter. On ne corrige pas `FormsScreen`
                    // (hors perimetre) : l'hote fournit le retour, en relisant le serveur pour que
                    // la liste dise la verite.
                    if (formsState.openForm != null) {
                        FormBackBar(onBack = { formsViewModel.load(); formsViewModel.close() })
                    }
                    FormsScreen(viewModel = formsViewModel)
                }
            }
        }
    }
}

/**
 * **Revenir a la liste des formulaires sans envoyer.**
 *
 * ⚠️ On relit le serveur **avant** de fermer : un formulaire regle par ailleurs (409) doit
 * disparaitre de la liste, sinon on rouvrirait un formulaire mort. C'est le meme principe que
 * l'absence de suppression optimiste — la verite vient du serveur.
 */
@Composable
private fun FormBackBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(onClick = onBack)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(
            imageVector = Lucide.ArrowLeft,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(stringResource(R.string.retour_formulaires_4ef29b),
            style = MaterialTheme.typography.bodyMedium,
            color = TetherTextSecondary,
        )
    }
}

/**
 * **La barre des deux files**, avec leur compte.
 *
 * ⚠️ Le compte est affiche et teinte d'alerte quand il est non nul : un onglet muet obligerait a
 * l'ouvrir pour savoir s'il contient quelque chose — c'est-a-dire a refaire le travail que le
 * badge doit epargner. C'est la meme regle que l'icone de la barre des sessions.
 */
@Composable
private fun ApprovalTabs(
    selected: ApprovalTab,
    permissions: Int,
    forms: Int,
    onSelect: (ApprovalTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        TabChip(
            label = stringResource(R.string.autorisations_ae3a20),
            count = permissions,
            active = selected == ApprovalTab.Permissions,
            onClick = { onSelect(ApprovalTab.Permissions) },
            modifier = Modifier.weight(1f),
        )
        TabChip(
            label = stringResource(R.string.formulaires_edb363),
            count = forms,
            active = selected == ApprovalTab.Forms,
            onClick = { onSelect(ApprovalTab.Forms) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabChip(
    label: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ L'alerte prime sur la selection : un onglet **actif** qui porte une demande doit rester
    // teinte d'alerte, sinon on lirait « c'est calme » en le regardant. Le fond dit la selection,
    // la teinte dit le contenu — deux informations distinctes.
    val tint = if (count > 0) TetherAlert else if (active) TetherTextPrimary else TetherTextSecondary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(if (active) LocalAccent.current.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .heightIn(min = TetherDimensions.touchTarget)
            // ⚠️ `selectable` (et pas `clickable`) : TalkBack doit annoncer un **onglet**, avec son
            // etat selectionne. Sur une file d'attente, savoir ou l'on se trouve compte autant que
            // ce qu'on y lit.
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            fontWeight = if (active || count > 0) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                style = TetherDataStyle,
                color = TetherAlert,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** La file des autorisations : liste, etat vide, et chargement. */
@Composable
private fun PermissionQueue(
    state: PermissionsUiState,
    formsWaiting: Int,
    onDecide: (PermissionRequest, PermissionDecision) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && !state.hasAny -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current)
            }
            !state.hasAny -> EmptyApprovals(formsWaiting = formsWaiting)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                state.error?.let { message ->
                    item(key = "error") {
                        Notice(text = message, tone = Tone.Alert)
                    }
                }
                item(key = "count") {
                    Text(
                        text = if (state.pending.size == 1) {
                            "1 action attend une réponse"
                        } else {
                            "${state.pending.size} actions attendent une réponse"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = TetherTextPrimary,
                    )
                }
                items(state.pending, key = { it.id }) { request ->
                    PermissionCard(
                        request = request,
                        busy = request.id in state.replying,
                        onDecide = { decision -> onDecide(request, decision) },
                    )
                }
            }
        }
    }
}

/**
 * Le cas « rien a approuver ».
 *
 * ⚠️ On **explique** au lieu de laisser un vide : l'utilisateur doit comprendre que l'absence de
 * demande est un etat normal (l'agent travaille avec les droits qu'il a), pas un chargement qui
 * n'aboutit pas.
 *
 * ⚠️ Si des formulaires attendent dans l'autre onglet, on le **dit** : un etat vide est un
 * mensonge si une demande existe ailleurs et qu'on la tait.
 */
@Composable
private fun EmptyApprovals(formsWaiting: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Lucide.ShieldCheck,
            contentDescription = null,
            tint = TetherTextSecondary,
            modifier = Modifier.size(32.dp),
        )
        Text(stringResource(R.string.rien_approuver_17e7f7),
            style = MaterialTheme.typography.titleSmall,
            color = TetherTextPrimary,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = if (formsWaiting > 0) {
                if (formsWaiting == 1) {
                    "1 formulaire attend une réponse dans l'onglet « Formulaires »."
                } else {
                    "$formsWaiting formulaires attendent une réponse dans l'onglet « Formulaires »."
                }
            } else {
                "L'agent travaille avec les droits qu'il a déjà. " +
                    "Une demande apparaîtra ici dès qu'il aura besoin d'autre chose."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (formsWaiting > 0) TetherAlert else TetherTextSecondary,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * Une demande et ses trois reponses.
 *
 * ⚠️ L'**action** est le titre, en grand, dans la fonte des donnees : c'est le mot que
 * l'utilisateur doit reconnaitre. `bash` ou `edit` ne se traduisent pas — les reformuler ferait
 * perdre precisement l'information qui permet de decider.
 */
@Composable
private fun PermissionCard(
    request: PermissionRequest,
    busy: Boolean,
    onDecide: (PermissionDecision) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Lucide.Hourglass,
                contentDescription = null,
                tint = TetherAlert,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = request.action.ifBlank { "action" },
                style = MaterialTheme.typography.titleSmall,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Ce sur quoi porte l'action : le fait decisif, donc jamais tronque a l'exces.
        if (request.resources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                request.resources.forEach { resource ->
                    Text(
                        text = resource,
                        style = TetherDataStyle,
                        color = TetherTextSecondary,
                    )
                }
            }
        }

        // Message du serveur, s'il en donne un : c'est lui qui explique le POURQUOI.
        request.message?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextSecondary.copy(alpha = 0.9f),
            )
        }

        if (request.save.isNotEmpty()) {
            Text(
                text = "« Toujours » mémoriserait : ${request.save.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
            )
        }

        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = LocalAccent.current,
                )
                Text(stringResource(R.string.envoi_reponse_ee6e99), style = TetherDataStyle, color = TetherTextSecondary)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DecisionButton(
                    label = stringResource(R.string.refuser_628971),
                    icon = Lucide.X,
                    tint = TetherAlert,
                    onClick = { onDecide(PermissionDecision.Reject) },
                )
                DecisionButton(
                    label = stringResource(R.string.fois_55ef18),
                    icon = Lucide.Check,
                    tint = LocalAccent.current,
                    // ⚠️ « Une fois » est propose en PREMIER dans la lecture (apres Refuser, qui
                    // doit rester accessible sans chercher) : c'est le choix qui n'engage rien,
                    // et c'est celui qu'on veut rendre le plus facile.
                    onClick = { onDecide(PermissionDecision.Once) },
                )
                DecisionButton(
                    label = stringResource(R.string.toujours_ec25a7),
                    icon = Lucide.ShieldCheck,
                    tint = TetherTextSecondary,
                    // Visuellement plus discret : c'est le seul choix qui survit a la session.
                    subtle = true,
                    onClick = { onDecide(PermissionDecision.Always) },
                )
            }
        }
    }
}

@Composable
private fun DecisionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    subtle: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(tint.copy(alpha = if (subtle) 0.06f else 0.12f))
            // ⚠️ 48 dp : approuver ou refuser une action d'agent est LA décision de l'app. Une
            // cible de 30 dp sur ces boutons serait le pire endroit pour rater son geste.
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md)
            // ⚠️ `role = Button` : TalkBack doit dire « bouton » et pas seulement lire le
            // libellé — sur une décision d'autorisation, la nature de l'élément compte autant que
            // son texte.
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = label,
            style = TetherDataStyle,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class Tone { Alert }

@Composable
private fun Notice(text: String, tone: Tone) {
    val color = when (tone) {
        Tone.Alert -> TetherAlert
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(color.copy(alpha = 0.12f))
            .padding(Spacing.md),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
