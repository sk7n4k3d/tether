package sh.sk7.tether.data.settings

import androidx.datastore.preferences.core.Preferences

/**
 * **Lire une preference sans jamais faire tomber l'application.**
 *
 * ### Le defaut que ceci ferme, rencontre en production
 *
 * `prefs[cle]` est generique : il demande la valeur du type attendu. Si le fichier en
 * porte un autre — un `String` la ou le code attend un `Boolean` — la lecture **leve**
 * une `ClassCastException`. L'app se fermait alors **avant le premier ecran**, sur un
 * fichier valide au sens protobuf, donc sans aucun moyen pour l'utilisateur de reparer ce
 * qui l'empeche d'ouvrir l'app.
 *
 * ### Pourquoi il n'y a pas de fonction generique ici
 *
 * Mesure, dans les deux sens :
 *
 * ```
 * val v: Any?     = prefs[cleBooleenne]   // passe — aucun cast n'est demande
 * val v: Boolean? = prefs[cleBooleenne]   // leve — le cast est ici, chez l'appelant
 * ```
 *
 * Le `checkcast` n'est pas dans `Preferences.get`, il est **genere chez l'appelant**, sur
 * la valeur rendue. Trois consequences, toutes verifiees :
 *
 *  - un `try` place dans une fonction appelee ne l'attrape pas : le cast se fait apres le
 *    retour ;
 *  - `inline` ne suffit pas non plus : le cast porte sur la **valeur de retour** de
 *    l'expression `try`, donc apres elle ;
 *  - une fonction generique ne peut pas le rattraper, quel que soit son ecriture.
 *
 * La seule sortie est de **ne jamais demander de type au DataStore** : on lit la valeur
 * brute (un `Any?`), et on la convertit nous-memes avec un `as?`, qui ne leve pas.
 *
 * ### Ce qu'on ne fait pas
 *
 * ⚠️ Pas de `catch` global sur le flux : il ferait retomber **toutes** les preferences a
 * leur defaut des qu'une seule est douteuse. Quelqu'un dont l'accueil a ete mal ecrit
 * perdrait son adresse de serveur. Ici, seule la cle fautive perd sa valeur.
 */

/** Un booleen, ou [defaut] si la cle est absente ou d'un autre type. */
internal fun Preferences.lireBooleen(cle: Preferences.Key<Boolean>, defaut: Boolean): Boolean =
    valeurBrute(cle) as? Boolean ?: defaut

/** Un texte, ou [defaut] si la cle est absente ou d'un autre type. */
internal fun Preferences.lireTexte(cle: Preferences.Key<String>, defaut: String): String =
    valeurBrute(cle) as? String ?: defaut

/**
 * La valeur **telle qu'elle est stockee**, sans que le compilateur ne demande de type.
 *
 * ⚠️ `Key<Any?>` n'est pas un detail : c'est ce qui fait rendre un `Any?` a `get`, donc
 * ce qui empeche l'insertion du `checkcast` qui rendrait la fonction inutile.
 */
private fun Preferences.valeurBrute(cle: Preferences.Key<*>): Any? =
    try {
        @Suppress("UNCHECKED_CAST")
        this[cle as Preferences.Key<Any?>]
    } catch (_: Throwable) {
        // Un fichier illisible, ou une entree que le DataStore refuse : on rend `null`,
        // et l'appelant retombe sur son defaut.
        null
    }
