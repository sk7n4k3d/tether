package sh.sk7.tether.ui.chat

import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton
import sh.sk7.tether.data.api.CredentialsProvider
import sh.sk7.tether.data.event.EventSource
import sh.sk7.tether.data.event.EventStream
import sh.sk7.tether.data.settings.ConnectionSettings

/**
 * [EventStreamFactory] de production : construit un [EventStream] pour les reglages courants.
 *
 * Les identifiants sont resolus via le [CredentialsProvider] partage (alimente par
 * `ConnectionStore`) : le mot de passe n'est jamais fige ici ni journalise.
 */
@Singleton
class DefaultEventStreamFactory @Inject constructor(
    private val http: HttpClient,
    private val credentialsProvider: CredentialsProvider,
) : EventStreamFactory {

    override suspend fun create(settings: ConnectionSettings): EventSource {
        val credentials = credentialsProvider.credentials()
            ?: error("aucun identifiant configuré")
        return EventStream(
            baseUrl = settings.baseUrl,
            credentials = credentials,
            http = http,
        )
    }
}
