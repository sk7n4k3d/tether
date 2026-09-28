package sh.sk7.tether.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DTO de `GET /api/experimental/session/stats`.
 *
 * ⚠️ Formes relevees sur le serveur le 2026-09-25 :
 * `{data: {range:{from,to}, sessions, subagents, prompts, steps, tokens:{...}, cost,
 *  tools:{mode, totals:{calls, succeeded, failed, unfinished}}, activeDays, streak,
 *  activity:[{date, steps}], models:[{model:{id, providerID, variant}, steps, tokens, cost}]}}`
 *
 * On garde les noms du serveur (y compris `providerID` dans un objet imbrique) plutot que de
 * renommer : une divergence de nom entre le DTO et l'API est ce qui produit les bugs les plus
 * couteux a diagnostiquer, parce qu'ils sont silencieux (le champ vaut simplement `null`).
 */
@Serializable
data class StatsEnvelope(val data: StatsDto? = null)

@Serializable
data class StatsDto(
    val range: StatsRangeDto? = null,
    val sessions: Int = 0,
    val subagents: Int = 0,
    val prompts: Int = 0,
    val steps: Int = 0,
    val tokens: StatsTokensDto? = null,
    val cost: Double = 0.0,
    val tools: StatsToolsDto? = null,
    val activeDays: Int = 0,
    val streak: Int = 0,
    val activity: List<StatsActivityDto> = emptyList(),
    val models: List<StatsModelDto> = emptyList(),
)

@Serializable
data class StatsRangeDto(val from: Long? = null, val to: Long? = null)

@Serializable
data class StatsTokensDto(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: StatsCacheDto? = null,
)

@Serializable
data class StatsCacheDto(val read: Long = 0, val write: Long = 0)

@Serializable
data class StatsToolsDto(val mode: String? = null, val totals: StatsToolsTotalsDto? = null)

@Serializable
data class StatsToolsTotalsDto(
    val calls: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val unfinished: Int = 0,
)

@Serializable
data class StatsActivityDto(val date: String, val steps: Int = 0)

@Serializable
data class StatsModelDto(
    val model: ModelRef? = null,
    val steps: Int = 0,
    val tokens: StatsTokensDto? = null,
    val cost: Double = 0.0,
)

/**
 * Un projet connu du serveur (`GET /api/project`).
 *
 * ⚠️ `canonical` est le chemin reel ; `id` en est le hash. On affiche le chemin, jamais le hash :
 * un identifiant technique ne dit rien a personne.
 */
@Serializable
data class ProjectDto(
    val id: String,
    val canonical: String? = null,
    val time: TimeInfo? = null,
    val sandboxes: List<JsonObject> = emptyList(),
)

/**
 * Une entree d'une **collection** de l'API (`/api/command`, `/api/skill`, `/api/mcp`,
 * `/api/plugin`, `/api/provider`).
 *
 * ⚠️ Ces routes renvoient `{location: {...}, data: [...]}` — une enveloppe **differente** de
 * `{data: [...]}` utilisee ailleurs. Confondre les deux donne un `data` vide sans erreur.
 */
@Serializable
data class LocatedEnvelope<T>(val location: JsonObject? = null, val data: List<T> = emptyList())

/** `GET /api/command` : une commande slash disponible. */
@Serializable
data class CommandDto(val name: String, val description: String? = null)

/** `GET /api/skill` : une competence chargeable. */
@Serializable
data class SkillDto(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

/** `GET /api/mcp` : un serveur MCP et son etat de connexion. */
@Serializable
data class McpServerDto(
    val name: String,
    val status: McpStatusDto? = null,
)

@Serializable
data class McpStatusDto(val status: String? = null)

/** `GET /api/plugin` : un plugin et son etat. */
@Serializable
data class PluginDto(
    val id: String,
    val features: JsonObject? = null,
    val state: PluginStateDto? = null,
)

@Serializable
data class PluginStateDto(val status: String? = null)

/** `GET /api/provider` : un fournisseur de modeles. */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String? = null,
    val activation: String? = null,
)

/** `GET /api/permission/saved` : une autorisation accordee et memorisee. */
@Serializable
data class SavedPermissionDto(
    val id: String,
    val action: String? = null,
    val resource: String? = null,
    val time: TimeInfo? = null,
)

