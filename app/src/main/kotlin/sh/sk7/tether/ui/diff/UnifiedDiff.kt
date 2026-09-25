package sh.sk7.tether.ui.diff

import sh.sk7.tether.data.api.FileDiffDto

/**
 * **Lecture d'un patch unifie.**
 *
 * ### Pourquoi un parseur, et pourquoi teste
 * Le serveur renvoie le patch en texte unifie (`@@ -1,4 +1,6 @@`). Une vue de diff doit colorer
 * chaque ligne selon son role. La regle parait simple — « un `+` commence une addition » — et elle
 * est **fausse** dans plusieurs cas qu'on rencontre tous les jours :
 *
 *  - l'en-tete `+++ b/fichier` commence par `+` mais n'est **pas** une ligne ajoutee ;
 *  - une ligne de **contenu** qui est elle-meme un `+++` (on ecrit du Markdown dans du code) ;
 *  - `--- a/fichier` commence par `-` mais n'est pas une suppression ;
 *  - une ligne vide ajoutee s'ecrit `+` tout seul ;
 *  - `\ No newline at end of file` n'est ni un ajout ni une suppression.
 *
 * ⚠️ Une regex sur le premier caractere afficherait donc l'en-tete comme du code ajoute. C'est le
 * genre d'erreur qu'on ne voit pas dans une capture d'ecran et qui rend un diff inutilisable
 * precisement quand on en a besoin. D'ou un etat explicite ([inHeader]) plutot qu'une heuristique.
 */
object UnifiedDiff {

    /**
     * Un fichier pret a afficher : ses lignes classees, et ses compteurs.
     *
     * ⚠️ `additions` / `deletions` viennent du **serveur**, jamais d'un comptage local : si les
     * deux divergeaient, c'est le serveur qui a raison (il connait le diff reel, pas seulement ce
     * qu'il a bien voulu inclure dans le patch, qui peut etre tronque par `context`).
     */
    data class FileDiff(
        val path: String,
        val status: Status,
        val additions: Int,
        val deletions: Int,
        /** `true` si le patch a ete omis ou tronque : on le dit, on ne fait pas semblant. */
        val patchMissing: Boolean,
        /**
         * **Toutes les lignes, a plat, dans l'ordre.**
         *
         * ⚠️ Volontairement **une seule liste** et pas une liste de blocs : l'affichage d'un diff
         * est une suite continue de lignes, et les regrouper par `@@` obligerait l'UI a re-aplatir
         * ce que le serveur avait deja aplati. Les reperes de bloc sont eux-memes des lignes
         * ([Kind.HunkHeader]) — c'est ce qui permet de les traiter comme n'importe quelle autre
         * ligne, y compris pour l'espacement.
         *
         * ⚠️ Ce choix corrige une incoherence : la version precedente avait un `Kind.HunkHeader`
         * qui n'etait **jamais emis**, parce que l'en-tete vivait dans un objet `Hunk` separe. Un
         * cas d'enumeration mort est un mensonge sur ce que le parseur produit.
         */
        val lines: List<Line>,
    ) {
        /** Un fichier binaire n'a pas de lignes a afficher, et le serveur le dit par un patch vide. */
        val isDisplayable: Boolean get() = lines.isNotEmpty()
    }

    enum class Status(val label: String) {
        Added("ajouté"),
        Deleted("supprimé"),
        Modified("modifié");

        companion object {
            /** ⚠️ Toute valeur inconnue retombe sur [Modified] plutot que de planter : un status
             *  nouveau cote serveur ne doit pas casser l'ecran. */
            fun from(raw: String): Status = when (raw) {
                "added" -> Added
                "deleted" -> Deleted
                else -> Modified
            }
        }
    }

    /** Une ligne et son role. Le classement est **decide**, pas devine a l'affichage. */
    data class Line(val kind: Kind, val text: String)

    enum class Kind {
        /** Ligne de contexte : prefixee d'un espace dans un diff unifie. */
        Context,

        /** Ligne ajoutee. */
        Addition,

