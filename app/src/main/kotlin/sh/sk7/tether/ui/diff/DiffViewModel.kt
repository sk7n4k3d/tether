package sh.sk7.tether.ui.diff

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
import kotlinx.coroutines.launch
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **Quelle source de diff on regarde.**
 *
 * ⚠️ Deux sources reelles, et elles ne repondent pas a la meme question :
 *  - [Session] : ce que **cette conversation** a change. C'est ce qu'on veut en revenant sur une
 *    session (« qu'est-ce que l'agent a touche ? ») ;
 *  - [Working] · [Branch] · [Committed] : l'etat du **depot**, independamment des sessions.
 *
 * Les confondre serait une erreur de sens : un depot peut avoir des modifications qu'aucune
 * session n'a produites (travail a la main, autre outil).
 */
enum class DiffScope(val label: String, val mode: String?) {
    Session("Session", null),
    Working(Res.of(R.string.non_commite_87a999), "working"),
    Branch("Branche", "branch"),
    Committed("Commits", "committed"),
}

sealed interface DiffUiState {
    data object Loading : DiffUiState

    /**
     * Rien a montrer — et on distingue **pourquoi**.
     *
     * ⚠️ « Aucun changement » et « pas un depot git » sont deux faits differents. Les confondre
     * ferait croire a une session inoffensive alors que le repertoire n'est simplement pas
     * versionne, donc que **rien ne peut etre annule**.
     */
    data class Empty(val reason: String) : DiffUiState
    data class Loaded(val files: List<UnifiedDiff.FileDiff>) : DiffUiState
    data class Error(val message: String) : DiffUiState
}

