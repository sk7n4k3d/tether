package sh.sk7.tether.domain.model

import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.Model

/**
 * **Les agents reellement selectionnables**, et celui que le serveur prend par defaut.
 *
 * ### Ce que dit la mesure (2026-09-26, notre opencode 2.0.x)
 *
 * `GET /api/agent` rend **23 agents**, repartis en `mode: all` (1), `primary` (6) et `subagent`
 * (16), dont 4 marques `hidden` (`compaction`, `title`, `summary`, `tank`).
 *
 * - `compaction`, `title` et `summary` sont **`primary` ET `hidden`** : ce sont des agents
 *   internes que le serveur s'invoque tout seul pour nommer les sessions et les resumer.
 *   Les proposer comme agent de travail, c'est proposer `Title` comme collegue.
 * - Les 16 `subagent` ne sont pas faits pour etre l'agent principal.
 * - Il reste donc **4 agents** : `general`, `build`, `plan`, `edit`.
 *
 * ### Pourquoi il n'y a pas de « modele par defaut »
 *
 * `/api/model/default` repond `glm-5.3-flash`, mais **une session fraichement creee a `model: null`**
 * (mesure : `POST /api/session {"location":…}` puis relecture). Le serveur ne resout agent et modele
 * qu'au **premier tour**. Afficher la route « default » comme si c'etait ce qu'on obtient serait
 * mentir — c'est une valeur de configuration, pas l'etat d'une session.
 *
 * ### Pourquoi il y a un « agent par defaut »
 *
 * L'agent de repli du serveur est materialise : `general` est le **seul** en `mode: "all"`, et il
 * porte `deepseek-v4.1-flash`. C'est ce que l'utilisateur obtient s'il ne choisit rien, et ca se
 * confirme a l'usage (il dit changer de modele a chaque session). On l'affiche donc, et on le
 * derive de la reponse — pas d'une constante codee en dur.
 */
object AgentCatalog {

    /**
     * Un agent est selectionnable s'il peut servir d'agent **principal** et n'est pas interne.
     *
     * ⚠️ Le test est `mode != "subagent"`, pas `mode == "primary"` : `general` est en `"all"`, et
     * c'est justement l'agent qu'on ne doit pas rater.
     */
    val Agent.selectable: Boolean
        get() = mode != "subagent" && !hidden

    /** Les agents selectionnables, dans l'ordre du serveur. */
    fun selectable(agents: List<Agent>): List<Agent> = agents.filter { it.selectable }

    /**
     * L'agent que le serveur prend quand on ne choisit rien : le seul en `mode: "all"`.
     *
     * `null` si le serveur n'en expose aucun — on n'invente alors **aucun** defaut, plutot que de
     * montrer un `general` codee en dur qui n'existerait pas sur un autre serveur.
     */
    fun serverDefault(agents: List<Agent>): Agent? = agents.firstOrNull { it.mode == "all" }

    /**
     * `providerID/id`, la meme forme que le libelle de modele d'une ligne de session.
     *
     * ⚠️ **Sans barre oblique orpheline** si le provider est absent : `/glm-5.3` avec une barre
     * en tete est un libelle casse, et un libelle casse se lit comme une panne. On affiche alors
     * le seul `id` — c'est moins informatif, mais ce n'est pas faux.
     */
    fun modelLabel(model: Model): String = withProvider(model.providerID, model.id)

    /** Ajoute le provider seulement s'il existe. */
    fun withProvider(providerID: String?, id: String): String =
        if (providerID.isNullOrBlank()) id else "$providerID/$id"

    /** Le modele **que cet agent amene**, ou `null` s'il herite du defaut. */
    fun Agent.carriedModelLabel(): String? = model?.let { withProvider(it.providerID, it.id) }
}
