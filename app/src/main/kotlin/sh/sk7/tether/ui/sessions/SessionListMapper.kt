package sh.sk7.tether.ui.sessions

import java.util.Locale
import sh.sk7.tether.data.api.Session

/**
 * Une session prete a afficher : seuls les champs utiles a la carte sont conserves.
 *
 * [timestamp] est l'horodatage d'age (updated, sinon created) ; il est nullable pour que
 * l'UI affiche un tiret plutot qu'une date fausse.
 */
data class SessionItem(
    val id: String,
    val title: String,
    val timestamp: Long?,
    val agent: String?,
    val modelLabel: String?,
    val costLabel: String?,
    val directory: String?,
)

/**
 * Traduit un DTO [Session] en [SessionItem].
 *
 * Regles :
 * - l'age se base sur `time.updated`, avec repli sur `time.created` (spike §5) ;
 * - un cout nul **ou** nul (0.0) n'affiche rien : afficher « 0,00 $ » sur une session
 *   neuve serait du bruit ;
 * - le modele est affiche `provider/id`, forme la plus courte qui reste non ambigue.
 */
object SessionListMapper {

    fun toItem(session: Session): SessionItem {
        val model = session.model
        return SessionItem(
            id = session.id,
            title = session.title?.takeIf { it.isNotBlank() } ?: "Sans titre",
            timestamp = session.time?.updated ?: session.time?.created,
            agent = session.agent?.takeIf { it.isNotBlank() },
            modelLabel = model?.let { "${it.providerID}/${it.id}" },
            costLabel = formatCost(session.cost),
            directory = session.location?.directory,
        )
    }

    fun toItems(sessions: List<Session>): List<SessionItem> = sessions.map(::toItem)

    private fun formatCost(cost: Double?): String? {
        if (cost == null || cost <= 0.0) return null
        return String.format(Locale.FRANCE, "%.2f $", cost)
    }
}
