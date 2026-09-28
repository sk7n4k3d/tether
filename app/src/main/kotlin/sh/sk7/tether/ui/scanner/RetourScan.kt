package sh.sk7.tether.ui.scanner

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import sh.sk7.tether.R

/**
 * **Ce que le telephone repond quand un QR est lu.**
 *
 * ### Pourquoi trois signaux, et pas un
 *
 * Scanner, c'est viser : l'utilisateur regarde l'ecran qu'il filme, pas celui du
 * telephone. Un changement de page ne se voit donc **pas** au moment ou il se produit —
 * il faut relever la tete, puis comprendre pourquoi l'ecran a change.
 *
 * Trois canaux parce qu'ils ne servent pas a la meme personne dans la meme situation :
 *
 *  - la **vibration** est le seul signal qui arrive quand le telephone est tenu et que
 *    l'ecran est loin des yeux ;
 *  - le **son** sert quand l'appareil est pose, ou a quelqu'un qui ne sent pas les
 *    vibrations courtes ;
 *  - le **toast** dit *ce qui s'est passe*, et il faut le lire — c'est le seul des trois
 *    qui porte un contenu.
 *
 * Les autres sont des reflexes : `startTone` et `vibrate` ne sont pas bloquants, et un
 * utilisateur qui a coupe le son du systeme garde la vibration.
 *
 * ### Deux formes, jamais confondues
 *
 * **Reconnaitre** : une vibration breve, un bip unique et montant, un toast qui dit quoi
 * verifier. **Refuser** : deux impulsions separees, un bip descendant, et un toast qui
 * dit pourquoi. La difference doit s'entendre et se sentir sans lire — c'est le seul
 * moyen de savoir qu'on peut arreter de viser.
 *
 * ⚠️ Aucun des trois n'est indispensable, et **aucun ne bloque** : si la vibration est
 * refusee par le systeme (mode silencieux, permission absente), le scan continue. Un
 * retour qui fait echouer l'action qu'il accompagne est pire que pas de retour.
 */
object RetourScan {

    /** Le QR est reconnu et va etre presente pour confirmation. */
    fun reconnu(context: Context) {
        vibre(context, longArrayOf(0L, 55L))
        son(ToneGenerator.TONE_PROP_ACK)
        toast(context, R.string.scanner_reconnu_5c1a2e)
    }

    /**
     * Le QR a ete lu, mais ce n'est pas un code d'appairage.
     *
     * ⚠️ On ne le dit **qu'une fois** par passage sur l'ecran : la camera lit plusieurs
     * images par seconde, et un QR etranger reste devant l'objectif. Sans ce verrou, le
     * telephone biperait vingt fois par seconde — l'utilisateur croirait a une panne.
     * Le verrou vit dans l'ecran, pas ici : ce fichier decrit un signal, pas sa cadence.
     */
    fun refuse(context: Context) {
        vibre(context, longArrayOf(0L, 70L, 90L, 70L))
        son(ToneGenerator.TONE_PROP_NACK)
        toast(context, R.string.scanner_pas_appairage_2b9c54)
    }

    private fun vibre(context: Context, motif: LongArray) {
        val vibrateur = vibrateur(context) ?: return
        if (!vibrateur.hasVibrator()) return
        // On joue le motif **sans** le repeter (`-1`) : un scan est un evenement, pas un
        // etat. Une vibration qui continue apres le geste ferait chercher une alarme.
        runCatching {
            vibrateur.vibrate(VibrationEffect.createWaveform(motif, -1))
        }
    }

    private fun vibrateur(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * Un bip court, sur le canal des notifications.
     *
     * ⚠️ **`release()` n'est pas immediat, et c'est le piege de cette API.** `startTone`
     * est asynchrone : liberer le generateur juste apres l'appel coupe le son avant qu'il
     * ne soit joue — un « bip » qui ne s'entend jamais, sans erreur. On le libere donc
     * apres la duree du ton, depuis un fil ephemere.
     *
     * Un fil par bip, c'est acceptable ici : un scan reussi arrive une fois, et un scan
     * refuse est deja limite a un signal par passage. Garder un generateur ouvert pour la
     * duree de l'ecran immobiliserait un decodeur audio pour un dixieme de seconde de son.
     *
     * ⚠️ `runCatching` sur la creation : sur un appareil sans sortie audio utilisable,
     * `ToneGenerator` peut lever — et un scan ne doit pas echouer parce qu'on n'a pas pu
     * faire « bip ».
     */
    private fun son(type: Int) {
        kotlin.concurrent.thread(name = "tether-bip") {
            runCatching {
                val generateur = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
                generateur.startTone(type, DUREE_MS)
                Thread.sleep(DUREE_MS.toLong() + MARGE_MS)
                generateur.release()
            }
        }
    }

    private fun toast(context: Context, message: Int) {
        // `applicationContext` : le message survit a la rotation, et un Toast retenu par
        // une activite detruite fuit.
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    /** Assez fort pour s'entendre d'un bureau, assez bas pour ne pas sursauter. */
    private const val VOLUME = 80

    /** Le temps que met `TONE_PROP_ACK` a se jouer ; genere par le systeme, pas par nous. */
    private const val DUREE_MS = 150

    /** Le temps laisse au ton pour finir avant que le generateur ne soit libere. */
    private const val MARGE_MS = 120
}