        /** Ligne supprimee. */
        Deletion,

        /** `@@ -a,b +c,d @@` : repere de position. */
        HunkHeader,

        /** En-tete de fichier (`---` / `+++` / `diff --git` / `index`). */
        FileHeader,

        /** `\ No newline at end of file` : une constatation, ni ajout ni suppression. */
        Meta,
    }

    /**
     * Lit le patch d'un fichier.
     *
     * ⚠️ On ne suppose **pas** que le patch est complet : `GET /session/{id}/diff` accepte un
     * parametre `context`, donc le serveur peut n'envoyer qu'un extrait. Ce qu'on affiche est ce
     * qu'on a recu, et [FileDiff.patchMissing] le signale quand il n'y a rien.
     */
    fun parse(dto: FileDiffDto): FileDiff {
        val out = mutableListOf<Line>()
        // ⚠️ Un seul etat suffit : « suis-je dans un bloc `@@` ? ». C'est lui qui distingue un
        // `+++` en-tete d'un `+++` de contenu, et c'est une garantie plus forte qu'une borne de
        // numero de ligne (dans un diff git reel, `---`/`+++` sont en positions 2 et 3).
        var inHunk = false

        dto.patch.split('\n').forEachIndexed { index, raw ->
            when {
                // Le repere de position : une ligne comme les autres, avec son propre role.
                raw.startsWith("@@") -> {
                    inHunk = true
                    out += Line(Kind.HunkHeader, raw)
                }

                !inHunk && isFileHeader(raw) -> out += Line(Kind.FileHeader, raw)

                inHunk -> out += classify(raw)

                // ⚠️ Hors bloc et hors en-tete : on **garde** la ligne plutot que de la jeter. Un
                // patch de forme inconnue doit rester lisible ; le silence serait pire.
                raw.isNotEmpty() -> out += Line(Kind.Meta, raw)

                // Ligne vide finale du `split` : ignoree, elle n'appartient a aucun bloc.
                else -> Unit
            }
        }

        return FileDiff(
            path = dto.file,
            status = Status.from(dto.status),
            additions = dto.additions,
            deletions = dto.deletions,
            patchMissing = dto.patch.isBlank(),
            lines = out,
        )
    }

    /**
     * Une ligne est-elle un en-tete de fichier ?
     *
     * ⚠️ **La protection n'est pas l'index, c'est la position** : cette fonction n'est appelee que
     * s'il n'y a **aucun bloc `@@` ouvert**. C'est une garantie plus forte qu'une borne de numero
     * de ligne — et c'est aussi la seule qui soit juste, puisque dans un diff git reel les lignes
     * `---` / `+++` sont en positions 2 et 3, apres `diff --git` et `index`.
     *
     * ⚠️ Un `+++` a l'interieur d'un bloc `@@` est donc du **contenu**, jamais un en-tete : c'est
     * ce que verifie le test « du code qui commence par trois plus ».
     */
    private fun isFileHeader(line: String): Boolean = when {
        line.startsWith("diff --git ") -> true
        line.startsWith("index ") -> true
        line.startsWith("new file mode") -> true
        line.startsWith("deleted file mode") -> true
        line.startsWith("--- ") -> true
        line.startsWith("+++ ") -> true
        else -> false
    }

    /** Classe une ligne **a l'interieur d'un bloc `@@`**. */
    private fun classify(line: String): Line = when {
        line.startsWith("\\") -> Line(Kind.Meta, line)
        line.startsWith("+") -> Line(Kind.Addition, line.removePrefix("+"))
        line.startsWith("-") -> Line(Kind.Deletion, line.removePrefix("-"))
        // ⚠️ Tout le reste est du contexte, y compris une ligne **vide** : dans un diff unifie,
        // une ligne de contexte vide s'ecrit comme une ligne entierement vide (`\n`), sans le
        // prefixe d'espace que git omet en fin de ligne. La traiter comme du vide serait correct,
        // mais la jeter perdrait le rythme du fichier.
        else -> Line(Kind.Context, line.removePrefix(" "))
    }
}
