package sh.sk7.tether.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **L'avancement du tour, en notifications.**
 *
 * ### Pourquoi ces tests portent sur des fonctions pures
 * Deux regles se jouent ici, et aucune ne se voit a la compilation :
 *  1. **Ne jamais mentir** — le texte affiche doit etre l'etape reelle, et le detail de routage
 *     (`tether:progress=`, `tether:session=`) ne doit jamais apparaitre a l'ecran.
 *  2. **Ne pas spammer** — les etapes successives d'une meme session doivent **remplacer** une
 *     seule notification, et non s'empiler. C'est l'ID qui le decide.
 *
 * Les deux se testent sans Android, exactement comme `decideNotification` dans [PushPolicyTest].
 */
class PushProgressTest {

    // ------------------------------------------------------------------
    // parsePush — le format JSON v1, puis les marqueurs en repli
    // ------------------------------------------------------------------

    @Test
    fun `le JSON v1 est reconnu, et sa progression rendue silencieuse`() {
        // ⚠️ Le defaut trouve sur un vrai push : le plugin etait passe au JSON v1 en annoncant
        // que « l'app lit les deux, JSON d'abord ». Ce repli n'existait pas ici, donc le corps
        // JSON ne portait aucun marqueur `tether:progress=` — `kind` retombait sur `TurnEnd` et
        // **chaque etape d'avancement sonnait comme une fin de tour**. Exactement l'inverse.
        val payload = parsePush(
            """{"v":1,"text":"Etape en cours","sessionID":"ses_f1b32e13","progress":true}"""
        )

        assertEquals(PushKind.Progress, payload.kind)
        assertEquals("Etape en cours", payload.text)
        assertEquals("ses_f1b32e13", payload.sessionID)
    }

    @Test
    fun `un JSON v1 sans progression reste une fin de tour`() {
        val payload = parsePush("""{"v":1,"text":"Termine","sessionID":"ses_9"}""")

        assertEquals(PushKind.TurnEnd, payload.kind)
        assertEquals("Termine", payload.text)
        assertEquals("ses_9", payload.sessionID)
    }

    @Test
    fun `le texte d un JSON n est jamais le JSON`() {
        // Le deuxieme defaut du meme incident : faute d'etre retire, le corps s'affichait brut.
        // « { "v":1, "text": … } » a l'ecran a la place de la phrase.
        val payload = parsePush("""{"v":1,"text":"Trois fichiers modifies","progress":false}""")

        assertEquals("Trois fichiers modifies", payload.text)
        assertFalse(payload.text.contains("{"))
    }

    @Test
    fun `un JSON malforme ne fait pas retomber sur les marqueurs`() {
        // ⚠️ Aucun corps v0 ne commence par `{`. Un repli sur les marqueurs afficherait donc
        // litteralement `{"v":1}` dans la notification — le format doit se decider sur le
        // premier caractere, comme cote serveur.
        val payload = parsePush("""{"v":1,"text":""")

        assertEquals(PushKind.TurnEnd, payload.kind)
        assertTrue(payload.text.startsWith("{"))
    }

    @Test
    fun `un JSON sans texte ne notifie pas une phrase vide`() {
        val payload = parsePush("""{"v":1,"progress":true}""")

        // On retombe sur le texte brut : mieux vaut une notification etrange qu'une
        // notification vide dont l'utilisateur ignore l'origine.
        assertEquals(PushKind.TurnEnd, payload.kind)
        assertTrue(payload.text.isNotBlank())
    }

    // ------------------------------------------------------------------
    // parsePush — le format v0, en repli
    // ------------------------------------------------------------------

    @Test
    fun `un message sans marqueur reste une fin de tour`() {
        // ⚠️ Le defaut est deliberement l'alerte : le plugin historique publie des fins de tour
        // sans marqueur, et un publieur tiers ne doit pas pouvoir les faire passer en silence.
        val payload = parsePush("Terminé : 3 fichiers modifiés")
        assertEquals(PushKind.TurnEnd, payload.kind)
        assertEquals("Terminé : 3 fichiers modifiés", payload.text)
    }

    @Test
    fun `le marqueur de progression est reconnu et retire du texte`() {
        // ⚠️ C'est LE point : afficher `tether:progress=1` a l'utilisateur serait laid et faux.
        val payload = parsePush("shell : npm install\n\ntether:progress=1\n\ntether:session=ses_abc")
        assertEquals(PushKind.Progress, payload.kind)
        assertEquals("shell : npm install", payload.text)
        assertEquals("ses_abc", payload.sessionID)
    }

    @Test
    fun `la session est extraite des deux natures de message`() {
        assertEquals("ses_1", parsePush("fin\n\ntether:session=ses_1").sessionID)
        assertEquals("ses_2", parsePush("étape\n\ntether:progress=1\n\ntether:session=ses_2").sessionID)
        assertEquals(null, parsePush("fin sans session").sessionID)
    }

