package sh.sk7.tether.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import sh.sk7.tether.ui.chat.ChatViewModel
import sh.sk7.tether.ui.chat.DefaultEventStreamFactory
import sh.sk7.tether.ui.chat.EventStreamFactory

/** Lie la fabrique de flux SSE a son implementation Ktor. */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventModule {

    @Binds
    @Singleton
    abstract fun bindEventStreamFactory(impl: DefaultEventStreamFactory): EventStreamFactory

    companion object {
        @Provides
        @AwaitingGraceMillis
        fun provideAwaitingGraceMillis(): Long = ChatViewModel.DEFAULT_AWAITING_GRACE_MILLIS
    }
}
