package sh.sk7.tether.domain.model

import kotlinx.serialization.json.JsonObject
import sh.sk7.tether.data.api.PermissionRequest
import sh.sk7.tether.data.api.Tokens

/**
 * Modele d'affichage d'une conversation.
 *
 * Ces types sont ceux que consomme l'UI : ils sont volontairement **plus pauvres**
 * que les DTO REST (`MessageDto`) et sont le produit du [EventReducer].
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    /**
     * Duree du raisonnement, formatee (« 695 ms »), telle que le **REST** la porte.
     *
     * ⚠️ Le serveur expose `content[].time.created` / `time.completed` sur la part
     * `reasoning` (mesure : 1790308830868 -> 1790308831563, soit 695 ms). Sans ce champ, la
     * ligne « Raisonnement » perdait sa duree **des qu'on rechargeait l'historique**, alors
     * que le direct l'affichait : une information qui disparait selon le chemin de lecture est
     * exactement ce qu'on ne veut pas.
     */
    val reasoningDurationLabel: String? = null,
    val tools: List<ToolCall> = emptyList(),
    /** Charge d'un contenu de forme inconnue : affichee, jamais jetee (Review Focus n°4). */
    val rawFallback: String? = null,
    /**
     * **Mode de livraison tant que ce message est ENCORE dans la file**, `null` sinon.
     *
     * ⚠️ C'est une mesure, pas une deduction : `Session.Inbox.Delivery` vaut **`steer`** (corrige
     * le tour en cours) ou **`queue`** (attend son tour). La capture du 2026-09-25 sur
     * `session.inbox.enqueued` donne exactement `"delivery":"queue"` / `"steer"`.
     *
     * ⚠️ La distinction est ce que les utilisateurs d'opencode reclamaient (issue #32157, 84 👍) et
     * que l'app jetait : « en file » sans le mode ne dit pas si le message va interrompre le tour
     * ou patienter.
     *
     * ⚠️ Le champ est **remis a `null` des que le message est livre** (`session.inbox.delivered`) :
     * il redevient un message utilisateur ordinaire. Le garder ferait afficher « en file » sur un
     * message deja remis a l'agent — c'est-a-dire l'inverse de la verite.
     */
    val delivery: String? = null,
) {
    /** Vrai tant que ce message attend encore dans la file (mode connu, non livre). */
    val isQueued: Boolean get() = delivery != null

    /** Vrai si ce message **corrige** le tour en cours plutot que d'attendre son tour. */
    val isSteering: Boolean get() = delivery == "steer"

    /**
     * **Vrai pour un message ecrit localement, pas encore confirme par le serveur.**
     *
     * ⚠️ Pourquoi cette propriete vit sur le modele et pas dans le ViewModel : la marque est
     * portee par l'**identifiant** (`local-…`, voir `ChatViewModel.OPTIMISTIC_PREFIX`), donc
     * toute comparaison ecrite ailleurs doit connaitre ce prefixe — et il y en avait six, dans
     * le ViewModel seul. La nommer ici, c'est une seule regle pour tout le monde.
     *
     * ⚠️ Elle repond a un bug reel (B5) : un optimiste est **absent du REST** (il n'existe que
     * localement), donc un filtre « ce qui n'est pas dans la fenetre REST » le considerait comme
     * de l'historique ancien et le remontait **tout en haut** de la conversation — alors qu'il
     * venait d'etre ecrit, en bas.
     */
    val isOptimistic: Boolean get() = id.startsWith(OPTIMISTIC_ID_PREFIX)

    companion object {
        /** Prefixe des identifiants locaux. ⚠️ Doit rester aligne sur le ViewModel. */
        const val OPTIMISTIC_ID_PREFIX: String = "local-"
    }
}

enum class Role { User, Assistant }

/**
 * Appel d'outil en cours ou termine.
 *
 * ⚠️ **La forme d'un evenement `session.tool.*` n'a jamais ete capturee** (le tour de
 * reference n'appelait pas d'outil). Ce modele est donc volontairement generique :
 * [raw] garde la charge exacte, l'UI ne doit rien supposer d'autre.
 */
data class ToolCall(
    val id: String,
    val name: String = "",
    val status: ToolStatus = ToolStatus.Running,
    val raw: String = "",
    /**
     * **Ce que l'outil a RECU** (`state.input`), en une ligne lisible.
     *
     * ⚠️ C'est la reponse directe au reproche « entre tes activations shell je vois ton
     * raisonnement mais pas ce que tu as ecrit » : le serveur envoie deja `input` (mesure sur
     * le serveur : `{"command": "uname -a; echo \"---\"; hostname"}`), l'app le **jetait**.
     * Une carte `read ok` sans chemin ne dit rien ; `read  /home/.../MEMORY.md` dit tout.
     *
     * `null` si l'outil n'a pas d'entree exploitable (on n'invente pas de resume).
     */
    val summary: String? = null,
    /**
     * **Ce que l'outil a PRODUIT** (`state.content[].text`), pour le corps deplie.
     *
     * ⚠️ Distinct de [raw] : [raw] est la charge JSON **brute** du flux SSE (utile au
     * diagnostic, illisible pour l'humain) ; [output] est le texte que l'outil a reellement
     * rendu. Deplier une carte doit montrer le resultat, pas du JSON.
     */
    val output: String? = null,
    /**
     * Horodatage serveur (`event.created`) du debut de l'outil. Sert a calculer la duree.
     *
     * ⚠️ On utilise l'horodatage **de l'evenement**, jamais `System.currentTimeMillis()` :
     *  - le reducer reste **pur** (voir le commentaire de classe d'`EventReducer`) ;
     *  - la duree mesure le temps **du serveur**, pas celui du telephone.
     */
    val startedAt: Long? = null,
    /**
     * Duree **mesuree par le serveur** (`time.ran` -> `time.completed`), deja formatee.
     *
     * ⚠️ Prioritaire sur le calcul local du reducer : le REST porte la duree reelle de
     * l'execution, y compris apres un rechargement d'historique ou le flux n'a rien vu passer.
     */
    val durationLabel: String? = null,
)

