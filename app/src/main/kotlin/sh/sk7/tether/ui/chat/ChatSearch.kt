package sh.sk7.tether.ui.chat

import sh.sk7.tether.domain.model.ChatMessage
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R

/**
 * **La recherche dans une conversation ouverte.**
 *
 * ### Pourquoi elle est distincte de la recherche de sessions
 * Les deux repondent a des questions differentes. `SessionSearch` repond a « **ou** est cette
 * conversation ? » ; ceci repond a « **a quel moment** a-t-on parle de ca ? ». Un utilisateur qui a
 * une conversation ouverte sous les yeux et cherche `timeout` ne veut pas la quitter pour une autre
 * — il veut savoir si c'est **la-dedans**.
 *
 * ### Ce qu'on cherche : le texte ET le raisonnement
 * ⚠️ On inclut le **raisonnement** et les **sorties d'outils**, et c'est un choix deliberé. Le cas
 * d'usage le plus frequent est de retrouver *« quelle commande a tourne ? »* ou *« pourquoi il a
 * fait ca ? »* — deux informations qui vivent dans les blocs replies. Chercher uniquement dans les
 * reponses visibles rendrait la recherche inutile precisement quand elle sert.
 *
 * ⚠️ La normalisation (minuscules, accents retires) est identique a celle de `SessionSearch` :
 * chercher « resume » doit trouver « Résumé » dans les deux ecrans. Deux regles de recherche
 * differentes dans la meme app seraient vecues comme un bug.
 */
object ChatSearch {

    /** Une correspondance, avec de quoi situer et surligner. */
    data class Match(
        val messageID: String,
        /** Nombre d'occurrences dans ce message. */
        val count: Int,
        /** Ou la correspondance se trouve — pour dire ce qu'on a trouve, pas seulement combien. */
        val field: Field,
    ) {
        // ⚠️ `label` reste en dur : un `enum` ne peut pas appeler `stringResource`,
        // qui n'existe que dans un contexte de composition. C'est le seul endroit ou
        // une chaine d'interface reste hors resource, et il est d'une ligne.
        enum class Field(val label: String) {
            Text("message"),
            Reasoning("raisonnement"),
            Tool("outil"),
        }
    }

    /** Le resultat complet : ce qui matche, et les identifiants correspondants. */
    data class Result(
        val query: String,
        val matches: List<Match>,
    ) {
        val total: Int get() = matches.sumOf { it.count }
        val messageCount: Int get() = matches.size
        val isEmpty: Boolean get() = matches.isEmpty()
        val ids: Set<String> get() = matches.map { it.messageID }.toSet()
    }

    /**
     * Cherche dans une liste de messages.
     *
     * ⚠️ Une requete vide renvoie un resultat **vide mais non actif** : l'appelant doit verifier
     * `query.isNotBlank()` avant d'afficher quoi que ce soit. Un resultat vide avec une requete
     * vide signifie « pas de recherche », pas « rien trouve » — et l'ecran ne dit pas la meme chose
     * dans les deux cas.
     */
    fun find(messages: List<ChatMessage>, query: String): Result {
        val needle = normalize(query)
        if (needle.isEmpty()) return Result(query = query, matches = emptyList())

        val matches = messages.mapNotNull { message ->
            val inText = occurrences(message.text, needle)
            // ⚠️ On exclut le raisonnement et les outils quand le message n'en a pas, mais on les
            // compte separement : savoir que ca matche dans le RAISONNEMENT change ce qu'on fait
            // (il faut deplier le bloc pour le voir).
            val inReasoning = occurrences(message.reasoning, needle)
            val inTools = message.tools.sumOf { tool ->
                occurrences(tool.summary.orEmpty(), needle) + occurrences(tool.output.orEmpty(), needle)
            }

            val total = inText + inReasoning + inTools
            if (total == 0) return@mapNotNull null

            Match(
                messageID = message.id,
                count = total,
                // ⚠️ On nomme le champ **majoritaire** : c'est ce qui dit a l'utilisateur ou il va
                // devoir regarder. Un message qui matche surtout dans son raisonnement replie ne
                // se voit pas au premier regard, et c'est exactement ce qu'il faut signaler.
                field = when (maxOf(inText, inReasoning, inTools)) {
                    inText -> Match.Field.Text
                    inReasoning -> Match.Field.Reasoning
                    else -> Match.Field.Tool
                },
            )
        }

        return Result(query = query, matches = matches)
    }

    /** Compte les occurrences, insensible a la casse et aux accents. */
    private fun occurrences(haystack: String, normalizedNeedle: String): Int {
        if (haystack.isBlank()) return 0
        val normalizedHay = normalize(haystack)
        var count = 0
        var index = normalizedHay.indexOf(normalizedNeedle)
        while (index >= 0) {
            count++
            index = normalizedHay.indexOf(normalizedNeedle, index + normalizedNeedle.length)
        }
        return count
    }

    /**
     * Minuscules, accents retires.
     *
     * ⚠️ On ne modifie **jamais** le texte affiche — seulement la cle de comparaison. L'utilisateur
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
}
