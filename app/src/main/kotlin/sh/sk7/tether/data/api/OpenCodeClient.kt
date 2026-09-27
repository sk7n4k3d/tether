package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Client REST du serveur opencode V2.
 *
 * Le serveur se protege par HTTP basic (`opencode:<motdepasse>`), pas par Bearer.
 * Les identifiants sont resolus **a chaque requete** via [CredentialsProvider] : c'est ce
 * qui permet a `ConnectionStore` de changer le mot de passe sans reconstruire le client.
 *
 * ⚠️ Noms de parametres query **reels** (verifies sur `/openapi.json`) :
 * - `GET /api/session` : `directory` (PAS `location[directory]`, silencieusement ignore)
 * - `GET /api/session/{id}/message` : `limit`, `order`, `cursor`, `type` (aucun `location`)
 * - `GET /api/model` · `/api/agent` : `location[directory]` (`style: deepObject`)
 *   ⚠️ `?location=/chemin` (valeur simple) est **rejete** par le serveur (`InvalidRequestError`)
 * - `POST /api/session` : le `location` va dans le **corps**
 * - `POST /api/session/{id}/prompt` : **aucun** parametre query
 */
class OpenCodeClient(
    baseUrl: String,
    private val credentialsProvider: CredentialsProvider,
    private val http: HttpClient,
) {
    /**
     * Variante a identifiants fixes (tests, appel ponctuel de diagnostic). Le client de
     * production doit preferer [CredentialsProvider] pour rester reactif aux reglages.
     */
    constructor(baseUrl: String, credentials: BasicAuthCredentials, http: HttpClient) :
        this(baseUrl, FixedCredentialsProvider(credentials), http)

    private val baseUrl: String = baseUrl.trimEnd('/')

    suspend fun info(): ServerInfo {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/info") { auth(credentials) }.body()
    }

    /**
     * Une page de `GET /api/session`. ⚠️ La route pagine par defaut a **50** : sans suivre
     * `cursor.next`, la liste est tronquee en silence (428 sessions reelles le 2026-09-25).
     * `limit` est honore par le serveur (verifie : 200 par page en 4 pages).
     *
     * ⚠️ **`parentID`** (mesure du 2026-09-26, 468 sessions sur le serveur) :
     *  - absent           -> toutes les sessions (200 sur la 1re page, dont **97 enfants**) ;
     *  - `parentID=<ses>` -> uniquement les enfants directs de cette session (32 pour
     *    `ses_f2b4097ecffe8dMdMYfZlf1eiS`, **toutes** verifiees enfants de ce parent) ;
     *  - `parentID=null`  -> uniquement les sessions **racines** (159, aucune avec `parentID`).
     *
     * ⚠️ La valeur litterale est bien la **chaine** `"null"`, pas une absence de parametre :
     * l'oublier renvoie tout, l'ecrire filtre. C'est le genre de confusion qui produit un ecran
     * ou les sous-agents se melangent aux conversations — d'ou [SessionParent], qui rend
     * l'intention explicite au lieu de la laisser a une chaine magique.
     */
    suspend fun sessionsPage(
        directory: String,
        limit: Int? = null,
        cursor: String? = null,
        parent: SessionParent? = null,
    ): CursorPage<Session> {
        val credentials = credentialsProvider.credentials()
        val envelope = http.get("$baseUrl/api/session") {
            auth(credentials)
            parameter("directory", directory)
            limit?.let { parameter("limit", it) }
            cursor?.let { parameter("cursor", it) }
            parent?.let { parameter("parentID", it.wire) }
        }.body<DataEnvelope<Session>>()
        return envelope.toPage()
    }

    suspend fun sessions(
        directory: String,
        limit: Int? = null,
        cursor: String? = null,
        parent: SessionParent? = null,
    ): List<Session> = sessionsPage(directory, limit, cursor, parent).data

    /**
     * Une page de `GET /api/session/{id}/message`.
     *
     * ⚠️ `order` ne s'applique qu'a la **premiere** page : l'OpenAPI precise « Do not combine
     * with order », le curseur porte deja le sens. Verifie : `order=asc` puis `cursor.next`
     * pagine vers l'avant **sans recouvrement**.
     */
    suspend fun messagesPage(
        sessionID: String,
        limit: Int? = null,
        cursor: String? = null,
        order: String? = null,
        type: String? = null,
    ): CursorPage<MessageDto> {
        val credentials = credentialsProvider.credentials()
        val envelope = http.get("$baseUrl/api/session/$sessionID/message") {
            auth(credentials)
            limit?.let { parameter("limit", it) }
            cursor?.let { parameter("cursor", it) }
            order?.let { parameter("order", it) }
            type?.let { parameter("type", it) }
        }.body<DataEnvelope<MessageDto>>()
        return envelope.toPage()
    }

    suspend fun messages(
        sessionID: String,
        limit: Int? = null,
        cursor: String? = null,
        order: String? = null,
        type: String? = null,
    ): List<MessageDto> = messagesPage(sessionID, limit, cursor, order, type).data

    /** `POST /api/session/{id}/interrupt` : stoppe l'execution en cours. `{interrupted}`. */
    suspend fun interrupt(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/interrupt") {
            auth(credentials)
        }.body<InterruptResponse>().interrupted
    }

    suspend fun models(location: String): List<Model> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/model") {
            auth(credentials)
            parameter("location[directory]", location)
        }.body<DataEnvelope<Model>>().data
    }

    /**
     * `GET /api/agent` : meme parametre deepObject que `/api/model`
     * (`location[directory]`, style `deepObject` dans l'OpenAPI). Sans lui, `data: []`
     * sans erreur.
     */
    suspend fun agents(location: String): List<Agent> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/agent") {
            auth(credentials)
            parameter("location[directory]", location)
        }.body<DataEnvelope<Agent>>().data
    }

    /** `GET /api/session/{id}` : renvoie `{data: <Session>}` (un objet), pour le titre. */
    suspend fun session(sessionID: String): Session {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID") {
            auth(credentials)
        }.body<SessionEnvelope>().data
    }

    /**
     * `POST /api/session` renvoie `{data: <Session>}` = un OBJET, pas un tableau.
     *
     * ⚠️ **Aucun parametre n'est obligatoire.** [model] et [agent] passent quand on les fournit
     * (le dernier choix memorise) et restent `null` sinon : le serveur resout l'agent et le modele
     * au premier tour, pas a la creation (mesure du 2026-09-26 sur le 2.0.x : `agent`, `model` et
     * `title` remontent a `null` sur une session fraiche). `title` n'est plus du tout un
     * parametre : le serveur ne reecrit pas le titre qu'on lui donne, donc lui en envoyer un
     * empechait la generation automatique. Voir [CreateSessionBody].
     */
    suspend fun createSession(
        location: String,
        model: ModelRef? = null,
        agent: String? = null,
    ): Session {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(
                CreateSessionBody(
                    location = LocationBody(location),
                    model = model,
                    agent = agent?.takeIf { it.isNotBlank() },
                ),
            )
        }.body<SessionEnvelope>().data
    }

    /** `POST /prompt` : le texte est dans `payload.text`, la reponse est `{data: <msg_*>}`. */
    suspend fun prompt(sessionID: String, text: String): PromptAcceptance =
        prompt(sessionID, PromptBody(text = text))

    /**
     * `POST /api/session/{id}/prompt` **avec pieces jointes**.
     *
     * ⚠️ Aucun parametre query : tout est dans le corps (`text`, `files`, `agents`, `skills`).
     * Les formes acceptees ont ete mesurees (§ [PromptBody]) : `data:` inline ou `file://` absolu
     * pour un fichier, jamais un chemin relatif ni une URL `https`.
     */
    suspend fun prompt(sessionID: String, body: PromptBody): PromptAcceptance {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/prompt") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body<PromptEnvelope>().data
    }

    /**
     * `PATCH /api/session/{id}` — seuls `title` et `permissions` sont acceptes.
     *
     * ⚠️ **204 sans corps** (verifie sur le serveur) : ne pas tenter de decoder une reponse.
     * Le faire levait `NoTransformationFoundException` et l'app annoncait « Échec de la
     * connexion » alors que le renommage avait **reussi** — une erreur inventee.
     */
    suspend fun renameSession(sessionID: String, title: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.patch("$baseUrl/api/session/$sessionID") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(RenameSessionBody(title = title))
        }
        return response.status.isSuccess()
    }

    /** `DELETE /api/session/{id}` — 204 sans corps. */
    suspend fun deleteSession(sessionID: String) {
        val credentials = credentialsProvider.credentials()
        http.delete("$baseUrl/api/session/$sessionID") { auth(credentials) }
    }

    /**
     * `POST /api/session/{id}/fork` — `{}` forke la session entiere (`before` optionnel).
     *
     * ⚠️ La reponse est la **nouvelle** session, pas l'ancienne.
     */
    suspend fun forkSession(sessionID: String): Session {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/fork") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(ForkSessionBody())
        }.body<SessionEnvelope>().data
    }

    /** `POST /api/session/{id}/compact` — `{}` suffit (`id`/`delivery` optionnels). */
    suspend fun compactSession(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/compact") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(ForkSessionBody())
        }
        return response.status.isSuccess()
    }

    // ------------------------------------------------------------------
    // Statistiques et inventaire du serveur
    // ------------------------------------------------------------------

    /**
     * `GET /api/experimental/session/stats` : les statistiques d'usage.
     *
     * ⚠️ `from` (millisecondes) restreint la plage — verifie sur le serveur : avec
     * `from=1790000000000`, `sessions` passe de 146 a 73 et l'activite de 13 a 5 jours.
     * Sans borne, le serveur renvoie tout son historique.
     */
    suspend fun stats(location: String, fromMillis: Long? = null): StatsDto? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/experimental/session/stats") {
            auth(credentials)
            // ⚠️ Cette route n'accepte **pas** `location` : ses parametres sont
            // `from, to, project, timezone, tools` (releve sur /openapi.json). Un
            // `location[directory]` y serait ignore.
            //
            // ⚠️ On envoie `directory` quand meme ? NON : on ne l'envoie pas, parce que le serveur
            // ne le declare pas. Les stats restent donc **celles de tout le serveur**, ce qui est
            // correct pour un tableau de bord d'usage et vaut mieux qu'un filtre fantome.
            fromMillis?.let { parameter("from", it) }
        }.body<StatsEnvelope>().data
    }

    /**
     * Les **collections** de l'API, qui partagent toutes l'enveloppe `{location, data}`.
     *
     * ⚠️ Enveloppe **differente** de `{data}` utilisee ailleurs : se tromper donne une liste vide
     * sans erreur, ce qui est le pire des modes d'echec pour un ecran d'inventaire.
     */
    private suspend inline fun <reified T> located(path: String, location: String): List<T> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl$path") {
            auth(credentials)
            at(location)
        }.body<LocatedEnvelope<T>>().data
    }

    suspend fun commands(location: String): List<CommandDto> = located("/api/command", location)

    suspend fun skills(location: String): List<SkillDto> = located("/api/skill", location)

    suspend fun mcpServers(location: String): List<McpServerDto> = located("/api/mcp", location)

    suspend fun plugins(location: String): List<PluginDto> = located("/api/plugin", location)

    suspend fun providers(location: String): List<ProviderDto> = located("/api/provider", location)

    /** `GET /api/permission/saved` : les autorisations memorisees, revocables. */
    suspend fun savedPermissions(location: String): List<SavedPermissionDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/permission/saved") {
            auth(credentials)
            at(location)
        }.body<ListEnvelope<SavedPermissionDto>>().data
    }

    /**
     * `DELETE /api/permission/saved/{id}` : revoque une autorisation memorisee.
     *
     * ⚠️ C'est une action de **securite** : elle retire un droit qui avait ete accorde. Elle
     * n'est jamais automatique — seul l'utilisateur la declenche.
     */
    suspend fun revokePermission(permissionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.delete("$baseUrl/api/permission/saved/$permissionID") {
            auth(credentials)
        }
        return response.status.isSuccess()
    }

    /** `GET /api/project` : les projets connus du serveur. */
    suspend fun projects(): List<ProjectDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/project") {
            auth(credentials)
        }.body<ListEnvelope<ProjectDto>>().data
    }

    /**
     * `GET /api/permission/request` : les demandes d'autorisation en attente **sur le serveur**.
     *
     * ⚠️ Enveloppe `{location, data}` **et non** `{data}` — comme `/api/command` et `/api/mcp`.
     * Se tromper d'enveloppe donne une liste vide sans erreur, donc une app qui croit qu'il n'y a
     * rien a approuver alors que l'agent est bloque.
     */
    suspend fun permissionRequests(location: String): List<PermissionAskDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/permission/request") {
            auth(credentials)
            at(location)
        }.body<LocatedEnvelope<PermissionAskDto>>().data
    }

    /** `GET /api/session/{id}/permission` : les demandes d'une session precise. */
    suspend fun sessionPermissionRequests(sessionID: String): List<PermissionAskDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/permission") {
            auth(credentials)
        }.body<ListEnvelope<PermissionAskDto>>().data
    }

    /**
     * `POST /api/session/{id}/permission/{requestID}/reply`.
     *
     * ⚠️ `decision` est une des trois chaines `once`, `always`, `reject` (enum
     * `Permission.Reply` de l'OpenAPI). Toute autre valeur est refusee par le serveur.
     */
    suspend fun replyPermission(
        sessionID: String,
        requestID: String,
        decision: String,
        message: String?,
    ): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/permission/$requestID/reply") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(PermissionReplyBody(decision = decision, message = message))
        }
        return response.status.isSuccess()
    }


    // ------------------------------------------------------------------
    // Diffs, contexte, worktrees, revert
    // ------------------------------------------------------------------

    /**
     * `GET /api/session/{id}/diff` : les fichiers modifies par la session, **patch inclus**.
     *
     * ⚠️ `from` / `to` sont des identifiants de MESSAGE : sans eux, le serveur compare l'etat
     * initial a l'etat courant. C'est ce qu'on veut pour une vue d'ensemble.
     */
    suspend fun sessionDiff(sessionID: String): List<FileDiffDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/diff") {
            auth(credentials)
        }.body<VcsEnvelope<FileDiffDto>>().data
    }

    /**
     * `GET /api/vcs/diff` : le diff du depot, hors d'une session.
     *
     * ⚠️ **`mode` est REQUIS** (mesure : sans lui, `400 Missing key at ["mode"]`). Trois valeurs :
     * `working` (modifications non commitees), `branch` (depuis la base de la branche),
     * `committed` (les derniers commits). Il n'y a pas de defaut cote serveur — on l'expose donc
     * en parametre obligatoire plutot que d'en inventer un.
     */
    suspend fun vcsDiff(location: String, mode: String): List<FileDiffDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/vcs/diff") {
            auth(credentials)
            at(location)
            parameter("mode", mode)
        }.body<VcsEnvelope<FileDiffDto>>().data
    }

    /**
     * `GET /api/vcs` : le repository et sa branche.
     *
     * ⚠️ `provider` **absent** signifie « pas un depot ». C'est l'information qui permet de ne pas
     * mentir sur une absence de modifications.
     */
    suspend fun vcsInfo(location: String): VcsInfoDto? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/vcs") {
            auth(credentials)
            at(location)
        }.body<VcsObjectEnvelope<VcsInfoDto>>().data
    }

    /** `GET /api/vcs/status` : les fichiers touches, sans les patches (plus leger). */
    suspend fun vcsStatus(location: String): List<VcsFileStatusDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/vcs/status") {
            auth(credentials)
            at(location)
        }.body<VcsEnvelope<VcsFileStatusDto>>().data
    }

    /**
     * `GET /api/session/{id}/context` : **ce qui occupe la fenetre de contexte**.
     *
     * ⚠️ Enveloppe `{data}` simple ici — pas de `location`. La liste renvoyee n'est **pas**
     * l'historique : c'est ce qui sera envoye au modele au prochain tour.
     */
    suspend fun sessionContext(sessionID: String): List<ContextMessageDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/context") {
            auth(credentials)
        }.body<ContextEnvelope>().data
    }

    /**
     * `POST /api/session/{id}/revert/stage` : **prepare** un retour en arriere.
     *
     * ⚠️ En deux temps (`stage` puis `commit`) et pas en un : c'est ce qui permet de **montrer**
     * les fichiers qui seraient touches avant de le faire. Un revert en un seul appel
     * modifierait le depot sans confirmation — inacceptable pour une action destructive.
     */
    suspend fun revertStage(sessionID: String, messageID: String): RevertResultDto? {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/session/$sessionID/revert/stage") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(RevertStageBody(messageID = messageID))
        }.body<RevertEnvelope>().data
    }

    /** `POST /api/session/{id}/revert/commit` : applique le retour prepare par [revertStage]. */
    suspend fun revertCommit(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/revert/commit") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(EmptyBody())
        }
        return response.status.isSuccess()
    }

    /**
     * `DELETE /api/session/{id}/revert` : **annule** le retour prepare (sans avoir committe).
     *
     * ⚠️ C'est le seul chemin de sortie d'un revert prepare : sans lui, un `stage` laisse la
     * session dans un etat intermediaire sans moyen d'en sortir depuis l'app.
     */
    suspend fun revertDiscard(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.delete("$baseUrl/api/session/$sessionID/revert") {
            auth(credentials)
        }
        return response.status.isSuccess()
    }

    /**
     * `GET /api/worktree` : les arbres de travail connus.
     *
     * ⚠️ **`projectID`, pas un chemin.** Mesure : `directory=/home/utilisateur` rend
     * `400 InvalidRequestError Missing key at ["projectID"]`. Le hash se recupere par
     * [location]. L'appelant le fournit donc, et c'est pour ca que la signature prend un
     * `projectID` et non un `directory`.
     */
    suspend fun worktrees(projectID: String): List<WorktreeDirDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/worktree") {
            auth(credentials)
            parameter("projectID", projectID)
        }.body<List<WorktreeDirDto>>()
    }

    /**
     * `POST /api/worktree` : cree un arbre de travail isole.
     *
     * ⚠️ `projectID` va dans le **corps** (`Worktree.CreateInput` le declare `required`), pas en
     * query. Le passer en parametre rend `400 Missing key at ["projectID"]`.
     */
    suspend fun createWorktree(
        projectID: String,
        name: String?,
        directory: String? = null,
    ): WorktreeInfoDto {
        val credentials = credentialsProvider.credentials()
        return http.post("$baseUrl/api/worktree") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(WorktreeCreateBody(projectID = projectID, name = name, directory = directory))
        }.body<WorktreeInfoDto>()
    }

    /**
     * `DELETE /api/worktree` : retire un arbre de travail.
     *
     * ⚠️ `projectID`, `directory` ET `force` sont les trois `required` de `Worktree.RemoveInput`.
     * `force` est expose plutot que fige : un arbre avec des modifications non commitees **ne peut
     * pas** etre retire sans forcer, et cet arbitrage appartient a l'utilisateur.
     */
    suspend fun removeWorktree(projectID: String, directory: String, force: Boolean): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.delete("$baseUrl/api/worktree") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(
                WorktreeRemoveBody(
                    projectID = projectID,
                    directory = directory,
                    force = force,
                ),
            )
        }
        return response.status.isSuccess()
    }

    /** `POST /api/session/{id}/model` : change le modele **de cette session**. */
    suspend fun setSessionModel(sessionID: String, model: ModelRef): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/model") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(SetModelBody(model = model))
        }
        return response.status.isSuccess()
    }

    /** `POST /api/session/{id}/agent` : change l'agent **de cette session**. */
    suspend fun setSessionAgent(sessionID: String, agent: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/agent") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(SetAgentBody(agent = agent))
        }
        return response.status.isSuccess()
    }

    /**
     * `POST /api/session/{id}/command` : lance une **commande slash**.
     *
     * ⚠️ La commande n'est pas un simple texte prefixe : elle a un **nom** que le serveur valide
     * contre sa liste. Envoyer `/review` comme texte de prompt ne declencherait rien.
     */
    suspend fun runCommand(sessionID: String, name: String, text: String = ""): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/command") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(SlashCommandBody(name = name, text = text))
        }
        return response.status.isSuccess()
    }

    /** `GET /api/session/{id}/inbox` : les messages **en file** d'attente. */
    suspend fun sessionInbox(sessionID: String): List<InboxItemDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/inbox") {
            auth(credentials)
        }.body<ListEnvelope<InboxItemDto>>().data
    }

    /**
     * `DELETE /api/session/{id}/inbox/{inboxID}` : **annule un message en file**.
     *
     * ⚠️ C'est la reponse directe a l'issue #4821 (126 👍) : un message soumis pendant que
     * l'agent tourne part en file et, sans cette route, **ne peut plus etre annule**.
     */
    suspend fun dismissInbox(sessionID: String, inboxID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.delete("$baseUrl/api/session/$sessionID/inbox/$inboxID") {
            auth(credentials)
        }
        return response.status.isSuccess()
    }

    /**
     * `PATCH /api/session/{id}/inbox/{inboxID}` : **change le mode de livraison** d'un message
     * en file (`{"delivery":"steer"|"queue"}`).
     *
     * ⚠️ Mesure du 2026-09-26 sur le serveur 2.0.x :
     *  - `PATCH {"delivery":"queue"}` sur un item `steer` -> **204 sans corps**, et `GET /inbox`
     *    rend aussitot `"delivery":"queue"` ;
     *  - valeur hors enum -> **400** `Expected Session.Inbox.Delivery at ["delivery"]` ;
     *  - id inconnu (ou deja livre) -> **409** `Pending input cannot change to queue: msg_…`.
     *
     * ⚠️ On ne tente **jamais** de decoder la reponse : c'est un 204, exactement comme
     * `renameSession` dont le decodage forcait `NoTransformationFoundException` sur une operation
     * pourtant reussie. L'appelant doit ensuite **relire la file** : ce sont l'item et son mode
     * reels qui font foi, pas l'`isSuccess`.
     *
     * ⚠️ `delivery` est une **chaine** (`Session.Inbox.Delivery`), pas un objet — meme piege que
     * dans [InboxItemDto.delivery].
     */
    suspend fun updateInboxDelivery(
        sessionID: String,
        inboxID: String,
        delivery: String,
    ): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.patch("$baseUrl/api/session/$sessionID/inbox/$inboxID") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(InboxDeliveryBody(delivery = delivery))
        }
        return response.status.isSuccess()
    }

    // ------------------------------------------------------------------
    // Formulaires
    // ------------------------------------------------------------------

    /**
     * `GET /api/form` : les formulaires **pendants du repertoire**.
     *
     * ⚠️ Enveloppe `{location, data}` (declaration `style: deepObject` sur `location`) — comme
     * `/api/permission/request`. Se tromper d'enveloppe rend une liste vide **sans erreur**, donc
     * un ecran qui affirme « rien a repondre » alors que l'agent est bloque.
     *
     * ⚠️ Mesure du 2026-09-26 : `GET /api/form?location[directory]=/home/utilisateur` ne voit **pas**
     * un formulaire cree a `/tmp/opencode` — le filtre par repertoire est reel. Un formulaire
     * `sessionID:"global"` (elicitation MCP) suit cette meme regle.
     */
    suspend fun forms(location: String): List<FormInfoDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/form") {
            auth(credentials)
            at(location)
        }.body<LocatedEnvelope<FormInfoDto>>().data
    }

    /**
     * `GET /api/session/{sessionID}/form` : les formulaires pendants **d'une session**.
     *
     * ⚠️ Enveloppe `{data}` **simple** ici, pas `{location, data}` (releve `/openapi.json` et
     * confirme sur le serveur) : deux formes d'enveloppe pour un meme mot « form », c'est
     * exactement la confusion qui rend un bug silencieux.
     *
     * ⚠️ Un `sessionID` inconnu rend **404** `SessionNotFoundError` (mesure). `"global"` n'est pas
     * une erreur : la route accepte cette pseudo-session et rend ses formulaires.
     */
    suspend fun sessionForms(sessionID: String): List<FormInfoDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/form") {
            auth(credentials)
        }.body<ListEnvelope<FormInfoDto>>().data
    }

    /**
     * `GET /api/session/{sessionID}/form/{formID}` : le formulaire **avec son etat**
     * (`pending` | `answered` | `cancelled`), et les reponses deja donnees.
     *
     * ⚠️ Enveloppe `{data}` simple, contenant un **objet** ([FormDetailDto]) et non une liste.
     */
    suspend fun sessionForm(sessionID: String, formID: String): FormDetailDto? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/$sessionID/form/$formID") {
            auth(credentials)
        }.body<FormDetailEnvelope>().data
    }

    /**
     * `POST /api/session/{sessionID}/form/{formID}/reply` : **repond a un formulaire pendant**.
     *
     * ⚠️ Mesure du 2026-09-26, les cinq reponses reelles :
     *  - **204** sans corps, formulaire accepte ;
     *  - **400** `FormInvalidAnswerError` : champ inconnu, type attendu different, champ requis
     *    manquant, `pattern`/`minLength`/`maxLength`/`minimum`/`maximum` viole, option hors liste,
     *    champ `when` inactif (« Form field is not active ») envoyе, externе non acquitte ;
     *  - **404** `FormNotFoundError` (ou `SessionNotFoundError`) ;
     *  - **409** `FormAlreadySettledError` : formulaire deja repondu ou annule.
     *
     * ⚠️ **L'envoi est un 204 : on ne decode rien.** Tenter de decoder, c'est reproduire le bug
     * de `renameSession`.
     *
     * ⚠️ On ne fabrique **jamais** de valeur pour « que ca passe » : le serveur valide finement
     * (`External form field must be acknowledged`, `Expected integer`, `Too many selections`…) et
     * une valeur inventee reviendrait en 400 — ou, pire, ferait accepter une reponse fausse qui
     * debloque l'agent sur une intention qui n'est pas celle de l'utilisateur.
     */
    suspend fun replyForm(sessionID: String, formID: String, answer: JsonObject): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/form/$formID/reply") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(FormReplyBody(answer = answer))
        }
        return response.status.isSuccess()
    }

    /**
     * `GET /api/model/default` : **le modele que le serveur applique** quand une session n'en
     * choisit pas.
     *
     * ⚠️ Mesure du 2026-09-26 : `{"data":{"id":"glm-5.3-flash","modelID":"glm-5.3-flash",
     * "providerID":"ollama-cloud",…}}`. C'est la verite du serveur, et elle peut differer du
     * « premier modele du catalogue » que l'app derivait jusqu'ici cote client.
     *
     * ⚠️ `data` peut etre **null** (schema : `Model.Info | null`), par exemple avant configuration.
     */
    suspend fun defaultModel(location: String): Model? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/model/default") {
            auth(credentials)
            at(location)
        }.body<DefaultModelEnvelope>().data
    }

    /**
     * `GET /api/vcs/base` : la **base de revision** que le serveur deduit de l'historique.
     *
     * ⚠️ Mesure du 2026-09-26 : `{"data":{"name":"master","ref":"refs/heads/master",
     * "source":"default"}}`. Peut valoir `null` (avant le premier commit, ou fournisseur sans
     * metadonnee de base).
     *
     * ⚠️ La doc serveur est explicite : un historique ambigu **exige** une base explicite sur les
     * requetes de diff. On expose donc cette valeur pour la **montrer**, jamais pour la substituer
     * en silence a un choix de l'utilisateur.
     */
    suspend fun vcsBase(location: String): VcsBaseDto? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/vcs/base") {
            auth(credentials)
            at(location)
        }.body<VcsBaseEnvelope>().data
    }

    /**
     * `POST /api/experimental/session/{id}/wait` : **attend que la boucle d'agent devienne idle**.
     *
     * ⚠️ Mesure du 2026-09-26 : sur une session idle, rend **204 en 8 ms** ; sur une session en
     * cours, **bloque jusqu'a la fin** (9,1 s mesures) ; sur un id inconnu, **404**.
     *
     * ⚠️ **204 sans corps** : on ne decode rien, on lit le statut (meme piege que `renameSession`).
     *
     * ⚠️ Le client **n'impose aucun timeout** : c'est la couche qui appelle ([OpenCodeGateway]) qui
     * borne l'attente (parametre `timeoutMillis`). Laisser le moteur HTTP couper la requete
     * rendrait un `SocketTimeoutException` — un echec apparent la ou l'agent travaille simplement
     * encore.
     */
    suspend fun waitForIdle(sessionID: String, timeoutMillis: Long): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/experimental/session/$sessionID/wait") {
            auth(credentials)
            // ⚠️ Le `HttpTimeout` global plafonne a 20 s : sans cette surcharge, l'attente serait
            // coupee au bout de 20 s et rendrait un **timeout** la ou l'agent travaille encore.
            // On aligne donc le timeout de CETTE requete sur celui voulu, avec une marge de 1 s
            // pour que l'abandon decide par l'appelant (avecTimeoutOrNull) arrive en premier.
            timeout { requestTimeoutMillis = timeoutMillis + 1_000 }
        }
        return response.status.isSuccess()
    }


    /**
     * **Le repertoire, nomme une seule fois pour toutes les routes `deepObject`.**
     *
     * ⚠️ Bug reel corrige ici, et il etait **silencieux** : les routes d'inventaire
     * (`/api/command`, `/api/skill`, `/api/mcp`, `/api/model`, `/api/agent`, `/api/provider`,
     * `/api/form`) declarent leur parametre `location` en **`style: deepObject`** — donc
     * `location[directory]=...`. Passer simplement `directory=...` est **ignore sans erreur** et
     * le serveur retombe sur son repertoire de travail courant.
     *
     * Mesure du 2026-09-25 : avec `directory=/tmp`, `/api/command` repond `location=/home/utilisateur`
     * (le cwd) ; avec `location[directory]=/tmp`, il repond `location=/tmp`. Les sept routes
     * marchaient **par accident**, parce que le cwd du serveur se trouvait etre le repertoire
     * configure. Des que les deux different, l'app afficherait les donnees du mauvais projet sans
     * que rien ne le signale — le pire des modes d'echec.
     *
     * ⚠️ Exception : `GET /api/session` (`sessionsPage`) attend bien `directory` **simple**
     * (`style: None`). C'est la seule, et c'est pour ca qu'elle ne passe pas par ici.
     */
    private fun HttpRequestBuilder.at(location: String) {
        parameter("location[directory]", location)
    }

    /**
     * `GET /api/location` : le repertoire **tel que le serveur le voit**, avec son `project.id`.
     *
     * ⚠️ Indispensable pour `/api/worktree`, qui n'accepte pas un chemin mais un **`projectID`**
     * (mesure : `directory=` y rend `400 Missing key at ["projectID"]`). Le projectID est un hash
     * qu'on ne peut pas fabriquer — il faut le demander.
     */
    suspend fun location(directory: String): LocationInfo {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/location") {
            auth(credentials)
            at(directory)
        }.body()
    }


    // ------------------------------------------------------------------
    // Etat vivant : activite, file d'attente, shells
    // ------------------------------------------------------------------

    /**
     * `GET /api/session/active` : **la seule source d'etat d'execution** du serveur.
     *
     * ⚠️ Enveloppe `{data: {ses_…: {type}}}` — une **carte**, pas une liste. Et le seul type
     * annonce est `running` : une session absente n'est pas en cours.
     */
    suspend fun activeSessions(location: String): Map<String, ActiveStateDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/session/active") {
            auth(credentials)
            parameter("directory", location)
        }.body<ActiveSessionsDto>().data
    }

    /**
     * `GET /api/shell` : les commandes shell connues du serveur, **terminees comprises**.
     *
     * ⚠️ Enveloppe `{location, data}` (deepObject sur `location`). C'est la seule facon de savoir
     * ce qui tourne encore en arriere-plan : l'API n'expose aucun « background » separe, un shell
     * de fond est simplement un shell dont le `status` vaut `running`.
     */
    suspend fun shells(location: String): List<ShellInfoDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/shell") {
            auth(credentials)
            at(location)
        }.body<ShellEnvelope>().data
    }

    /**
     * `GET /api/pty` : les **terminaux** ouverts.
     *
     * ⚠️ C'est la seule source de « travail de fond » qui fonctionne sur ce serveur :
     * `POST /api/session/{id}/shell` rend 500 (bug de plugin). Un client qui ne regarderait que
     * les shells conclurait a tort que rien ne tourne.
     */
    suspend fun terminals(location: String): List<PtyInfoDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/pty") {
            auth(credentials)
            at(location)
        }.body<PtyEnvelope>().data
    }

    /**
     * `GET /api/shell/{id}/output` : la sortie d'un shell.
     *
     * ⚠️ `cursor` permet de ne lire que le **nouveau** depuis un point connu. Sans lui, chaque
     * lecture redonnerait depuis le debut — ce qui, sur une commande verbeuse, ferait relire des
     * centaines de kilo-octets a chaque rafraichissement.
     */
    suspend fun shellOutput(shellID: String, cursor: Int? = null): ShellOutputDto? {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/shell/$shellID/output") {
            auth(credentials)
            cursor?.let { parameter("cursor", it) }
        }.body<ShellOutputEnvelope>().data
    }

    /**
     * `POST /api/session/{id}/view` : marque la session comme **vue jusqu'a cet `idle`**.
     *
     * ⚠️ On envoie l'horodatage `idle` **du serveur**, pas l'heure locale : c'est ce qui garantit
     * que le serveur compare deux grandeurs de meme origine. Envoyer l'heure du telephone ferait
     * apparaitre ou disparaitre un « pas vu » selon le fuseau.
     */
    suspend fun markViewed(sessionID: String, idle: Long): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/view") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(ViewBody(idle = idle))
        }
        return response.status.isSuccess()
    }

    /**
     * `POST /api/session/{id}/background` : **deplace les outils bloquants en arriere-plan**.
     *
     * ⚠️ C'est la reponse a « je ne vois pas les commandes en arriere-plan » : sans cet appel, un
     * outil de premier plan tient la session ouverte alors qu'il pourrait continuer en observation.
     * L'appel est un no-op si rien ne bloque (doc du serveur : « Idle requests are a no-op »), donc
     * le declencher est sans risque.
     */
    suspend fun backgroundTools(sessionID: String): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/session/$sessionID/background") {
            auth(credentials)
        }
        return response.status.isSuccess()
    }

    /**
     * `POST /api/experimental/session/{id}/skill` : **active une competence** dans la session.
     *
     * ⚠️ Mesure du 2026-09-25 : rend **204 sans corps**. Ne pas tenter de decoder une reponse —
     * c'est exactement le bug de `renameSession`, ou `NoTransformationFoundException` faisait
     * annoncer un echec sur une operation reussie.
     *
     * ⚠️ L'effet est **asynchrone** : la competence est ajoutee comme message et l'execution
     * reprend (`resume` par defaut). Le resultat arrive par le flux, comme un prompt.
     */
    suspend fun activateSkill(sessionID: String, skillID: String, resume: Boolean? = null): Boolean {
        val credentials = credentialsProvider.credentials()
        val response = http.post("$baseUrl/api/experimental/session/$sessionID/skill") {
            auth(credentials)
            contentType(ContentType.Application.Json)
            setBody(SkillActivationBody(id = skillID, resume = resume))
        }
        return response.status.isSuccess()
    }

    /**
     * `GET /api/fs/list` : les enfants **directs** d'un chemin.
     *
     * ⚠️ `path` est **relatif au `location`** ; un chemin absolu rend un `404 FileNotFoundError`
     * (mesure). Absent, le serveur liste la racine du `location`.
     */
    suspend fun fsList(location: String, path: String? = null): List<FsEntryDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/fs/list") {
            auth(credentials)
            at(location)
            path?.let { parameter("path", it) }
        }.body<LocatedEnvelope<FsEntryDto>>().data
    }

    /**
     * `GET /api/fs/find` : **cherche recursivement** des entrees.
     *
     * ⚠️ `query` est **requis** (`400` sans lui). `type` restreint a `file` ou `directory`.
     * `limit` est declare `type: string` dans l'OpenAPI — on l'envoie comme tel.
     */
    suspend fun fsFind(
        location: String,
        query: String,
        type: String? = null,
        limit: Int? = null,
    ): List<FsEntryDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/fs/find") {
            auth(credentials)
            at(location)
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it.toString()) }
        }.body<LocatedEnvelope<FsEntryDto>>().data
    }

    /**
     * `GET /api/fs/read/<chemin>` : lit **un fichier**.
     *
     * ⚠️ Trois faits mesures le 2026-09-25, tous silencieux si on les ignore :
     *
     * 1. **Le chemin va dans l'URL**, apres `/read/` — c'est un joker (`/api/fs/read/<chemin>`), pas un
     *    parametre nomme. Les segments sont encodes un par un pour qu'une barre oblique reste un
     *    separateur de chemin et ne devienne pas `%2F` (ce que le serveur refuserait).
     * 2. **Le chemin est relatif au `location`** : `/api/fs/read/home/utilisateur/...` rend `404`.
     * 3. La reponse est du **binaire brut** (`application/octet-stream`), jamais l'enveloppe JSON
     *    de `list` et `find`. On lit donc les octets, pas un corps type.
     *
     * On renvoie `null` sur `404` (fichier absent) : c'est une absence, pas une erreur — meme
     * distinction que `vcsInfo`, ou « pas un depot » n'est pas un echec.
     */
    suspend fun fsRead(location: String, path: String): ByteArray? {
        val credentials = credentialsProvider.credentials()
        val encoded = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val response = http.get("$baseUrl/api/fs/read/$encoded") {
            auth(credentials)
            at(location)
        }
        if (!response.status.isSuccess()) return null
        return response.readBytes()
    }

    /** `GET /api/reference` : les references invocables du repertoire. */
    suspend fun references(location: String): List<ReferenceDto> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/reference") {
            auth(credentials)
            at(location)
        }.body<LocatedEnvelope<ReferenceDto>>().data
    }

    /**
     * `GET /api/vcs/branch` : les branches **locales et distantes** du depot.
     *
     * ⚠️ `data` est un tableau de **chaines nues**, pas d'objets (verifie sur `/openapi.json` :
     * `Vcs.BranchList` = `array of string`). Attendre `{name: ...}` rendrait une liste vide sans
     * erreur — le pire des modes d'echec pour un selecteur.
     *
     * ⚠️ `search` filtre cote serveur, mais on ne s'en sert pas : mesure, `search=feat` sur un
     * depot ou la branche `feat/palier-1-2-etat` existe rend `data: []`. On charge donc la liste
     * et on filtre cote app, ce qui evite un appel reseau par frappe.
     */
    suspend fun branches(location: String, search: String? = null, limit: Int? = null): List<String> {
        val credentials = credentialsProvider.credentials()
        return http.get("$baseUrl/api/vcs/branch") {
            auth(credentials)
            at(location)
            search?.let { parameter("search", it) }
            limit?.let { parameter("limit", it.toString()) }
        }.body<BranchListEnvelope>().data
    }

    private fun HttpRequestBuilder.auth(credentials: BasicAuthCredentials?) {
        if (credentials == null) return
        basicAuth(credentials.username, credentials.password)
    }

    private fun <T> DataEnvelope<T>.toPage(): CursorPage<T> =
        CursorPage(data = data, next = cursor?.next, previous = cursor?.previous)

    private class FixedCredentialsProvider(
        private val fixed: BasicAuthCredentials,
    ) : CredentialsProvider {
        override suspend fun credentials(): BasicAuthCredentials = fixed
    }

    companion object {
        /** JSON tolerant : l'API V2 renvoie des champs non documentes. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/** Installe la negociation de contenu kotlinx sur une config HttpClient. */
fun HttpClientConfig<*>.configureTether() {
    install(ContentNegotiation) { json(OpenCodeClient.json) }
}