    @Test
    fun `un corps fait uniquement de lignes de routage donne un texte vide`() {
        // ⚠️ L'appelant remplace alors par un libelle neutre (voir TetherPushService). On ne veut
        // pas que la ligne technique soit ce qui reste a l'ecran.
        val payload = parsePush("tether:progress=1\n\ntether:session=ses_x")
        assertEquals(PushKind.Progress, payload.kind)
        assertTrue(payload.text.isBlank())
    }

    // ------------------------------------------------------------------
    // decideNotification — l'alerte ou le silence
    // ------------------------------------------------------------------

    @Test
    fun `une etape n alerte pas quand l app est en arriere-plan`() {
        // ⚠️ Le cas central de la demande : voir l'avancee SANS etre reveille par chaque outil.
        assertEquals(
            PushDecision.Progress,
            decideNotification(appForeground = false, pendingDecisions = 0, kind = PushKind.Progress),
        )
    }

    @Test
    fun `une etape ne se decide pas comme une autorisation en attente`() {
        // ⚠️ LE piege que ce test ferme : si l'avancement tombait dans la branche `pendingDecisions`,
        // il deviendrait une notification persistante « Autorisation requise » portant le texte d'un
        // appel d'outil. Un mensonge, et une alerte qui ne se balaie plus.
        assertEquals(
            PushDecision.Progress,
            decideNotification(appForeground = false, pendingDecisions = 3, kind = PushKind.Progress),
        )
    }

    @Test
    fun `une etape ne notifie pas ce qu on regarde`() {
        assertEquals(
            PushDecision.Skip,
            decideNotification(appForeground = true, pendingDecisions = 0, kind = PushKind.Progress),
        )
    }

    @Test
    fun `une fin de tour reste inchangee a cote de l avancement`() {
        // ⚠️ Non-regression : l'ajout de l'avancement ne doit rien changer aux fins de tour.
        assertEquals(
            PushDecision.Transient,
            decideNotification(appForeground = false, pendingDecisions = 0, kind = PushKind.TurnEnd),
        )
        assertEquals(
            PushDecision.Ongoing,
            decideNotification(appForeground = false, pendingDecisions = 1, kind = PushKind.TurnEnd),
        )
    }

    // ------------------------------------------------------------------
    // progressNotificationId — un id par session, un seul par session
    // ------------------------------------------------------------------

    @Test
    fun `deux sessions distinctes ne se volent pas leur avancement`() {
        // ⚠️ Deux sessions ouvertes tournent en parallele. Avec un ID unique, l'etape de B
        // ecraserait celle de A : on afficherait `shell : npm install` a propos d'une tache qui
        // n'a jamais lance cette commande.
        assertNotEquals(
            progressNotificationId("ses_aaaa"),
            progressNotificationId("ses_bbbb"),
        )
    }

    @Test
    fun `une meme session garde toujours le meme id`() {
        // ⚠️ C'est ce qui fait l'anti-spam : les etapes successives remplacent une seule ligne.
        assertEquals(
            progressNotificationId("ses_stable"),
            progressNotificationId("ses_stable"),
        )
    }

    @Test
    fun `une session absente retombe sur un id neutre, hors des ids fixes`() {
        // ⚠️ Ne jamais retomber sur 1001/1002 (fin de tour / autorisation) : une etape sans session
        // ne doit pas ecraser une vraie alerte.
        val id = progressNotificationId(null)
        assertTrue(id > 1002, "l'id $id entrerait en collision avec les ids fixes")
        assertEquals(id, progressNotificationId(""))
    }

    // ------------------------------------------------------------------
    // targetFor — ou mene le tap
    // ------------------------------------------------------------------

    @Test
    fun `une etape reconnue mene a sa session`() {
        assertEquals(
            PushTarget.Session("ses_abc"),
            targetFor(PushDecision.Progress, "ses_abc"),
        )
    }

    @Test
    fun `une etape sans session reconnue ouvre l app`() {
        assertEquals(PushTarget.App, targetFor(PushDecision.Progress, null))
    }

    // ------------------------------------------------------------------
    // endpointNeedsRepublish — le relais ne doit jamais se vider
    // ------------------------------------------------------------------

