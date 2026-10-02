package sh.sk7.tether.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import sh.sk7.tether.data.api.CredentialsProvider
import sh.sk7.tether.data.api.InMemoryCredentialsProvider
import sh.sk7.tether.data.api.configureTether

/**
 * Fournit les singletons reseau de l'application.
 *
 * ⚠️ **Securite** : le logging Ktor est plafonne a [LogLevel.INFO] et l'en-tete
 * `Authorization` est masque par defaut — `ALL`/`BODY` ecrirait le mot de passe basic
 * en clair dans logcat (fuite reelle, pas une pinaillerie). Ne jamais remonter ce niveau
 * ni retirer ce filtre.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideInMemoryCredentialsProvider(): InMemoryCredentialsProvider =
        InMemoryCredentialsProvider()

    @Provides
    @Singleton
    fun provideCredentialsProvider(impl: InMemoryCredentialsProvider): CredentialsProvider = impl

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideHttpClient(@ApplicationContext context: Context): HttpClient = HttpClient(OkHttp) {
        configureTether()
        // ⚠️ **INDISPENSABLE, ET SON ABSENCE ETAIT UN BUG MAJEUR.** Ktor 2.x a
        // `expectSuccess = false` par defaut : un 4xx/5xx ne leve **rien**, la reponse est
        // simplement rendue telle quelle. Or TOUT le traitement d'erreur de l'app repose sur
        // `ClientRequestException` (`ConnectionErrors.describe`, `isUnauthorized`) : sans ce
        // reglage, aucune de ces branches n'etait atteinte.
        //
        // Ce qui se passait reellement avec un mauvais mot de passe : le serveur rend **401 avec
        // un corps vide** (verifie), le client essaie de le deserialiser, et echoue avec une
        // **erreur de decodage JSON**. L'utilisateur voyait « Echec de la connexion :
        // JsonConvertException. » — un message incomprehensible, qui ne dit ni que le mot de passe
        // est faux, ni qu'il faut le changer.
        expectSuccess = true
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            // ⚠️ Pas de `requestTimeoutMillis` global : en Ktor 2.3.11 il s'applique AUSSI aux
            // reponses longue duree non-websocket — donc au SSE `/api/event`, coupe toutes
            // les 20 s et provoque une resync complete (~6 requetes) a chaque reconnexion.
            // Le SSE posera son propre timeout par requete (infini + socket 45 s, cf.
            // EventStream) ; le reste garde le timeout ci-dessous via socketTimeout.
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 20_000
        }
        install(Logging) {
            // Le corps des requetes n'est pas logge, mais en release chaque ligne + statut
            // partait en logcat pour chaque appel reseau : du bruit et des ecritures disque
            // pour rien. (buildConfig est desactive dans ce projet — cf. AboutScreen — donc
            // on se fie au drapeau debuggable du manifest.)
            level = if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) LogLevel.INFO else LogLevel.NONE
        }
    }

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("tether_settings")
    }
}
