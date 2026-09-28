package sh.sk7.tether.push

/**
 * L'appairage recu par deep link : `opencode://pair?s=<serveur>&t=<jeton>`.
 *
 * ## Pourquoi des primitifs, et pas un `Uri`
 *
 * `android.net.Uri` n'est pas disponible dans un test JVM sans Robolectric, et ce projet
 * n'en a pas — il verifie donc ses regles metier sur des **fonctions pures**, et laisse
 * l'extraction Android a la frontiere. C'est le meme choix que [routeFromUri], qui prend
 * `scheme`/`host`/`pathSegments` plutot qu'un `Intent` : la regle est testee, l'appel
 * Android ne l'est pas. Ici, la fonction d'appel vit dans `TetherNavHost`.
 *
 * ## Pourquoi cette surface merite des tests bien a part
 *
 * C'est **la seule entree du projet que l'utilisateur ne saisit pas**. Tout le reste
 * passe par l'ecran, ou par une demande authentifiee. Ici, le contenu vient d'un QR que
 * l'utilisateur a scanne — donc d'un tiers qui a pu fabriquer l'image.
 *
 * ### L'attaque que la validation empeche
 *
 * Le champ `s` designe le serveur auquel l'app va **enregistrer son endpoint de push**.
 * Un endpoint UnifiedPush est une capacite d'ecriture : celui qui le connait peut pousser
 * une notification sur le telephone. Un QR forge avec `s=https://attaquant.fr` ferait
 * pointer l'app chez le tiers, qui recevrait ensuite tout ce que l'agent notifie — y
 * compris des extraits de session.
 *
 * D'ou les trois regles :
 *  - `s` en `https`, sauf `localhost` et `127.0.0.1` en `http` (developpement) ;
 *  - `t` a la forme exacte d'un jeton emis par le serveur ;
 *  - et surtout, meme valide, **l'ecran de confirmation affiche le serveur**. Une
 *    validation n'est pas un consentement : elle evite l'erreur, pas l'intention.
 *
 * Les memes regles sont appliquees par le serveur (`pair` dans `plugin/tether/rpc.ts`).
 * Un seul des deux qui valide laisserait passer l'autre.
 */
object PairingLink {

    const val SCHEME = "opencode"
    const val HOST = "pair"

    /** 16 octets en base64url, sans remplissage : 22 caracteres. */
    const val TOKEN_LENGTH = 22

    /** Ce que l'app sait faire du lien avant de l'avoir accepte. */
    data class Demande(
        val server: String,
        val token: String,
    )

    /**
     * Analyse les parties d'un URI, ou renvoie `null`.
     *
     * `null` signifie « ce n'est pas un lien d'appairage » — y compris quand cela en est
     * un mais invalide. L'appelant affiche dans les deux cas la meme chose. Distinguer
     * « malforme » de « refuse » n'aide personne, et malformerait un oracle sur ce que
     * l'app accepte.
     *
     * [query] est la query **deja decodee**. Pour le chemin reel, passer par [depuisUri].
     */
    fun fromParts(scheme: String?, host: String?, query: Map<String, String>): Demande? {
        if (!SCHEME.equals(scheme, ignoreCase = true)) return null
        if (!HOST.equals(host, ignoreCase = true)) return null

        val server = query["s"]?.trim().orEmpty()
        val token = query["t"]?.trim().orEmpty()
        if (!serveurAccepte(server)) return null
        if (!jetonBienForme(token)) return null

        return Demande(server, token)
    }

    /**
     * Analyse une query **brute** : `s=https%3A%2F%2Fexemple.fr%3A4096&t=…`.
     *
     * ### Pourquoi decoder ici plutot que de laisser faire `android.net.Uri`
     *
     * `Uri.getQueryParameter` fait le travail, mais c'est du code **non teste** : le projet
     * n'a pas Robolectric, donc cette ligne ne serait jamais exercee. Or c'est exactement
     * l'endroit ou une erreur passe inapercue — une query mal decodee donne soit un refus
     * (visible), soit une adresse de serveur bricolee (invisible).
     *
     * En decodant ici, sur du `java.net` pur, la regle redevient testable — et elle est
     * testee, y compris les entrees hostiles.
     *
     * ### Le `+` n'est pas un espace
     *
     * `java.net.URLDecoder` suit la semantique **formulaire** : il transforme `+` en
     * espace. En query URI, `+` est un caractere litteral. Un jeton base64url n'a pas de
     * `+` (il utilise `-` et `_`), mais un chemin de serveur peut en contenir un : le
     * decodateur de formulaire le detruirait en silence. On ne touche donc qu'a `%XX`.
     *
     * ⚠️ Un echappement mal forme fait Echouer **tout** le lien plutot que d'ignorer le
     * parametre. Laisser passer une valeur brute laissee a moitie decodee, c'est
     * accepter une adresse que personne n'a lue.
     */
    fun depuisUri(scheme: String?, host: String?, query: String?): Demande? =
        fromParts(scheme, host, decoderQuery(query) ?: return null)