    @Test
    fun `un endpoint inconnu ou change est toujours republie`() {
        // ⚠️ Sans cette branche, une reinstallation ou un renouvellement de topic par le
        // distributeur laisserait le serveur publier vers un endpoint mort.
        assertTrue(
            endpointNeedsRepublish(
                lastEndpoint = null,
                current = "https://ntfy.example.com/upA",
                lastAtMillis = 0L,
                nowMillis = 1_000L,
                intervalMillis = 3 * 60 * 60 * 1000L,
            ),
        )
        assertTrue(
            endpointNeedsRepublish(
                lastEndpoint = "https://ntfy.example.com/upOLD",
                current = "https://ntfy.example.com/upNEW",
                lastAtMillis = 999_999L,
                nowMillis = 1_000_000L,
                intervalMillis = 3 * 60 * 60 * 1000L,
            ),
        )
    }

    @Test
    fun `le meme endpoint n est pas republie avant l intervalle`() {
        val interval = 3 * 60 * 60 * 1000L
        assertEquals(
            false,
            endpointNeedsRepublish("upsame", "upsame", 0L, interval - 1, interval),
        )
    }

    @Test
    fun `le meme endpoint est republie une fois l intervalle atteint`() {
        // ⚠️ C'est ce qui empeche le relais de se vider : mesure du 2026-09-26, le serveur ntfy
        // a `cache-duration: 6h`. Republier a 6h laissait une marge nulle ; l'intervalle est
        // desormais la moitie du cache (3h).
        val interval = 3 * 60 * 60 * 1000L
        assertEquals(
            true,
            endpointNeedsRepublish("upsame", "upsame", 0L, interval, interval),
        )
        assertEquals(
            true,
            endpointNeedsRepublish("upsame", "upsame", 0L, interval + 1, interval),
        )
    }

    @Test
    fun `l intervalle de republication garde une marge sur le cache ntfy reel`() {
        // ⚠️ Non-regression sur un fait MESURE : le serveur de Bastien a `cache-duration: 6h`.
        // Republier a 6h (l'ancienne valeur) = marge nulle. On verifie que l'intervalle est
        // strictement inferieur au cache, avec au moins un facteur 2 de securite.
        val cacheNtfyReel = 6 * 60 * 60 * 1000L
        assertTrue(
            REPUBLISH_INTERVAL_MS * 2 <= cacheNtfyReel,
            "intervalle=${REPUBLISH_INTERVAL_MS} ms, cache ntfy=$cacheNtfyReel ms",
        )
    }

    // ------------------------------------------------------------------
    // shouldClearProgress — l'etape ne survit pas au tour
    // ------------------------------------------------------------------

    @Test
    fun `une fin de tour retire l avancement, quelle que soit la session`() {
        // ⚠️ Le nettoyage a lieu AVANT le test de premier plan : sinon, fin de tour app ouverte =
        // `Skip`, donc aucune notification ET aucune suppression — l'etape resterait a l'ecran
        // pour toujours, a dire « shell : npm install » d'un travail fini.
        assertTrue(shouldClearProgress(PushKind.TurnEnd))
    }

    @Test
    fun `une etape de progression ne supprime rien`() {
        // ⚠️ Sinon chaque nouvelle etape effacerait la precedente avant de l'ecrire : soit rien,
        // soit un clignotement. L'anti-spam se fait par le remplacement du meme ID.
        assertEquals(false, shouldClearProgress(PushKind.Progress))
    }

    // ---------------------------------------------------------------------
    // La decision du TAP : session ou app ?
    //
    // ⚠️ Regression mesuree sur le Pixel (2026-09-26) : ma validation exigeait que la
    // session soit CONNUE de l'app. Log a l'appui :
    //     deep link : session=ses_f2b4... connue=false decision=Progress
    // à CHAQUE notification, donc repli sur l'app : le tap ouvrait l'app sans la session.
    //
    // Cause structurelle : le push PEUT relancer le processus, et au moment ou la notification
    // se construit le detenteur d'etat n'a rien charge (bySession vide). La validation etait
    // donc incapable de marcher dans le cas principal (app fermee).
    // ---------------------------------------------------------------------

    @Test
    fun `une session plausible est acceptee sans dependre de l'etat charge`() {
        // ⚠️ C'est LE point du correctif : la forme suffit. Aucun etat a interroger, donc la
        // decision ne peut pas dependre du moment (process fraichement relance, cache vide).
        assertTrue("ses_f2b4097ecffe8dMdMYfZlf1eiS".looksLikeSessionID())
        assertTrue("ses_x".looksLikeSessionID())
    }

    @Test
    fun `une charge qui ne ressemble pas a une session est refusee`() {
        // ⚠️ Le topic relais accepte des publications anonymes : on garde un filtre. Mais il
        // porte sur une FORME, pas sur une existence — le risque ecarte est d'ouvrir une session
        // vide, pas une escalation.
        assertFalse("".looksLikeSessionID())
        assertFalse("msg_abc".looksLikeSessionID())
        assertFalse("../../etc/passwd".looksLikeSessionID())
        assertFalse("https://exemple.test".looksLikeSessionID())
    }

}
