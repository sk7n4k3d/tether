package sh.sk7.tether.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * DTOs du contrat API opencode V2, formes verifiees contre les fixtures reelles
 * capturees le 2026-09-24 (fixtures, docs/spike-api-v2-2026-09-24.md).
 */

/**
 * Enveloppe unique des listings. Deux formes coexistent cote serveur :
 * - `{data, cursor}` pour `/api/session` et `/api/session/{id}/message`
 * - `{location, data}` pour `/api/model`, `/api/agent`, `/api/provider`,
 *   `/api/permission/request`, `/api/form`
 * Les trois champs sont optionnels : un seul type decode les deux formes.
 * [T] est par defaut une liste ; pour `POST /api/session` (objet) voir [SessionEnvelope].
 */
@Serializable
data class DataEnvelope<T>(
    val data: List<T> = emptyList(),
    val cursor: Cursor? = null,
    val location: LocationInfo? = null,
)

/** `POST /api/session` renvoie `{data: <Session>}` = un OBJET, pas un tableau (spike §4). */
@Serializable
data class SessionEnvelope(val data: Session)

@Serializable
data class Cursor(val previous: String? = null, val next: String? = null)

/**
 * Une page d'un listing pagine, avec le curseur pour continuer.
 *
 * Les routes `GET /api/session` et `GET /api/session/{id}/message` paginent par defaut a
 * **50** elements : sans suivre [next], une liste est tronquee en silence (428 sessions
 * reelles le 2026-09-25). [next] est nul quand il n'y a plus rien a charger.
 */
data class CursorPage<T>(
    val data: List<T>,
    val next: String? = null,
    val previous: String? = null,
)

/**
 * **Une page d'historique, avec de quoi remonter d'un cran.**
 *
 * ⚠️ Distincte de [CursorPage] : ici `messages` est deja **remise a l'endroit**
 * (chronologique) et `cursorBack` a ete **valide** — il vaut `null` quand il n'y a plus rien
 * a charger.
 *
 * ⚠️ **`cursorBack == null` est la seule preuve fiable de fin d'historique.** Mesure du
 * 2026-09-25 : le serveur renvoie un `next` **non nul** sur la derniere page, deja courte ;
 * suivre ce curseur rend **0 message**. Une UI qui se fierait a `messages.size < limit` ou a
 * `next != null` resterait bloquee avec « charger plus » affiche pour toujours.
 */
data class HistoryPage(
    val messages: List<MessageDto>,
    val cursorBack: String?,
)

/** Reponse de `POST /api/session/{id}/interrupt` : `{interrupted}`. */
@Serializable
data class InterruptResponse(val interrupted: Boolean = false)

@Serializable
data class LocationInfo(val directory: String, val project: ProjectInfo? = null)

@Serializable
data class ProjectInfo(val id: String, val directory: String, val canonical: String? = null)

@Serializable
data class ModelRef(
    val id: String,
    val providerID: String,
    val variant: String? = null,
)

@Serializable
data class Tokens(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: CacheTokens = CacheTokens(),
)

@Serializable
data class CacheTokens(val read: Long = 0, val write: Long = 0)

@Serializable
data class TimeInfo(
    val created: Long? = null,
    val updated: Long? = null,
    val completed: Long? = null,
    val streamed: Long? = null,
    val idle: Long? = null,
    val viewed: Long? = null,
    /** `Session.time` : horodatage d'archivage. */
    val archived: Long? = null,
    /** `ContentPart.time` des parts `tool` : duree d'execution. */
    val ran: Long? = null,
)

