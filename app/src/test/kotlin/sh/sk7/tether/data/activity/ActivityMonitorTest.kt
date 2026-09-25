package sh.sk7.tether.data.activity

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import sh.sk7.tether.data.api.Session
import sh.sk7.tether.data.api.TimeInfo
import sh.sk7.tether.data.settings.ConnectionMonitor
import sh.sk7.tether.data.settings.ConnectionSettings
import sh.sk7.tether.data.settings.ConnectionStore
import sh.sk7.tether.domain.model.Activity
import sh.sk7.tether.domain.model.ShellActivity
import sh.sk7.tether.testing.NeutralGateway
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Le croisement des sessions connues avec l'état du serveur.**
 *
 * ⚠️ Ce qui ne se voit pas à la compilation : quelles sessions deviennent `Running`, quels
 * sous-agents en sont déduits, et si le compte de file poussé par le chat survit à un cycle.
 * Une erreur ici ferait disparaître un sous-agent actif de la liste, ou remettrait « en file »
 * à zéro toutes les 12 s.
 */
class ActivityMonitorTest {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val files = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        files.forEach { it.delete() }
    }

    private fun realStore(settings: ConnectionSettings): ConnectionStore {
        val dir = File(System.getProperty("java.io.tmpdir"), "tether-monitor-test").apply { mkdirs() }
        val file = File(dir, "m-${UUID.randomUUID()}.preferences_pb")
        files += file
        val store = ConnectionStore(
            PreferenceDataStoreFactory.create(scope = scope) { file },
            InMemoryCredentialsProvider(),
        )
        runBlocking { store.save(settings) }
        return store
    }

    private class FakeGateway(
        private val sessions: List<Session>,
        private val active: Set<String>,
    ) : NeutralGateway() {
        override suspend fun allSessions(
            settings: ConnectionSettings,
            pageSize: Int,
        ): List<Session> = sessions

        override suspend fun activeSessions(settings: ConnectionSettings): Set<String> = active
    }

    private fun monitor(gateway: sh.sk7.tether.data.api.OpenCodeGateway, store: ConnectionStore) = ActivityMonitor(
        store = store,
        gateway = gateway,
        connection = ConnectionMonitor(store),
        appScope = scope,
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `un sous-agent actif est identifie sous son parent`() = runBlocking<Unit> {
        val sessions = listOf(
            Session(id = "ses_parent", title = "Parent", time = TimeInfo(idle = 100, viewed = 100)),
            Session(id = "ses_sub", parentID = "ses_parent", title = "Sous-agent"),
        )
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val gateway = FakeGateway(sessions, active = setOf("ses_sub"))
        val monitor = monitor(gateway, store)

        monitor.refresh()

        assertEquals(Activity.Running, monitor.state.value.bySession["ses_sub"]?.activity)
        assertEquals(1, monitor.state.value.activeSubagents.size)
        assertEquals("ses_sub", monitor.state.value.activeSubagents.single().sessionID)
        assertEquals(1, monitor.state.value.activeSubagentCount("ses_parent"))
    }

    @Test
    fun `le compte de file pousse par le chat survit a un cycle`() = runBlocking<Unit> {
        // ⚠️ Le cycle periodique ne lit PAS l'inbox (une requete par session serait hors de prix).
        // Sans le report du compte precedent, « 2 en file » retomberait a zero a chaque cycle de
        // 12 s et l'en-tete clignoterait.
        val sessions = listOf(Session(id = "ses_1", title = "S", time = TimeInfo(idle = 100, viewed = 100)))
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val gateway = FakeGateway(sessions, active = emptySet())
        val monitor = monitor(gateway, store)

        monitor.refresh()
        monitor.publishQueue("ses_1", 2)
        assertEquals(2, monitor.state.value.bySession["ses_1"]?.queuedCount)

        monitor.refresh()

        assertEquals(2, monitor.state.value.bySession["ses_1"]?.queuedCount, "le report doit tenir")
    }

    @Test
    fun `une session en file est classee Queued`() = runBlocking<Unit> {
        val sessions = listOf(Session(id = "ses_1", title = "S", time = TimeInfo(idle = 100, viewed = 100)))
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val monitor = monitor(FakeGateway(sessions, active = emptySet()), store)

        monitor.refresh()
        monitor.publishQueue("ses_1", 1)

        assertEquals(Activity.Queued, monitor.state.value.bySession["ses_1"]?.activity)
    }

    @Test
    fun `publishQueue sur une session inconnue ne fait rien`() = runBlocking<Unit> {
        // ⚠️ Le chat peut pousser avant que la liste n'ait alimenté le monitor : on ignore plutôt
        // que de créer une entrée fantôme sans les autres champs.
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val monitor = monitor(FakeGateway(emptyList(), emptySet()), store)
        monitor.refresh()

        monitor.publishQueue("ses_inconnue", 3)

        assertTrue(monitor.state.value.bySession.isEmpty())
    }

    @Test
    fun `le sessionID d un shell est lu dans metadata`() = runBlocking<Unit> {
        // ⚠️ Mesure : le `sessionID` n'est pas a la racine du shell, il vit dans `metadata`. Le
        // schema OpenAPI annonce seulement `metadata: {type: object}` — s'y fier laissait croire
        // qu'un shell n'etait rattachable a rien. C'est de la lecture de forme, invisible a la
        // compilation.
        val store = realStore(ConnectionSettings(password = "x", directory = "/home/utilisateur"))
        val gateway = object : NeutralGateway() {
            override suspend fun allSessions(
                settings: ConnectionSettings,
                pageSize: Int,
            ): List<Session> = emptyList()

            override suspend fun shells(
                settings: ConnectionSettings,
            ): List<sh.sk7.tether.data.api.ShellInfoDto> = listOf(
                sh.sk7.tether.data.api.ShellInfoDto(
                    id = "sh_1",
                    status = "running",
                    command = "sleep 600",
                    metadata = kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"sessionID":"ses_parent"}""",
                    ) as kotlinx.serialization.json.JsonObject,
                    time = sh.sk7.tether.data.api.ShellTimeDto(started = 1_000),
                ),
            )
        }
        val monitor = monitor(gateway, store)

        monitor.refresh()

        val shell = monitor.state.value.shells.single()
        assertEquals("ses_parent", shell.sessionID)
        assertEquals("sleep 600", shell.firstLine)
        assertTrue(monitor.state.value.liveShells.isNotEmpty())
    }
}