/** Enveloppe simple `{data: [...]}` (permissions sauvegardees, projets). */
@Serializable
data class ListEnvelope<T>(val data: List<T> = emptyList())

// ------------------------------------------------------------------
// Diffs, contexte, worktrees, inbox, revert
// ------------------------------------------------------------------

/**
 * `FileDiff.Info` — **un fichier modifie**, avec son patch unifie.
 *
 * Formes relevees sur `/openapi.json` (2026-09-25) :
 * `{file, patch, additions, deletions, status: added|deleted|modified}`.
 *
 * ⚠️ Le `patch` est conserve **tel quel** (texte unifie `@@ ... @@`). On ne le re-parsera pas en
 * amont : la vue qui l'affiche a besoin des lignes exactes pour les colorer, et une
 * normalisation prematurée perdrait les lignes de contexte.
 */
@Serializable
data class FileDiffDto(
    val file: String,
    val patch: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
)

/** `GET /api/session/{id}/diff` et `GET /api/vcs/diff` : `{location, data:[FileDiff.Info]}`. */
@Serializable
data class VcsEnvelope<T>(val location: LocationInfo? = null, val data: List<T> = emptyList())

/** `Vcs.FileStatus` — un fichier touche, **sans** le patch. */
@Serializable
data class VcsFileStatusDto(
    val file: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "modified",
)

/**
 * `Vcs.Info` — **le repertoire est-il versionne ?**
 *
 * ⚠️ C'est le seul moyen de distinguer « aucune modification » de « pas un depot ». Mesure du
 * 2026-09-25 :
 *  - `/home/user`         -> `{"branch":{}}`, **aucun `provider`** : pas un depot ;
 *  - `Projects/tether`        -> `{"provider":"git","branch":{"current":"master",…}}`.
 *
 * Sans cette distinction, l'ecran afficherait « aucune modification » pour un repertoire ou
 * **rien ne peut etre annule** — deux faits opposes dits par la meme phrase.
 */
@Serializable
data class VcsInfoDto(
    val provider: String? = null,
    val branch: VcsBranchInfoDto? = null,
) {
    val isVersioned: Boolean get() = !provider.isNullOrBlank()
}

@Serializable
data class VcsBranchInfoDto(
    val current: String? = null,
    val default: String? = null,
)

/** `GET /api/vcs/branch` : `{location, data: [noms de branches]}`. */
@Serializable
data class VcsBranchEnvelope(val location: LocationInfo? = null, val data: List<String> = emptyList())

/**
 * `GET /api/model/default` : `{location, data: <Model.Info>|null}`.
 *
 * ⚠️ Enveloppe `{location, data}` avec `data` **objet ou null** — une quatrieme forme apres
 * `{data:[…]}`, `{location,data:[…]}` et `{location,data:{…}}`. On reutilise [VcsObjectEnvelope],
 * dont la forme est identique (`location` + `data` nullable) : deux classes pour le meme contrat
 * finiraient par diverger.
 */
typealias DefaultModelEnvelope = VcsObjectEnvelope<Model>

/** `Vcs.Base` : la base de revision deduite par le serveur (`name`, `ref`, `source`). */
@Serializable
data class VcsBaseDto(
    val name: String = "",
    val ref: String = "",
    /** `reflog` (deduit de l'historique de creation de branche) ou `default`. */
    val source: String = "",
)

/** `GET /api/vcs/base` : `{location, data: <Vcs.Base>|null}`. */
@Serializable
data class VcsBaseEnvelope(val location: LocationInfo? = null, val data: VcsBaseDto? = null)

/**
 * Une entree de la **fenetre de contexte** (`GET /api/session/{id}/context`).
 *
 * ### Formes RELEVEES sur 98 entrees reelles (2026-09-25), pas devinees
 * La premiere version de ce DTO avait ete ecrite d'apres l'OpenAPI seul, et elle **echouait au
 * decodage** — `summary` y etait type en booleen alors que le serveur y met une **chaine de
 * plusieurs kilo-octets** (le resume de compaction). Le decodage levait
 * `JsonConvertException` et l'ecran affichait « Contexte indisponible » sur une session saine.
 *
 * Cles reellement presentes, par type de message :
 *
 * | type | cles |
 * |---|---|
 * | `assistant` | `agent, content, cost, finish, model, time, tokens, id, type` |
 * | `compaction` | `summary, reason, recent, status, cost, model, time, tokens, id, type` |
 * | `user` | `text, files, agents, time, id, type` |
 * | `synthetic` · `system` | `text, description, metadata, time, id, type` |
 * | `idle` | `outcome, time, id, type` |
 *
 * ⚠️ **Seuls `assistant` et `compaction` portent `cost` et `tokens`.** Les autres n'en ont pas —
 * et c'est normal : ils ne declenchent pas d'appel au modele. Un ecran qui afficherait « 0 $ »
 * pour eux laisserait croire a des messages gratuits, alors que la question ne s'applique pas.
 */
