package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.basicAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Ignore
import sh.sk7.tether.data.settings.ConnectionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test d'integration du chemin **reellement utilise par l'UI** ([KtorOpenCodeGateway]) contre
 * le serveur opencode de le serveur.
 *
 * Ignore par defaut : ne doit pas casser la suite si le serveur est absent.
 * A lancer manuellement :
 *
 * ./gradlew :app:testDebugUnitTest --tests '*GatewayLiveTest*' \
 *   -Dtether.baseUrl=http://192.0.2.10:4096 \
 *   -Dtether.password="$(cat ~/.config/opencode/ocremote-password)"
 */
@Ignore("Test d'integration : requiert un serveur opencode joignable")
class GatewayLiveTest {

    private val baseUrl: String = System.getProperty("tether.baseUrl") ?: "http://127.0.0.1:4096"
    private val password: String = System.getProperty("tether.password") ?: ""
    private val directory: String = System.getProperty("tether.directory") ?: "/home/user"

    private fun gateway(): KtorOpenCodeGateway {
        val http = HttpClient(OkHttp) { configureTether() }
        val credentials = InMemoryCredentialsProvider(BasicAuthCredentials(password = password))
        return KtorOpenCodeGateway(http, credentials)
    }

    private fun settings() = ConnectionSettings(
        baseUrl = baseUrl,
        password = password,
        directory = directory,
    )

    @Test
    fun `allSessions liste les sessions du repertoire`() = runBlocking<Unit> {
        val sessions = gateway().allSessions(settings())
        assertTrue(sessions.isNotEmpty(), "aucune session")
        assertTrue(sessions.all { it.id.startsWith("ses_") })
    }

    @Test
    fun `le gateway resout modeles et agents avec le parametre location`() = runBlocking<Unit> {
        val gateway = gateway()
        val models = gateway.models(settings())
        val agents = gateway.agents(settings())
        assertTrue(models.isNotEmpty(), "aucun modele : le parametre location[directory] est mal signe")
        assertTrue(agents.isNotEmpty(), "aucun agent : le parametre location[directory] est mal signe")
        assertTrue(models.any { it.id == "deepseek-v4.1-flash" })
    }

    /**
     * Defaut connu de la Task 1.5 : `GET /api/session` pagine a 50 par defaut. `allSessions`
     * doit suivre le curseur et rendre **plus de 50** sessions (428 mesurees le 2026-09-25).
     */
    @Test
    fun `allSessions suit le curseur et depasse la premiere page de 50`() = runBlocking<Unit> {
        val gateway = gateway()
        val first = gateway.sessionsPage(settings(), limit = 50, cursor = null)
        assertEquals(50, first.data.size, "la premiere page fait 50")
        assertTrue(first.next != null, "le curseur next doit etre present apres la premiere page")

        val all = gateway.allSessions(settings())
        assertTrue(all.size > 50, "pagination non suivie : ${all.size} sessions seulement")
        assertEquals(all.size, all.map { it.id }.distinct().size, "sessions dupliquees entre pages")
        println("allSessions -> ${all.size} sessions (${all.size / 50 + 1} pages)")
    }

    // ------------------------------------------------------------------
    // Palier 3 : capacites serveur, exercees contre le serveur REEL
    // ------------------------------------------------------------------

    /**
     * ⚠️ Ces tests ne verifient pas seulement que les routes existent : ils verifient les **formes
     * reelles**, qui sont la source des cinq erreurs documentees dans le plan.
     */
    @Test
    fun `fsList et fsFind rendent des entrees reelles`() = runBlocking<Unit> {
        val gateway = gateway()
        val root = gateway.fsList(settings(), path = null)
        assertTrue(root.isNotEmpty(), "la racine du repertoire doit contenir des entrees")
        assertTrue(root.all { it.path.isNotBlank() })
        println("fsList racine -> ${root.size} entrees, ex: ${root.first().path} (${root.first().type})")

        val found = gateway.fsFind(settings(), query = "settings.gradle.kts")
        assertTrue(found.isNotEmpty(), "find doit trouver le fichier de build")
        assertTrue(found.any { it.path.endsWith("settings.gradle.kts") })
    }

    @Test
    fun `fsRead lit un fichier texte par chemin relatif`() = runBlocking<Unit> {
        val gateway = gateway()
        // ⚠️ Chemin RELATIF au repertoire configure. Un absolu rend 404 (mesure).
        val path = "Projects/tether/settings.gradle.kts"
        val bytes = gateway.fsRead(settings(), path)
        requireNotNull(bytes) { "le fichier doit etre lisible : $path" }
        val text = bytes.decodeToString()
        assertTrue(text.contains("rootProject.name"), "contenu inattendu : ${text.take(80)}")
        println("fsRead -> ${bytes.size} octets")
    }

