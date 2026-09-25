package sh.sk7.tether.ui.sessions

import java.util.Locale
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.ui.theme.NodeState

/**
 * Une session prete a afficher.
 *
 * ⚠️ **Principe de non-mensonge** (`docs/design-soul.md` §3) : l'API opencode expose le
 * cout, les tokens (dont le cache), le provider, l'agent, l'etat d'execution et la
 * filiation parent/enfant. Tether n'en cache **aucun** — c'est un instrument, pas un chat.
 * Avant cette version, l'app ignorait `parentID`, `tokens` et `outcome` : 67 % des sessions
 * (297 sur 437) etaient des sous-agents invisibles, presentes a plat comme des sessions
 * normales. C'etait un mensonge par omission.
 */
data class SessionItem(
    val id: String,
    val title: String,
    val timestamp: Long?,
    val agent: String?,
    val modelLabel: String?,
    val costLabel: String?,
    /** Cout brut, necessaire au cumul parent + enfants. `null` si non facture. */
    val costValue: Double? = null,
    val directory: String?,
    /** Id du parent si c'est une sous-session (delegation de sous-agent). */
    val parentID: String? = null,
    /** Nombre de tokens en cache — c'est 94,7 % du volume reel sur le profil de Bastien. */
    val cacheReadLabel: String? = null,
    /** Tokens d'entree + sortie, forme compacte (« 3,4 M »). */
    val tokensLabel: String? = null,
    /** Etat d'execution : `succeeded`, `interrupted`, ou null si en cours. */
    val outcome: String? = null,
    /** Vrai si c'est une sous-session (rendue indente sous son parent). */
    val isSub: Boolean = false,
    /**
     * Nombre de sous-agents rattaches. Sert au chevron de depliage : `3` affiche « 3 »
     * a cote du parent, `0` n'affiche aucun chevron (rien a deplier).
     */
    val childCount: Int = 0,
    /**
     * Cout **cumule de la branche** : la session + toutes ses sous-sessions.
     *
     * ⚠️ **Principe de non-mensonge applique au budget** : un parent affiche a 1,29 $ dont les
     * delegations ont coute 3 $ de plus **ment sur ce qu'a reellement coute la tache**. Or 297
     * des 437 sessions sont des sous-agents : ignorer leur cout sous-estime massivement la
     * depense reelle, et c'est exactement la donnee sur laquelle Bastien doit arbitrer.
     *
     * Reste `null` si rien n'a ete facture (pas de « 0,00 $ » decoratif).
     */
    val branchCostLabel: String? = null,
    /** Cout cumule brut, pour les tests et le tri. */
    val branchCost: Double? = null,
    /** Nombre de sous-agents dont le cout est inclus dans [branchCostLabel] (transparence). */
    val branchCostChildren: Int = 0,
    /**
     * Vrai si un descendant de cette session est en cours.
     *
     * ⚠️ Un parent termine dont un sous-agent tourne doit quand meme **allumer son fil** :
     * sinon une delegation active reste totalement invisible depuis la liste, et on croit
     * que rien ne se passe (297 des 437 sessions sont des sous-agents).
     */
    val branchActive: Boolean = false,
) {
    /**
     * Etat du nœud sur le fil (voir [sh.sk7.tether.ui.theme.TetherRail]).
     *
     * ### ⚠️ Ce champ ne dit PAS si la session tourne
     * Il dit **ce que le serveur a annonce du dernier tour** : un `outcome` absent signifie
     * seulement « pas de verdict enregistre ». Mesure sur une capture reelle de 50 sessions :
     * **10 ont `outcome == null`** sans tourner pour autant.
     *
     * ⚠️ Confondre les deux faisait pulser 10 nœuds « en cours » sur des sessions au repos — et
     * l'utilisateur apprend a ignorer le signal, ce qui detruit la valeur de tout l'ecran.
     *
     * **La seule source d'etat d'execution est `/api/session/active`**, lue par
     * [sh.sk7.tether.data.activity.ActivityMonitor] et exposee en `FleetState`. L'apparence qui
     * depend de l'activite doit venir de la, jamais d'ici.
     *
     * ⚠️ On garde ce champ pour ce qu'il sait vraiment : **afficher l'issue** d'un tour termine
     * (succes, echec, interruption). C'est une information differente et legitime.
     */
    val nodeState: NodeState
        get() = when (outcome) {
            "succeeded" -> NodeState.Done
            "interrupted" -> NodeState.Failed
            "failed" -> NodeState.Failed
            // ⚠️ `null` (aucun verdict enregistre) n'est PAS « en cours » : c'est « on ne sait
            // pas », et un etat inconnu se dessine comme un etat neutre, pas comme une activite.
            null -> NodeState.Idle
            else -> NodeState.Done
        }
}

