package sh.sk7.tether.data.api

import kotlinx.serialization.Serializable

/**
 * **Le systeme de fichiers vu par le serveur** (`/api/fs/...`).
 *
 * ### Pourquoi ces routes existent dans l'app
 * L'agent lit des fichiers sur la machine ; sans ces routes, l'app ne peut montrer que ce que
 * l'agent a bien voulu citer. Avec elles, on peut **verifier un chemin avant de l'envoyer**, voir
 * ce que l'agent verra d'un fichier, et chercher ou se trouve un fichier — les trois gestes qui
 * evitent un aller-retour de conversation pour une faute de frappe.
 *
 * ### Les formes sont mesurees, pas deduites du schema
 * Releve du 2026-09-25 sur le serveur de le serveur :
 *
 * ```
 * GET /api/fs/list?path=Projects/tether
 *   -> {"location":{"directory":"/home/utilisateur"},
 *       "data":[{"path":"Projects/tether/app/","type":"directory"}, ...]}
 *
 * GET /api/fs/find?query=gradlew&type=file&limit=3
 *   -> {"location":{...},"data":[{"path":"gradlew","type":"file"}, ...]}
 *
 * GET /api/fs/read/Projects/tether/settings.gradle.kts
 *   -> 200, corps BRUT (`application/octet-stream`), pas de JSON
 * ```
 *
 * ⚠️ **Deux pieges mesures, et ils sont silencieux :**
 *
 * 1. `path` est **relatif au `location`**. Un chemin **absolu** est refuse :
 *    `GET /api/fs/read/home/utilisateur/...` rend `404 FileNotFoundError`. Meme chose pour `list`.
 *    Le prefixe `{path}` de `read` est un **joker** (`/api/fs/read/<chemin>` dans l'OpenAPI), pas un
 *    parametre nomme : on ne peut donc pas l'encoder comme une query.
 * 2. `read` renvoie du **binaire brut**, jamais l'enveloppe `{location, data}` des deux autres.
 *    Les confondre ferait echouer le decodage JSON sur un fichier parfaitement lisible.
 *
 * ⚠️ `find` a un parametre `limit` declare `type: string` dans l'OpenAPI (et non `integer`) :
 * il faut donc l'envoyer comme chaine, ce que `parameter()` fait de toute facon.
 */
@Serializable
data class FsEntryDto(
    /** Chemin **relatif au `location`**, tel que le serveur le rend. */
    val path: String,
    /** `file` ou `directory` (seules deux valeurs de l'enum cote serveur). */
    val type: String = "file",
) {
    val isDirectory: Boolean get() = type == "directory"
}

/**
 * `GET /api/reference` : une reference invocable (`@nom`) dans le repertoire.
 *
 * ⚠️ Mesure du 2026-09-25 : renvoie `{"location":{...},"data":[]}` sur ce serveur — la route
 * **existe** et repond, elle n'est simplement pas alimentee ici. Un ecran qui l'afficherait
 * comme « cassee » mentirait ; on dit « aucune », ce qui est un fait different.
 */
@Serializable
data class ReferenceDto(
    val name: String,
    val path: String = "",
    val description: String? = null,
    val hidden: Boolean = false,
)

/**
 * `Reference.Source` : d'ou vient la reference. On ne garde que le type, qui est ce qui se dit
 * a l'utilisateur ; le reste de la charge est opaque et ne nous sert pas.
 */
@Serializable
data class ReferenceSourceDto(val type: String? = null)

/** `GET /api/vcs/branch` : `{location, data: [noms de branches]}` — des chaines nues. */
@Serializable
data class BranchListEnvelope(
    val location: LocationInfo? = null,
    val data: List<String> = emptyList(),
)
