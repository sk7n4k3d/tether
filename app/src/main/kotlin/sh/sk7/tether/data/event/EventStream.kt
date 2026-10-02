package sh.sk7.tether.data.event

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.accept
import io.ktor.client.request.basicAuth
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException
import sh.sk7.tether.data.api.BasicAuthCredentials
import sh.sk7.tether.data.api.OpenCodeClient
import kotlin.math.min
import kotlin.math.pow

/** Etat de la connexion SSE, expose pour declencher la resync REST (spec 4.2). */
enum class ConnectionState { Connecting, Connected, Disconnected }

/**
 * Politique de reconnexion : backoff exponentiel plafonne.
 * Deterministe (pas de jitter) pour rester testable.
 */
data class Backoff(
    val initialMillis: Long = 500,
    val maxMillis: Long = 30_000,
    val factor: Double = 2.0,
) {
    /** Delai avant la (re)tentative numero [attempt] (0 = premiere). */
    fun delayMillis(attempt: Int): Long {
        if (attempt <= 0) return initialMillis
        val raw = initialMillis * factor.pow(attempt)
        return min(raw.toLong(), maxMillis)
    }

    companion object {
        val Default = Backoff()
    }
}

/**
 * Client du flux SSE opencode V2 (`GET /api/event`).
 *
 * Choix d'implementation : **pas** de plugin `ktor-client-sse`. On lit la reponse
 * brute via `prepareGet(...).execute { bodyAsChannel() }` et on alimente [SseParser]
 * ligne par ligne. Raison : le flux V2 n'emet **ni `event:` ni `id:`** (le type et
 * l'id sont dans le JSON), donc le decodage SSE du plugin n'apporterait rien tout en
 * ajoutant une dependance. Le protocole est respecte a la main, commentaires compris.
 *
 * Regles :
 * - le `Content-Type` est verifie **avant** de lire : tout ce qui n'est pas
 *   `text/event-stream` (dont `text/html` = fallback SPA) est rejete sans etre parse
 *   ([SseParser.requireEventStream]) ;
 * - **pas de `Last-Event-ID`** : le flux n'a pas de ligne `id:`, la reprise d'etat
 *   passe par le REST (spec 4.2). [state] signale chaque (re)connexion pour cela ;
 * - reconnexion automatique avec [Backoff] exponentiel.
 */
class EventStream(
    baseUrl: String,
    private val credentials: BasicAuthCredentials,
    private val http: HttpClient,
    private val json: kotlinx.serialization.json.Json = OpenCodeClient.json,
    private val backoff: Backoff = Backoff.Default,
) : EventSource {
    private val baseUrl: String = baseUrl.trimEnd('/')

    private val _state = MutableStateFlow(ConnectionState.Disconnected)

    /** Etat courant, a observer pour resynchroniser via le REST a chaque reconnexion. */
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Derniere erreur de connexion, pour diagnostic. */
    @Volatile
    var lastError: Throwable? = null
        private set

    /**
     * Flux d'evenements decode. Se reconnecte automatiquement ; ne termine que si le
     * collecteur annule. Un evenement JSON illisible est **ignore** (jamais fatal).
     */
    override fun connect(): Flow<OcEvent> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = ConnectionState.Connecting
            try {
                receiveLoop { event -> emit(event) }
                // Fermeture propre cote serveur : on repart d'un backoff minimal
                // (pas de hot-loop si le serveur accepte puis ferme aussitot).
                lastError = null
                _state.value = ConnectionState.Disconnected
                delay(backoff.delayMillis(0))
                attempt = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                _state.value = ConnectionState.Disconnected
                delay(backoff.delayMillis(attempt))
                attempt++
            }
        }
    }

    private suspend fun receiveLoop(onEvent: suspend (OcEvent) -> Unit) {
        http.prepareGet("$baseUrl/api/event") {
            basicAuth(credentials.username, credentials.password)
            accept(ContentType.Text.EventStream)
            // Le SSE vit tant que le serveur vit : pas de request timeout ici. Le heartbeat
            // du serveur (15 s) garde la socket active ; le socket timeout ci-dessous (45 s)
            // ne declenche que si trois heartbeats manquent — une vraie panne, pas un flux sain.
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 45_000
            }
        }.execute { response ->
            SseParser.requireEventStream(response.contentType()?.toString())
            _state.value = ConnectionState.Connected
            val parser = SseParser()
            readLines(response.bodyAsChannel()) { line ->
                for (frame in parser.feed(line + "\n")) {
                    decode(frame.data)?.let { onEvent(it) }
                }
            }
        }
    }

    private fun decode(data: String): OcEvent? = try {
        json.decodeFromString(OcEvent.serializer(), data)
    } catch (_: SerializationException) {
        null
    }

    private suspend fun readLines(channel: ByteReadChannel, onLine: suspend (String) -> Unit) {
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            onLine(line)
        }
    }
}
