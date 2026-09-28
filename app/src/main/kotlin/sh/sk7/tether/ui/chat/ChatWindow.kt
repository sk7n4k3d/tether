package sh.sk7.tether.ui.chat

/**
 * Fenetre glissante de l'historique : combien de messages sont **charges et affiches** dans une
 * conversation.
 *
 * ### Pourquoi ce n'est pas un detail d'optimisation
 * **Mesure sur le serveur** (2026-09-25) : sur les 8 sessions les plus lourdes, la moyenne est
 * de **810 messages**, avec un maximum a **2 040** (une session Domotique).
 * La version precedente les chargeait **tous** a chaque ouverture et a chaque reconnexion :
 * des centaines de requetes paginees, des milliers d'objets en memoire, et un `LazyColumn` de
 * plusieurs milliers d'items. Concretement, ouvrir une grosse session etait lent, et chaque
 * reconnexion relancait tout le travail.
 *
 * ### Le comportement vise
 * On ouvre sur les **40 derniers messages** — la fin de la conversation, l'endroit ou l'on vit.
 * Le reste se charge **au scroll vers le haut**, par tranches. C'est le comportement de ChatGPT,
 * Claude et Cursor : personne ne remonte 2 000 messages avant de pouvoir lire.
 *
 * ⚠️ **Ce n'est pas une perte d'information** : c'est la meme donnee, servie a la demande. Le
 * principe de non-mensonge du `design-soul.md` porte sur ce qu'on **cache a l'utilisateur**, pas
 * sur ce qu'on charge paresseusement.
 */
object ChatWindow {
    /** Messages charges a l'ouverture (la fin de la conversation). */
    const val INITIAL = 40

    /** Messages ajoutes quand on remonte (par tranche). */
    const val PAGE = 40

    /**
     * Marge de declenchement du chargement : on charge **avant** d'atteindre le sommet, pour que
     * l'utilisateur ne voie jamais la pause. Le cout est nul — une page chargee un peu tot coute
     * exactement la meme chose qu'une page chargee au bon moment.
     */
    const val PREFETCH_THRESHOLD = 5

    /**
     * Taille de page demandee au serveur.
     *
     * ⚠️ **100 et non 40** : le serveur pagine deja par curseur, on lui demande donc une page
     * un peu large, puis on n'en **montre** que [PAGE]. Cela evite un aller-retour reseau a
     * chaque cran de scroll tout en gardant l'affichage par tranches regulieres.
     */
    const val SERVER_PAGE = 100
}
