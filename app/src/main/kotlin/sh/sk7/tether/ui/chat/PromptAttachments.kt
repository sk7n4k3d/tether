package sh.sk7.tether.ui.chat

import sh.sk7.tether.data.api.PromptFileAttachment

/**
 * **Un fichier joint en attente d'envoi**, avec de quoi l'afficher.
 *
 * ⚠️ On garde la taille separement de l'URI : une fois encode en base64, l'URI fait 33 % de plus
 * et ne dit plus rien a l'utilisateur. C'est la **taille du fichier** qui l'interesse, pas celle de
 * sa transcription.
 */
data class PendingAttachment(
    val name: String,
    val sizeBytes: Int,
    /** L'URI serialisable dans le corps du prompt (`data:` inline ou `file://`). */
    val uri: String,
) {
    /** Libelle de taille, pre-calcule pour ne pas formater a chaque recomposition. */
    val sizeLabel: String get() = PromptAttachments.formatSize(sizeBytes)

    /** La forme attendue par `POST /prompt`. */
    fun toWire(): PromptFileAttachment = PromptFileAttachment(uri = uri, name = name)
}

/**
 * **Construire une piece jointe que le serveur accepte.**
 *
 * ### Pourquoi cette logique est isolee et testee
 * Le serveur n'accepte que **deux** formes d'URI (mesure du 2026-09-25) :
 *
 * ```
 *   data:text/plain;base64,aGVsbG8=                            -> 200
 *   file:///home/user/Projects/tether/settings.gradle.kts -> 200
 *   Projects/tether/README.md                                  -> 400 Invalid attachment URI
 *   https://example.com/x.md                                   -> 400 Unsupported
 * ```
 *
 * Une erreur ici ne se voit **pas** a la compilation, et l'utilisateur ne decouvre le probleme
 * qu'apres avoir appuye sur Envoyer — le prompt est refuse alors qu'il a joint son fichier. On
 * isole donc la fabrication de l'URI, qui est la partie qui peut se tromper, et on la teste.
 *
 * ⚠️ On choisit **`data:` inline** plutot que `file://` : le telephone n'a pas acces au disque du
 * serveur, et le nom du fichier local sur le telephone n'a aucun sens pour l'agent. Le contenu est
 * donc transmis, pas un chemin.
 */
object PromptAttachments {

    /**
     * Taille maximale acceptee pour un fichier joint.
     *
     * ⚠️ Mesure du 2026-09-25 : le serveur accepte sans broncher un corps de **4 Mo**
     * (`3 Mo` binaires, `4 000 094` octets de JSON). On se fixe une borne d'app **plus basse** que
     * ce que le serveur tolere, et pour une raison qui n'est pas la sienne : le base64 gonfle de
     * 33 %, la charge reste en memoire du telephone, et au-dela d'une poignee de mega-octets le
     * `POST` devient long sur un reseau mobile. Un refus **explicite** vaut mieux qu'un envoi qui
     * echoue au bout de trente secondes.
     */
    const val MAX_FILE_BYTES: Int = 4 * 1024 * 1024

    /**
     * Une piece jointe a partir du contenu d'un fichier **du telephone**.
     *
     * @param name nom affiche cote serveur (le nom local, tel quel : c'est ce que l'utilisateur
     *   reconnait, et le renommer lui ferait douter de ce qu'il a joint).
     * @param mime type MIME devine (`text/plain` par defaut : le serveur s'en sert pour decoder).
     * @return `null` si le fichier depasse [MAX_FILE_BYTES] — l'appelant doit alors le dire a
     *   l'utilisateur plutot que d'envoyer silencieusement autre chose.
     */
    fun fromBytes(name: String, mime: String?, bytes: ByteArray): PromptFileAttachment? {
        if (bytes.size > MAX_FILE_BYTES) return null
        val contentType = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        // ⚠️ `java.util.Base64` (API 26, donc minSdk 26 suffit) et non `android.util.Base64` :
        // ce dernier n'existe pas dans une JVM de test, donc la logique ne serait pas testable
        // sans Robolectric — une dependance qu'on ne veut pas ajouter pour une ligne.
        // ⚠️ L'encodeur **sans saut de ligne** est obligatoire : un `data:` URI contenant `\n`
        // n'est pas un URI valide, et le serveur le refuserait.
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        return PromptFileAttachment(uri = "data:$contentType;base64,$encoded", name = name)
    }

    /**
     * Une piece jointe a partir d'un fichier **du serveur**, par chemin absolu.
     *
     * ⚠️ Le chemin doit etre **absolu** (`file:///...`). `file://Projects/...` rend
     * `400 Invalid file URI` et `file:Projects/...` rend `400 Unable to read attachment` (mesures).
     * On ne « devine » pas le prefixe du repertoire : un chemin relatif n'a de sens que par rapport
     * a un `location`, que cette fonction ne connait pas.
     *
     * @return `null` si le chemin n'est pas absolu — mieux vaut ne rien joindre que joindre une
     *   URI que le serveur refusera.
     */
    fun fromServerPath(path: String): PromptFileAttachment? {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) return null
        val name = trimmed.trimEnd('/').substringAfterLast('/').ifBlank { trimmed }
        return PromptFileAttachment(uri = "file://$trimmed", name = name)
    }

    /** Formate une taille de fichier pour l'affichage dans la puce. */
    fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> "${bytes / 1024} Ko"
        // ⚠️ `Locale.ROOT` et non la locale par defaut : sinon un telephone en locale francaise
        // afficherait « 1,5 Mo » et un test en locale anglaise « 1.5 Mo » — la meme donnee, deux
        // textes. Le formatage d'une taille n'est pas une question de langue.
        else -> String.format(java.util.Locale.ROOT, "%.1f Mo", bytes / 1024.0 / 1024.0)
    }
}
