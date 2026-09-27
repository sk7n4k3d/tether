package sh.sk7.tether.testing

import sh.sk7.tether.data.api.HistoryPage
import sh.sk7.tether.data.api.Agent
import sh.sk7.tether.data.api.CommandDto
import sh.sk7.tether.data.api.ContextMessageDto
import sh.sk7.tether.data.api.CursorPage
import sh.sk7.tether.data.api.FileDiffDto
import sh.sk7.tether.data.api.FormAnswerValue
import sh.sk7.tether.data.api.FormDetailDto
import sh.sk7.tether.data.api.FormInfoDto
import sh.sk7.tether.data.api.FsEntryDto
import sh.sk7.tether.data.api.InboxItemDto
import sh.sk7.tether.data.api.McpServerDto
import sh.sk7.tether.data.api.MessageDto
import sh.sk7.tether.data.api.Model
import sh.sk7.tether.data.api.ModelRef
import sh.sk7.tether.data.api.OpenCodeGateway
import sh.sk7.tether.data.api.PluginDto
import sh.sk7.tether.data.api.ProjectDto
import sh.sk7.tether.data.api.PromptAcceptance
import sh.sk7.tether.data.api.PromptBody
import sh.sk7.tether.data.api.PromptPayload
import sh.sk7.tether.data.api.ProviderDto
import sh.sk7.tether.data.api.PtyInfoDto
import sh.sk7.tether.data.api.ReferenceDto
import sh.sk7.tether.data.api.RevertResultDto
import sh.sk7.tether.data.api.SavedPermissionDto
import sh.sk7.tether.data.api.ServerInfo
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.api.SessionParent
import sh.sk7.tether.data.api.ShellInfoDto
import sh.sk7.tether.data.api.ShellOutputDto
import sh.sk7.tether.data.api.SkillDto
import sh.sk7.tether.data.api.VcsBaseDto
import sh.sk7.tether.data.api.VcsFileStatusDto
import sh.sk7.tether.data.api.VcsInfoDto
import sh.sk7.tether.data.api.WorktreeDirDto
import sh.sk7.tether.data.api.WorktreeInfoDto
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.domain.model.PermissionDecision
import sh.sk7.tether.domain.model.PermissionRequest
import sh.sk7.tether.domain.model.UsageStats

/**
 * **Base des fakes de test** : chaque route de l'API renvoie une valeur neutre.
 *
 * ### Pourquoi cette classe existe
 * Avant, deux `FakeGateway` implementaient l'interface **entierement**, chacun dans son fichier de
 * test. Ajouter une route obligeait a la dupliquer deux fois — et j'ai du le faire deux fois de
 * suite. C'est un cout qui grandit avec l'API, et surtout un piege : oublier une copie ne se voit
 * pas au compilateur tant qu'on ne compile pas les tests.
 *
 * ⚠️ **Le contrat est explicite** : un test n'herite d'ici que pour **surcrire ce qu'il exerce**.
 * Tout ce qui n'est pas surcri renvoie une valeur neutre (liste vide, `false`, `0`). Ce n'est pas
 * une commodite — c'est une affirmation : « ce test ne porte pas sur cette route ». Un test qui
 * depend d'une route doit la surcrire, sinon il test une valeur par defaut sans le dire.
 *
 * ⚠️ **Aucune methode n'est `final `**: toutes sont `open`, precisement pour etre surcrite.
 *
 * ⚠️ Les valeurs neutres sont **choisies pour ne pas mentir** : une liste vide signifie « rien »,
 * pas « zero element configure ». Un test qui a besoin de distinguer les deux doit surcrire.
 */
open class NeutralGateway : OpenCodeGateway {

    override suspend fun info(settings: ConnectionSettings): ServerInfo = ServerInfo(version = "test")

    override suspend fun sessionsPage(
        settings: ConnectionSettings,
        limit: Int?,
        cursor: String?,
        parent: SessionParent?,
    ): CursorPage<Session> = CursorPage(data = emptyList())

    override suspend fun models(settings: ConnectionSettings): List<Model> = emptyList()

    override suspend fun agents(settings: ConnectionSettings): List<Agent> = emptyList()