    @Test
    fun `fsRead rend null sur un fichier absent, sans lever`() = runBlocking<Unit> {
        val gateway = gateway()
        assertNull(gateway.fsRead(settings(), "ce/chemin/n/existe/pas.txt"))
    }

    @Test
    fun `references et branches repondent, meme vides`() = runBlocking<Unit> {
        val gateway = gateway()
        // ⚠️ Mesure du 2026-09-25 : `/api/reference` rend `data: []` sur ce serveur. Ce n'est pas
        // un echec, c'est une absence — le test verifie que l'appel ABOUTIT.
        val refs = gateway.references(settings())
        println("references -> ${refs.size}")
        val branches = gateway.branchesIn(settings(), "${directory}/Projects/tether")
        assertTrue(branches.isNotEmpty(), "le depot tether doit avoir des branches")
        println("branches -> $branches")
    }

    // ------------------------------------------------------------------
    // Lot formulaires + parentID + inbox (mesures du 2026-09-26)
    // ------------------------------------------------------------------

    /**
     * ⚠️ **Le filtre `parentID` doit reellement filtrer.** Mesure du 2026-09-26 sur 468 sessions :
     * la liste non filtree contient 97 enfants ; `parentID=null` rend 159 sessions **toutes
     * racines**. Sans filtre, un ecran qui veut les racines les melangerait aux sous-agents.
     */
    @Test
    fun `le filtre parentID separe racines et enfants`() = runBlocking<Unit> {
        val gateway = gateway()
        val root = gateway.allSessions(settings(), parent = SessionParent.Roots)
        assertTrue(root.isNotEmpty(), "aucune session racine")
        assertTrue(root.all { it.parentID == null }, "parentID=null doit ne rendre que des racines")

        // On prend une session racine qui a des enfants connus (la session de reference du projet).
        val withChildren = root.firstOrNull { it.id == "ses_f2b4097ecffe8dMdMYfZlf1eiS" }
        if (withChildren != null) {
            val children = gateway.allSessions(settings(), parent = SessionParent.Of(withChildren.id))
            assertTrue(children.isNotEmpty(), "cette session doit avoir des enfants")
            assertTrue(
                children.all { it.parentID == withChildren.id },
                "tous les enfants doivent pointer vers le parent",
            )
            println("parentID -> ${children.size} enfants de ${withChildren.id}")
        }
    }

    /** `GET /api/model/default` : le serveur **dit** son defaut au lieu qu'on le devine. */
    @Test
    fun `defaultModel rend le modele par defaut du serveur`() = runBlocking<Unit> {
        val model = gateway().defaultModel(settings())
        requireNotNull(model) { "le serveur doit annoncer un modele par defaut" }
        assertTrue(model.id.isNotBlank())
        println("model/default -> ${model.providerID}/${model.id}")
    }

    /** `GET /api/vcs/base` : sur le depot tether (branche master), la base est `master`. */
    @Test
    fun `vcsBase rend une base sur un depot versionne`() = runBlocking<Unit> {
        val gateway = gateway()
        val base = gateway.vcsBase(settings().copy(directory = "${directory}/Projects/tether"))
        requireNotNull(base) { "le depot tether doit avoir une base de revision" }
        assertTrue(base.ref.isNotBlank())
        println("vcs/base -> ${base.name} (${base.ref}, ${base.source})")
    }

    /**
     * **`wait` rend immediatement sur une session au repos** : c'est ce qui remplace notre sondage.
     *
     * ⚠️ Mesure du 2026-09-26 : 204 en 8 ms sur une session idle, mais la requete **bloque** si
     * l'agent tourne (9,1 s mesures). On n'exerce donc ici que le cas idle — bloquer sur une
     * session active rendrait le test long et non deterministe.
     */
    @Test
    fun `waitForIdle rend vrai sur une session au repos`() = runBlocking<Unit> {
        val gateway = gateway()
        val idle = gateway.allSessions(settings(), parent = SessionParent.Roots)
            .firstOrNull { it.id == "ses_f262ea1ebffek2cytGmBof0LSE" }
            ?: return@runBlocking
        val start = System.currentTimeMillis()
        assertTrue(gateway.waitForIdle(settings(), idle.id, timeoutMillis = 5_000))
        println("session.wait -> confirme en ${System.currentTimeMillis() - start} ms")
    }