@Serializable
data class ContextMessageDto(
    val type: String = "",
    val id: String = "",
    val time: TimeInfo? = null,

    // ---- champs de compaction : le message qui REMPLACE l'historique ----

    /**
     * Le **texte du resume de compaction**, plusieurs kilo-octets.
     *
     * ⚠️ Une chaine, jamais un booleen. C'est une donnee de plusieurs milliers de caracteres :
     * l'UI ne doit pas l'afficher d'un bloc, seulement sa **taille** — c'est la taille qui
     * explique ou est passe le contexte, pas le texte.
     */
    val summary: String? = null,
    /** Pourquoi la compaction a eu lieu (`manual`, `auto`…). */
    val reason: String? = null,
    /** `recent` est une **chaine** cote serveur, pas un booleen (mesure). */
    val recent: String? = null,
    /** `completed`, `running`… */
    val status: String? = null,

    // ---- champs des autres types ----

    /** `user`, `synthetic`, `system`. */
    val text: String? = null,
    /** `synthetic`, `system` : la nature du message injecte. */
    val description: String? = null,
    /** `assistant` : quel agent a produit le message. */
    val agent: String? = null,
    /** `idle` : l'issue du tour (`succeeded`, `error`…). */
    val outcome: String? = null,

    // ---- mesures, presentes sur assistant et compaction seulement ----

    val model: ModelRef? = null,
    val cost: Double? = null,
    val tokens: Tokens? = null,
)

/** `GET /api/session/{id}/context` : `{data: [ContextMessageDto]}`. */
@Serializable
data class ContextEnvelope(val data: List<ContextMessageDto> = emptyList())

/**
 * `Session.Revert` — ce qu'un `revert/stage` a prepare.
 *
 * ⚠️ `snapshot` est **opaque** : c'est lui qu'il faut renvoyer au serveur. On ne l'interprete
 * jamais, et l'app ne l'affiche pas — c'est un jeton, pas une donnee.
 */
@Serializable
data class RevertResultDto(
    val messageID: String = "",
    val partID: String? = null,
    val snapshot: String? = null,
    val files: List<FileDiffDto> = emptyList(),
)

@Serializable
data class RevertEnvelope(val data: RevertResultDto? = null)

/** Corps de `POST /api/session/{id}/revert/stage`. */
@Serializable
data class RevertStageBody(
    val messageID: String,
    /** `null` = le serveur decide (comportement par defaut). */
    val files: Boolean? = null,
)

/**
 * `Worktree.Directory` — un **arbre de travail isole**.
 *
 * ⚠️ Un worktree permet de faire travailler l'agent sur une copie de travail a part, sans
 * toucher a l'arbre principal. C'est la reponse a « je veux essayer sans risquer mon depot ».
 */
@Serializable
data class WorktreeDirDto(
    val directory: String = "",
)

/** `Worktree.Info` : `{directory}`. */
@Serializable
data class WorktreeInfoDto(val directory: String = "")

/** Corps de `POST /api/worktree`. */
@Serializable
data class WorktreeCreateBody(
    /**
     * ⚠️ **Dans le CORPS**, pas en query : `Worktree.CreateInput` le declare `required`. Le passer
     * en parametre rend `400 Missing key at ["projectID"]` — verifie sur le serveur.
     */
    val projectID: String,
    /** Point de depart : une branche ou un commit existant. */
    val from: String? = null,
    /** Nom de la nouvelle branche a creer. */
    val branch: String? = null,
    /** Chemin souhaite pour l'arbre. */
    val directory: String? = null,
    val name: String? = null,
)

