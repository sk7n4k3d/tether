package sh.sk7.tether.ui.stats

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
import sh.sk7.tether.domain.model.UsageStats
import sh.sk7.tether.ui.settings.ConnectionErrors

/**
 * Plage de temps des statistiques.
 *
 * ⚠️ Traduite en `from` (millisecondes) et **pas** en parametre symbolique : verifie sur le
 * serveur, `days=7` ou `range=7d` sont **ignores** (il renvoie 13 jours et 146 sessions), seul
 * `from=<ms>` restreint reellement la plage. Envoyer un parametre que le serveur ignore donnerait
 * un ecran qui ment sans erreur — le pire des cas.
 */
enum class StatsRange(val label: String, val days: Int?) {
    Week("7 j", 7),
    Month("30 j", 30),
    Quarter("90 j", 90),
    All("Tout", null),
}

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Loaded(val stats: UsageStats) : StatsUiState
    data class Error(val message: String) : StatsUiState
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val store: ConnectionStore,
    private val gateway: OpenCodeGateway,
    @param:IoDispatcher
    private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    private val _range = MutableStateFlow(StatsRange.Month)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    init {
        load()
    }

    override fun onCleared() {
        scope.cancel()
    }

    fun setRange(next: StatsRange) {
        if (_range.value == next) return
        _range.value = next
        load()
    }

    fun load() {
        _state.value = StatsUiState.Loading
        scope.launch {
            try {
                val settings = store.current()
                val from = _range.value.days?.let { days ->
                    System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
                }
                _state.value = StatsUiState.Loaded(gateway.stats(settings, from))
            } catch (e: Exception) {
                _state.value = StatsUiState.Error(ConnectionErrors.describe(e))
            }
        }
    }
}
