package sh.sk7.tether.ui.chat

import sh.sk7.tether.data.api.CommandDto

/**
 * **Quand la palette de commandes doit-elle s'ouvrir, et sur quoi.**
 *
 * ### Pourquoi cette logique est isolee et testee
 * La detection parait triviale — « le texte commence par `/` » — et elle est fausse dans trois cas
 * qu'on rencontre tout de suite :
 *
 *  1. **Un chemin de fichier** : `/home/sk7/mon fichier` commence par `/` et n'est pas une
 *     commande. Ouvrir la palette y masquerait la saisie alors qu'on ecrit une phrase ;
 *  2. **Une commande deja complete** : `/review ce diff` a un espace, donc le nom est fige et la
 *     palette n'a plus rien a proposer ;
 *  3. **Un slash au milieu** : `voir src/main.kt` — le `/` est la mais pas en tete.
 *
 * ⚠️ Une heuristique fausse ici produit un symptome penible : la palette s'ouvre par-dessus la
 * conversation pendant qu'on ecrit un chemin, et il faut la fermer pour continuer.
 */
object SlashInput {

    /**
     * L'etat de la palette pour un contenu de champ.
     *
     * @param query ce qui suit le `/`, sans espace : c'est le filtre a appliquer.
     */
    data class State(val visible: Boolean, val query: String) {
        companion object {
            val Hidden = State(visible = false, query = "")
        }
    }

    /**
     * Analyse le contenu du champ.
     *
     * ⚠️ On exige que le `/` soit le **premier caractere** : c'est la convention de toutes les
     * interfaces a commandes, et le seul moyen de ne pas confondre avec un chemin relatif au
     * milieu d'une phrase.
     */
    fun parse(text: String): State {
        if (!text.startsWith("/")) return State.Hidden

        val rest = text.removePrefix("/")

        // ⚠️ Un espace signifie que le nom est termine : on n'est plus en train de le choisir, on
        // ecrit ses arguments. Laisser la palette ouverte masquerait la conversation pour rien.
        if (rest.contains(" ")) return State.Hidden

        return State(visible = true, query = rest)
    }

    /**
     * Filtre les commandes sur la frappe.
     *
     * ⚠️ Une requete vide renvoie **tout** : c'est ce qui fait qu'un `/` seul montre les 28
     * commandes, c'est-a-dire qu'il **revient a les decouvrir**. Une liste vide sur `/` seul
     * priverait l'utilisateur de la seule facon de savoir ce qui existe.
     */
    fun filter(commands: List<CommandDto>, query: String): List<CommandDto> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return commands
        return commands.filter { it.name.lowercase().contains(needle) }
    }

    /**
     * Remplace le contenu du champ par la commande choisie.
     *
     * ⚠️ On garde un **espace final** : l'utilisateur va presque toujours ajouter un argument
     * (`/review` puis le diff a revoir), et le lui faire taper est une friction gratuite.
     */
    fun apply(command: CommandDto): String = "/${command.name} "

    /**
     * Decompose une saisie de commande pour l'envoi.
     *
     * ⚠️ On rend `null` si ce n'est **pas** une commande valide : l'appelant doit alors passer par
     * un envoi de prompt normal. C'est la securite contre un `/` qui serait du texte — le serveur
     * refuserait un nom inconnu, et l'utilisateur verrait une erreur incomprehensible.
     */
    fun toCommand(text: String, commands: List<CommandDto>): Pair<String, String>? {
        // ⚠️ On nettoie AVANT d'examiner : un copier-coller colle souvent des espaces ou une fin
        // de ligne, et ` /init ` doit etre reconnu. Le premier jet analysait le texte brut, donc
        // un espace en tete suffisait a faire passer une vraie commande pour du texte — attrape
        // par le test.
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null

        val withoutSlash = trimmed.removePrefix("/")
        val name = withoutSlash.substringBefore(' ').trim()
        if (name.isEmpty()) return null

        // ⚠️ On verifie le nom contre la liste du serveur AVANT d'envoyer. Le serveur refuserait
        // un nom inconnu, mais avec un message technique ; ici l'app peut dire clairement que la
        // commande n'existe pas et laisser l'utilisateur continuer a ecrire son message.
        if (commands.none { it.name == name }) return null

        val args = withoutSlash.removePrefix(name).trim()
        return name to args
    }
}
