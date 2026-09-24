package sh.sk7.tether.di

import javax.inject.Qualifier

/** Dispatcher des entrees/sorties reseau et disque, injectable pour les tests JVM. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
