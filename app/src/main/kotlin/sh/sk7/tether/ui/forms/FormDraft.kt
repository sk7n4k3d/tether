package sh.sk7.tether.ui.forms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import sh.sk7.tether.data.api.FormAnswerValue
import sh.sk7.tether.data.api.FormFieldDto
import sh.sk7.tether.data.api.FormWhenDto
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **Les types d'un `Form.Field`, nommes une fois.**
 *
 * ⚠️ Le discriminant est une **chaine** cote serveur (`type`). On la traduit ici pour que le
 * `when` d'un composant Compose ne compare jamais des litteraux disperses : un type ajoute cote
 * serveur doit se voir **ici**, pas dans cinq `when` differents.
 */
enum class FormFieldKind(val wire: String) {
    String("string"),
    Number("number"),
    Integer("integer"),
    Boolean("boolean"),
    Multiselect("multiselect"),
    External("external"),
    ;

    companion object {
        /** `null` pour un type inconnu : un champ non reconnu ne doit jamais faire lever. */
        fun from(wire: String): FormFieldKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Le type d'un champ, ou `null` s'il est inconnu du client (jamais une exception). */
val FormFieldDto.kind: FormFieldKind? get() = FormFieldKind.from(type)

/**
 * **Une valeur saisie**, telle que l'ecran la porte.
 *
 * ⚠️ On garde le **texte brut** pour les champs `number`/`integer` au lieu d'un `Double` : c'est
 * ce que l'utilisateur tape (« 3. », « -  », chaine vide) et le parser trop tot ferait disparaitre
 * ce qu'il est en train d'ecrire. La conversion se fait au moment de l'envoi, une seule fois.
 */
sealed interface FormDraftValue {
    /** `string`, `number`, `integer` : la saisie textuelle. */
    data class Raw(val text: String) : FormDraftValue

    /** `boolean` : etat d'un interrupteur (jamais « absent » — `false` est une reponse). */
    data class Toggle(val on: Boolean) : FormDraftValue

    /** `multiselect` : les valeurs cochees. */
    data class Choice(val selected: Set<String>) : FormDraftValue
}

/**
 * **L'etat de saisie d'un formulaire**, indexe par `key`.
 *
 * ⚠️ Il contient les **valeurs par defaut des le depart** (`FormDraft.of`), et pas seulement ce
 * que l'utilisateur touche. Mesure du 2026-09-26 : le serveur **n'applique pas** les defauts —
 * repondre `{}` a `{"type":"integer","default":7}` rend `204` avec `answer: {}`. C'est donc a
 * l'app de pre-remplir, sinon un champ requis avec defaut est declare manquant.
 */
data class FormDraft(val values: Map<String, FormDraftValue> = emptyMap()) {

    operator fun get(key: String): FormDraftValue? = values[key]

    fun with(key: String, value: FormDraftValue): FormDraft =
        copy(values = values + (key to value))

    /** Change la saisie texte d'un champ (string / number / integer). */
    fun text(key: String, value: String): FormDraft = with(key, FormDraftValue.Raw(value))

    /** Bascule un booleen. */
    fun toggle(key: String, on: Boolean): FormDraft = with(key, FormDraftValue.Toggle(on))

    /** Coche ou decoche une valeur d'un multiselect. */
    fun select(key: String, value: String, selected: Boolean): FormDraft {
        val current = (values[key] as? FormDraftValue.Choice)?.selected.orEmpty()
        val next = if (selected) current + value else current - value
        return with(key, FormDraftValue.Choice(next))
    }

    companion object {
        /**
         * Le brouillon initial d'un formulaire : **tous les champs**, defauts compris.
         *
         * ⚠️ On remplit aussi les champs dont la condition n'est pas remplie : leur defaut sert a
         * **evaluer** les conditions des autres champs. Mesure du 2026-09-26 : un champ a
         * `default:"a"` rend le `when {eq a}` vrai meme si l'utilisateur ne l'a pas touche.
         */
        fun of(fields: List<FormFieldDto>): FormDraft =
            FormDraft(fields.mapNotNull { field -> field.defaultDraftValue()?.let { field.key to it } }.toMap())
    }
}

/** La valeur par defaut d'un champ, projetee dans le brouillon. `null` s'il n'y en a pas. */
fun FormFieldDto.defaultDraftValue(): FormDraftValue? {
    val element = default ?: return null
    if (element is JsonNull) return null
    return when (kind) {
        FormFieldKind.String -> FormDraftValue.Raw((element as? JsonPrimitive)?.content ?: return null)
        FormFieldKind.Number, FormFieldKind.Integer -> {
            val primitive = element as? JsonPrimitive ?: return null
            FormDraftValue.Raw(primitive.content)
        }
        FormFieldKind.Boolean -> FormDraftValue.Toggle((element as? JsonPrimitive)?.booleanOrNull ?: return null)
        FormFieldKind.Multiselect -> {
            val array = element as? JsonArray ?: return null
            FormDraftValue.Choice(array.mapNotNull { (it as? JsonPrimitive)?.content }.toSet())
        }
        FormFieldKind.External, null -> null
    }
}

/**
 * **La valeur effective d'un champ pour evaluer une condition** : la saisie, sinon le defaut.
 *
 * ⚠️ On ne « devine » pas un type : la comparaison se fait selon le type **declare** du champ
 * reference, jamais selon la forme de la valeur. C'est ce qui distingue `3` (entier) de `"3"`
 * (chaine) — la confusion exacte qui vaut un `400 Expected integer` (mesure 2026-09-26).
 */
fun FormFieldDto.effectiveDraftValue(draft: FormDraft): FormDraftValue? =
    draft[key] ?: defaultDraftValue()

/**
 * **Le champ est-il actif ?** — c'est-a-dire : toutes ses conditions `when` sont-elles remplies.
 *
 * ⚠️ Un champ sans `when` est **toujours actif**, meme `hidden`. Mesure du 2026-09-26 :
 * `hidden:true, required:true` et omis rend `400 Missing required form field` — le serveur ne
 * traite donc **pas** `hidden` comme « inactif ». Seul `when` retire un champ de la reponse.
 *
 * @param byKey les champs du formulaire, indexes par cle : la comparaison a besoin du **type
 *   declare** du champ reference, pas de la forme de la valeur saisie.
 */
fun FormFieldDto.isActive(draft: FormDraft, byKey: Map<String, FormFieldDto>): Boolean =
    `when`.all { condition -> condition.evaluate(draft, byKey) }

/**
 * Evalue une condition `when` contre le brouillon courant.
 *
 * ⚠️ Semantique mesuree le 2026-09-26 :
 *  - `eq` : vrai si la valeur effective **egale** la valeur de condition. Pour un `multiselect`,
 *    vrai si la valeur est **dans** la selection ;
 *  - `neq` : vrai si la valeur effective **existe** et differe. Pour un `multiselect`, vrai si la
 *    valeur n'est pas dans la selection ;
 *  - un champ **absent** (ni saisie ni defaut) rend `eq` et `neq` **faux tous les deux** (mesure :
 *    `m` omis, `d when m neq b`, `d` envoye -> `400 Form field is not active`).
 */
fun FormWhenDto.evaluate(draft: FormDraft, byKey: Map<String, FormFieldDto>): Boolean {
    val referenced = byKey[key] ?: return false
    val effective = referenced.effectiveDraftValue(draft) ?: return false

    return when (referenced.kind) {
        FormFieldKind.Multiselect -> {
            val selected = (effective as? FormDraftValue.Choice)?.selected ?: return false
            val target = (value as? JsonPrimitive)?.content ?: return false
            when (op) {
                "eq" -> target in selected
                "neq" -> target !in selected
                else -> false
            }
        }
        FormFieldKind.Boolean -> {
            val on = (effective as? FormDraftValue.Toggle)?.on ?: return false
            val target = (value as? JsonPrimitive)?.booleanOrNull ?: return false
            when (op) {
                "eq" -> on == target
                "neq" -> on != target
                else -> false
            }
        }
        FormFieldKind.Number, FormFieldKind.Integer -> compareNumbers(effective, value)
        FormFieldKind.String -> {
            val text = (effective as? FormDraftValue.Raw)?.text ?: return false
            val target = (value as? JsonPrimitive)?.content ?: return false
            when (op) {
                "eq" -> text == target
                "neq" -> text != target
                else -> false
            }
        }
        // Un `external` n'a pas de valeur de saisie : une condition qui le vise n'est jamais vraie.
        FormFieldKind.External, null -> false
    }
}

private fun FormWhenDto.compareNumbers(effective: FormDraftValue?, conditionValue: JsonElement?): Boolean {
    val raw = (effective as? FormDraftValue.Raw)?.text?.trim().orEmpty()
    val actual = raw.toDoubleOrNull() ?: return false
    val primitive = conditionValue as? JsonPrimitive ?: return false
    val target = primitive.doubleOrNull ?: return false
    return when (op) {
        "eq" -> actual == target
        "neq" -> actual != target
        else -> false
    }
}

/**
 * **Construit la reponse d'un formulaire, ou explique pourquoi elle ne peut pas partir.**
 *
 * ### Regles, toutes mesurees sur le serveur 2.0.x le 2026-09-26
 *
 * 1. **`external` est toujours acquitte `true`**, meme `hidden`, meme `when` non rempli. Mesure :
 *    l'omettre rend `400 External form field must be acknowledged`, y compris quand sa condition
 *    est fausse — le serveur verifie l'acquittement **avant** la porte `when`. Envoyer `true`
 *    rend `204`. Un `external` n'est pas une reponse de l'utilisateur : c'est une confirmation.
 * 2. **Un champ dont la condition n'est pas remplie n'est jamais envoye** : l'envoyer rend
 *    `400 Form field is not active`. On le retire, defaut compris.
 * 3. **Un champ requis doit porter une valeur** : le serveur n'applique pas les defauts, donc le
 *    brouillon pre-remplit. Un requis vide (chaine vide, aucun nombre, selection vide) est
 *    refuse par le serveur (`400 Missing required form field`).
 * 4. **Le type est celui de la classe, pas d'une chaine** : `FormAnswerValue.Integer` part en
 *    entier, `Text` en chaine. C'est ce qui evite `400 Expected number`.
 * 5. **Un champ optionnel sans valeur n'est pas envoye** : le serveur l'accepte absent, et
 *    envoyer une chaine vide pour un `string` requis serait de toute facon refuse.
 *
 * ⚠️ On applique **aussi** les contraintes locales (`pattern`, bornes, options) pour refuser
 * **avant** l'aller-retour et dire a l'utilisateur quel champ cloche. Mais le serveur reste le
 * juge final : un `Invalid` local est un confort, pas la securite.
 */
object FormAnswerBuilder {

    fun build(fields: List<FormFieldDto>, draft: FormDraft): FormSubmission {
        val answer = LinkedHashMap<String, FormAnswerValue>()
        val errors = mutableListOf<FieldError>()
        // ⚠️ Les conditions `when` ont besoin du **type declare** du champ vise, pas seulement du
        // brouillon : on indexe donc les champs une fois, pour toute la construction.
        val byKey = fields.associateBy { it.key }

        fields.forEach { field ->
            val kind = field.kind
            // 1. Un `external` est un CONSENTEMENT recueilli, pas une valeur fabriquee.
            // ⚠️ Avant, l'app envoyait `Flag(true)` sans jamais demander : elle fabriquait
            // une confirmation que l'utilisateur n'avait pas donnee — precisement ce que
            // la regle du projet interdit (« on ne fabrique jamais de valeur »). La case
            // est cocher dans l'ecran ([ExternalField]) ; sans elle, le champ porte son
            // erreur et l'envoi est bloque.
            if (kind == FormFieldKind.External) {
                val acquis = (draft.values[field.key] as? FormDraftValue.Toggle)?.on == true
                if (acquis) {
                    answer[field.key] = FormAnswerValue.Flag(true)
                } else if (field.required) {
                    errors.add(FieldError(field.key, field.label(), Res.of(R.string.ressource_externe_a_confirmer_c7d1f9)))
                }
                return@forEach
            }
            // 2. Condition non remplie : le champ ne fait pas partie de la reponse.
            if (!field.isActive(draft, byKey)) return@forEach

            val value = field.effectiveDraftValue(draft)
            when (kind) {
                FormFieldKind.String -> buildString(field, value, answer, errors)
                FormFieldKind.Number -> buildNumber(field, value, answer, errors)
                FormFieldKind.Integer -> buildInteger(field, value, answer, errors)
                FormFieldKind.Boolean -> answer[field.key] =
                    FormAnswerValue.Flag((value as? FormDraftValue.Toggle)?.on ?: false)
                FormFieldKind.Multiselect -> buildMultiselect(field, value, answer, errors)
                FormFieldKind.External, null -> Unit
            }
        }

        return if (errors.isEmpty()) FormSubmission.Ready(answer) else FormSubmission.Invalid(errors)
    }

    private fun buildString(
        field: FormFieldDto,
        value: FormDraftValue?,
        answer: MutableMap<String, FormAnswerValue>,
        errors: MutableList<FieldError>,
    ) {
        val text = (value as? FormDraftValue.Raw)?.text.orEmpty()
        if (text.isEmpty()) {
            // Un requis vide est un manque ; un optionnel vide n'est simplement pas une reponse.
            if (field.required) errors += FieldError(field.key, field.label(), Res.of(R.string.champ_requis_c895d4))
            return
        }
        field.validateTextLength(text, errors)
        field.pattern?.takeIf { it.isNotBlank() }?.let { pattern ->
            // ⚠️ **Correspondance partielle**, pas `matches()`. Mesure du 2026-09-26 : le motif
            // `[0-9]+` accepte `abc123` mais refuse `abc` — le serveur cherche donc une
            // correspondance **quelque part** dans la chaine, comme `RegExp.test` en JS, et non un
            // alignement complet. Utiliser `matches()` refuserait `abc123` que le serveur accepte :
            // une erreur locale qui bloque une reponse valide.
            if (!runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(true)) {
                errors += FieldError(field.key, field.label(), Res.of(R.string.correspond_format_attendu_4fe135))
            }
        }
        if (field.options.isNotEmpty() && !field.custom && text !in field.options.map { it.value }) {
            errors += FieldError(field.key, field.label(), Res.of(R.string.valeur_hors_choix_272474))
        }
        answer[field.key] = FormAnswerValue.Text(text)
    }

    private fun buildNumber(
        field: FormFieldDto,
        value: FormDraftValue?,
        answer: MutableMap<String, FormAnswerValue>,
        errors: MutableList<FieldError>,
    ) {
        val raw = (value as? FormDraftValue.Raw)?.text?.trim().orEmpty()
        if (raw.isEmpty()) {
            if (field.required) errors += FieldError(field.key, field.label(), Res.of(R.string.nombre_requis_a59778))
            return
        }
        val number = raw.toDoubleOrNull()
        if (number == null) {
            errors += FieldError(field.key, field.label(), Res.of(R.string.nombre_invalide_27b477))
            return
        }
        field.validateBounds(number, errors)
        answer[field.key] = FormAnswerValue.Decimal(number)
    }

    private fun buildInteger(
        field: FormFieldDto,
        value: FormDraftValue?,
        answer: MutableMap<String, FormAnswerValue>,
        errors: MutableList<FieldError>,
    ) {
        val raw = (value as? FormDraftValue.Raw)?.text?.trim().orEmpty()
        if (raw.isEmpty()) {
            if (field.required) errors += FieldError(field.key, field.label(), Res.of(R.string.nombre_entier_requis_f45373))
            return
        }
        // ⚠️ On accepte « 3 » et « 3.0 » (le serveur accepte 3.0, mesure), mais pas « 3.5 ».
        val asDouble = raw.toDoubleOrNull()
        val asLong = raw.toLongOrNull() ?: asDouble?.takeIf { it == it.toLong().toDouble() }?.toLong()
        if (asLong == null) {
            errors += FieldError(field.key, field.label(), Res.of(R.string.nombre_entier_attendu_ba5230))
            return
        }
        field.validateBounds(asLong.toDouble(), errors)
        answer[field.key] = FormAnswerValue.Integer(asLong)
    }

    private fun buildMultiselect(
        field: FormFieldDto,
        value: FormDraftValue?,
        answer: MutableMap<String, FormAnswerValue>,
        errors: MutableList<FieldError>,
    ) {
        val selected = (value as? FormDraftValue.Choice)?.selected.orEmpty()
        if (selected.isEmpty()) {
            if (field.required) errors += FieldError(field.key, field.label(), Res.of(R.string.selection_requise_d87172))
            return
        }
        val values = field.options.map { it.value }
        if (values.isNotEmpty() && !field.custom && selected.any { it !in values }) {
            errors += FieldError(field.key, field.label(), Res.of(R.string.valeur_hors_choix_272474))
        }
        field.minItems?.let {
            if (selected.size < it) errors += FieldError(field.key, field.label(), Res.of(R.string.moins_choix_1932fe))
        }
        field.maxItems?.let {
            if (selected.size > it) errors += FieldError(field.key, field.label(), Res.of(R.string.choix_76e7e3))
        }
        answer[field.key] = FormAnswerValue.Items(selected.toList())
    }

    private fun FormFieldDto.validateTextLength(text: String, errors: MutableList<FieldError>) {
        minLength?.let {
            if (text.length < it) errors += FieldError(key, label(), Res.of(R.string.trop_court_caracteres_2aed12))
        }
        maxLength?.let {
            if (text.length > it) errors += FieldError(key, label(), Res.of(R.string.trop_long_caracteres_b812bc))
        }
    }

    private fun FormFieldDto.validateBounds(number: Double, errors: MutableList<FieldError>) {
        minBound()?.let {
            if (number < it) errors += FieldError(key, label(), Res.of(R.string.valeur_trop_petite_58bff5))
        }
        maxBound()?.let {
            if (number > it) errors += FieldError(key, label(), Res.of(R.string.valeur_trop_grande_2b14ef))
        }
    }
}

/** Le titre affichable d'un champ, avec repli sur sa cle. */
fun FormFieldDto.label(): String = title?.takeIf { it.isNotBlank() } ?: key

/**
 * Une borne `minimum`/`maximum`.
 *
 * ⚠️ Le schema autorise explicitement les **chaines** `"Infinity"`, `"-Infinity"` et `"NaN"` : une
 * borne infinie n'est pas une contrainte, c'est une absence de borne dans cette direction. On
 * renvoie donc `null` pour ces cas plutot que de faire echouer la conversion ou de comparer a
 * l'infini.
 */
fun FormFieldDto.minBound(): Double? = numericBound(minimum)

fun FormFieldDto.maxBound(): Double? = numericBound(maximum)

private fun numericBound(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    val value = primitive.doubleOrNull ?: return null
    // "Infinity"/"-Infinity"/"NaN" (chaines autorisees par le schema) : pas une borne exploitable.
    return value.takeIf { it.isFinite() }
}

/** Une erreur bloquante, rattachee a un champ. */
data class FieldError(
    val key: String,
    val label: String,
    val reason: String,
) {
    /** Message pret a afficher. */
    val message: String get() = "$label : $reason"
}

/** Le resultat de la construction d'une reponse. */
sealed interface FormSubmission {
    /** La reponse est complete, prete a etre postee. */
    data class Ready(val answer: Map<String, FormAnswerValue>) : FormSubmission

    /** L'envoi doit etre **bloque** : des champs ne sont pas valides. */
    data class Invalid(val errors: List<FieldError>) : FormSubmission
}

/** Convertit la reponse construite en objet JSON, forme exacte attendue par le serveur. */
fun FormSubmission.Ready.toJsonObject(): JsonObject = answer.toAnswerJson()

/**
 * Traduit une reponse typee en `answer` JSON.
 *
 * ⚠️ C'est **le seul point de conversion** : le type de valeur decide de la forme (`Integer` ->
 * `3`, `Text` -> `"3"`), jamais une chaine devinee. Mesure du 2026-09-26 : envoyer `"3"` la ou un
 * nombre est attendu rend `400`, et c'est exactement ce que cette conversion rend impossible.
 */
fun Map<String, FormAnswerValue>.toAnswerJson(): JsonObject =
    JsonObject(mapValues { (_, value) -> value.toJson() })