@HiltViewModel
class DiffViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * ⚠️ La session est **optionnelle** : l'ecran est aussi accessible pour le depot seul. Le
     * `savedStateHandle` la porte quand on vient du chat.
     */
    private val sessionID: String? = savedStateHandle.get<String>("sessionID")

    /**
     * **Le repertoire de reference pour les portees « depot ».**
     *
     * ⚠️ C'est celui de la **session**, quand il est connu, et seulement a defaut celui des
     * reglages. Une session travaille dans son propre repertoire : mesurer les modifications du
     * depot configure pour l'app donnerait le diff d'un **autre** projet. Mesure du 2026-09-25 :
     * les reglages pointent `/home/user` (pas un depot), alors qu'une session peut travailler
     * dans `Projects/tether` (depot git, avec des modifications reelles).
     *
     * ⚠️ Resolu **une fois** au chargement et garde : le redemander a chaque portee ferait
     * dependre l'affichage d'un appel reseau supplementaire, pour une valeur qui ne change pas.
     */
    private var workDirectory: String? = null

    private val _state = MutableStateFlow<DiffUiState>(DiffUiState.Loading)
    val state: StateFlow<DiffUiState> = _state.asStateFlow()

    /** La portee courante est **derivee** : sans session connue, on ne peut pas la proposer. */
    val availableScopes: List<DiffScope> =
        if (sessionID == null) DiffScope.entries.filter { it != DiffScope.Session }
        else DiffScope.entries.toList()

    private val _scope = MutableStateFlow(availableScopes.first())

    /** La source regardee. Nommee `diffScope` et non `scope` : `scope` est deja le CoroutineScope. */
    val diffScope: StateFlow<DiffScope> = _scope.asStateFlow()

    init {
        load()
    }

    override fun onCleared() {
        scope.cancel()
    }

    fun setScope(next: DiffScope) {
        if (_scope.value == next) return
        _scope.value = next
        load()
    }

    fun load() {
        _state.value = DiffUiState.Loading
        scope.launch {
            try {
                val settings = store.current()
                val current = _scope.value
                if (workDirectory == null) {
                    workDirectory = resolveWorkDirectory(settings)
                }
                val raw = when (current) {
                    DiffScope.Session -> {
                        val id = sessionID ?: return@launch run {
                            _state.value = DiffUiState.Empty(Res.of(R.string.aucune_session_associee_361daa))
                        }
                        gateway.sessionDiff(settings, id)
                    }
                    else -> gateway.vcsDiffIn(settings, workDirectory!!, current.mode!!)
                }

                if (raw.isEmpty()) {
                    // ⚠️ **On verifie AVANT de dire « aucun changement ».** Mesure du
                    // 2026-09-25 : `/home/user` n'est pas un depot (`{"branch":{}}`, sans
                    // `provider`), et la route rend une liste vide. Dire « aucune modification »
                    // y serait faux — et dangereux : dans un repertoire non versionne, **rien ne
                    // peut etre annule**, ce qui est l'inverse d'un constat rassurant.
                    _state.value = DiffUiState.Empty(current.explainEmpty(settings, workDirectory!!))
                    return@launch
                }

                // ⚠️ Le tri se fait par **nombre de lignes touchees**, decroissant : les fichiers
                // les plus travailles d'abord. Un ordre alphabetique noierait le fichier principal
                // sous dix fichiers de configuration touches d'une ligne.
                val parsed = raw
                    .map { UnifiedDiff.parse(it) }
                    .sortedByDescending { it.additions + it.deletions }
                _state.value = DiffUiState.Loaded(parsed)
            } catch (e: Exception) {
                _state.value = DiffUiState.Error(ConnectionErrors.describe(e))
            }
        }
    }

    /**
     * Ce que signifie « rien » selon la source.
     *
     * ⚠️ On explique au lieu de dire « aucun changement » partout : la meme absence n'a pas le
     * meme sens. Et pour les portees qui portent sur le **depot**, on demande d'abord au serveur
     * si ce repertoire est versionne — sinon « aucune modification » serait un mensonge commode.
     */
    private suspend fun DiffScope.explainEmpty(
        settings: sh.sk7.tether.data.settings.ConnectionSettings,
        directory: String,
    ): String = when (this) {
        DiffScope.Session -> Res.of(R.string.session_modifie_aucun_5075a0)
        DiffScope.Working -> {
            if (isVersioned(settings, directory)) {
                Res.of(R.string.aucune_modification_non_898586)
            } else {
                // ⚠️ Formulation qui **n'annonce pas** un depot sain : sans gestion de version,
                // aucune modification ne peut etre annulee. Le dire est le seul service qu'on
                // puisse rendre ici.
                "Ce répertoire n'est pas versionné : les modifications faites par l'agent ne " +
                    Res.of(R.string.peuvent_etre_suivies_c116db)
            }
        }
        DiffScope.Branch -> Res.of(R.string.aucun_ecart_base_b1237d)
        DiffScope.Committed -> Res.of(R.string.aucun_commit_afficher_c24626)
    }

    /**
     * Le repertoire configure est-il versionne ?
     *
     * ⚠️ Un echec de cette verification est traite comme **« on ne sait pas »**, et on retombe sur
     * la formulation neutre : affirmer « pas versionne » sur une erreur reseau serait aussi faux
     * que l'inverse.
     */
    private suspend fun isVersioned(
        settings: sh.sk7.tether.data.settings.ConnectionSettings,
        directory: String,
    ): Boolean = runCatching {
        gateway.vcsInfo(settings, directory)?.isVersioned
    }.getOrNull() ?: true

    /**
     * Le repertoire sur lequel mesurer le depot.
     *
     * ⚠️ On demande la **session** d'abord. Si elle est inconnue ou si l'appel echoue, on retombe
     * sur le repertoire des reglages — un echec ici ne doit pas empecher l'ecran de fonctionner,
     * il doit seulement le rendre moins precis.
     */
    private suspend fun resolveWorkDirectory(
        settings: sh.sk7.tether.data.settings.ConnectionSettings,
    ): String {
        val id = sessionID ?: return settings.directory
        return runCatching { gateway.session(settings, id).location?.directory }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: settings.directory
    }
}
