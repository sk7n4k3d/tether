package sh.sk7.tether.data.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Source d'evenements du flux opencode V2, vue par les ViewModels.
 *
 * L'interface existe pour que le chat soit **testable en JVM pur** : on injecte un faux flux
 * (`flowOf(...)`, `emptyFlow()`) sans serveur ni moteur HTTP, exactement comme
 * `OpenCodeGateway` le permet deja pour le REST.
 *
 * ⚠️ `EventStream` est la seule implementation de production, mais **le flux est partage par
 * toutes les sessions** : son collecteur doit filtrer, et [state] sert a declencher une
 * resynchronisation REST a chaque (re)connexion (spec §4.2).
 */
interface EventSource {
    /** Flux d'evenements decode, avec reconnexion automatique. Ne termine que si annule. */
    fun connect(): Flow<OcEvent>

    /** Etat de la connexion SSE, a observer pour resynchroniser l'etat via le REST. */
    val state: StateFlow<ConnectionState>
}