@Serializable
data class Session(
    val id: String,
    val projectID: String? = null,
    val parentID: String? = null,
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val outcome: String? = null,
    val time: TimeInfo? = null,
    val location: LocationInfo? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class Model(
    val id: String,
    val modelID: String? = null,
    val providerID: String? = null,
    val name: String? = null,
    val variants: List<ModelVariant> = emptyList(),
)

@Serializable
data class ModelVariant(val id: String, val settings: JsonObject? = null)

/**
 * Agent de `GET /api/agent`. Seuls les champs d'affichage sont retenus : la reponse reelle
 * porte aussi `system` (prompt complet, plusieurs kilo-octets) et `permissions`, inutiles ici.
 * `mode` vaut `primary`, `subagent` ou `all`.
 *
 * ⚠️ **`model` n'est pas un detail** : chaque agent porte LE sien (mesure du 2026-09-26 sur notre
 * 2.0.x : `build` porte `glm-5.3`, `plan` et `edit` portent `deepseek-v4.1-flash`). Choisir un
 * agent, c'est donc choisir un modele sans le dire — d'ou son affichage dans le selecteur.
 * `null` = l'agent herite du defaut.
 */
@Serializable
data class Agent(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean = false,
    val model: ModelRef? = null,
)

/**
 * Message du listing. Le discriminant est [type] — PAS un enum `role`.
 * Types reels observes : `assistant`, `user`, `idle`, `synthetic`.
 */
@Serializable
data class MessageDto(
    val id: String,
    val type: String,
    val time: TimeInfo? = null,
    /** `user` du listing : texte a plat. */
    val text: String? = null,
    /** `user` via `POST /prompt` : le texte est dans `payload.text`. */
    val payload: PromptPayload? = null,
    /** `assistant` : parts typees `reasoning`, `text`, `tool`. */
    val content: List<ContentPart> = emptyList(),
    val agent: String? = null,
    val model: ModelRef? = null,
    /** `idle` : resultat du tour. */
    val outcome: String? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
    val finish: String? = null,
    /** `synthetic` : libelle humain de la part injectee. */
    val description: String? = null,
    val files: List<JsonObject> = emptyList(),
    val agents: List<JsonObject> = emptyList(),
    val metadata: JsonObject? = null,
)

@Serializable
data class PromptPayload(val text: String? = null)

/**
 * Part de `content[]` d'un message assistant.
 * - `reasoning` : `text` + `state`
 * - `text` : `text`
 * - `tool` : `id`, `name`, `executed`, `state` (`{status, input, content, metadata}`)
 */
@Serializable
data class ContentPart(
    val type: String,
    val id: String? = null,
    val name: String? = null,
    val text: String? = null,
    val executed: Boolean? = null,
    val state: JsonObject? = null,
    val time: TimeInfo? = null,
)

/**
 * Corps de `POST /api/session/{id}/prompt`.
 *
 * ⚠️ `files`, `agents` et `skills` sont **mesures** sur le serveur le 2026-09-25, pas deduits du
 * schema. Les formes qui marchent :
 *
 * ```
 * files:  [{"uri":"data:text/plain;base64,aGVsbG8=","name":"x"}]   -> 200
 * files:  [{"uri":"file:///home/utilisateur/.../settings.gradle.kts"}] -> 200
 * agents: [{"name":"build"}]                                        -> 200
 * skills: [{"id":"test-driven-development"}]                        -> 200
 * ```
 *
 * ⚠️ **Les chemins relatifs sont refuses** : `{"uri":"README.md"}` rend
 * `400 Invalid attachment URI`. Et `https://` rend `400 Unsupported attachment URI`. Seuls
 * `data:` (inline, que le serveur transmet en base64) et `file://` (chemin **absolu lisible par
 * le serveur**) sont acceptes.
 *
 * ⚠️ `delivery` est une **chaine** (`steer` | `queue`) et non un objet : c'est le meme piege que
 * dans l'inbox, la ou le schema annonce `Session.Inbox.Delivery` sans dire que c'est un enum de
 * chaines.
 */
@Serializable
data class PromptBody(
    val text: String,
    val files: List<PromptFileAttachment> = emptyList(),
    val agents: List<PromptAgentAttachment> = emptyList(),
    val skills: List<PromptSkillAttachment> = emptyList(),
)

/**
 * Une piece jointe de prompt — **la forme du serveur**, pas une commodite d'app.
 *
 * ⚠️ `PromptInput.FileAttachment` declare `uri` comme **seul requis** (les autres champs sont
 * optionnels) : on n'envoie donc ni `description` ni `mention` vides, qui ne servent a rien et
 * pourraient faire naitre un objet que le serveur n'attend pas.
 */
@Serializable
data class PromptFileAttachment(
    val uri: String,
    val name: String? = null,
)

/** `Prompt.AgentAttachment` : seul `name` est requis. */
@Serializable
data class PromptAgentAttachment(val name: String)

/** `PromptInput.SkillAttachment` : seul `id` est requis. */
@Serializable
data class PromptSkillAttachment(val id: String)

/**
 * Corps de `POST /api/experimental/session/{id}/skill`.
 *
 * ⚠️ `id` est le **seul champ requis** ; `resume` est optionnel cote serveur (l'omission fait
 * reprendre l'execution). On l'expose en nullable pour ne pas figer un defaut que le serveur n'a
 * pas exprime.
 */
@Serializable
data class SkillActivationBody(
    val id: String,
    val resume: Boolean? = null,
)

/** `PATCH /api/session/{id}` : `title` seul (`additionalProperties: false`). */
@Serializable
data class RenameSessionBody(val title: String)

/** Corps vide `{}` : `fork` sans `before` forke la session entiere, `compact` sans `id`. */
@Serializable
data class ForkSessionBody(val before: String? = null)

@Serializable
data class PromptEnvelope(val data: PromptAcceptance)

/** Reponse de `POST /prompt` : l'item d'inbox accepte (spike §10bis). */
@Serializable
data class PromptAcceptance(
    val id: String,
    val sessionID: String,
    val type: String,
    val payload: PromptPayload? = null,
    /** `steer` | `queue`. */
    val delivery: String? = null,
    val time: TimeInfo? = null,
)

/**
 * Corps de `POST /api/session`.
 *
 * ⚠️ **Aucun champ n'est obligatoire** (mesure : `GET /openapi.json` sur notre 2.0.x,
 * `SessionCreate.required` est absent). Avec `{"location":{…}}` seul, le serveur repond **200** et
 * cree la session avec `title`, `agent` et `model` a `null` — il ne_resout rien tant que le
 * premier tour n'a pas tourne.
 *
 * ⚠️ **`title` n'est plus envoye, et c'est volontaire.** Le serveur **ne reecrit pas** le titre
 * qu'on lui donne (mesure : `{"title":"Nouvelle session"}` est conserve tel quel). Envoyer un
 * titre generique empechait donc opencode d'en generer un descriptif. Un `null` explicite est
 * accepte au meme titre qu'un champ absent (mesure : HTTP 200, et la reponse ne contient aucune
 * cle `title`), donc `explicitNulls` reste a `true` — pas de changement global du `Json`.
 */
@Serializable
data class CreateSessionBody(
    val location: LocationBody,
    val title: String? = null,
    val model: ModelRef? = null,
    /** Optionnel : le serveur applique son agent par defaut si absent. */
    val agent: String? = null,
)

@Serializable
data class LocationBody(val directory: String)

/**
 * Demande de permission. Champs et noms alignes sur `Permission.Request` de l'OpenAPI
 * (`/openapi.json`, 2026-09-24). ⚠️ La forme n'a **jamais ete observee** en trafic reel
 * (auto-approbation, spike §8) : les requis du contrat sont declares non-null, le reste
 * reste tolérant.
 */
@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val source: PermissionSource? = null,
    val message: String? = null,
)

