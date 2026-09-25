package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import sh.sk7.tether.data.settings.ConnectionSettings

/**
 * **Ce que le client met reellement dans ses URL.**
 *
 * ### Pourquoi ces tests existent
 * Le bug corrige le 2026-09-25 etait invisible : sept routes d'inventaire declarent leur
 * parametre `location` en `style: deepObject` (`location[directory]=...`). Le client envoyait
 * `directory=...`, qui est **silencieusement ignore** — le serveur retombait sur son repertoire de
 * travail courant. Tout fonctionnait, parce que ce repertoire se trouvait etre le bon.
 *
 * ⚠️ Un test d'integration n'aurait **rien vu** : il aurait obtenu les bonnes donnees. Seule
 * l'inspection de l'URL emise revele la faute. C'est exactement ce que fait ce fichier : il ne
 * teste pas le resultat, il teste **la requete**.
 *
 * ⚠️ Le `MockEngine` n'est pas la pour simuler un serveur : il est la pour **capturer** l'URL. Les
 * reponses sont vides et n'ont aucune importance.
 */
class OpenCodeClientUrlTest {

    /** Capture les URL demandees et repond une charge vide valide. */
    private class Recorder {
        val urls = mutableListOf<String>()

        /** Corps a renvoyer selon le chemin, pour satisfaire le decodage. */
        var body: String = """{"data":[]}"""

        val client: HttpClient = HttpClient(MockEngine { request ->
            urls += request.url.toString()
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }) {
            install(ContentNegotiation) { json(OpenCodeClient.json) }
        }
    }

    private fun client(recorder: Recorder) = OpenCodeClient(
        baseUrl = "http://serveur.test:4096",
        credentials = BasicAuthCredentials("opencode", "x"),
        http = recorder.client,
    )

    @Test
    fun `les routes d inventaire envoient location crochet directory`() = runBlocking {
        // ⚠️ Ce sont EXACTEMENT les routes declarees `style: deepObject` dans /openapi.json.
        // Les lister ici est le but du test : si une route s'ajoute au client sans le helper, ce
        // test ne la couvrira pas — mais les cinq-ci ne peuvent plus regresser.
        val cases: List<Pair<String, suspend (OpenCodeClient) -> Unit>> = listOf(
            "/api/command" to { c -> c.commands("/tmp/ailleurs") },
            "/api/skill" to { c -> c.skills("/tmp/ailleurs") },
            "/api/mcp" to { c -> c.mcpServers("/tmp/ailleurs") },
            "/api/plugin" to { c -> c.plugins("/tmp/ailleurs") },
            "/api/provider" to { c -> c.providers("/tmp/ailleurs") },
        )

        cases.forEach { (path, call) ->
            val recorder = Recorder()
            call(client(recorder))

            val url = recorder.urls.single()
            assertTrue(url.startsWith("http://serveur.test:4096$path"), "mauvais chemin : $url")
            // ⚠️ L'encodage est celui de l'URL : `[` devient `%5B` et `]` devient `%5D`. On
            // accepte les deux formes, ce qui compte est que le nom soit `location[directory]`.
            val encoded = url.contains("location%5Bdirectory%5D=%2Ftmp%2Failleurs")
            val decoded = url.contains("location[directory]=/tmp/ailleurs")
            assertTrue(encoded || decoded, "location[directory] absent de : $url")
        }
    }

    @Test
    fun `le repertoire passe est bien celui demande, pas celui du serveur`() = runBlocking {
        val recorder = Recorder()
        client(recorder).commands("/un/repertoire/different")

        val url = recorder.urls.single()
        // ⚠️ Le point du bug : sans ce nom exact, le serveur repondait pour SON repertoire. On
        // verifie donc que la valeur demandee est bien celle transmise.
        assertTrue(
            url.contains("un%2Frepertoire%2Fdifferent") || url.contains("un/repertoire/different"),
            "le repertoire demande est absent de : $url",
        )
    }

    @Test
    fun `sessionsPage garde directory simple, seule exception`() = runBlocking {
        val recorder = Recorder()
        // `GET /api/session` declare `directory` en `style: None` : le passer en deepObject
        // casserait la route. C'est la seule qui ne passe pas par le helper, et ce testle fige.
        client(recorder).sessionsPage(
            directory = "/mon/repertoire",
            limit = 10,
            cursor = null,
        )

        val url = recorder.urls.single()
        assertTrue(url.contains("directory="), "directory simple attendu : $url")
        assertTrue(!url.contains("location%5B"), "location[] ne doit PAS etre utilise ici : $url")
    }

    @Test
    fun `vcs diff envoie le mode requis`() = runBlocking {
        val recorder = Recorder()
        client(recorder).vcsDiff("/tmp/ailleurs", mode = "working")

        val url = recorder.urls.single()
        // ⚠️ Sans `mode`, le serveur rend `400 Missing key at ["mode"]` (mesure).
        assertTrue(url.contains("mode=working"), "mode requis absent de : $url")
        assertTrue(
            url.contains("location%5Bdirectory%5D") || url.contains("location[directory]"),
            "location[directory] absent de : $url",
        )
    }

    @Test
    fun `worktree envoie un projectID, jamais un chemin`() = runBlocking {
        val recorder = Recorder()
        // ⚠️ `GET /api/worktree` renvoie `Worktree.List`, c'est-a-dire un **tableau nu** — pas
        // l'enveloppe `{data:[...]}` des autres routes (releve sur /openapi.json). Le mock doit
        // donc rendre `[]`, sinon le decodage echoue et le test ne prouve plus rien.
        recorder.body = "[]"
        client(recorder).worktrees("ac3ac0f7b02c57780a93d78e63a6c94b41daf5b5")

        val url = recorder.urls.single()
        // ⚠️ Mesure : `directory=` sur cette route rend
        // `400 InvalidRequestError Missing key at ["projectID"]`.
        assertTrue(url.contains("projectID=ac3ac0f7b02c57780a93d78e63a6c94b41daf5b5"), url)
        assertTrue(!url.contains("directory="), "cette route n'accepte pas directory : $url")
    }

    @Test
    fun `les stats n envoient pas de location fantome`() = runBlocking {
        val recorder = Recorder()
        recorder.body = """{"data":{"sessions":1}}"""
        client(recorder).stats("/tmp/ailleurs", fromMillis = 1_790_000_000_000L)

        val url = recorder.urls.single()
        // ⚠️ `/api/experimental/session/stats` ne declare ni `location` ni `directory` : ses
        // parametres sont `from, to, project, timezone, tools`. Envoyer `location[directory]`
        // serait un parametre ignore de plus — et ferait croire a un filtre qui n'existe pas.
        assertTrue(url.contains("from=1790000000000"), url)
        assertTrue(!url.contains("location"), "aucun location attendu : $url")
        assertTrue(!url.contains("directory"), "aucun directory attendu : $url")
    }
}
