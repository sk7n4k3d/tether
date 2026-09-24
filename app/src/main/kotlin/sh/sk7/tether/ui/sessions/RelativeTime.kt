package sh.sk7.tether.ui.sessions

import java.util.Locale

/**
 * Age relatif d'un horodatage epoch (millisecondes), pour la liste des sessions.
 *
 * Volontairement sans dependance Android ni locale systeme : la sortie est stable et
 * testable. L'unite est toujours presente, la valeur est arrondie vers le bas.
 */
object RelativeTime {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    fun format(nowMillis: Long, timestampMillis: Long?): String {
        if (timestampMillis == null) return "—"
        val elapsed = nowMillis - timestampMillis
        // Un horodatage dans le futur (horloge desynchronisee) ne rend pas de duree negative.
        if (elapsed < MINUTE) return "à l'instant"
        if (elapsed < HOUR) return "il y a ${elapsed / MINUTE} min"
        if (elapsed < DAY) return "il y a ${elapsed / HOUR} h"
        if (elapsed < WEEK) return "il y a ${elapsed / DAY} j"
        return shortDate(timestampMillis)
    }

    private fun shortDate(millis: Long): String {
        val date = java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return String.format(
            Locale.ROOT,
            "%02d/%02d/%04d",
            date.dayOfMonth,
            date.monthValue,
            date.year,
        )
    }
}