/**
 * Traduit un DTO [Session] en [SessionItem].
 *
 * Regles :
 * - l'age se base sur `time.updated`, avec repli sur `time.created` (spike §5) ;
 * - un cout nul **ou** nul (0.0) n'affiche rien : afficher « 0,00 $ » sur une session
 *   neuve serait du bruit ;
 * - le modele est affiche `provider/id`, forme la plus courte qui reste non ambigue ;
 * - le parent est conserve : c'est lui qui permet d'afficher l'arbre au lieu d'une liste.
 */
object SessionListMapper {

    fun toItem(session: Session): SessionItem {
        val model = session.model
        val tokens = session.tokens
        return SessionItem(
            id = session.id,
            title = session.title?.takeIf { it.isNotBlank() } ?: "Sans titre",
            timestamp = session.time?.updated ?: session.time?.created,
            agent = session.agent?.takeIf { it.isNotBlank() },
            modelLabel = model?.let { "${it.providerID}/${it.id}" },
            costLabel = formatCost(session.cost),
            costValue = session.cost?.takeIf { it > 0.0 },
            directory = session.location?.directory,
            parentID = session.parentID,
            cacheReadLabel = tokens?.cache?.read?.takeIf { it > 0 }?.let(::formatCount),
            tokensLabel = tokens?.let { formatTokens(it.input, it.output) },
            outcome = session.outcome,
            isSub = session.parentID != null,
        )
    }

    fun toItems(sessions: List<Session>): List<SessionItem> = sessions.map(::toItem)

    /**
     * Ordonne les sessions **en arbre plutot qu'en liste plate**.
     *
     * Une session parente est suivie immediatement de ses sous-sessions (les delegations
     * de sous-agents), qui representent 67 % de l'activite reelle.
     *
     * @param includeSubs si faux, seules les sessions principales sont rendues.
     */
    fun toTree(sessions: List<Session>, includeSubs: Boolean = true): List<SessionItem> {
        val items = toItems(sessions)
        val childrenByParent = items.filter { it.isSub }.groupBy { it.parentID }
        val roots = items.filter { !it.isSub }

        // ⚠️ Une sous-session dont le parent n'est pas dans la page courante est orpheline :
        // on la remonte a la racine plutot que de la perdre (la pagination peut couper).
        val rootIds = roots.map { it.id }.toHashSet()
        val orphans = items.filter { it.isSub && it.parentID !in rootIds }

        // Un parent « allume » si lui-meme ou l'un de ses enfants tourne.
        val activeParents = items.filter { it.isSub && it.nodeState == NodeState.Active }
            .mapNotNull { it.parentID }
            .toHashSet()

        val ordered = mutableListOf<SessionItem>()
        for (root in roots + orphans) {
            val lit = root.id in activeParents
            val kids = childrenByParent[root.id].orEmpty()

            // --- COUT CUMULE DE LA BRANCHE ---
            // On additionne le cout des enfants au cout du parent : c'est ce que la tache a
            // reellement coute. On garde le nombre d'enfants inclus pour pouvoir l'annoncer
            // (un total sans son perimetre est un chiffre malhonnete).
            val ownCost = root.costValue ?: 0.0
            val kidsCost = kids.sumOf { it.costValue ?: 0.0 }
            val total = ownCost + kidsCost

            ordered += root.copy(
                branchActive = lit,
                childCount = kids.size,
                branchCost = if (total > 0.0) total else null,
                branchCostLabel = if (total > 0.0) formatCost(total) else null,
                branchCostChildren = kids.count { (it.costValue ?: 0.0) > 0.0 },
            )
            if (includeSubs) {
                ordered += kids
            }
        }
        return ordered
    }

    private fun formatCost(cost: Double?): String? {
        if (cost == null || cost <= 0.0) return null
        return String.format(Locale.FRANCE, "%.2f $", cost)
    }

    /** « 1 240 » ou « 3,4 M » — lisible d'un coup d'œil sans compter les zeros. */
    private fun formatCount(value: Long): String = when {
        value >= 1_000_000_000L -> String.format(Locale.FRANCE, "%.1f Md", value / 1_000_000_000.0)
        value >= 1_000_000L -> String.format(Locale.FRANCE, "%.1f M", value / 1_000_000.0)
        value >= 1_000L -> String.format(Locale.FRANCE, "%.1f k", value / 1_000.0)
        else -> value.toString()
    }

    /** Entree + sortie : ce qui a reellement ete facture ce tour-la. */
    private fun formatTokens(input: Long, output: Long): String {
        val total = input + output
        if (total <= 0) return ""
        return "${formatCount(input)} in · ${formatCount(output)} out"
    }
}
