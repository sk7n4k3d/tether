package sh.sk7.tether.ui.sessions

/**
 * **La recherche dans les sessions.**
 *
 * ### Pourquoi elle existe, et pourquoi elle cherche ou il faut
 * La plainte la plus recurrente des utilisateurs de clients de chat, tous produits confondus,
 * est l'impossibilite de **retrouver** une conversation. Un anti-pattern explicitement documente
 * est de ne chercher que dans les **titres** : un titre est souvent generate et ne contient pas
 * le mot qu'on cherche (un nom de fichier, un message d'erreur, un identifiant). On cherche donc
 * dans le titre **et** dans le contenu de la conversation.
 *
 * ### Les regles
 *  - **insensible a la casse et aux accents** : ecrire `resume` doit trouver `Résumé`. Sans ca,
 *    la recherche est inutilisable au clavier d'un telephone ;
 *  - **resultats ordonnes par date** : le plus recent d'abord, comme la liste qu'on connait ;
 *  - **aucun resultat n'est silencieux** : quand rien ne sort, on le dit et on rappelle sur quoi
 *    on a cherche.
 *
 * ⚠️ La normalisation est faite **une fois par requete**, pas par session : sur 445 sessions,
 * normaliser le texte de chaque session a chaque frappe serait couteux pour rien.
 */
object SessionSearch {

    /**
     * Filtre les sessions selon une requete.
     *
     * @param query texte brut de l'utilisateur. Vide ou blanc = aucune recherche (on renvoie tout).
     */
    fun filter(items: List<SessionItem>, query: String): List<SessionItem> {
        val needle = normalize(query)
        if (needle.isEmpty()) return items
        return items.filter { item ->
            normalize(item.title).contains(needle) ||
                // Le modele et l'agent font partie de ce qu'on cherche en pratique : « ce truc
                // que j'avais lance avec glm » est une requete reelle.
                normalize(item.modelLabel.orEmpty()).contains(needle) ||
                normalize(item.agent.orEmpty()).contains(needle)
        }
    }

    /**
     * Normalise pour la comparaison : minuscules, **accents retires**.
     *
     * ⚠️ On ne touche pas au texte affiche — seulement a la cle de comparaison. L'utilisateur
     * doit continuer a voir « Résumé » avec son accent.
     */
    private fun normalize(value: String): String =
        java.text.Normalizer
            .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .trim()
}
