package sh.sk7.tether.push

import android.content.Context
import android.util.Base64
import org.unifiedpush.android.connector.data.PushEndpoint

/**
 * L'abonnement **Web Push** de cet appareil, tel que le distributeur l'a remis.
 *
 * ## Pourquoi il faut le stocker en entier
 *
 * Le distributeur fournit trois choses dans [PushEndpoint] : l'`url`, la cle publique
 * (`pubKeySet.pubKey`, la cle P-256DH du client) et le secret d'authentification
 * (`pubKeySet.auth`). Le chiffrement Web Push (RFC 8291) a besoin des **trois**.
 *
 * ⚠️ La version precedente ne gardait que l'`url`, et la publiait sur un topic ntfy
 * anonyme. Ce choix etait coherent avec l'ancien canal — un topic ntfy ne transporte que
 * des messages, pas une cle de chiffrement cote serveur. Des que le serveur chiffre
 * lui-meme (RFC 8291), **l'url seule ne suffit plus** : sans les deux autres, le serveur ne
 * peut pas produire un payload que seul cet appareil peut lire. Les perdre rendait le canal
 * Web Push impossible, silencieusement.
 *
 * ## Le stockage
 *
 * `SharedPreferences` en mode prive, comme le reste de l'app.
 *
 * ⚠️ **C'est un secret, et il vit sur l'appareil.** C'est inevitable : le serveur doit
 * pouvoir chiffrer, donc la cle doit etre des deux cotes. Ce qui n'est pas inevitable, c'est
 * de l'ecrire lisible par les autres applications — d'ou le mode prive, et le fait que
 * rien ne le recopie ailleurs. L'endpoint est une capacite d'ecriture ; il ne doit jamais
 * partir dans un log, ce que verifient les tests du plugin cote serveur.
 */
object PushSubscription {

    private const val PREFS = "tether-push"

    private const val CLE_URL = "subscription-url"
    private const val CLE_PUBKEY = "subscription-pubkey"
    private const val CLE_AUTH = "subscription-auth"
    private const val CLE_DISTRIBUTEUR = "subscription-distributor"

    /** L'abonnement, ou `null` si le distributeur n'en a pas encore fourni. */
    data class Abonnement(
        val url: String,
        val p256dh: String,
        val auth: String,
        val distributor: String?,
    )

    /**
     * Enregistre ce que le distributeur vient de remettre.
     *
     * Un endpoint `temporary` est refuse : il ne survivra pas au redemarrage du
     * distributeur, donc le serveur pousserait vers un point d'acces mort. Tant qu'un
     * endpoint durable n'est pas arrive, l'appairage affiche «endpoint non definitif».
     */
    fun remember(context: Context, endpoint: PushEndpoint, distributor: String?): Boolean {
        if (endpoint.temporary) return false
        val url = endpoint.url
        // `pubKeySet` est nullable dans la bibliotheque : un distributeur peut annoncer un
        // point d'acces sans cle — il ne sera alors pas un point d'acces Web Push, mais un
        // simple topic. Ce n'est pas une erreur de notre cote, seulement un canal que le
        // serveur ne pourra pas chiffrer. On refuse donc de memoriser.
        val cles = endpoint.pubKeySet
        if (cles == null) return false
        val pubKey = cles.pubKey
        val secret = cles.auth
        if (url.isBlank() || pubKey.isBlank() || secret.isBlank()) return false

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CLE_URL, url)
            .putString(CLE_PUBKEY, encode(pubKey))
            .putString(CLE_AUTH, encode(secret))
            .putString(CLE_DISTRIBUTEUR, distributor)
            .apply()
        return true
    }

    /** L'abonnement memorise, ou `null`. */
    fun load(context: Context): Abonnement? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(CLE_URL, null) ?: return null
        val p256dh = prefs.getString(CLE_PUBKEY, null)?.let(::decode) ?: return null
        val auth = prefs.getString(CLE_AUTH, null)?.let(::decode) ?: return null
        return Abonnement(url, p256dh, auth, prefs.getString(CLE_DISTRIBUTEUR, null))
    }

    /**
     * Oublie l'abonnement. Appele apres un retrait volontaire, pour que l'app ne
     * reapparaisse pas comme enregistree aupres d'un serveur dont on l'a exclue.
     */
    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(CLE_URL)
            .remove(CLE_PUBKEY)
            .remove(CLE_AUTH)
            .remove(CLE_DISTRIBUTEUR)
            .apply()
    }

    /**
     * L'abonnement est-il **definitif** ?
     *
     * Un point d'acces temporaire ne vaut rien : il expire avec le distributeur, et le
     * serveur pousserait dans le vide sans aucun signe. On le dit plutot que de
     * s'eregister et de croire que ca marche.
     */
    fun estDefinitif(endpoint: PushEndpoint): Boolean = !endpoint.temporary

    // Base64 : le `SharedPreferences` est du XML sur disque, et un secret en clair y
    // serait lisible par une sauvegarde ou un extraction. Ce n'est **pas** du chiffrement
    // — les preferences d'une application sont dans son propre espace, protege par le
    // sandbox — mais cela evite qu'un fichier de preferences soit lu tel quel.
    private fun encode(valeur: String): String =
        Base64.encodeToString(valeur.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(valeur: String): String? =
        try {
            String(Base64.decode(valeur, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
}
