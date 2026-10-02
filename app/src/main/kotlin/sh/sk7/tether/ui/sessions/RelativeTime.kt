package sh.sk7.tether.ui.sessions

import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * L'horloge d'affichage des ages relatifs.
 *
 * ⚠️ Pourquoi un flux et pas `System.currentTimeMillis()` direct dans la composition :
 * (1) lire l'horloge pendant la composition est impur — le meme rendu peut donner des
 * ages differents selon le thread ; (2) surtout, RIEN ne rafraichissait : « il y a 2 min »
 * restait affiche tant qu'aucune autre recomposition ne se produisait. Une emission par
 * minute suffit : la granularite du format est la minute.
 *
 * Plafonne a 60 emissions : un ecran laisse ouvert une nuit ne cree pas de timer immortel.
 */
@Composable
fun rememberMinuteTick(): Long {
    return produceState(initialValue = System.currentTimeMillis()) {
        var ticks = 0
        while (currentCoroutineContext().isActive && ticks < 60) {
            delay(60_000)
            value = System.currentTimeMillis()
            ticks++
        }
    }.value
}

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

    /**
     * Un temps relatif, en toutes lettres.
     *
     * `chaine` est le resolveur de traduction, **injectable** pour une raison precise :
     * un test JVM n'a pas de `Context`, donc pas de ressources, donc pas de texte
     * francais a comparer. Avec un resolveur fourni, le test continue de verifier ce
     * qui compte — le format, l'arrondi, le seuil — sans dependre d'une langue.
     *
     * Sans lui, l'appel est `Res.of`, ce qui lit la langue en vigueur.
     */
    fun format(
        nowMillis: Long,
        timestampMillis: Long?,
        chaine: (Int, Array<out Any>) -> String = { id, args -> Res.of(id, *args) },
    ): String {
        if (timestampMillis == null) return "—"
        val elapsed = nowMillis - timestampMillis
        // Un horodatage dans le futur (horloge desynchronisee) ne rend pas de duree negative.
        if (elapsed < MINUTE) return chaine(R.string.instant_2427aa, emptyArray())
        if (elapsed < HOUR) return chaine(R.string.elapsed_minute_min_517d83, arrayOf(elapsed / MINUTE))
        if (elapsed < DAY) return chaine(R.string.elapsed_hour_74a62f, arrayOf(elapsed / HOUR))
        if (elapsed < WEEK) return chaine(R.string.elapsed_day_21154c, arrayOf(elapsed / DAY))
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
