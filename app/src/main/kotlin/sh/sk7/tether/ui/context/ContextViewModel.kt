package sh.sk7.tether.ui.context

import androidx.lifecycle.SavedStateHandle
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
import sh.sk7.tether.data.api.ContextMessageDto
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.di.IoDispatcher
import sh.sk7.tether.ui.settings.ConnectionErrors

/**
 * **Une categorie de messages dans la fenetre**, avec son poids.
 *
 * ⚠️ C'est le regroupement par **type** qui repond a la question « qu'est-ce qui mange ma
 * fenetre ». Mesure sur une session reelle de 96 entrees : `assistant` = 68 % du cout,
 * `compaction` = 32 % pour **un seul** message. Une liste a plat de 96 lignes noierait ce fait ;
 * deux categories le montrent immediatement.
 *
 * ⚠️ `cost` reste `null` quand le serveur n'en donne pas — et il n'en donne **que** pour
 * `assistant` et `compaction`. Afficher « 0 $ » pour un message `user` ferait croire a une mesure,
 * alors que la question ne s'applique pas.
 */
data class ContextGroup(
    val type: String,
    val count: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cost: Double,
    /** Part du cout total observe, entre 0 et 1. */
    val costShare: Float,
) {
    /** Libelle lisible. Le type serveur reste la cle ; ceci n'est qu'un affichage. */
    val label: String
        get() = when (type) {
            "assistant" -> "Réponses de l'agent"
            "user" -> "Tes messages"
            "compaction" -> "Résumés"
            "synthetic" -> "Messages synthétiques"
            "system" -> "Messages système"
            "idle" -> "Marqueurs de fin de tour"
            "shell" -> "Commandes shell"
            "skill" -> "Compétences chargées"
            else -> type
        }
}

/**
 * Le message le plus lourd de la fenetre, quel que soit son type.
 *
 * ⚠️ Mesure : sur cette session, **un seul** message de compaction coûte 0,0612 $ sur 0,1931 $
 * — un tiers de la facture en une ligne. Sans le surligner, il faut additionner mentalement
 * 96 entrées pour s'en apercevoir.
 */
data class HeaviestEntry(
    val id: String,
    val type: String,
    val cost: Double,
    val summarySize: Int,
)

sealed interface ContextUiState {
    data object Loading : ContextUiState

    /**
     * Rien dans la fenetre — et ce n'est pas une erreur.
     *
     * ⚠️ Distinction corrigee apres l'avoir vue a l'ecran : une session neuve n'a rien envoye, ce
     * qui est normal. Le classer en `Error` affichait « Contexte indisponible » en orange pour une
     * session qui va parfaitement bien.
     */
    data object Empty : ContextUiState

    data class Loaded(
        val entryCount: Int,
        val groups: List<ContextGroup>,
        val heaviest: HeaviestEntry?,
        val totalInput: Long,
        val totalOutput: Long,
        val totalCost: Double,
    ) : ContextUiState

    data class Error(val message: String) : ContextUiState
}

/**
 * **Le contexte d'une session : ou il part, et combien il coute.**
 *
 * ### La distinction qui fait tout l'interet
 * La route liste ce qui sera **envoye au prochain tour**, pas l'historique. Mesure : sur une
 * session de 445 messages, la fenetre en contient 96 — dont **un resume de compaction qui tient
 * lieu de tout ce qui a precede**. C'est le fait le plus utile de tout le client : il explique a
 * la fois les « oublis » de l'agent et la facture.
 *
 * ⚠️ On regroupe par **type**, on ne liste pas les 96 entrees. La question est « qu'est-ce qui
 * mange ma fenetre », pas « quel est le 47e message ». Une liste a plat repond a l'autre question.
 */
@HiltViewModel
class ContextViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val sessionID: String = checkNotNull(savedStateHandle.get<String>("sessionID")) {
        "L'ecran Contexte exige un sessionID"
    }

    private val _state = MutableStateFlow<ContextUiState>(ContextUiState.Loading)
    val state: StateFlow<ContextUiState> = _state.asStateFlow()

    init {
        load()
    }

    override fun onCleared() {
        scope.cancel()
    }

    fun load() {
        _state.value = ContextUiState.Loading
        scope.launch {
            try {
                val settings = store.current()
                val raw = gateway.sessionContext(settings, sessionID)

                if (raw.isEmpty()) {
                    _state.value = ContextUiState.Empty
                    return@launch
                }

                val totalInput = raw.sumOf { it.tokens?.input ?: 0L }
                val totalOutput = raw.sumOf { it.tokens?.output ?: 0L }
                val totalCost = raw.sumOf { it.cost ?: 0.0 }

                val groups = raw
                    .groupBy { it.type.ifBlank { "inconnu" } }
                    .map { (type, entries) ->
                        val cost = entries.sumOf { it.cost ?: 0.0 }
                        ContextGroup(
                            type = type,
                            count = entries.size,
                            inputTokens = entries.sumOf { it.tokens?.input ?: 0L },
                            outputTokens = entries.sumOf { it.tokens?.output ?: 0L },
                            cost = cost,
                            // ⚠️ Un cout total nul laisse la part a 0 : c'est honnete et evite un
                            // `NaN`, qui se dessinerait n'importe comment.
                            costShare = if (totalCost <= 0.0) 0f else (cost / totalCost).toFloat(),
                        )
                    }
                    // Tri par COUT, pas par nombre : c'est ce qu'on paie qui doit venir en premier.
                    // Un type tres frequent mais gratuit ne doit pas masquer celui qui coute.
                    .sortedByDescending { it.cost }

                val heaviest = raw
                    .maxByOrNull { it.cost ?: 0.0 }
                    ?.takeIf { (it.cost ?: 0.0) > 0.0 }
                    ?.let { dto ->
                        HeaviestEntry(
                            id = dto.id,
                            type = dto.type,
                            cost = dto.cost ?: 0.0,
                            summarySize = dto.summary?.length ?: 0,
                        )
                    }

                _state.value = ContextUiState.Loaded(
                    entryCount = raw.size,
                    groups = groups,
                    heaviest = heaviest,
                    totalInput = totalInput,
                    totalOutput = totalOutput,
                    totalCost = totalCost,
                )
            } catch (e: Exception) {
                _state.value = ContextUiState.Error(ConnectionErrors.describe(e))
            }
        }
    }
}

/** Non utilise directement, mais garde la reference du DTO pour la documentation du contrat. */
private typealias ContextDtoRef = ContextMessageDto
