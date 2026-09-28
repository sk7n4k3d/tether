package sh.sk7.tether.push

import android.content.Context
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'identifiant de cet appareil, stable et **aleatoire**.
 *
 * ## Pourquoi pas `ANDROID_ID`
 *
 * `Settings.Secure.ANDROID_ID` serait plus simple : il est deja la, il ne bouge pas. Mais
 * c'est un identifiant **de l'appareil**, derivé de la cle de signature, et il sort de
 * l'app vers le serveur. Le serveur n'a besoin que de distinguer « ce telephone » de « ce
 * telephone-la », pas de le reconnaitre.
 *
 * Un UUID aleatoire genere au premier lancement, puis persiste, fait le meme travail et
 * ne dit rien du telephone. Il survit aux redemarrages et aux mises a jour ; apres une
 * reinitialisation usine, il change — donc l'appareil doit etre reappaire, ce qui est
 * correct : un telephone reinitialise n'est plus celui qu'on a appaire.
 */
@Singleton
class DeviceIdentity @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    /**
     * L'identifiant, cree au premier appel.
     *
     * `commit()` et non `apply()` : la valeur doit etre sur disque **avant** qu'on
     * l'envoie au serveur. Avec `apply()`, un arret brutal juste apres l'envoi laisserait
     * le serveur avec un identifiant que l'app a oublie — et le prochain enregistrement
     * en creerait un autre, laissant une entree orpheline.
     */
    fun id(): String {
        val prefs = context.getSharedPreferences("tether-identity", Context.MODE_PRIVATE)
        prefs.getString(CLE, null)?.let { existant ->
            if (existant.isNotBlank()) return existant
        }
        val neuf = UUID.randomUUID().toString()
        prefs.edit().putString(CLE, neuf).commit()
        return neuf
    }

    companion object {
        private const val CLE = "device-id"
    }
}