    override suspend fun createSession(
        settings: ConnectionSettings,
        model: ModelRef?,
        agent: String?,
    ): Session {
        lastCreateModel = model
        lastCreateAgent = agent
        return Session(id = "ses_new")
    }

    /** Dernier choix transmis a la creation, pour les assertions. */
    var lastCreateModel: ModelRef? = null
    var lastCreateAgent: String? = null

    override suspend fun session(settings: ConnectionSettings, sessionID: String): Session =
        Session(id = sessionID)

    override suspend fun prompt(
        settings: ConnectionSettings,
        sessionID: String,
        text: String,
    ): PromptAcceptance = PromptAcceptance(id = "msg_t", sessionID = sessionID, type = "user")

    override suspend fun prompt(
        settings: ConnectionSettings,
        sessionID: String,
        body: PromptBody,
    ): PromptAcceptance =
        PromptAcceptance(id = "msg_t", sessionID = sessionID, type = "user", payload = PromptPayload(body.text))

    override suspend fun messagesPage(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int?,
        cursor: String?,
        order: String?,
    ): CursorPage<MessageDto> = CursorPage(data = emptyList())

    override suspend fun messagesPageBack(
        settings: ConnectionSettings,
        sessionID: String,
        limit: Int,
        cursor: String?,
    ): HistoryPage = HistoryPage(messages = emptyList(), cursorBack = null)

    override suspend fun interrupt(settings: ConnectionSettings, sessionID: String): Boolean = true

    override suspend fun renameSession(
        settings: ConnectionSettings,
        sessionID: String,
        title: String,
    ): Boolean = true

    override suspend fun deleteSession(settings: ConnectionSettings, sessionID: String) = Unit

    override suspend fun forkSession(settings: ConnectionSettings, sessionID: String): Session =
        Session(id = "ses_fork")

    override suspend fun compactSession(settings: ConnectionSettings, sessionID: String): Boolean = true

    override suspend fun stats(settings: ConnectionSettings, fromMillis: Long?): UsageStats = UsageStats()

    override suspend fun commands(settings: ConnectionSettings): List<CommandDto> = emptyList()

    override suspend fun skills(settings: ConnectionSettings): List<SkillDto> = emptyList()

    override suspend fun mcpServers(settings: ConnectionSettings): List<McpServerDto> = emptyList()

    override suspend fun plugins(settings: ConnectionSettings): List<PluginDto> = emptyList()

    override suspend fun providers(settings: ConnectionSettings): List<ProviderDto> = emptyList()

    override suspend fun savedPermissions(settings: ConnectionSettings): List<SavedPermissionDto> =
        emptyList()

    override suspend fun revokePermission(settings: ConnectionSettings, permissionID: String): Boolean =
        true

    override suspend fun projects(settings: ConnectionSettings): List<ProjectDto> = emptyList()

    override suspend fun pendingPermissions(settings: ConnectionSettings): List<PermissionRequest> =
        emptyList()

