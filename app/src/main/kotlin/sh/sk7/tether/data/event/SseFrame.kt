package sh.sk7.tether.data.event

/**
 * Une frame SSE brute, telle qu'extraite du flux.
 *
 * Le flux opencode V2 n'emet **aucune ligne `id:`** : [id] reste donc `null` en
 * pratique (il est porte par le JSON de [data], cf. `OcEvent.id`). Le champ est
 * conserve pour rester conforme au protocole SSE.
 */
data class SseFrame(
    val id: String? = null,
    val data: String,
)
