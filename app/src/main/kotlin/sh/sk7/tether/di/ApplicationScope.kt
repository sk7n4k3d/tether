package sh.sk7.tether.di

import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * **Une portée qui vit aussi longtemps que l'application.**
 *
 * ### Pourquoi elle existe
 * Certains travaux doivent survivre à l'écran qui les a lancés : le détenteur d'état vivant
 * (`ActivityMonitor`) doit continuer d'interroger le serveur quand on passe de la liste au chat,
 * sinon l'état se réinitialiserait à chaque navigation.
 *
 * ⚠️ **`SupervisorJob` et non `Job`** : l'échec d'un enfant ne doit pas annuler la portée
 * entière. Sans ça, une seule exception dans un cycle d'interrogation tuerait la boucle pour le
 * reste de la vie du processus — un bug qui ne se voit pas au démarrage, seulement après.
 *
 * ⚠️ **Ce n'est PAS une raison d'y mettre du travail non annulable.** Un travail qui n'a plus
 * d'observateur doit s'arrêter de lui-même : c'est au consommateur de gérer son compte d'abonnés
 * (voir `ActivityMonitor.acquire` / `release`). Une portée applicative qui continue de travailler
 * pour personne est exactement la façon dont on vide une batterie sans que rien ne le signale.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {

    /**
     * ⚠️ Instanciée **une fois**, jamais recréée : c'est tout l'intérêt. Un `@Provides` sans
     * portée créerait une nouvelle portée à chaque injection, donc des boucles concurrentes.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