/**
 * Corps de `DELETE /api/worktree`.
 *
 * ⚠️ Les **trois** champs sont `required` dans `Worktree.RemoveInput` — y compris `force`. Ne pas
 * l'envoyer rend un 400 (schema verifie sur `/openapi.json`).
 */
@Serializable
data class WorktreeRemoveBody(
    val projectID: String,
    val directory: String,
    val force: Boolean,
)

/** Corps de `POST /api/session/{id}/model` — changer le modele d'une session. */
@Serializable
data class SetModelBody(val model: ModelRef)

/** Corps de `POST /api/session/{id}/agent` — changer l'agent d'une session. */
@Serializable
data class SetAgentBody(val agent: String)

/**
 * Corps de `POST /api/session/{id}/command` — lancer une **commande slash**.
 *
 * ⚠️ Un nom inconnu est refuse par le serveur : la liste de `GET /api/command` est donc la
 * source de verite, y compris pour les arguments (le `text` porte ce qui suit le nom).
 */
@Serializable
data class SlashCommandBody(
    val name: String,
    val text: String = "",
)

/** Corps vide, pour les routes `POST` qui n'attendent rien. */
@Serializable
class EmptyBody

/**
 * `Session.Inbox.Info` — un message **en file**, pas encore remis a l'agent.
 *
 * ⚠️ `payload` est un objet polymorphe cote serveur (`user`, `synthetic`, `compaction`, `move`).
 * On ne garde que de quoi **afficher et annuler** : le type, le texte quand il existe, et
 * l'identifiant necessaire au `DELETE`.
 */