/** `Permission.Source` : decrit l'origine de la demande (aujourd'hui seule variante `tool`). */
@Serializable
data class PermissionSource(
    val type: String,
    val messageID: String? = null,
    val id: String? = null,
)

/** `GET /api/info`. */
@Serializable
data class ServerInfo(
    val version: String,
    val pid: Int? = null,
    val urls: List<String> = emptyList(),
    val paths: JsonObject? = null,
)

/** `Permission.Request` tel que le serveur le renvoie (formes relevees 2026-09-25). */
@Serializable
data class PermissionAskDto(
    val id: String,
    val sessionID: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val save: List<String> = emptyList(),
    val message: String? = null,
)

/** Corps de `POST /api/session/{id}/permission/{requestID}/reply`. */
@Serializable
data class PermissionReplyBody(
    val decision: String,
    val message: String? = null,
)

// ------------------------------------------------------------------
// Formulaires : l'agent pose une question et attend la reponse
// ------------------------------------------------------------------

/**
 * `Form.Info` — un formulaire **en attente**, tel que `GET /api/form` ou
 * `GET /api/session/{id}/form` le renvoie.
 *
 * ⚠️ **Formes mesurees sur le serveur 2.0.x le 2026-09-26**, pas deduites du schema :
 *
 * ```
 * POST /api/session/ses_…/form {"title":"probe","fields":[…]}
 * -> {"data":{"id":"frm_…","sessionID":"ses_…","title":"probe","fields":[…]}}
 * GET  /api/form?location[directory]=/tmp -> {"location":{…},"data":[Form.Info,…]}
 * ```
 *
 * ⚠️ **`sessionID` peut valoir `"global"`** (mesure : formulaire cree par
 * `POST /api/session/global/form`, rendu par `GET /api/form` et `GET /api/session/global/form`).
 * Ce n'est **pas** une session : aucun `GET /api/session/global` ni `/message` n'existe. Le code
 * ne doit donc jamais supposer qu'une session se trouve derriere cet identifiant.
 */
