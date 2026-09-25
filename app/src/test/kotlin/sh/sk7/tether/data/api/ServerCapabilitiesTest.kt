package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Les capacites serveur du palier 3 : ce que le client met dans ses requetes, et ce qu'il lit.**
 *
 * ### Pourquoi ces tests ne sont pas des tests d'integration
 * Les formes exercees ici ont ete **relevees sur le serveur de le serveur le 2026-09-25** (voir les
 * commentaires de chaque methode) ; l'integration serait donc rejouable, mais plus lente et
 * dependante d'un serveur. Ce qui doit etre fige, c'est le **contrat d'appel** : un chemin dans
 * l'URL, un `data:` inline, un corps brut, une liste de chaines.
 *
 * ⚠️ Trois pieges y sont encodés en tests parce qu'ils sont **silencieux** s'ils reviennent :
 *  - `fsRead` met le chemin dans l'URL (joker) et lit des **octets**, pas du JSON ;
 *  - `branches` decode un tableau de **chaines nues**, pas d'objets ;
 *  - `activateSkill` accepte un **204 sans corps** et ne doit pas tenter de le decoder.
 */
class ServerCapabilitiesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private var lastRequest: io.ktor.client.request.HttpRequestData? = null

    private fun mockClient(body: String, contentType: String = "application/json"): HttpClient {
        val engine = MockEngine { request ->
            lastRequest = request
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(json) } }
    }

    private fun client(body: String, contentType: String = "application/json") =
        OpenCodeClient(
            baseUrl = "http://host:4096",
            credentials = BasicAuthCredentials(password = "x"),
            http = mockClient(body, contentType),
        )

    // ------------------------------------------------------------------
    // 3.1 Pieces jointes de prompt
    // ------------------------------------------------------------------

    @Test
    fun `le prompt avec pieces jointes signe files agents et skills`() = runBlocking {
        val body = """{"data":{"id":"msg_1","sessionID":"ses_1","type":"user"}}"""
        val c = client(body)
        val acceptance = c.prompt(
            sessionID = "ses_1",
            body = PromptBody(
                text = "regarde ça",
                files = listOf(PromptFileAttachment(uri = "data:text/plain;base64,aGVsbG8=", name = "note.txt")),
                agents = listOf(PromptAgentAttachment(name = "build")),
                skills = listOf(PromptSkillAttachment(id = "test-driven-development")),
            ),
        )
        assertEquals("msg_1", acceptance.id)

        val sent = String(lastRequest!!.body.toByteArray())
        // ⚠️ Mesure : ces trois cles existent au premier niveau du corps. Les envoyer ailleurs
        // (dans `metadata` par exemple) serait accepte par le serveur et **totalement ignore**.
        assertContains(sent, """"files":[""")
        assertContains(sent, """"agents":[""")
        assertContains(sent, """"skills":[""")
        assertContains(sent, """"uri":"data:text/plain;base64,aGVsbG8="""")
        assertContains(sent, """"name":"build"""")
        assertContains(sent, """"id":"test-driven-development"""")
    }

    @Test
    fun `un prompt sans piece jointe n envoie pas de listes vides`() = runBlocking {
        val body = """{"data":{"id":"msg_2","sessionID":"ses_1","type":"user"}}"""
        val c = client(body)
        c.prompt("ses_1", "bonjour")

        val sent = String(lastRequest!!.body.toByteArray())
        // ⚠️ Un corps minimal : envoyer `"files":[]` serait un mensonge d'API de plus, et le
        // serveur n'en a pas besoin. Le defaut de `PromptBody` est la liste vide, donc ce test
        // verifie que la serialisation **omet** bien le champ plutot que de l'ecrire vide.
        assertContains(sent, """"text":"bonjour"""")
        assertTrue(!sent.contains("\"files\""), "files ne doit pas apparaitre : $sent")
        assertTrue(!sent.contains("\"agents\""), "agents ne doit pas apparaitre : $sent")
        assertTrue(!sent.contains("\"skills\""), "skills ne doit pas apparaitre : $sent")
    }

    // ------------------------------------------------------------------
    // 3.2 Activer un skill
    // ------------------------------------------------------------------

    @Test
    fun `activateSkill vise la route experimentale et ne decode pas de corps`() = runBlocking {
        // ⚠️ Mesure : 204 sans corps. Si le client tentait de decoder, il leverait
        // `NoTransformationFoundException` et l'app annoncerait un echec sur un succes.
        val engine = MockEngine { request ->
            lastRequest = request
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val c = OpenCodeClient("http://host:4096", BasicAuthCredentials(password = "x"), http)

        assertTrue(c.activateSkill("ses_1", "opencode"))
        assertEquals(
            "http://host:4096/api/experimental/session/ses_1/skill",
            lastRequest!!.url.toString(),
        )
        assertContains(String(lastRequest!!.body.toByteArray()), """"id":"opencode"""")
    }

    // ------------------------------------------------------------------
    // 3.3 Explorateur de fichiers
    // ------------------------------------------------------------------

    @Test
    fun `fsList envoie location et path relatif`() = runBlocking {
        val body = """{"location":{"directory":"/home/utilisateur"},"data":[
            {"path":"app/","type":"directory"},{"path":"build.gradle.kts","type":"file"}]}"""
        val c = client(body)
        val entries = c.fsList("/home/utilisateur", "Projects/tether")

        assertEquals(2, entries.size)
        assertEquals("build.gradle.kts", entries[1].path)
        assertTrue(entries[0].isDirectory)
        assertTrue(!entries[1].isDirectory)

        val req = lastRequest!!
        assertEquals("http://host:4096/api/fs/list", req.url.toString().substringBefore("?"))
        assertEquals("Projects/tether", req.url.parameters["path"])
        assertEquals("/home/utilisateur", req.url.parameters["location[directory]"])
    }

    @Test
    fun `fsFind signe query type et limit comme chaine`() = runBlocking {
        // ⚠️ `limit` est declare `type: string` dans l'OpenAPI : l'envoyer en entier tel quel
        // marcherait, mais ce test fige la forme reellement emise.
        val body = """{"location":{"directory":"/tmp"},"data":[{"path":"gradlew","type":"file"}]}"""
        val c = client(body)
        val entries = c.fsFind("/tmp", "gradlew", type = "file", limit = 3)

        assertEquals(1, entries.size)
        val req = lastRequest!!
        assertEquals("gradlew", req.url.parameters["query"])
        assertEquals("file", req.url.parameters["type"])
        assertEquals("3", req.url.parameters["limit"])
    }

    // ------------------------------------------------------------------
    // 3.4 Lire un fichier
    // ------------------------------------------------------------------

    @Test
    fun `fsRead met le chemin dans l URL sans encoder les separateurs`() = runBlocking {
        val c = client("pluginManagement { }", contentType = "application/octet-stream")
        val bytes = c.fsRead("/home/utilisateur", "Projects/tether/settings.gradle.kts")

        assertEquals("pluginManagement { }", bytes?.decodeToString())
        val req = lastRequest!!
        val pathOnly = req.url.toString().substringBefore("?")
        assertTrue(
            pathOnly == "http://host:4096/api/fs/read/Projects/tether/settings.gradle.kts",
            "le chemin doit rester lisible, segment par segment : $pathOnly",
        )
        // ⚠️ Si une barre oblique du CHEMIN etait encodee en `%2F`, le serveur chercherait un
        // fichier nomme « Projects/tether/settings.gradle.kts » a la racine — 404 silencieux. On
        // ne regarde donc que la partie chemin : le `location` en query, lui, s'encode legitimement.
        assertTrue(!pathOnly.contains("%2F"), "aucune barre oblique encodee dans le chemin : $pathOnly")
        assertEquals("/home/utilisateur", req.url.parameters["location[directory]"])
    }

    @Test
    fun `fsRead rend null sur un fichier absent, pas une exception`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"_tag":"FileNotFoundError"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val c = OpenCodeClient("http://host:4096", BasicAuthCredentials(password = "x"), http)

        // ⚠️ Mesure : un fichier absent rend `404 FileNotFoundError`. C'est une **absence**, pas un
        // echec — meme distinction que « pas un depot » pour `vcsInfo`.
        assertNull(c.fsRead("/home/utilisateur", "n/existe/pas.txt"))
    }

    // ------------------------------------------------------------------
    // 3.7 References, 3.8 Branches
    // ------------------------------------------------------------------

    @Test
    fun `references decode l enveloppe location data`() = runBlocking {
        val body = """{"location":{"directory":"/tmp"},"data":[
            {"name":"tether","path":"docs/","description":"le plan","hidden":false}]}"""
        val c = client(body)
        val refs = c.references("/tmp")

        assertEquals(1, refs.size)
        assertEquals("tether", refs[0].name)
        assertEquals("le plan", refs[0].description)
    }

    @Test
    fun `branches decode un tableau de chaines nues`() = runBlocking {
        // ⚠️ `Vcs.BranchList` = `array of string`. Attendre `{name:...}` rendrait une liste
        // **vide sans erreur** : le selecteur serait vide et personne ne saurait pourquoi.
        val body = """{"location":{"directory":"/tmp"},"data":["master","feat/palier-1-2-etat"]}"""
        val c = client(body)
        val branches = c.branches("/tmp")

        assertEquals(listOf("master", "feat/palier-1-2-etat"), branches)
        assertEquals("/tmp", lastRequest!!.url.parameters["location[directory]"])
    }
}