@Serializable
data class InboxItemDto(
    val id: String,
    val sessionID: String? = null,
    val type: String = "",
    val time: TimeInfo? = null,
    /**
     * Charge du message, **polymorphe** cote serveur (`Session.Inbox.UserPayload` &
     * compagnie). On la garde brute et on en extrait ce qu'on peut.
     *
     * ⚠️ Ce n'est **pas** une paresse : typer quatre variantes dont on a verifie aucune (l'inbox
     * etait vide sur ce serveur le 2026-09-25) produirait des DTO plausibles mais faux, et un
     * `text` toujours `null` sans que rien ne le signale. Ici, l'absence est visible.
     */
    val payload: JsonObject? = null,
    /**
     * ⚠️ **Une CHAINE**, pas un objet — et c'est une mesure, pas le schema.
     * `Session.Inbox.Delivery` est un enum : `steer` (corriger le tour en cours) ou `queue`
     * (attendre son tour). Le premier jet l'avait type en `JsonObject` d'apres
     * `Session.Inbox.Info`, dont le champ est bien un objet polymorphe ; mais `Delivery` lui-meme
     * est une chaine. Le decodage aurait echoue a la premiere entree en file.
     *
     * ⚠️ La distinction compte : `steer` corrige, `queue` attend. C'est exactement ce que les
     * utilisateurs d'opencode reclamaient (issue #32157, 84 👍) et l'app la jetait.
     */
    val delivery: String? = null,
) {
    /** Vrai si ce message corrige le tour en cours plutot que d'attendre son tour. */
    val isSteering: Boolean get() = delivery == "steer"

    /**
     * Texte affichable, cherche dans la charge.
     *
     * ⚠️ On cherche la cle `text` **et** `prompt` : selon le type d'entree, le serveur nomme
     * differemment ce qui est, pour l'utilisateur, le meme fait.
     */
    val displayText: String?
        get() = payload?.let { payload ->
            (payload["text"] ?: payload["prompt"])?.let { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        }
}

/**
 * `{location, data}` ou **`data` est un OBJET** et non une liste.
 *
 * ⚠️ Troisieme forme d'enveloppe du serveur, apres `{data:[...]}` et `{location, data:[...]}`.
 * Mesure : `GET /api/vcs` rend `{"location":{...},"data":{"provider":"git","branch":{...}}}`.
 * Utiliser l'enveloppe de liste ferait echouer le decodage — donc l'ecran conclurait a tort a une
 * absence de depot.
 */
@Serializable
data class VcsObjectEnvelope<T>(val location: LocationInfo? = null, val data: T? = null)

// ------------------------------------------------------------------
// Etat vivant : activite des sessions et shells
// ------------------------------------------------------------------

/**
 * `SessionActive` — **le seul etat d'execution que le serveur annonce**.
 *
 * Mesure du 2026-09-25 : `GET /api/session/active` rend
 * `{"data": {"ses_…": {"type": "running"}}}`. Le schema ne connait qu'**une** valeur, `running` :
 * une session absente de cette liste n'est donc **pas** en cours, et tout le reste (« termine »,
 * « pas vu », « en echec ») se deduit d'autres champs.
 *
 * ⚠️ C'est une **carte indexee par identifiant** (`patternProperties ^ses`), pas une liste. La
 * decoder en liste donne une erreur, pas une liste vide.
 */
@Serializable
data class ActiveSessionsDto(
    val data: Map<String, ActiveStateDto> = emptyMap(),
)

@Serializable
data class ActiveStateDto(val type: String = "")

/**
 * `Shell.Info` — une commande shell du serveur.
 *
 * ⚠️ **Le `sessionID` vit dans `metadata`**, pas a la racine. Mesure du 2026-09-25 :
 * `metadata = {"sessionID": "ses_…"}`. Le schema OpenAPI annonce seulement
 * `metadata: {type: object}` sans le detailler — s'y fier laissait croire qu'un shell n'etait
 * rattachable a rien, ce qui aurait impose une section separee sans raison.
 *
 * ⚠️ `time.started` est **obligatoire** cote serveur, `time.completed` ne l'est pas : un shell
 * vivant n'a pas d'horodatage de fin. On ne peut donc pas deduire son etat de l'absence de
 * `completed` — c'est `status` qui fait foi.
 */
@Serializable
data class ShellInfoDto(
    val id: String,
    val status: String = "",
    val command: String = "",
    val cwd: String? = null,
    val shell: String? = null,
    val file: String? = null,
    val pid: Long? = null,
    val exit: Double? = null,
    val metadata: JsonObject? = null,
    val time: ShellTimeDto? = null,
) {
    /** La session qui a lance ce shell, lue dans `metadata` (mesure). */
    val sessionID: String?
        get() = metadata?.let { md ->
            (md["sessionID"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
}

@Serializable
data class ShellTimeDto(
    val started: Long? = null,
    val completed: Long? = null,
)

/** `GET /api/shell` : `{location, data: [Shell.Info]}`. */
@Serializable
data class ShellEnvelope(
    val location: LocationInfo? = null,
    val data: List<ShellInfoDto> = emptyList(),
)

/**
 * Sortie d'un shell, lue **incrementalement**.
 *
 * ⚠️ `cursor` est le point de reprise : on relit a partir de la, pas depuis le debut. Un shell qui
 * tourne a `size = 0` — la sortie s'accumule, elle n'est pas vide « pour toujours ». Sans le
 * curseur, chaque lecture redonnerait tout ou rien.
 */
@Serializable
data class ShellOutputDto(
    val output: String = "",
    val cursor: Int = 0,
    val size: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class ShellOutputEnvelope(
    val location: LocationInfo? = null,
    val data: ShellOutputDto? = null,
)

/**
 * Corps de `POST /api/session/{id}/view` : on marque vu **jusqu'a cet `idle`**.
 *
 * ⚠️ C'est ce qui rend le suivi « pas vu » juste : on declare *jusqu'ou* on a regarde, pas
 * *que* on a regarde. Si un nouveau tour se termine apres, la session redevient « pas vu »
 * d'elle-meme — sans course, et sans etat local a maintenir.
 */
@Serializable
data class ViewBody(val idle: Long)

/**
 * `Pty` — un **terminal** ouvert sur la machine.
 *
 * ⚠️ Mesure : `POST /api/session/{id}/shell` rend **500** sur ce serveur (bug du plugin
 * `cc-safety-net`), alors que `POST /api/pty` rend **200**. Un client qui ne regarderait que les
 * shells conclurait « rien ne tourne » alors qu'un terminal est ouvert.
 *
 * ⚠️ Pas de `sessionID` : un terminal n'appartient pas a une session. On ne fabrique pas ce lien.
 */
@Serializable
data class PtyInfoDto(
    val id: String,
    val title: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val status: String = "",
    val pid: Long? = null,
    val exitCode: Int? = null,
)

/** `GET /api/pty` : `{location, data: [Pty]}`. */
@Serializable
data class PtyEnvelope(val location: LocationInfo? = null, val data: List<PtyInfoDto> = emptyList())
