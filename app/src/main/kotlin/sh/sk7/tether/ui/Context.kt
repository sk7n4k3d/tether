package sh.sk7.tether.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Retrouve l'`Activity` derriere un `Context`.
 *
 * ## Pourquoi il faut remonter la chaine
 *
 * `LocalContext.current` dans Compose n'est **pas** une activity : c'est le contexte
 * d'application, ou un `ContextThemeWrapper` pose par le `setContent`. Il ne descend
 * donc pas dans l'arbre des `ContextWrapper`, et un cast echouerait.
 *
 * On remonte donc jusqu'a ce qui n'est pas un wrapper. C'est la technique ordinaire, et
 * elle a un defaut connu : si un `ContextWrapper` atypique est insere, la Activity n'est
 * pas trouvee. D'ou le retour nullable — un `?: null` qui **n'echoue pas**, parce
 * qu'une activity introuvable doit empecher de changer la langue, seulement de
 * recadrer l'ecran.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
