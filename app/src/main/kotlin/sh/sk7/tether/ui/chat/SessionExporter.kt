package sh.sk7.tether.ui.chat

import sh.sk7.tether.domain.model.ChatMessage
import sh.sk7.tether.domain.model.Role
import sh.sk7.tether.domain.model.SessionUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * **Export d'une conversation en Markdown.**
 *
 * ### Pourquoi Markdown, et pas PDF
 * C'est le format que les utilisateurs attendent reellement d'un export de conversation
 * technique, pour une raison concrete : il se **colle dans un depot**, se relit dans n'importe
 * quel editeur, et garde les blocs de code intacts. Un PDF perd la selection et la copie des
 * blocs — precise dans les plaintes d'utilisateurs (« le PDF a un texte invisible »).
 *
 * ⚠️ **On ne perd rien.** Les plaintes les plus vives portent sur les exporteurs qui suppriment
 * le raisonnement et les appels d'outils : ce sont precisement les elements qu'on veut relire
 * pour comprendre **comment** une reponse a ete produite. Ils sont donc inclus, mais **replies**
 * dans des blocs `<details>` — presents dans le fichier, discrets a la lecture.
 */
object SessionExporter {

    /**
     * ⚠️ **Cree a chaque appel, jamais partage.**
     *
     * `SimpleDateFormat` n'est **pas thread-safe** : un exemplaire partage dans un `object` produit
     * des dates fausses ou des exceptions des que deux threads l'utilisent. Aujourd'hui l'export
     * etait appele depuis un seul point (le thread de composition), donc le bug restait dormant —
     * mais l'export sur un gros fichier doit passer en arriere-plan, et c'est exactement ce
     * changement qui le reveillerait. Le cout de creation est negligeable (un export par clic).
     */
    private fun stamp(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE)

    /**
     * Rend une conversation complete en Markdown.
     *
     * @param title titre de la session, pour l'en-tete du fichier.
     * @param sessionID identifiant, inclus pour retrouver la session d'origine.
     */
    fun toMarkdown(title: String, sessionID: String, state: SessionUiState): String = buildString {
        appendLine("# $title")
        appendLine()
        // ⚠️ L'identifiant et la date sont dans le fichier : sans eux, un export qu'on relit
        // dans six mois ne peut plus etre rattache a sa session d'origine.
        appendLine("- Session : `$sessionID`")
        appendLine("- Exporté le : ${stamp().format(Date())}")
        state.cost?.takeIf { it > 0 }?.let {
            appendLine("- Coût : ${String.format(Locale.FRANCE, "%.2f", it)} \$")
        }
        state.tokens?.let {
            val total = it.input + it.output
            if (total > 0) appendLine("- Tokens : ${count(it.input)} in · ${count(it.output)} out")
        }
        appendLine()
        appendLine("---")
        appendLine()

        state.messages.forEach { message ->
            append(message.toMarkdown())
            appendLine()
        }
    }

    private fun ChatMessage.toMarkdown(): String = buildString {
        when (role) {
            Role.User -> {
                appendLine("## Toi")
                appendLine()
                appendLine(text)
            }
            Role.Assistant -> {
                appendLine("## Agent")
                appendLine()
                // Raisionnement replie : present, mais discret. `details` s'ouvre sur GitHub,
                // VS Code et la plupart des lecteurs Markdown.
                reasoning.takeIf { it.isNotBlank() }?.let {
                    appendLine("<details><summary>Raisonnement${
                        reasoningDurationLabel?.let { d -> " · $d" } ?: ""
                    }</summary>")
                    appendLine()
                    appendLine(it)
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }
                text.takeIf { it.isNotBlank() }?.let {
                    appendLine(it)
                    appendLine()
                }
                // ⚠️ Les outils sont inclus avec leur entree ET leur sortie : c'est le detail
                // qu'on vient chercher quand on relit un export (« quelle commande a tourne ? »).
                tools.forEach { tool ->
                    appendLine("<details><summary>outil : ${tool.name} — ${
                        when (tool.status) {
                            sh.sk7.tether.domain.model.ToolStatus.Running -> Res.of(R.string.cours_db22a7)
                            sh.sk7.tether.domain.model.ToolStatus.Succeeded -> "ok"
                            sh.sk7.tether.domain.model.ToolStatus.Failed -> "échec"
                        }
                    }${tool.durationLabel?.let { d -> " · $d" } ?: ""}</summary>")
                    appendLine()
                    tool.summary?.let {
                        appendLine("```")
                        appendLine(it)
                        appendLine("```")
                        appendLine()
                    }
                    tool.output?.takeIf { it.isNotBlank() }?.let {
                        appendLine("```")
                        appendLine(it)
                        appendLine("```")
                    }
                    appendLine("</details>")
                    appendLine()
                }
            }
        }
    }

    private fun count(value: Long): String = when {
        value >= 1_000_000 -> "%.1f M".format(Locale.FRANCE, value / 1_000_000.0)
        value >= 1_000 -> "%.1f k".format(Locale.FRANCE, value / 1_000.0)
        else -> value.toString()
    }
}
