package sh.sk7.tether.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import sh.sk7.tether.data.event.DefaultEventSourceFactory
import sh.sk7.tether.data.event.EventSourceFactory
import sh.sk7.tether.ui.chat.ChatViewModel

/** Lie la fabrique de flux SSE a son implementation Ktor et fournit ses constantes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventModule {

    @Binds
    @Singleton
    abstract fun bindEventSourceFactory(impl: DefaultEventSourceFactory): EventSourceFactory

    companion object {
        @Provides
        @AwaitingGraceMillis
        fun provideAwaitingGraceMillis(): Long = ChatViewModel.DEFAULT_AWAITING_GRACE_MILLIS
    }
}
