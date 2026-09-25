package sh.sk7.tether.ui.settings

/**
 * Traduit une erreur de connexion en message affichable.
 *
 * ⚠️ Regle de securite : le message **ne contient jamais le mot de passe** ni un en-tete
 * d'autorisation. On ne reprend jamais `Throwable.message` brut d'une requete authentifiee
 * sans le filtrer ; on prefere un libelle dedie au cas d'erreur reconnu.
 */
object ConnectionErrors {

    fun describe(error: Throwable): String = when (error) {
        is io.ktor.client.plugins.ClientRequestException ->
            when (error.response.status.value) {
                401, 403 -> "Mot de passe refusé par le serveur."
                in 400..499 -> "Requête refusée (${error.response.status.value}). Vérifie l'URL."
                else -> "Erreur serveur (${error.response.status.value})."
            }
        is io.ktor.client.plugins.ServerResponseException ->
            "Le serveur a répondu ${error.response.status.value}."
        is java.net.UnknownHostException -> "Hôte introuvable : vérifie l'adresse."
        is java.net.ConnectException -> "Connexion refusée. Le serveur est-il joignable sur ce port ?"
        is java.net.SocketTimeoutException -> "Délai dépassé : le serveur ne répond pas."
        else -> "Échec de la connexion : ${error::class.simpleName ?: "erreur inconnue"}."
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