@Serializable
data class FormInfoDto(
    val id: String,
    val sessionID: String = "",
    val title: String = "",
    val metadata: JsonObject? = null,
    val fields: List<FormFieldDto> = emptyList(),
) {
    /** Vrai pour une elicitation MCP : `sessionID == "global"`, donc aucune session derriere. */
    val isGlobal: Boolean get() = sessionID == GLOBAL_SESSION_ID

    companion object {
        /** Identifiant de pseudo-session des elicitations MCP (mesure 2026-09-26). */
        const val GLOBAL_SESSION_ID: String = "global"
    }
}

/**
 * `Form.Detail` — un formulaire avec son **etat**. Renvoye par
 * `GET /api/session/{sessionID}/form/{formID}` (mesure : `{"data":{…,"state":{…}}}`).
 */
@Serializable
data class FormDetailDto(
    val id: String,
    val sessionID: String = "",
    val title: String = "",
    val metadata: JsonObject? = null,
    val fields: List<FormFieldDto> = emptyList(),
    val state: FormStateDto? = null,
)

/** `Form.State` : `pending` | `answered` (avec `answer`) | `cancelled`. */
@Serializable
data class FormStateDto(
    val status: String = "",
    /** Reponses deja donnees, quand `status == "answered"`. */
    val answer: JsonObject? = null,
) {
    val isPending: Boolean get() = status == "pending"
}

/** `Form.Option` : une valeur proposee, avec son libelle affichable. */
@Serializable
data class FormOptionDto(
    val value: String,
    val label: String = "",
    val description: String? = null,
)

/**
 * `Form.When` : une **condition d'affichage** portant sur un champ precedent.
 *
 * ⚠️ Mesure du 2026-09-26 : `value` est valide par le serveur contre les **options** du champ
 * vise (`"Form field condition value must be one of the field's options"` sinon). Il est donc
 * toujours d'un type simple — chaine, nombre ou booleen — ce que porte [JsonElement].
 */
@Serializable
data class FormWhenDto(
    val key: String,
    /** `eq` ou `neq` (`Form.When.op`). */
    val op: String = "eq",
    val value: JsonElement? = null,
)

