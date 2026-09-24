package sh.sk7.tether.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import sh.sk7.tether.data.api.KtorOpenCodeGateway
import sh.sk7.tether.data.api.OpenCodeGateway

/** Lie l'interface [OpenCodeGateway] a son implementation Ktor. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayModule {

    @Binds
    @Singleton
    abstract fun bindOpenCodeGateway(impl: KtorOpenCodeGateway): OpenCodeGateway
}
