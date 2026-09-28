package sh.sk7.tether.ui.i18n

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import sh.sk7.tether.R

/**
 * Les traductions, hors arbre de composition.
 *
 * ## Pourquoi un point d'entree statique
 *
 * `stringResource` ne fonctionne que dans un composable. Or une bonne partie des textes
 * n'y vit pas : un `ViewModel` prepare un message d'erreur, un `BroadcastReceiver`
 * repond a une action de notification, un `Service` journalise un evenement. Aucun de
 * ces endroits n'a d'arbre de composants.
 *
 * La solution est de porter l'**identifiant** plutot que le texte, et de le resoudre la
 * ou il est affiche. Ce fichier est l'endroit ou la resolution a lieu quand il n'y a pas
 * d'ecran.
 *
 * C'est un point d'entree statique, et donc un etat global. Il est initialise une seule
 * fois, dans [sh.sk7.tether.TetherApp.onCreate], avant toute utilisation. Le prix est
 * de la meme langue. Le gain est que les textes d'erreur, qui sont
 * les textes d'erreur, qui sont precisement ceux qu'on lit dans sa langue, sont
 * traduits.
 *
 * ## La langue
 *
 * On ne resolve pas avec les `Resources` de l'application brute, mais avec un contexte
 * **reconfigure** a la langue choisie. Sans cela, un message d'erreur sortrait en
 * anglais pendant que l'ecran est en francais — c'est-a-dire dans un-message-de plus
 * que le probleme qu'on cherche a resoudre.
 */
object Res {

    private var ressources: Resources? = null

    /**
     * A appeler une fois, au demarrage de l'application.
     *
     * Un second appel est ignore : les `Resources` d'une `Application` sont un cache
     * partage, et les remplacer en cours de route laisserait deux vues divergentes de
     * la meme langue.
     */
    fun installer(context: Context) {
        if (ressources != null) return
        ressources = LangueCache.appliquer(context, context.applicationContext).resources
    }

    /**
     * La traduction d'un identifiant, dans la langue en vigueur.
     *
     * Les arguments suivent les `formatArgs` d'Android : `%1$s`, `%2$d`. Ils sont
     * passes tels quels, sans controle de type — c'est ce que fait `getString`, et un
     * controle ici donnerait une fausse assurance : c'est au moment du formatage que
     * l'erreur apparaitrait, sur un appareil, en production.
     */
    fun of(@StringRes id: Int, vararg args: Any): String {
        val r = ressources ?: return marqueur(id)
        return try {
            r.getString(id, *args)
        } catch (e: java.util.MissingFormatArgumentException) {
            // Un placeholder sans argument, ou l'inverse. L'ecran affiche un texte
            // faux — mais il ne plante pas, et l'utilisateur voit le **contexte**,
            // qui est plus utile qu'une exception dans un toast.
            marqueur(id)
        }
    }

    /**
     * Le texte rendu quand aucune ressource n'est disponible.
     *
     * C'est le cas de tout test JVM : pas de `Context`, donc pas de ressources. Le
     * marqueur porte l'**identifiant**, ce qui permet a un test de verifier *quel*
     * message a ete choisi — c'est la decision que la fonction arbitre — sans avoir
     * a materialiser une traduction qu'un test unitaire Android ne peut pas faire.
     *
     * Il ne rend pas un « vide » : un ecran muet est pire qu'un ecran qui montre son
     * propre identifiant.
     */
    fun marqueur(id: Int): String = "res:$id"

    /** L'identifiant, quand le texte n'est resolu qu'a l'affichage. */
    fun raw(id: Int): Int = id
}