    /**
     * Analyse le **texte brut** d'un QR : `opencode://pair?s=…&t=…`.
     *
     * C'est l'entree du scanner integre, qui ne dispose que d'une chaine — pas d'un
     * `android.net.Uri` ni d'un `Intent`. On decoupe avec `java.net.URI` (JDK, donc
     * testable) et on delegue a [depuisUri] : une **seule** validation pour les deux
     * chemins, sinon ils finiraient par diverger sur ce que l'app accepte.
     *
     * ⚠️ On passe `rawQuery`, pas `query` : [depuisUri] decode lui-meme, et lui donner
     * une query deja decodee reviendrait a decoder deux fois — un `%25` deviendrait un
     * `%` puis un octet invalide.
     */
    fun depuisTexte(brut: String): Demande? {
        val uri = try {
            java.net.URI(brut.trim())
        } catch (_: Exception) {
            return null
        }
        return depuisUri(uri.scheme, uri.host, uri.rawQuery)
    }

    /** `a=b&c=d` vers une map, ou `null` si la query est malformee. */
    private fun decoderQuery(brute: String?): Map<String, String>? {
        if (brute.isNullOrEmpty()) return emptyMap()
        val resultat = mutableMapOf<String, String>()
        for (paire in brute.split('&')) {
            if (paire.isEmpty()) continue
            val i = paire.indexOf('=')
            // Une paire sans `=` est un drapeau sans valeur : on l'ignore, comme le fait
            // `Uri`. Ce n'est pas une erreur — `?debug` est un lien valide.
            if (i < 0) continue
            val cle = decode(paire.substring(0, i)) ?: return null
            val valeur = decode(paire.substring(i + 1)) ?: return null
            // ⚠️ **Le premier gagne**, pas le dernier. Une query qui repete `s` est
            // anormale, et c'est la forme que prendrait un lien ou l'on glisse une
            // destination derriere celle du TUI. Prendre le dernier donnerait la
            // destination de l'ajout ; prendre le premier, celle du lien d'origine.
            // C'est aussi ce que fait `android.net.Uri`, donc les deux chemins de l'app
            // ne peuvent pas diverger sur le meme lien.
            if (cle.isNotEmpty() && cle !in resultat) resultat[cle] = valeur
        }
        return resultat
    }

    /** `%XX` -> octet. Un echappement invalide donne `null`. */
    private fun decode(valeur: String): String? {
        if (!valeur.contains('%')) return valeur
        val octets = java.io.ByteArrayOutputStream(valeur.length)
        var i = 0
        while (i < valeur.length) {
            val c = valeur[i]
            if (c == '%') {
                if (i + 2 >= valeur.length) return null
                val hex = valeur.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                octets.write(hex)
                i += 3
            } else {
                // Un caractere non-ASCII direct passe en UTF-8 : la query peut venir
                // d'un encodeur qui n'a pas encode les accents.
                octets.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return String(octets.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Le serveur doit-il etre accepte ?
     *
     * `https` partout, sauf `http` sur une boucle locale. La reflexion : un telephone
     * n'a aucune raison d'appairer en `http` vers autre chose que sa propre machine.
     *
     * ⚠️ Un hote que la JVM ne sait pas decrire est **refuse** : `café.fr` (punya code en
     * clair) ou `a+b.fr` (`+` n'est pas valide dans un hote). Ce n'est pas un effet de
     * bord subi, c'est la regle : l'adresse sert a l'utilisateur a **verifier** ce qu'il
     * est en train d'autoriser, et une adresse que l'app ne sait pas afficher fidelement
     * ne remplit pas ce role. Le TUI n'a qu'a emettre du punycode.
     */
    fun serveurAccepte(server: String): Boolean {
        if (server.isEmpty()) return false
        if (server.startsWith("https://")) return hoteDe(server) != null
        if (server.startsWith("http://")) {
            val host = hoteDe(server) ?: return false
            return host == "localhost" || host == "127.0.0.1" || host == "::1"
        }
        return false
    }

    /**
     * Le jeton doit-il avoir la forme d'un jeton ?
     *
     * On ne peut pas savoir si le jeton existe — seul le serveur le sait — mais on
     * refuse d'envoyer quoi que ce soit si la forme n'est pas celle qu'il emet. Cela
     * evite qu'un lien bricole transforme l'appairage en requete arbitraire.
     */
    fun jetonBienForme(token: String): Boolean {
        if (token.length != TOKEN_LENGTH) return false
        return token.all { c ->
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
        }
    }

    /**
     * L'hote d'une URL, sans dependre d'`android.net.Uri`.
     *
     * `java.net.URI` est dans le JDK, donc disponible dans les tests JVM. On s'en sert
     * pour une seule chose — isoler l'hote — parce que c'est **l'hote** qui decide du
     * `localhost`, pas le reste de l'URL.
     *
     * Un echec de parsing donne `null` : une URL que la JVM ne lit pas est une URL
     * qu'on refuse, ce qui est le bon sens de securite ici.
     */
    private fun hoteDe(url: String): String? =
        try {
            java.net.URI(url).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
}
