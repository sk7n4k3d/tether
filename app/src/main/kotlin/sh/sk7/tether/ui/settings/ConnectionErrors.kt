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
}
