package sh.sk7.tether.ui.forms

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Hourglass
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Send
import com.composables.icons.lucide.TriangleAlert
import sh.sk7.tether.data.api.FormFieldDto
import sh.sk7.tether.data.api.FormInfoDto
import sh.sk7.tether.data.api.FormOptionDto
import sh.sk7.tether.ui.theme.Spacing
import sh.sk7.tether.ui.theme.LocalAccent
import sh.sk7.tether.ui.theme.TetherAlert
import sh.sk7.tether.ui.theme.TetherComposerBorder
import sh.sk7.tether.ui.theme.TetherComposerSurface
import sh.sk7.tether.ui.theme.TetherDataStyle
import sh.sk7.tether.ui.theme.TetherDimensions
import sh.sk7.tether.ui.theme.TetherTextMuted
import sh.sk7.tether.ui.theme.TetherTextPrimary
import sh.sk7.tether.ui.theme.TetherTextSecondary
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **Repondre a un formulaire qui bloque l'agent.**
 *
 * ### Pourquoi cet ecran est indispensable
 * Un formulaire bloque l'agent **exactement comme une permission** : la session reste immobile
 * tant que personne ne repond. Or l'app recevait deja `form.created` sur le flux et le stockait
 * dans son etat — **rien ne le consommait**. Un blocage totalement invisible, la pire des pannes :
 * l'utilisateur voit une session qui ne bouge plus et n'a aucun moyen de savoir pourquoi.
 *
 * ### Ce que l'ecran montre, et pourquoi
 *  - **Le titre du formulaire** vient du serveur, pas d'un libelle invente : c'est la question que
 *    l'agent pose, dans ses mots.
 *  - **Chaque champ dit ce qu'il attend** : `placeholder`, `description`, bornes, options avec leur
 *    libelle. Un formulaire d'agent peut demander une borne, un choix ou une confirmation.
 *  - **Un champ `external`** n'est pas une saisie : c'est une **ressource externe a ouvrir et a
 *    confirmer**. Mesure du 2026-09-26 : le serveur exige son acquittement (`400 External form
 *    field must be acknowledged` s'il manque), donc l'ecran porte une case explicite.
 *
 * ⚠️ **On ne cache jamais un champ sans le dire** : un champ `hidden` est retire de la saisie
 * (l'agent n'attend pas que l'utilisateur le remplisse), mais on affiche une ligne qui explique
 * qu'il existe et qu'il sera envoye — un champ invisible qui change le resultat serait un piege.
 */
@Composable
fun FormsScreen(
    modifier: Modifier = Modifier,
    viewModel: FormsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.loading && state.isEmpty -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current)
            }
            state.openForm != null -> FormDetail(
                form = state.openForm!!,
                state = state,
                viewModel = viewModel,
            )
            state.isEmpty -> EmptyForms(error = state.error, onRetry = viewModel::load)
            else -> FormList(state = state, onOpen = viewModel::open, onReload = viewModel::load)
        }
    }
}

/** La liste des formulaires pendants. */
@Composable
private fun FormList(
    state: FormsUiState,
    onOpen: (Int) -> Unit,
    onReload: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        state.error?.let { message ->
            item(key = "error") { Notice(message) }
        }
        item(key = "count") {
            Text(
                text = if (state.forms.size == 1) {
                    Res.of(R.string.formulaire_attend_reponse_95c199)
                } else {
                    "${state.forms.size} formulaires attendent une réponse"
                },
                style = MaterialTheme.typography.titleSmall,
                color = TetherTextPrimary,
            )
        }
        itemsIndexed(state.forms, key = { _, form -> form.id }) { index, form ->
            FormCard(form = form, onClick = { onOpen(index) })
        }
    }
}

/**
 * Une carte de formulaire.
 *
 * ⚠️ On dit **combien de champs** et **combien sont requis** : un formulaire de douze champs
 * dont deux obligatoires ne se remplit pas de la meme facon qu'un questionnaire complet, et
 * l'utilisateur doit le savoir avant d'ouvrir.
 */
