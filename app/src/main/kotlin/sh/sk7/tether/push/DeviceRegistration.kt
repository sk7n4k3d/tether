package sh.sk7.tether.push

import android.content.Context

/**
 * Le serveur auquel cet appareil est appaire.
 *
 * ### Pourquoi il faut s'en souvenir
 *
 * Un endpoint UnifiedPush n'est pas durable : le distributeur le redistribue a chaque
 * redemarrage, et le serveur garde alors un point d'acces mort. L'app doit donc
 * re-enregistrer aupres du **meme** serveur — mais son jeton d'appairage est a usage
 * unique, donc mort lui aussi. C'est ce souvenir qui rend le renouvellement possible
 * sans repasser par un QR.
 *
 * ⚠️ On ne retient **que l'adresse et le repertoire**, jamais le mot de passe : celui-ci
 * reste dans `ConnectionStore` et n'est lu qu'au moment de l'appel. Un fichier
 * d'enregistrement ne doit pas devenir un second endroit ou le secret traine.
 *
 * ### Pourquoi un seul serveur
 *
 * Tether se connecte a un serveur a la fois (c'est le modele de l'app). Multiplier les
 * enregistrementsicy n'aurait pas de sens, et le premier venu gagnerait. Si un jour la
 * connexion multi-serveur existe, ce type devient une liste — et l'ecran d'appairage
 * doit le dire.
 */
object DeviceRegistration {

    private const val PREFS = "tether-registration"
    private const val CLE_SERVER = "server"
    private const val CLE_REPERTOIRE = "directory"

    data class Enregistrement(val server: String, val directory: String)

    /** Memorise le serveur appaire. Appele **apres** un `registerDevice` reussi. */
    fun remember(context: Context, server: String, directory: String) {
        if (server.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CLE_SERVER, server)
            .putString(CLE_REPERTOIRE, directory)
            .apply()
    }

    /**
     * Le serveur appaire, ou `null`.
     *
     * ⚠️ Le registre n'est efface que par [oublier], appele quand l'utilisateur retire
     * l'appareil depuis le TUI. Sans cela, un appareil retire continuerait de
     * re-enregistrer son endpoint au prochain redemarrage du distributeur — le retrait
     * serait sans effet, ce qui est pire que de ne pas proposer le retrait.
     */
    fun load(context: Context): Enregistrement? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val server = prefs.getString(CLE_SERVER, null)
        if (server.isNullOrBlank()) return null
        return Enregistrement(server, prefs.getString(CLE_REPERTOIRE, "") ?: "")
    }

    /** Oublie le serveur. L'appareil n'est plus enregistre nulle part. */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(CLE_SERVER)
            .remove(CLE_REPERTOIRE)
            .apply()
    }
}
