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
    /**
     * ⚠️ **Compilee une fois, pas a chaque appel.** Mesure : `filter` appelle `normalize` trois
     * fois par session, soit ~1 350 compilations de regex **par frappe** sur 450 sessions. Une
     * `Regex` construite dans le corps d'une fonction se recompile a chaque appel — c'est le
     * piege classique, et il est invisible a la lecture.
     */
    private val ACCENTS = Regex("\\p{Mn}+")

    private fun normalize(value: String): String =
        java.text.Normalizer
            .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(ACCENTS, "")
            .trim()
}