/**
 * `Form.Field` — **union discriminee par [type]**, decodee a plat.
 *
 * ### Pourquoi une seule classe plate et non six types separes
 * Le discriminant est un **champ** (`type`) et non une propriete de schema : la reponse reelle
 * porte `"type":"string"` dans le meme objet que `minLength`. On decode donc tout ce qui peut
 * apparaitre, chaque sous-type remplissant seulement ses champs ; la projection **typee** se fait
 * ensuite dans `sh.sk7.tether.ui.forms.FormField.from(dto)`, ce qui rend l'union explicite cote
 * UI sans dependre de la configuration polymorphe globale du `Json`.
 *
 * ⚠️ `minimum`, `maximum` et `default` sont des [JsonElement] et **pas** des nombres : le schema
 * `Form.NumberField` autorise explicitement les chaines `"Infinity"`, `"-Infinity"` et `"NaN"`.
 * Typer en `Double?` ferait echouer le decodage de tout le formulaire des qu'un de ces cas
 * apparait — un formulaire entier devient indecodable pour une borne infinie.
 */
@Serializable
data class FormFieldDto(
    val key: String,
    val type: String,
    val title: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    /** Un champ cache n'est jamais affiche ; il reste neanmoins valide par le serveur. */
    val hidden: Boolean = false,
    /** Conditions d'affichage (`Form.When`). Toutes doivent etre vraies pour que le champ vive. */
    val `when`: List<FormWhenDto> = emptyList(),

    // ---- string ----
    /** `email` | `uri` | `date` | `date-time`. */
    val format: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val pattern: String? = null,
    val placeholder: String? = null,

    // ---- number | integer ----
    val minimum: JsonElement? = null,
    val maximum: JsonElement? = null,

    // ---- string | multiselect ----
    val options: List<FormOptionDto> = emptyList(),
    /** `string`/`multiselect` : accepte une valeur hors `options`. */
    val custom: Boolean = false,

    // ---- multiselect ----
    val minItems: Int? = null,
    val maxItems: Int? = null,

    // ---- external ----
    val url: String? = null,

    /**
     * Valeur par defaut, **polymorphe** : chaine, nombre, booleen ou tableau de chaines selon le
     * type du champ. `null` = pas de defaut.
     *
     * ⚠️ Mesure du 2026-09-26 : le serveur **n'applique pas** les defauts. Repondre `{}` a un
     * champ `{"type":"integer","default":7}` rend `204` avec `answer: {}` — le defaut n'est pas
     * inscrit. Une reponse pour un champ **requis** avec defaut rend `400 Missing required`. C'est
     * donc a l'app de pre-remplir et d'envoyer le defaut.
     */
    val default: JsonElement? = null,
)

/**
 * Corps de `POST /api/session/{sessionID}/form/{formID}/reply`.
 *
 * ⚠️ `Form.Answer` est un objet a **proprietes libres** (`additionalProperties: Form.Value`), et
 * non un tableau. On le porte comme [JsonObject] brut : c'est la forme exacte que le serveur
 * valide (cles = `key` des champs, valeurs = `Form.Value` = string | number | boolean | string[]).
 */
@Serializable
data class FormReplyBody(val answer: JsonObject)

/**
 * Filtre `parentID` de `GET /api/session`.
 *
 * ⚠️ L'OpenAPI declare `pattern: ^ses` **ou** `enum: ["null"]` : la valeur « racines seulement »
 * est donc la **chaine** `"null"`. Un `null` Kotlin (parametre omis) ne filtre rien du tout.
 * [SessionParent] rend cette distinction impossible a confondre.
 */
sealed interface SessionParent {
    /** La chaine reellement transmise. */
    val wire: String

    /** Enfants directs d'une session (`parentID=ses_…`). */
    data class Of(val sessionID: String) : SessionParent {
        override val wire: String get() = sessionID
    }

