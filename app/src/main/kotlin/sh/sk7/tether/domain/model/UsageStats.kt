package sh.sk7.tether.domain.model

/**
 * Les **statistiques d'usage** d'un serveur opencode, telles que le serveur les calcule.
 *
 * ⚠️ Aucun de ces chiffres n'est estime ou recompose cote app : tout vient de
 * `GET /api/experimental/session/stats`. Le serveur sait des choses que l'app ne pourrait pas
 * reconstruire fidelement (le **streak**, les **jours actifs**, la repartition par modele sur
 * une plage), et c'est exactement pour ca qu'on les lui demande au lieu de les recalculer sur
 * les sessions qu'on a en memoire — qui sont paginees, donc partielles.
 */
data class UsageStats(
    /** Bornes de la plage couverte. */
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val sessions: Int = 0,
    /** Sous-agents : le chiffre qui montre l'ampleur reelle des delegations. */
    val subagents: Int = 0,
    val prompts: Int = 0,
    /** Etapes d'execution : une unite d'activite plus fine que le message. */
    val steps: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cost: Double = 0.0,
    val toolCalls: Int = 0,
    val toolSucceeded: Int = 0,
    val toolFailed: Int = 0,
    val toolUnfinished: Int = 0,
    /** Nombre de jours distincts avec de l'activite. */
    val activeDays: Int = 0,
    /** Jours consecutifs d'activite — la seule « gamification » du serveur, on la garde. */
    val streak: Int = 0,
    /** Activite par jour, du plus ancien au plus recent. */
    val activity: List<DailyActivity> = emptyList(),
    /** Repartition par modele, la plus couteuse en premier. */
    val models: List<ModelUsage> = emptyList(),
) {
    val totalTokens: Long get() = inputTokens + outputTokens + reasoningTokens

    /**
     * Taux d'echec des outils, en pourcentage.
     *
     * ⚠️ Un outil **inacheve** compte comme non reussi : c'est le cas qui trahit le plus de
     * problemes (execution coupee, serveur tombe), et l'exclure flatterait le chiffre.
     */
    val toolFailureRate: Double?
        get() = if (toolCalls == 0) null else (toolFailed + toolUnfinished) * 100.0 / toolCalls

    val tokenCacheRatio: Double?
        get() = if (inputTokens == 0L) null else cacheReadTokens * 100.0 / inputTokens

    val hasAnything: Boolean get() = sessions > 0 || steps > 0
}

/** Une journee d'activite. `steps` est le seul compteur expose par le serveur. */
data class DailyActivity(
    /** Format `YYYY-MM-DD`, tel que le serveur le rend. */
    val date: String,
    val steps: Int,
)

/**
 * Consommation d'un modele sur la plage.
 *
 * ⚠️ On garde le `providerID` separe du modele : deux modeles de meme nom chez deux providers
 * n'ont pas le meme prix, et les coller masquerait lequel des deux on paie.
 */
data class ModelUsage(
    val model: String,
    val provider: String,
    val steps: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cost: Double,
)
