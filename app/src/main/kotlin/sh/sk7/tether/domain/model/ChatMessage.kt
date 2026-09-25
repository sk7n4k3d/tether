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
    val tools: List<ToolCall> = emptyList(),
    /** Charge d'un contenu de forme inconnue : affichee, jamais jetee (Review Focus n°4). */
    val rawFallback: String? = null,
)

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
data class FormRequest(
    val id: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

enum class SessionStatus { Idle, Running, Succeeded, Failed, Interrupted }

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
