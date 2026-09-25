package sh.sk7.tether.di

import javax.inject.Qualifier

/**
 * Delai (ms) sans evenement avant que l'ecran de chat ne rende la main.
 *
 * Un `Long` nu serait ambigu pour Dagger ; ce qualifier le nomme et permet d'injecter une
 * valeur par defaut unique, tout en laissant les tests passer la leur.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AwaitingGraceMillis
