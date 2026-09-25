package sh.sk7.tether.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.sk7.tether.data.settings.ConnectionSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Ce que le client met dans ses requetes, et ce qu'il lit, pour les routes du lot formulaires.**
 *
 * ### Pourquoi ces tests ne sont pas des tests d'integration
 * Les formes exercees ici ont ete **relevees sur le serveur de le serveur le 2026-09-26** (voir les
 * commentaires de chaque methode) ; l'integration serait donc rejouable, mais plus lente et
 * dependante d'un serveur. Ce qui doit etre fige, c'est le **contrat d'appel** : un chemin, une
 * enveloppe, un corps, et surtout les cas ou une erreur **silencieuse** serait possible.
 *
 * ⚠️ Trois pieges y sont encodés en tests parce qu'ils sont **silencieux** s'ils reviennent :
 *  - `GET /api/form` a une enveloppe `{location, data}` alors que `GET /api/session/{id}/form` a
 *    `{data}` : se tromper rend une liste vide **sans erreur** ;
 *  - `parentID=null` doit partir en **chaine litterale** `"null"`, sinon le filtre ne s'applique
 *    pas et les sous-agents se melangent aux racines ;
 *  - `replyForm` et `updateInboxDelivery` rendent **204 sans corps** : decoder ferait lever sur une
 *    operation reussie (le bug historique de `renameSession`).
 */
class FormRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private var lastRequest: io.ktor.client.request.HttpRequestData? = null
    private var lastBody: String? = null

    private fun client(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): OpenCodeClient {
        val engine = MockEngine { request ->
            lastRequest = request
            lastBody = (request.body as? io.ktor.http.content.TextContent)?.text
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            // ⚠️ `waitForIdle` pose un timeout par requete : le plugin doit etre installe, comme il
            // l'est en production (voir `NetworkModule`).
            install(io.ktor.client.plugins.HttpTimeout)
        }
        return OpenCodeClient("http://host:4096", BasicAuthCredentials(password = "x"), http)
    }

    private fun query(name: String): String? =
        lastRequest?.url?.parameters?.get(name)

    // ------------------------------------------------------------------
    // GET /api/form : enveloppe {location, data}
    // ------------------------------------------------------------------

    @Test
    fun `forms lit l enveloppe location data et envoie le repertoire en deepObject`() = runBlocking {
        // ⚠️ Reponse reelle du serveur : `{"location":{"directory":"/tmp/opencode"},"data":[...]}`.
        // Lire `{data}` au lieu de `{location, data}` rendrait une liste vide sans erreur.
        val body = """
            {"location":{"directory":"/tmp/opencode"},"data":[
              {"id":"frm_1","sessionID":"ses_1","title":"probe","fields":[{"key":"q","type":"string"}]}
            ]}
        """.trimIndent()
        val client = client(body)

        val forms = client.forms("/tmp/opencode")

        assertEquals(1, forms.size)
        assertEquals("frm_1", forms.first().id)
        assertEquals("ses_1", forms.first().sessionID)
        assertEquals("string", forms.first().fields.first().type)
        assertEquals("/tmp/opencode", query("location[directory]"))
    }

    // ------------------------------------------------------------------
    // GET /api/session/{id}/form : enveloppe {data} SIMPLE
    // ------------------------------------------------------------------

    @Test
    fun `sessionForms lit l enveloppe data simple et vise la session`() = runBlocking {
        // ⚠️ Reponse reelle : `{"data":[{"id":"frm_…","sessionID":"ses_…",…}]}` — PAS de `location`.
        val body = """{"data":[{"id":"frm_9","sessionID":"global","title":"mcp","fields":[{"key":"k","type":"string"}]}]}"""
        val client = client(body)

        val forms = client.sessionForms("global")

        assertEquals(1, forms.size)
        assertTrue(forms.first().isGlobal, "sessionID global doit etre reconnu")
        assertTrue(lastRequest!!.url.encodedPath.endsWith("/api/session/global/form"))
    }

    @Test
    fun `un formulaire global n est pas une session`() = runBlocking {
        val body = """{"data":[{"id":"frm_1","sessionID":"global","title":"t","fields":[]}]}"""
        val form = client(body).sessionForms("global").first()
        assertTrue(form.isGlobal)
        assertEquals(FormInfoDto.GLOBAL_SESSION_ID, form.sessionID)
    }

    // ------------------------------------------------------------------
    // GET /api/session/{id}/form/{formID} : objet + etat
    // ------------------------------------------------------------------

    @Test
    fun `sessionForm lit l objet detail avec son etat`() = runBlocking {
        // ⚠️ Reponse reelle : `{"data":{"id":…,"fields":[…],"state":{"status":"answered","answer":{"q":"hi"}}}}`.
        val body = """
            {"data":{"id":"frm_1","sessionID":"ses_1","title":"t","fields":[{"key":"q","type":"string"}],
             "state":{"status":"answered","answer":{"q":"hi"}}}}
        """.trimIndent()
        val detail = client(body).sessionForm("ses_1", "frm_1")

        assertEquals("frm_1", detail?.id)
        assertEquals("answered", detail?.state?.status)
        assertFalse(detail!!.state!!.isPending)
        assertEquals(JsonPrimitive("hi"), detail.state.answer!!["q"])
    }

    @Test
    fun `sessionForm rend null si le serveur ne donne pas d objet`() = runBlocking {
        assertNull(client("""{"data":null}""").sessionForm("ses_1", "frm_1"))
    }

    // ------------------------------------------------------------------
    // POST reply : 204 sans corps, on ne decode rien
    // ------------------------------------------------------------------

    @Test
    fun `replyForm poste answer et n essaie pas de decoder un 204`() = runBlocking {
        // ⚠️ Reponse reelle : **204 sans corps**. Tenter de decoder leverait
        // `NoTransformationFoundException` sur une reponse pourtant acceptee.
        val client = client("", HttpStatusCode.NoContent)

        val ok = client.replyForm(
            sessionID = "ses_1",
            formID = "frm_1",
            answer = buildJsonObject { put("q", JsonPrimitive("hi")) },
        )

        assertTrue(ok)
        assertTrue(lastRequest!!.url.encodedPath.endsWith("/api/session/ses_1/form/frm_1/reply"))
        assertEquals("POST", lastRequest!!.method.value)
        assertTrue(lastBody!!.contains("\"answer\""), lastBody!!)
        assertTrue(lastBody!!.contains("\"q\""), lastBody!!)
    }

    // ------------------------------------------------------------------
    // PATCH inbox : 204 sans corps, delivery en chaine
    // ------------------------------------------------------------------

    @Test
    fun `updateInboxDelivery patche la livraison en chaine`() = runBlocking {
        val client = client("", HttpStatusCode.NoContent)

        val ok = client.updateInboxDelivery("ses_1", "msg_2", InboxDelivery.QUEUE)

        assertTrue(ok)
        assertEquals("PATCH", lastRequest!!.method.value)
        assertTrue(lastRequest!!.url.encodedPath.endsWith("/api/session/ses_1/inbox/msg_2"))
        // ⚠️ `delivery` est une CHAINE (`Session.Inbox.Delivery`), pas un objet.
        assertEquals("""{"delivery":"queue"}""", lastBody)
    }

    @Test
    fun `InboxDelivery reconnait seulement steer et queue`() {
        assertTrue(InboxDelivery.isValid("steer"))
        assertTrue(InboxDelivery.isValid("queue"))
        assertFalse(InboxDelivery.isValid("bogus"))
        assertFalse(InboxDelivery.isValid(null))
    }

    // ------------------------------------------------------------------
    // parentID : filtre explicite, dont la chaine "null"
    // ------------------------------------------------------------------

    @Test
    fun `parentID absent ne filtre pas`() = runBlocking {
        client("""{"data":[],"cursor":null}""").sessionsPage("/d", limit = 10)
        assertNull(query("parentID"), "sans filtre, aucun parentID ne doit partir")
    }

    @Test
    fun `parentID d un parent part en identifiant de session`() = runBlocking {
        client("""{"data":[],"cursor":null}""")
            .sessionsPage("/d", limit = 10, parent = SessionParent.Of("ses_root"))
        assertEquals("ses_root", query("parentID"))
    }

    @Test
    fun `parentID racines part en chaine litterale null`() = runBlocking {
        // ⚠️ Mesure du 2026-09-26 : `parentID=null` (chaine) rend UNIQUEMENT les racines (159 sur
        // 468). Omettre le parametre renvoie tout : la confusion est silencieuse et melange les
        // sous-agents aux conversations.
        client("""{"data":[],"cursor":null}""")
            .sessionsPage("/d", limit = 10, parent = SessionParent.Roots)
        assertEquals("null", query("parentID"))
    }

    @Test
    fun `allSessions propage le filtre parent a chaque page`() = runBlocking {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            respond(
                content = """{"data":[{"id":"ses_${urls.size}","parentID":"ses_root"}],"cursor":{"next":"p${urls.size}"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val http = HttpClient(engine) { install(ContentNegotiation) { json(json) } }
        val gateway = KtorOpenCodeGateway(http, InMemoryCredentialsProvider(BasicAuthCredentials(password = "x")))

        gateway.allSessions(
            ConnectionSettings(password = "x", directory = "/d"),
            pageSize = 1,
            parent = SessionParent.Of("ses_root"),
        )

        assertTrue(urls.size >= 2, "le curseur doit etre suivi")
        assertTrue(urls.all { it.contains("parentID=ses_root") }, urls.toString())
    }

    // ------------------------------------------------------------------
    // GET /api/model/default et /api/vcs/base : data objet ou null
    // ------------------------------------------------------------------

    @Test
    fun `defaultModel lit un objet dans data et envoie location`() = runBlocking {
        // ⚠️ Reponse reelle : `{"location":{…},"data":{"id":"glm-5.3-flash","providerID":"ollama-cloud",…}}`.
        val body = """{"location":{"directory":"/d"},"data":{"id":"glm-5.3-flash","providerID":"ollama-cloud"}}"""
        val model = client(body).defaultModel("/d")

        assertEquals("glm-5.3-flash", model?.id)
        assertEquals("ollama-cloud", model?.providerID)
        assertEquals("/d", query("location[directory]"))
    }

    @Test
    fun `defaultModel rend null quand data est null`() = runBlocking {
        assertNull(client("""{"location":{"directory":"/d"},"data":null}""").defaultModel("/d"))
    }

    @Test
    fun `vcsBase lit name ref source`() = runBlocking {
        // ⚠️ Reponse reelle : `{"location":{…},"data":{"name":"master","ref":"refs/heads/master","source":"default"}}`.
        val body = """{"location":{"directory":"/d"},"data":{"name":"master","ref":"refs/heads/master","source":"default"}}"""
        val base = client(body).vcsBase("/d")

        assertEquals("master", base?.name)
        assertEquals("refs/heads/master", base?.ref)
        assertEquals("default", base?.source)
    }

    @Test
    fun `waitForIdle poste sur la route et n essaie pas de decoder un 204`() = runBlocking {
        // ⚠️ Mesure : 204 sans corps sur une session idle. Decoder leverait sur un succes.
        val client = client("", HttpStatusCode.NoContent)

        assertTrue(client.waitForIdle("ses_1", timeoutMillis = 1_000))
        assertEquals("POST", lastRequest!!.method.value)
        assertTrue(lastRequest!!.url.encodedPath.endsWith("/api/experimental/session/ses_1/wait"))
    }

    @Test
    fun `waitForIdle par le gateway abandonne au timeout sans lever`() = runBlocking {
        // ⚠️ Un agent bloque sur une permission ne devient **jamais** idle : sans borne, l'appel
        // resterait suspendu pour toujours. `false` signifie « pas confirme », pas « echec ».
        val engine = MockEngine {
            kotlinx.coroutines.delay(10_000)
            respond("", HttpStatusCode.NoContent)
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(io.ktor.client.plugins.HttpTimeout)
        }
        val gateway = KtorOpenCodeGateway(http, InMemoryCredentialsProvider(BasicAuthCredentials(password = "x")))

        val confirmed = gateway.waitForIdle(ConnectionSettings(password = "x"), "ses_1", timeoutMillis = 50)

        assertFalse(confirmed)
    }

    // ------------------------------------------------------------------
    // FormAnswerValue : la forme JSON exacte envoyee
    // ------------------------------------------------------------------

    @Test
    fun `FormAnswerValue serialise chaque type dans sa forme exacte`() {
        assertEquals(""""x"""", FormAnswerValue.Text("x").toJson().toString())
        assertEquals("3", FormAnswerValue.Integer(3).toJson().toString())
        assertEquals("3.5", FormAnswerValue.Decimal(3.5).toJson().toString())
        assertEquals("true", FormAnswerValue.Flag(true).toJson().toString())
        assertEquals("""["a","b"]""", FormAnswerValue.Items(listOf("a", "b")).toJson().toString())
    }

    @Test
    fun `un entier ne porte jamais de point decimal`() {
        // ⚠️ Mesure : un `3.0` envoye a un `integer`... est accepte, mais un `Decimal(3.0)`
        // enverrait `3.0` la ou `3` est la forme attendue. On verifie la forme exacte.
        assertFalse(FormAnswerValue.Integer(3).toJson().toString().contains("."))
    }

    // ------------------------------------------------------------------
    // Risque de blocage silencieux : la permission d'une session ENFANT
    // ------------------------------------------------------------------

    /**
     * **Une `permission.asked` peut appartenir a une session enfant — et c'est un risque mesure.**
     *
     * ### La mesure du 2026-09-26 (modele 2.0.x)
     * Un `task` de sous-agent a produit :
     * ```
     * permission.asked  data.sessionID = ses_f256b406dffeS1tX3zAJYIMYJD   <- session ENFANT
     *                   data.parentID  = ses_f256eb69bffe4gZTmuOEzc9Shs   <- session RACINE
     * GET /api/session/<RACINE>/permission -> {"data":[]}                  <- la racine ne la voit PAS
     * GET /api/permission/request          -> [la permission, sessionID=enfant]
     * ```
     *
     * ⚠️ **Le `sessionID` de la demande n'est jamais celui de la session racine.** Un ecran qui
     * filtrerait « les permissions de MA session » jetterait cet evenement, et l'agent (le parent)
     * resterait bloque sans que rien ne l'indique — exactement le defaut de l'issue #44747.
     *
     * ⚠️ Ce test fige le **mecanisme** : le gateway transporte le `sessionID` **du serveur**, sans
     * jamais le remapper vers un parent. C'est ce qui rend possible de le traiter globalement
     * (`GET /api/permission/request`), seule lecture qui voit les enfants.
     */
    @Test
    fun `pendingPermissions preserve le sessionID enfant du serveur`() = runBlocking {
        // ⚠️ Enveloppe reelle `{location, data}` avec le sessionID de l'ENFANT.
        val body = """
            {"location":{"directory":"/tmp/opencode"},"data":[
              {"id":"per_1","sessionID":"ses_enfant","action":"shell",
               "resources":["uname -a"],"save":["uname *"]}
            ]}
        """.trimIndent()
        val http = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json(json) } }
        val gateway = KtorOpenCodeGateway(http, InMemoryCredentialsProvider(BasicAuthCredentials(password = "x")))

        val pending = gateway.pendingPermissions(ConnectionSettings(password = "x", directory = "/tmp/opencode"))

        assertEquals("ses_enfant", pending.single().sessionID)
    }
}
