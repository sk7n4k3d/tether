package sh.sk7.tether.ui.settings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **La distinction « identifiants refusés » / « injoignable » — le test qui manquait.**
 *
 * ### Pourquoi ce fichier existe
 * ⚠️ Un bug est resté invisible parce que **rien ne testait ce chemin** : quatre appelants
 * testaient `message.contains("401")` sur le texte produit par [ConnectionErrors.describe] — or
 * `describe` traduit 401 en « Mot de passe refusé par le serveur. », **sans chiffre**. Ces quatre
 * tests étaient donc **toujours faux**.
 *
 * Conséquence : `ConnectionStatus.Unauthorized` n'était jamais produit, et un utilisateur avec un
 * mauvais mot de passe voyait « Serveur injoignable : machine éteinte / tunnel Tailscale /
 * 127.0.0.1 ». Il cherchait un problème réseau qui n'existait pas — exactement le mensonge que ce
 * module interdit.
 *
 * ⚠️ Le test porte sur le **type** de l'erreur, jamais sur le texte : c'est la leçon du bug. Un
 * message est une traduction d'affichage ; le statut HTTP est un fait du protocole.
 *
 * ⚠️ On produit les erreurs avec un `MockEngine` **réel** plutôt qu'en construisant une
 * `HttpResponse` à la main : cette dernière approche dépend de trop de détails internes de Ktor,
 * donc elle casserait à la première montée de version sans rien prouver de plus.
 */
class ConnectionErrorsTest {

    /**
     * Une erreur Ktor authentique, produite par une vraie requete contre un moteur simule.
     *
     * ⚠️ On rend **l'exception telle que Ktor la leve** : `ClientRequestException` pour un 4xx,
     * `ServerResponseException` pour un 5xx. Les melanger fausserait le test : ce sont deux
     * classes differentes, et `isUnauthorized` ne doit reconnaitre que la premiere.
     */
    private fun realError(status: Int): Throwable = runBlocking {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.fromValue(status),
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val client = HttpClient(engine) {
            // ⚠️ Comme la production : sans ce reglage, Ktor 2.x ne leve rien sur 4xx et le test
            // ne prouverait que le comportement du moteur simule, pas celui de l'app.
            expectSuccess = true
        }
        try {
            client.get("http://test.invalid/api/info")
            error("le moteur simulé aurait dû répondre $status")
        } catch (e: io.ktor.client.plugins.ResponseException) {
            e
        } finally {
            client.close()
        }
    }

    @Test
    fun `un 401 est reconnu comme refus d identifiants`() {
        // ⚠️ LE test qui aurait attrapé le bug : avant, aucun chemin ne produisait `true`.
        assertTrue(
            ConnectionErrors.isUnauthorized(realError(401)),
            "un 401 doit être reconnu — sinon l'utilisateur est envoyé chercher un problème réseau",
        )
    }

    @Test
    fun `un 403 est reconnu comme refus d identifiants`() {
        assertTrue(ConnectionErrors.isUnauthorized(realError(403)))
    }

    @Test
    fun `une erreur reseau n est PAS un refus d identifiants`() {
        // ⚠️ L'erreur inverse compte autant : classer une panne réseau comme un refus de mot de
        // passe ferait changer un mot de passe qui était bon.
        assertFalse(ConnectionErrors.isUnauthorized(java.net.ConnectException("refusée")))
        assertFalse(ConnectionErrors.isUnauthorized(java.net.UnknownHostException("hôte")))
        assertFalse(ConnectionErrors.isUnauthorized(java.net.SocketTimeoutException("délai")))
    }

    @Test
    fun `un 500 ou un 404 n est pas un refus d identifiants`() {
        // Le serveur est joignable et nous a entendus : le mot de passe n'est pas en cause.
        assertFalse(ConnectionErrors.isUnauthorized(realError(500)))
        assertFalse(ConnectionErrors.isUnauthorized(realError(404)))
    }

    @Test
    fun `le statut est cherche dans la chaine des causes`() {
        // ⚠️ Ktor enveloppe parfois l'erreur. Si le statut se trouve un niveau plus bas et qu'on
        // ne regarde que la surface, on retombe dans le bug : `false` sur un vrai 401.
        val wrapped = RuntimeException("enveloppe", realError(401))

        assertTrue(ConnectionErrors.isUnauthorized(wrapped))
    }

    @Test
    fun `une chaine de causes circulaire ne fait pas boucler`() {
        // ⚠️ Une chaîne de causes peut être cyclique. Sans borne, la boucle ne s'arrêterait
        // jamais — un gel de l'app au moment même où on diagnostique une panne.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        // Le test réussit s'il termine : on ne cherche pas un résultat, seulement l'absence de
        // boucle infinie.
        assertFalse(ConnectionErrors.isUnauthorized(a))
    }

    @Test
    fun `le message affiche ne porte pas le code de statut`() {
        // ⚠️ Ce test **documente la cause du bug** : le message est une traduction, il ne porte
        // pas le code. C'est précisément pourquoi tester `contains("401")` ne pouvait pas marcher,
        // et pourquoi `isUnauthorized` existe. Si un jour ce message change de formulation, ce
        // test le signale — et rappelle que personne ne doit s'appuyer dessus.
        val message = ConnectionErrors.describe(realError(401))

        assertFalse(
            message.contains("401"),
            "le message ne doit pas porter le code : s'appuyer dessus EST le bug qu'on a corrigé",
        )
        assertTrue(
            message.contains("Mot de passe"),
            "mais il doit dire clairement que c'est le mot de passe",
        )
    }
}