@Composable
private fun FormCard(form: FormInfoDto, onClick: () -> Unit) {
    val required = form.fields.count { it.required && it.kind != FormFieldKind.External }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerMd))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerMd))
            .heightIn(min = TetherDimensions.touchTarget)
            .clickable(onClick = onClick)
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
                text = form.title.ifBlank { "Formulaire" },
                style = MaterialTheme.typography.titleSmall,
                color = TetherTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = buildString {
                append(if (form.fields.size == 1) Res.of(R.string.champ_6c6a0f) else "${form.fields.size} champs")
                if (required > 0) append(Res.of(R.string.required_obligatoire_368684) + if (required > 1) "s" else "")
            },
            style = TetherDataStyle,
            color = TetherTextSecondary,
        )
        // ⚠️ Une elicitation MCP (`sessionID:"global"`) ne vient d'aucune session : le dire evite
        // de croire qu'une conversation est bloquee alors que la question est globale.
        if (form.isGlobal) {
            Text(Res.of(R.string.question_globale_hors_46c623),
                style = MaterialTheme.typography.bodySmall,
                color = TetherTextMuted,
            )
        }
    }
}

/** Le formulaire ouvert : ses champs puis l'action d'envoi. */
@Composable
private fun FormDetail(
    form: FormInfoDto,
    state: FormsUiState,
    viewModel: FormsViewModel,
) {
    val errors = state.fieldErrors.associateBy { it.key }
    val shown = form.fields.filterNot { it.hidden }
    val hidden = form.fields.filter { it.hidden }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item(key = "title") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = form.title.ifBlank { "Formulaire" },
                    style = MaterialTheme.typography.titleMedium,
                    color = TetherTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(Res.of(R.string.cochez_recu_ressources_b72d25) +
                          " " + stringResource(R.string.reponse_serveur_exige_c2a2d1),
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextSecondary,
                )
            }
        }

        state.submitError?.let { message ->
            item(key = "submitError") { Notice(message) }
        }

        itemsIndexed(shown, key = { _, field -> field.key }) { _, field ->
            FormFieldInput(
                field = field,
                byKey = form.fields.associateBy { it.key },
                draft = state.draft,
                error = errors[field.key],
                viewModel = viewModel,
            )
        }

        if (hidden.isNotEmpty()) {
            item(key = "hidden") {
                Text(
                    text = hidden.joinToString(prefix = Res.of(R.string.champs_fournis_agent_96cb48), separator = ", ") { it.label() },
                    style = MaterialTheme.typography.bodySmall,
                    color = TetherTextMuted,
                )
            }
        }

        item(key = "submit") {
            SubmitRow(state = state, onSubmit = viewModel::submit)
        }
    }
}

