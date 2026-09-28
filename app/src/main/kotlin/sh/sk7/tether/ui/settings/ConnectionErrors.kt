package sh.sk7.tether.ui.settings

import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * Traduit une erreur de connexion en message affichable.
 *
 * ⚠️ Regle de securite : le message **ne contient jamais le mot de passe** ni un en-tete
 * d'autorisation. On ne reprend jamais `Throwable.message` brut d'une requete authentifiee
 * sans le filtrer ; on prefere un libelle dedie au cas d'erreur reconnu.
 */
object ConnectionErrors {

    fun describe(error: Throwable): String = when (error) {
        is io.ktor.client.plugins.ClientRequestException -> {
            val code = error.response.status.value
            when (code) {
                401, 403 -> Res.of(R.string.mot_passe_refuse_3ee22f)
                in 400..499 -> Res.of(R.string.requete_refusee_error_5a1f5d, code)
                else -> Res.of(R.string.erreur_serveur_error_063339, code)
            }
        }
        is io.ktor.client.plugins.ServerResponseException ->
            Res.of(R.string.serveur_repondu_error_146bc6, error.response.status.value)
        is java.net.UnknownHostException -> Res.of(R.string.hote_introuvable_verifie_05248c)
        is java.net.ConnectException -> Res.of(R.string.connexion_refusee_serveur_89c562)
        is java.net.SocketTimeoutException -> Res.of(R.string.delai_depasse_serveur_8629d4)
        else -> Res.of(
            R.string.echec_connexion_error_42ecac,
            error::class.simpleName ?: "erreur inconnue",
        )
    }

    /**
     * **Le serveur a-t-il refuse les identifiants ?**
     *
     * ### Pourquoi cette fonction existe
     * ⚠️ Elle corrige un bug qui rendait la distinction **impossible**. Quatre appelants
     * testaient `message.contains("401")` sur le texte produit par [describe] — or [describe]
     * traduit 401 en « Mot de passe refusé par le serveur. », **sans chiffre**. Ces quatre tests
     * etaient donc **toujours faux**.
     *
     * Consequence, mesuree dans le code : `ConnectionStatus.Unauthorized` n'etait jamais produit,
     * l'ecran hors-connexion affichait « Serveur injoignable : machine eteinte / tunnel Tailscale
     * / 127.0.0.1 » a quelqu'un dont **le seul probleme etait un mot de passe errone**. C'est
     * exactement le mensonge que ce module interdit : il envoie l'utilisateur chercher un probleme
     * qui n'existe pas.
     *
     * ### Pourquoi tester le TYPE et non le texte
     * ⚠️ Le message est une **traduction** destinee a l'affichage : il peut changer de formulation
     * sans que la cause change. Le statut HTTP, lui, est un fait du protocole. S'appuyer sur le
     * texte serait refaire l'erreur qu'on vient de corriger.
     *
     * ⚠️ `cause` est exploree : Ktor enveloppe parfois l'erreur, et le statut peut se trouver un
     * niveau plus bas.
     */
    fun isUnauthorized(error: Throwable): Boolean {
        var current: Throwable? = error
        // ⚠️ Borne volontaire : on ne veut pas boucler sur une chaine de causes circulaire.
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current is io.ktor.client.plugins.ClientRequestException &&
                current.response.status.value in UNAUTHORIZED_CODES
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private val UNAUTHORIZED_CODES = setOf(401, 403)

    private const val MAX_CAUSE_DEPTH = 8
}