    /** Sessions racines uniquement (`parentID=null`, chaine litterale). */
    data object Roots : SessionParent {
        override val wire: String get() = "null"
    }
}

/** Corps de `PATCH /api/session/{id}/inbox/{inboxID}` : `steer` ou `queue`. */
@Serializable
data class InboxDeliveryBody(val delivery: String)

/** Les deux modes de livraison d'un message en file (`Session.Inbox.Delivery`). */
object InboxDelivery {
    /** Corrige le tour en cours : le message est injecte tout de suite. */
    const val STEER: String = "steer"

    /** Attend son tour : le message est remis a la fin de l'execution. */
    const val QUEUE: String = "queue"

    /** Vrai si la valeur est l'un des deux modes reconnus par le serveur. */
    fun isValid(value: String?): Boolean = value == STEER || value == QUEUE
}

/** `GET /api/session/{sessionID}/form/{formID}` : `{data: <Form.Detail>}` (un objet). */
@Serializable
data class FormDetailEnvelope(val data: FormDetailDto? = null)

/**
 * **Une valeur de reponse a un formulaire** (`Form.Value` de l'OpenAPI).
 *
 * ⚠️ Quatre formes seulement, et **pas une de plus** : `string`, `number`, `boolean`,
 * `string[]`. Le type est porte par la classe, jamais par une chaine devinee : c'est ce qui rend
 * impossible d'envoyer `"3"` (chaine) la ou le serveur attend `3` (nombre) — erreur mesuree le
 * 2026-09-26 : `400 Expected number for form field: age`.
 *
 * ⚠️ **Entiers et nombres sont deux variantes distinctes** : un `3.0` Kotlin serialise en `3.0`
 * et le serveur repond `400 Expected integer`. Un entier doit donc partir en entier.
 */
sealed interface FormAnswerValue {
    /** Forme JSON exacte envoyee au serveur. */
    fun toJson(): JsonElement

    /** Une chaine (`Form.StringField`). */
    data class Text(val value: String) : FormAnswerValue {
        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    /** Un nombre decimal (`Form.NumberField`). */
    data class Decimal(val value: Double) : FormAnswerValue {
        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    /** Un entier (`Form.IntegerField`). ⚠️ Serialise **sans** partie decimale. */
    data class Integer(val value: Long) : FormAnswerValue {
        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    /** Un booleen (`Form.BooleanField`, et l'acquittement d'un champ `external`). */
    data class Flag(val value: Boolean) : FormAnswerValue {
        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    /** Une selection multiple (`Form.MultiselectField`). */
    data class Items(val value: List<String>) : FormAnswerValue {
        override fun toJson(): JsonElement = JsonArray(value.map { JsonPrimitive(it) })
    }
}

// ---------------------------------------------------------------------------
// Le RPC du plugin Tether
//
// `/api/rpc/{id}/{method}` n'est pas dans le schema du serveur : c'est une surface
// ajoutee par les plugins. Le corps est `{ "input": … }` — un objet brut, pas un
// `@Serializable`, parce que l'input depend de la methode et que l'ecrire en JSON
// donnerait une classe par methode pour une forme identique.
// ---------------------------------------------------------------------------

/** `{ "input": { … } }` — l'enveloppe de toute appel RPC. */
data class RpcRequest(val input: JsonObject)

/**
 * Une erreur **nommee** du serveur.
 *
 * ⚠️ On ne remplace pas ce message par un code HTTP. `type` vaut `invalid` quand la
 * requete est malformee, et `unpaired` quand le jeton a expire ou a deja servi : l'app
 * doit pouvoir dire « rescane le QR » plutot que « le serveur a refuse ».
 */
class TetherRpcException(
    val type: String,
    override val message: String,
) : Exception(message) {

    /** Le jeton n'est plus valable : l'utilisateur doit rescanner un QR. */
    val jetonPerdu: Boolean get() = type == "unpaired"
}