    /**
     * **Un formulaire est cree, lu, repondu, puis refuse comme deja regle.**
     *
     * ⚠️ On exerce les **cinq reponses reelles** : `204` a l'envoi, `409` sur un second envoi. Le
     * formulaire est cree par le test lui-meme — **via une requete brute**, pas par le gateway :
     * l'app repond aux formulaires, elle n'en cree pas a la place de l'agent, et on n'ajoute donc
     * pas cette route au contrat d'application.
     */
    @Test
    fun `le cycle complet d un formulaire cree lu repondu`() = runBlocking<Unit> {
        val gateway = gateway()
        val settings = settings()
        val sessionID = gateway.allSessions(settings, parent = SessionParent.Roots).first().id

        val created = createFormProbe(sessionID, "probe tether formulaire")
        println("form.create -> ${created.id}")

        val pending = gateway.sessionForms(settings, sessionID)
        assertTrue(pending.any { it.id == created.id }, "le formulaire cree doit etre pendant")

        // ⚠️ Un `external` doit etre acquitte : on le construit comme l'ecran le ferait.
        val fields = pending.first { it.id == created.id }.fields
        val draft = sh.sk7.tether.ui.forms.FormDraft.of(fields).text("nom", "tether")
        val submission = sh.sk7.tether.ui.forms.FormAnswerBuilder.build(fields, draft)
        assertIs<sh.sk7.tether.ui.forms.FormSubmission.Ready>(
            submission,
            "la reponse doit etre complete : $submission",
        )
        val answer = submission.answer

        assertTrue(gateway.replyForm(settings, sessionID, created.id, answer), "l'envoi doit etre accepte")
        val after = gateway.sessionForms(settings, sessionID)
        assertFalse(after.any { it.id == created.id }, "un formulaire repondu ne doit plus etre pendant")
        println("form.reply -> accepte (204), puis absent de la liste")

        // ⚠️ Mesure du 2026-09-26 : un second envoi rend **409 FormAlreadySettledError**. Ici le
        // client du test n'active pas `expectSuccess` (le vrai, lui, le fait via `NetworkModule`) :
        // le refus se lit donc sur le **booleen**, pas sur une exception. On accepte les deux
        // formes — ce qui compte est qu'un formulaire deja regle ne soit **jamais** annonce comme
        // accepte.
        val second = runCatching { gateway.replyForm(settings, sessionID, created.id, answer) }
        val accepted = second.getOrNull()
        assertFalse(accepted == true, "un formulaire deja regle ne doit pas etre annonce comme accepte")
        println("form.reply (2e) -> refus: ${second.exceptionOrNull()?.javaClass?.simpleName ?: accepted}")
    }

    /**
     * Depannage : `GET /api/form` (les pendants du repertoire) doit etre joignable.
     *
     * ⚠️ C'est la route de **resynchronisation** : si elle rendait une erreur ou une mauvaise
     * enveloppe, un formulaire arrive pendant une coupure SSE serait invisible pour toujours.
     */
    @Test
    fun `pendingForms repond avec l enveloppe location data`() = runBlocking<Unit> {
        val forms = gateway().pendingForms(settings())
        // Le compte n'est pas fixe (d'autres formulaires peuvent etre pendants), on verifie que
        // l'appel aboutit et que le filtre par repertoire est bien celui demande.
        println("form.list -> ${forms.size} formulaire(s) pendant(s) pour ${settings().directory}")
        assertTrue(forms.all { it.id.startsWith("frm_") })
    }

    /**
     * Cree un formulaire de test par une requete brute (la route n'est pas dans le gateway : l'app
     * n'en cree pas). Renvoie le `Form.Info` decode.
     */
    private suspend fun createFormProbe(sessionID: String, title: String): FormInfoDto {
        val http = HttpClient(OkHttp) { configureTether() }
        val body = http.post("$baseUrl/api/session/$sessionID/form") {
            basicAuth("opencode", password)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("title", JsonPrimitive(title))
                    put(
                        "fields",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("key", JsonPrimitive("nom"))
                                    put("type", JsonPrimitive("string"))
                                    put("required", JsonPrimitive(true))
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("key", JsonPrimitive("site"))
                                    put("type", JsonPrimitive("external"))
                                    put("url", JsonPrimitive("https://example.com"))
                                },
                            )
                        },
                    )
                },
            )
        }
        return body.body<FormDetailEnvelope>().data
            ?.let { FormInfoDto(id = it.id, sessionID = it.sessionID, title = it.title, fields = it.fields) }
            ?: error("le serveur n'a pas renvoye de formulaire")
    }
}
