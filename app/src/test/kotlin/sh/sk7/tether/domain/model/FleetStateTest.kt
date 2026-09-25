package sh.sk7.tether.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **L'ordre de priorité de l'état global.**
 *
 * ⚠️ Ces tests portent sur la seule décision de conception qui compte vraiment : **quel état
 * domine**. Le reste (formes de DTO, décodage) se voit à la compilation ; celui-ci, non. Une
 * inversion ferait afficher « en cours » alors qu'une autorisation attend — et l'utilisateur
 * regarderait l'écran sans rien faire, au moment précis où l'app sert à quelque chose.
 */
class FleetStateTest {

    private fun session(
        id: String,
        activity: Activity,
        idleAt: Long? = null,
        viewedAt: Long? = null,
        queued: Int = 0,
    ) = SessionActivity(
        sessionID = id,
        activity = activity,
        queuedCount = queued,
        waitingCount = if (activity == Activity.Waiting) 1 else 0,
        idleAt = idleAt,
        viewedAt = viewedAt,
    )

    private fun fleet(vararg activities: SessionActivity) = FleetState(
        bySession = activities.associateBy { it.sessionID },
    )

    @Test
    fun `t attend PRIME sur tout le reste`() {
        // ⚠️ LE test qui compte. Une session bloquée sur une autorisation, dix qui tournent :
        // c'est l'attente qu'il faut voir. L'inverse ferait regarder l'écran sans agir.
        val state = fleet(
            session("a", Activity.Running),
            session("b", Activity.Waiting),
            session("c", Activity.Unseen, idleAt = 100),
        )

        assertEquals(Activity.Waiting, state.summary)
        assertEquals(1, state.waitingCount)
    }

    @Test
    fun `termine pas vu prime sur en cours`() {
        // ⚠️ Un tour fini qu'on n'a pas vu est une **information nouvelle** : elle doit passer
        // avant du travail qui continue, parce qu'elle peut demander une action et pas l'autre.
        val state = fleet(
            session("a", Activity.Running),
            session("b", Activity.Unseen, idleAt = 100),
        )

        assertEquals(Activity.Unseen, state.summary)
    }

    @Test
    fun `en cours prime sur la file`() {
        val state = fleet(
            session("a", Activity.Running),
            session("b", Activity.Queued, queued = 2),
        )

        assertEquals(Activity.Running, state.summary)
    }

    @Test
    fun `un echec ancien ne masque pas du travail en cours`() {
        // ⚠️ Choix deliberé : `Failed` est **apres** `Running` dans l'ordre. Un echec d'hier ne
        // doit pas faire croire que rien ne tourne aujourd'hui. Il reste visible par session.
        val state = fleet(
            session("a", Activity.Failed),
            session("b", Activity.Running),
        )

        assertEquals(Activity.Running, state.summary)
    }

    @Test
    fun `un echec seul remonte quand meme`() {
        val state = fleet(session("a", Activity.Failed))

        assertEquals(Activity.Failed, state.summary)
    }

    @Test
    fun `tout est calme`() {
        val state = fleet(session("a", Activity.Idle))

        assertEquals(Activity.Idle, state.summary)
        assertFalse(state.hasAnything)
    }

    @Test
    fun `un shell vivant suffit a dire qu il se passe quelque chose`() {
        // ⚠️ Un shell de fond qui tourne est du travail reel, meme sans session active : c'est
        // precisement ce que l'app ne montrait pas.
        val state = FleetState(
            bySession = emptyMap(),
            shells = listOf(
                ShellActivity(id = "sh_1", command = "sleep 600", status = "running"),
            ),
        )

        assertTrue(state.hasAnything)
        assertEquals(1, state.liveShells.size)
    }

    @Test
    fun `un shell termine ne compte plus comme vivant`() {
        val state = FleetState(
            shells = listOf(
                ShellActivity(id = "sh_1", command = "ls", status = "exited"),
                ShellActivity(id = "sh_2", command = "sleep 600", status = "running"),
            ),
        )

        assertEquals(1, state.liveShells.size)
        assertEquals("sh_2", state.liveShells.first().id)
    }

    @Test
    fun `pas vu, jamais ouvert compte comme pas vu`() {
        // ⚠️ `viewed == null` est le cas le plus frequent ET le plus interessant : une session
        // terminee qu'on n'a jamais ouverte. La traiter comme « vu » perdrait exactement
        // l'information qu'on veut montrer.
        val s = session("a", Activity.Unseen, idleAt = 500, viewedAt = null)

        assertTrue(s.isUnseen)
        assertTrue(s.needsAttention)
    }

    @Test
    fun `pas vu, un tour plus recent que le marquage redevient pas vu`() {
        // ⚠️ C'est toute l'elegance de `time.viewed` cote serveur : on a regarde jusqu'a un point,
        // et un nouveau tour fini APRES ce point doit resurgir tout seul. Aucun etat local a
        // maintenir, aucune course possible.
        val s = session("a", Activity.Unseen, idleAt = 900, viewedAt = 500)

        assertTrue(s.isUnseen)
    }

    @Test
    fun `vu, regarder apres la fin ne signale rien`() {
        val s = session("a", Activity.Idle, idleAt = 500, viewedAt = 900)

        assertFalse(s.isUnseen)
        assertFalse(s.needsAttention)
    }

    @Test
    fun `pas vu, une session qui attend ne compte pas double`() {
        // ⚠️ Une session bloquee sur une autorisation a peut-etre un `idle` anterieur : elle ne
        // doit pas apparaitre A LA FOIS dans « t attend » et dans « termine pas vu ». Un compteur
        // qui double ferait croire a deux problemes la ou il y en a un.
        val s = SessionActivity(
            sessionID = "a",
            activity = Activity.Waiting,
            waitingCount = 1,
            idleAt = 500,
            viewedAt = null,
        )

        assertFalse(s.isUnseen, "une session qui attend ne doit pas compter comme pas-vue")
        assertTrue(s.needsAttention, "mais elle doit bien demander attention")
    }

    @Test
    fun `duree de shell formatee, et absente si on ne sait pas`() {
        // ⚠️ Un shell vivant n'a pas d'horodatage de fin : sa duree se calcule jusqu'a
        // "maintenant". Un shell termine mais **sans** `completed` rend `null` plutot qu'une
        // duree inventee — on ne fabrique pas une mesure.
        val running = ShellActivity(
            id = "sh_1", command = "sleep 600", status = "running", startedAt = 1_000,
        )
        assertEquals("59 s", running.durationLabel(now = 60_000))

        val done = ShellActivity(
            id = "sh_2", command = "ls", status = "exited",
            startedAt = 1_000, completedAt = 1_500,
        )
        assertEquals("500 ms", done.durationLabel(now = 99_999))

        val unknown = ShellActivity(id = "sh_3", command = "ls", status = "exited")
        assertEquals(null, unknown.durationLabel(now = 1_000))
    }

    @Test
    fun `la file compte separement des sessions qui attendent`() {
        // ⚠️ « En file » et « t attend » sont deux faits differents : le premier attend son tour
        // tout seul, le second attend **toi**. Les melanger ferait croire a une intervention
        // necessaire alors que rien ne bloque.
        val state = fleet(
            session("a", Activity.Queued, queued = 3),
            session("b", Activity.Idle),
        )

        assertEquals(Activity.Queued, state.summary)
        assertEquals(0, state.waitingCount)
        assertEquals(1, state.queued.size)
    }
}