/** Un champ de saisie, adapte a son type. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormFieldInput(
    field: FormFieldDto,
    byKey: Map<String, FormFieldDto>,
    draft: FormDraft,
    error: FieldError?,
    viewModel: FormsViewModel,
) {
    // ⚠️ Un champ dont la condition n'est pas remplie n'est **pas** affiche (il ne sera pas
    // envoye non plus). Le garder visible laisserait croire qu'il faut le remplir.
    if (!field.isActive(draft, byKey)) return

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        when (field.kind) {
            // ⚠️ Un `external` n'est pas une saisie : on l'affiche (l'URL a reconnaitre), mais il
            // porte son propre acquittement implicite — aucun `error` ne peut le viser, le serveur
            // l'accepte toujours.
            FormFieldKind.External -> ExternalField(field)
            FormFieldKind.Boolean -> BooleanField(field, draft, error, viewModel)
            FormFieldKind.Multiselect -> MultiselectField(field, draft, error, viewModel)
            FormFieldKind.String -> TextField(field, draft, error, viewModel, KeyboardType.Text)
            FormFieldKind.Number -> TextField(field, draft, error, viewModel, KeyboardType.Decimal)
            FormFieldKind.Integer -> TextField(field, draft, error, viewModel, KeyboardType.Number)
            null -> Notice("Type de champ non reconnu : ${field.type}.")
        }
        // ⚠️ `BooleanField` et `MultiselectField` affichent l'erreur **eux-memes**, a cote du
        // controle concerne : la remonter aussi ici la doublerait a l'ecran.
        if (field.kind != FormFieldKind.Boolean && field.kind != FormFieldKind.Multiselect) {
            error?.let { FieldErrorText(it.reason) }
        }
    }
}

/** Champ `string`, `number` ou `integer` : une saisie, avec ses bornes dites. */
@Composable
private fun TextField(
    field: FormFieldDto,
    draft: FormDraft,
    error: FieldError?,
    viewModel: FormsViewModel,
    keyboard: KeyboardType,
) {
    val value = (draft[field.key] as? FormDraftValue.Raw)?.text.orEmpty()
    OutlinedTextField(
        value = value,
        onValueChange = { viewModel.setText(field.key, it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(FieldLabel(field)) },
        placeholder = field.placeholder?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        supportingText = FieldSupportingText(field)?.let { { Text(it) } },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

/** Champ `boolean` : un interrupteur. */
@Composable
private fun BooleanField(
    field: FormFieldDto,
    draft: FormDraft,
    error: FieldError?,
    viewModel: FormsViewModel,
) {
    val on = (draft[field.key] as? FormDraftValue.Toggle)?.on ?: false
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TetherDimensions.touchTarget)
            .toggleable(
                value = on,
                role = Role.Switch,
                onValueChange = { viewModel.setToggle(field.key, it) },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(FieldLabel(field), style = MaterialTheme.typography.bodyMedium, color = TetherTextPrimary)
            field.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TetherTextSecondary)
            }
            error?.let { FieldErrorText(it.reason) }
        }
        Switch(checked = on, onCheckedChange = { viewModel.setToggle(field.key, it) })
    }
}

/** Champ `multiselect` : des cases, avec leur libelle et leur description. */
@Composable
private fun MultiselectField(
    field: FormFieldDto,
    draft: FormDraft,
    error: FieldError?,
    viewModel: FormsViewModel,
) {
    val selected = (draft[field.key] as? FormDraftValue.Choice)?.selected.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(FieldLabel(field), style = MaterialTheme.typography.bodyMedium, color = TetherTextPrimary)
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TetherTextSecondary)
        }
        field.options.forEach { option ->
            OptionRow(
                option = option,
                checked = option.value in selected,
                onToggle = { viewModel.setSelected(field.key, option.value, it) },
            )
        }
        FieldSupportingText(field)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TetherTextMuted)
        }
        error?.let { FieldErrorText(it.reason) }
    }
}

/** Une option cochable d'un `multiselect`. */
@Composable
private fun OptionRow(
    option: FormOptionDto,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TetherDimensions.touchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                .background(if (checked) LocalAccent.current.copy(alpha = 0.16f) else TetherComposerSurface)
                .border(1.dp, if (checked) LocalAccent.current else TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerSm)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Lucide.Check, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(12.dp))
            }
        }
        Column {
            Text(option.label.ifBlank { option.value }, style = MaterialTheme.typography.bodyMedium, color = TetherTextPrimary)
            option.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TetherTextSecondary)
            }
        }
    }
}

/**
 * Champ `external` : **une ressource a ouvrir, puis a confirmer**.
 *
 * ⚠️ Ce n'est pas une saisie : le serveur exige un acquittement booleen
 * (`400 External form field must be acknowledged` sans lui, mesure du 2026-09-26). L'app
 * l'ajoute toujours a `true` dans la reponse ([FormAnswerBuilder]) — c'est une confirmation du
 * fait que l'utilisateur a pris connaissance de l'URL, pas une valeur qu'il invente.
 */
@Composable
private fun ExternalField(field: FormFieldDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(TetherComposerSurface)
            .border(1.dp, TetherComposerBorder, RoundedCornerShape(TetherDimensions.cornerSm))
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(FieldLabel(field), style = MaterialTheme.typography.bodyMedium, color = TetherTextPrimary)
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = TetherTextSecondary)
        }
        field.url?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TetherDataStyle, color = LocalAccent.current)
        }
        Text(Res.of(R.string.sera_confirme_comme_27018c),
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextMuted,
        )
    }
}