enum class ToolStatus { Running, Succeeded, Failed }

/**
 * Formulaire en attente. Forme de `form.created` **jamais capturee** : on conserve
 * la charge brute sans l'interpreter.
 */
/**
 * **Formulaire en attente d'une reponse** (`form.created`).
 *
 * ⚠️ **La forme est desormais MESUREE** (capture du 2026-09-26) : elle n'est plus une supposition.
 * La charge est `data.form`, et non la racine de `data` :
 *
 * ```
 * {"form":{"id":"frm_…","sessionID":"ses_…","title":"Probe Tether","fields":[…]}}
 * ```
 *
 * L'ancien code lisait `id` a la racine — donc **toujours `null`**, et le formulaire etait stocke
 * sans identifiant, sans titre, sans session. Il n'etait ni affichable ni remplissable. Un
 * formulaire qui attend bloque l'agent, exactement comme une permission : c'est ce qui rendait ce
 * bug couteux.
 *
 * ⚠️ [raw] garde la charge complete : un formulaire est du texte **non fiable**, et si une forme
 * evolue on veut pouvoir la relire au lieu de la jeter.
 */
data class FormRequest(
    val id: String? = null,
    /**
     * La session a qui le formulaire appartient, telle que le serveur la donne.
     *
     * ⚠️ Peut valoir **`"global"`** (elicitation MCP) : ce n'est **pas** une session. Ne jamais
     * supposer qu'il existe une session derriere — voir `FormInfoDto.isGlobal`.
     */
    val sessionID: String? = null,
    val title: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

enum class SessionStatus {
    Idle,
    Running,
    Succeeded,
    Failed,
    Interrupted;

    /**
     * **Le tour est fini** — quelle qu'en soit l'issue.
     *
     * ⚠️ Pourquoi sur l'enum et pas dans le reducer : trois appelants en ont besoin, dans trois
     * fichiers differents ([sh.sk7.tether.data.repository.EventReducer] pour ne pas rejouer un
     * statut terminal, le chat pour marquer vu ce qu'on vient de lire). Une copie privee par
     * fichier est exactement ce qui fait diverger une regle.
     *
     * ⚠️ `Idle` **n'est pas terminal** : c'est l'absence de tour, pas un tour fini. Confondre les
     * deux ferait marquer « vu » une conversation ou rien ne s'est termine.
     */
    fun isTerminal(): Boolean = this == Succeeded || this == Failed || this == Interrupted
}

/**
 * Etat d'un ecran de session, produit uniquement par [sh.sk7.tether.data.repository.EventReducer].
 *
 * Les champs `streaming*` sont l'etat **transitoire** du tour en cours ; a la cloture du
 * tour ils sont fusionnes dans [messages] puis remis a zero.
 */
data class SessionUiState(
    val sessionID: String,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String? = null,
    val streamingReasoning: String? = null,
    val streamingTools: List<ToolCall> = emptyList(),
    /** Id du message assistant du tour en cours (`assistantMessageID` des evenements). */
    val assistantMessageID: String? = null,
    val status: SessionStatus = SessionStatus.Idle,
    val pendingPermission: PermissionRequest? = null,
    val pendingForm: FormRequest? = null,
    /** Hashes du bloc d'instructions (`session.instructions.updated`, cles `core/...`). */
    val instructions: Map<String, String> = emptyMap(),
    /**
     * Duree du raisonnement du tour en cours, formatee (« 12 s »).
     *
     * Mesuree entre `session.reasoning.started` et `session.reasoning.ended`. La ligne
     * repliee du raisonnement DOIT porter une duree : sans elle, l'utilisateur ne sait pas
     * si le modele a reflechi 2 secondes ou 2 minutes (regle tiree de ChatGPT/Claude/Grok,
     * qui affichent tous « Thought for Xs »).
     */
    val reasoningDurationLabel: String? = null,
    /** Horodatage serveur du debut du raisonnement (interne : sert au calcul, non affiche). */
    val reasoningStartedAt: Long? = null,
    /**
     * Duree par appel d'outil, indexee par id de `ToolCall`.
     *
     * Même raison : un `shell` de 40 s sans duree passe pour de la reflexion du modele.
     */
    val toolDurations: Map<String, String> = emptyMap(),
    val cost: Double? = null,          // alimente par session.usage.updated
    val tokens: Tokens? = null,        // alimente par session.usage.updated
)
