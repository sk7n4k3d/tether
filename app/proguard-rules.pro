# \u26a0\ufe0f Regles R8 pour Tether.
#
# ### Pourquoi ce fichier est indispensable
# R8 renomme et supprime le code non reference **statiquement**. Or trois familles de code de cette
# app ne sont referencees que par leur NOM, en reflexion ou par le framework :
#   1. **kotlinx-serialization** : les `@Serializable` sont resolus par leur nom de classe genere ;
#   2. **Hilt / Dagger** : les modules et points d'injection sont charges par reflexion ;
#   3. **UnifiedPush** : le service est declare dans le manifeste et instancie par Android.
# Sans ces regles, l'app compile, s'installe, et **plante au demarrage** — sans erreur lisible.

# kotlinx-serialization : garder les serialiseurs generes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class sh.sk7.tether.**$$serializer { *; }
-keepclassmembers class sh.sk7.tether.** {
    *** Companion;
}

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# UnifiedPush : le service est instancie par le framework
-keep class sh.sk7.tether.push.** { *; }

# Ktor / OkHttp : options et serialiseurs charges par nom
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.**

# Le modele de domaine est traverse par serialisation et par les reducers
-keep class sh.sk7.tether.domain.model.** { *; }
