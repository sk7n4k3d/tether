package sh.sk7.tether.ui.forms

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.FormInfoDto
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors

/**
 * **L'etat de l'ecran des formulaires.**
 *
 * ⚠️ On porte **le brouillon** ([FormDraft]) en plus du formulaire : c'est ce qui permet aux
 * conditions `when` d'etre evaluees a chaque frappe sans relire le serveur, et de garder les
 * valeurs par defaut pre-remplies (mesure : le serveur ne les applique pas).
 */
data class FormsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val forms: List<FormInfoDto> = emptyList(),
    /** Index du formulaire affiche dans [forms], ou `null` si l'on voit la liste. */
    val openIndex: Int? = null,
    /** Le brouillon du formulaire ouvert. */
    val draft: FormDraft = FormDraft(),
    /** Erreurs de validation locales **avant** envoi (jamais une valeur inventee). */
    val fieldErrors: List<FieldError> = emptyList(),
    /** Erreur renvoyee par le serveur au dernier envoi (400/404/409). */
    val submitError: String? = null,
    /** Un envoi est en vol : on desactive le bouton. */
    val sending: Boolean = false,
) {
    /** Le formulaire ouvert, ou `null`. */
    val openForm: FormInfoDto? get() = openIndex?.let { forms.getOrNull(it) }

    /** Aucun formulaire pendant : l'ecran doit le **dire**, pas rester blanc. */
    val isEmpty: Boolean get() = forms.isEmpty()
}

/**
 * **Repondre a un formulaire qui bloque l'agent.**
 *
 * ### Pourquoi cet ecran est le pendant de [sh.sk7.tether.ui.permissions.PermissionsViewModel]
 * Un formulaire bloque l'agent **au meme titre qu'une permission** : tant que personne ne repond,
 * la session reste immobile. Or l'app stockait `form.created` dans son etat sans que rien ne le
 * consomme — un blocage invisible, exactement le defaut que l'ecran d'approbations corrige pour
 * les permissions.
 *
 * ### Ce qui est mesure, et structure l'ecran
 *  - **le serveur n'applique pas les defauts** (repondre `{}` a un champ `default:7` rend `204`
 *    avec `answer:{}`) : le brouillon pre-remplit donc les defauts ;
 *  - **`external` est toujours acquitte `true`**, meme `hidden` (omettre rend
 *    `400 External form field must be acknowledged`) : [FormAnswerBuilder] l'ajoute toujours ;
 *  - **un champ `when` inactif ne doit pas etre envoye** (`400 Form field is not active`) ;
 *  - **`sessionID` peut valoir `"global"`** (elicitation MCP) : aucune session n'existe derriere,
 *    on ne l'utilise donc **que** pour composer l'URL de reponse, jamais pour lire un historique.
 *
 * ⚠️ **On n'envoie jamais une valeur inventee pour « que ca passe ».** Une reponse fausse
 * debloque l'agent sur une intention qui n'est pas celle de l'utilisateur, ce qui est pire qu'un
 * blocage visible. Toute invalidation est **dite** et bloque le bouton.
 */
@HiltViewModel
class FormsViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(FormsUiState())
    val state: StateFlow<FormsUiState> = _state.asStateFlow()

    init {
        load()
    }

    override fun onCleared() {
        scope.cancel()
    }

    /**
     * **Les formulaires pendants du repertoire** (`GET /api/form`, enveloppe `{location, data}`).
     *
     * ⚠️ C'est la route de resynchronisation : le flux SSE est « volatile by contract », un
     * `form.created` emis pendant une coupure est invisible pour toujours. Sans cette lecture, un
     * formulaire arrive pendant une coupure ne serait **jamais** rattrape.
     */
    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val settings = store.current()
                val forms = gateway.pendingForms(settings)
                _state.update { it.copy(loading = false, error = null, forms = forms) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = ConnectionErrors.describe(e)) }
            }
        }
    }

    /**
     * Ouvre un formulaire : le brouillon part de ses **valeurs par defaut**.
     *
     * ⚠️ Les defauts sont necessaires **et** pour afficher, **et** pour evaluer les conditions :
     * un champ a `default:"a"` rend le `when {eq a}` vrai meme si l'utilisateur n'y touche pas
     * (mesure du 2026-09-26).
     */
    fun open(index: Int) {
        val form = _state.value.forms.getOrNull(index) ?: return
        _state.update {
            it.copy(
                openIndex = index,
                draft = FormDraft.of(form.fields),
                fieldErrors = emptyList(),
                submitError = null,
            )
        }
    }

    /** Revient a la liste des formulaires. */
    fun close() {
        _state.update { it.copy(openIndex = null, fieldErrors = emptyList(), submitError = null) }
    }

    /** Met a jour la saisie d'un champ (`string`, `number`, `integer`). */
    fun setText(key: String, text: String) {
        _state.update { it.copy(draft = it.draft.text(key, text), fieldErrors = emptyList(), submitError = null) }
    }

    /** Bascule un booleen. */
    fun setToggle(key: String, on: Boolean) {
        _state.update { it.copy(draft = it.draft.toggle(key, on), fieldErrors = emptyList(), submitError = null) }
    }

    /** Coche ou decoche un choix de `multiselect`. */
    fun setSelected(key: String, value: String, selected: Boolean) {
        _state.update {
            it.copy(draft = it.draft.select(key, value, selected), fieldErrors = emptyList(), submitError = null)
        }
    }

    /**
     * **Construit puis envoie la reponse.**
     *
     * ⚠️ Deux barrières, dans cet ordre :
     *  1. [FormAnswerBuilder] refuse localement (champ requis vide, borne, option) et on **bloque**
     *     l'envoi en affichant les raisons. C'est un confort : le serveur reste le juge ;
     *  2. le serveur peut encore refuser (`400`), et son message est **affiche tel quel** — pas
     *     reformule, parce qu'il nomme precisement le champ fautif.
     *
     * ⚠️ On ne retire **pas** le formulaire de la liste de facon optimiste avant l'envoi : a la
     * difference d'une permission, une reponse invalide est frequente et le formulaire doit
     * rester visible pour etre corrige. On **relit** la liste apres un succes, ce qui est la
     * verite (un formulaire repondu par ailleurs ne doit pas rester affiche).
     */
    fun submit() {
        val state = _state.value
        val form = state.openForm ?: return
        if (state.sending) return

        when (val submission = FormAnswerBuilder.build(form.fields, state.draft)) {
            is FormSubmission.Invalid -> {
                _state.update { it.copy(fieldErrors = submission.errors, submitError = null) }
            }
            is FormSubmission.Ready -> {
                _state.update { it.copy(sending = true, fieldErrors = emptyList(), submitError = null) }
                scope.launch {
                    try {
                        val settings = store.current()
                        gateway.replyForm(settings, form.sessionID, form.id, submission.answer)
                        // ⚠️ Le serveur est la verite : on relit la liste apres un succes.
                        val fresh = runCatching { gateway.pendingForms(settings) }.getOrDefault(_state.value.forms)
                        _state.update {
                            it.copy(
                                sending = false,
                                openIndex = null,
                                forms = fresh,
                                fieldErrors = emptyList(),
                                submitError = null,
                            )
                        }
                    } catch (e: Exception) {
                        _state.update {
                            it.copy(sending = false, submitError = ConnectionErrors.describe(e))
                        }
                    }
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(submitError = null, fieldErrors = emptyList()) }
    }
}