    override suspend fun sessionPermissions(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<PermissionRequest> = emptyList()

    override suspend fun replyPermission(
        settings: ConnectionSettings,
        sessionID: String,
        requestID: String,
        decision: PermissionDecision,
        message: String?,
    ): Boolean = true

    override suspend fun sessionDiff(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<FileDiffDto> = emptyList()

    override suspend fun vcsDiff(
        settings: ConnectionSettings,
        mode: String,
    ): List<FileDiffDto> = emptyList()

    override suspend fun vcsDiffIn(
        settings: ConnectionSettings,
        directory: String,
        mode: String,
    ): List<FileDiffDto> = emptyList()

    override suspend fun projectID(settings: ConnectionSettings): String? = null

    override suspend fun activeSessions(settings: ConnectionSettings): Set<String> = emptySet()

    override suspend fun shells(settings: ConnectionSettings): List<ShellInfoDto> = emptyList()

    override suspend fun terminals(settings: ConnectionSettings): List<PtyInfoDto> = emptyList()

    override suspend fun shellOutput(
        settings: ConnectionSettings,
        shellID: String,
        cursor: Int?,
    ): ShellOutputDto? = null

    override suspend fun markViewed(
        settings: ConnectionSettings,
        sessionID: String,
        idle: Long,
    ): Boolean = true

    override suspend fun backgroundTools(settings: ConnectionSettings, sessionID: String): Boolean =
        true

    override suspend fun activateSkill(
        settings: ConnectionSettings,
        sessionID: String,
        skillID: String,
    ): Boolean = true

    override suspend fun fsList(settings: ConnectionSettings, path: String?): List<FsEntryDto> =
        emptyList()

    override suspend fun fsFind(
        settings: ConnectionSettings,
        query: String,
        type: String?,
    ): List<FsEntryDto> = emptyList()

    override suspend fun fsRead(settings: ConnectionSettings, path: String): ByteArray? = null

    override suspend fun references(settings: ConnectionSettings): List<ReferenceDto> = emptyList()

    override suspend fun branches(settings: ConnectionSettings): List<String> = emptyList()

    override suspend fun branchesIn(
        settings: ConnectionSettings,
        directory: String,
    ): List<String> = emptyList()

    override suspend fun vcsInfo(
        settings: ConnectionSettings,
        directory: String,
    ): VcsInfoDto = VcsInfoDto(provider = "git")

    override suspend fun vcsStatus(settings: ConnectionSettings): List<VcsFileStatusDto> = emptyList()

    override suspend fun sessionContext(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<ContextMessageDto> = emptyList()

    override suspend fun stageRevert(
        settings: ConnectionSettings,
        sessionID: String,
        messageID: String,
    ): RevertResultDto? = null

    override suspend fun commitRevert(settings: ConnectionSettings, sessionID: String): Boolean = true

    override suspend fun discardRevert(settings: ConnectionSettings, sessionID: String): Boolean = true

    override suspend fun worktrees(settings: ConnectionSettings): List<WorktreeDirDto> = emptyList()

    override suspend fun createWorktree(
        settings: ConnectionSettings,
        name: String?,
    ): WorktreeInfoDto = WorktreeInfoDto(directory = name.orEmpty())

    override suspend fun removeWorktree(
        settings: ConnectionSettings,
        directory: String,
        force: Boolean,
    ): Boolean = true

    override suspend fun setSessionModel(
        settings: ConnectionSettings,
        sessionID: String,
        model: ModelRef,
    ): Boolean = true

    override suspend fun setSessionAgent(
        settings: ConnectionSettings,
        sessionID: String,
        agent: String,
    ): Boolean = true

    override suspend fun runCommand(
        settings: ConnectionSettings,
        sessionID: String,
        name: String,
        text: String,
    ): Boolean = true

    override suspend fun sessionInbox(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<InboxItemDto> = emptyList()

    override suspend fun dismissInbox(
        settings: ConnectionSettings,
        sessionID: String,
        inboxID: String,
    ): Boolean = true

    /** Dernier mode demande par un PATCH d'inbox, pour verifier ce qui part vraiment. */
    var lastDelivery: String? = null

    /** Force l'echec du PATCH : le serveur rend 409 quand le message part deja. */
    @Volatile
    var deliveryFailure: Throwable? = null

    override suspend fun updateInboxDelivery(
        settings: ConnectionSettings,
        sessionID: String,
        inboxID: String,
        delivery: String,
    ): Boolean {
        deliveryFailure?.let { throw it }
        lastDelivery = delivery
        return true
    }

    override suspend fun pendingForms(settings: ConnectionSettings): List<FormInfoDto> = emptyList()

    override suspend fun sessionForms(
        settings: ConnectionSettings,
        sessionID: String,
    ): List<FormInfoDto> = emptyList()

    override suspend fun sessionForm(
        settings: ConnectionSettings,
        sessionID: String,
        formID: String,
    ): FormDetailDto? = null

    override suspend fun replyForm(
        settings: ConnectionSettings,
        sessionID: String,
        formID: String,
        answer: Map<String, FormAnswerValue>,
    ): Boolean = true

    override suspend fun defaultModel(settings: ConnectionSettings): Model? = null

    override suspend fun vcsBase(settings: ConnectionSettings): VcsBaseDto? = null

    override suspend fun waitForIdle(
        settings: ConnectionSettings,
        sessionID: String,
        timeoutMillis: Long,
    ): Boolean = true
}