/** Le bouton d'envoi, avec l'etat de blocage **explique**. */
@Composable
private fun SubmitRow(state: FormsUiState, onSubmit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.fieldErrors.isNotEmpty()) {
            Notice(
                Res.of(R.string.envoi_bloque_b58f67) + state.fieldErrors.joinToString(", ") { it.message },
            )
        }
            // La description est lue ici : `semantics` s'execute hors composition.
            val descEnvoyer_reponse = Res.of(R.string.envoyer_reponse_5783f5)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                .background(LocalAccent.current.copy(alpha = 0.12f))
                .heightIn(min = TetherDimensions.touchTarget)
                .clickable(enabled = !state.sending, onClick = onSubmit)
                .padding(horizontal = Spacing.lg)
                .semantics { role = Role.Button; contentDescription = descEnvoyer_reponse },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = LocalAccent.current,
                )
                Text(Res.of(R.string.envoi_a62561), style = TetherDataStyle, color = TetherTextSecondary)
            } else {
                Icon(Lucide.Send, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(14.dp))
                Text(Res.of(R.string.envoyer_e9ce24), style = TetherDataStyle, color = LocalAccent.current, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FieldErrorText(reason: String) {
    Text(reason, style = MaterialTheme.typography.bodySmall, color = TetherAlert, modifier = Modifier.padding(top = Spacing.xs))
}

/** Le libelle affichable d'un champ, avec la mention « requis ». */
private fun FieldLabel(field: FormFieldDto): String = buildString {
    append(field.label())
    if (field.required && field.kind != FormFieldKind.External) append(" *")
}

/**
 * Les contraintes d'un champ, **dites** plutot que subies.
 *
 * ⚠️ Afficher les bornes et le format evite l'aller-retour : le serveur refuse un `pattern` ou une
 * borne violee (`400 Form field does not match pattern`, mesure du 2026-09-26), et le dire avant
 * la frappe vaut mieux qu'apres l'envoi.
 */
private fun FieldSupportingText(field: FormFieldDto): String? {
    val parts = buildList {
        field.format?.takeIf { it.isNotBlank() }?.let { add(Res.of(R.string.format_cd1231)) }
        field.minLength?.let { add(Res.of(R.string.caracteres_minimum_3ae93a)) }
        field.maxLength?.let { add(Res.of(R.string.caracteres_maximum_5d98f2)) }
        field.minBound()?.let { add(Res.of(R.string.minimum_cdf7be)) }
        field.maxBound()?.let { add(Res.of(R.string.maximum_24a511)) }
        field.minItems?.let { add(Res.of(R.string.moins_choix_2911d8)) }
        field.maxItems?.let { add(Res.of(R.string.choix_52940f)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Le cas « aucun formulaire pendant ». */
@Composable
private fun EmptyForms(error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (error != null) Lucide.TriangleAlert else Lucide.Check,
            contentDescription = null,
            tint = if (error != null) TetherAlert else TetherTextSecondary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = if (error != null) Res.of(R.string.lecture_impossible_f1df6b) else Res.of(R.string.aucun_formulaire_attente_f42b48),
            style = MaterialTheme.typography.titleSmall,
            color = TetherTextPrimary,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Text(
            text = error
                ?: "L'agent n'attend aucune réponse pour le moment. " +
                Res.of(R.string.formulaire_apparaitra_ici_58c6e4),
            style = MaterialTheme.typography.bodySmall,
            color = TetherTextSecondary,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        if (error != null) {
            Text(Res.of(R.string.reessayer_895d41),
                style = TetherDataStyle,
                color = LocalAccent.current,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .clip(RoundedCornerShape(TetherDimensions.cornerSm))
                    .heightIn(min = TetherDimensions.touchTarget)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = Spacing.lg),
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TetherDimensions.cornerSm))
            .background(TetherAlert.copy(alpha = 0.12f))
            .padding(Spacing.md),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = TetherAlert)
    }
}
