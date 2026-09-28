package sh.sk7.tether.data.event

import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton
import sh.sk7.tether.data.api.CredentialsProvider
import sh.sk7.tether.data.settings.ConnectionSettings
import androidx.compose.ui.res.stringResource
import sh.sk7.tether.R
import sh.sk7.tether.ui.i18n.Res

/**
 * Construit une [EventSource] pour les reglages courants.
 *
 * `EventStream` depend de l'URL de base et des identifiants, qui changent avec les reglages :
 * on le construit donc **a la demande** plutot qu'a l'injection. C'est aussi la couture qui
 * rend le ViewModel de chat testable en JVM (on injecte une fausse source).
 *
 * `suspend` : les identifiants sont resolus via le `CredentialsProvider` partage au moment
 * de la connexion.
 */
fun interface EventSourceFactory {
    suspend fun create(settings: ConnectionSettings): EventSource
}

/**
 * [EventSourceFactory] de production : construit un [EventStream] pour les reglages courants.
 *
 * ⚠️ Vit dans `data/event` (et non dans `ui/chat`) : il fabrique un objet de ce package et ne
 * depend d'aucun type d'UI. Le laisser cote UI inverserait la couche.
 *
 * Les identifiants sont resolus via le [CredentialsProvider] partage (alimente par
 * `ConnectionStore`) : le mot de passe n'est jamais fige ici ni journalise.
 */
@Singleton
class DefaultEventSourceFactory @Inject constructor(
    private val http: HttpClient,
    private val credentialsProvider: CredentialsProvider,
) : EventSourceFactory {

    override suspend fun create(settings: ConnectionSettings): EventSource {
        val credentials = credentialsProvider.credentials()
            ?: error(Res.of(R.string.aucun_identifiant_configure_1c332a))
        return EventStream(
            baseUrl = settings.baseUrl,
            credentials = credentials,
            http = http,
        )
    }
}
