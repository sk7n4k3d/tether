package sh.sk7.tether.domain.model

/**
 * Une **demande d'autorisation** en attente : l'agent veut faire quelque chose et attend.
 *
 * ### C'est le coeur d'un client d'agent
 * Le rapport d'usage d'opencode est sans ambiguite : ce qui manque le plus aux utilisateurs, et
 * ce qui les fait rester devant leur ecran, c'est de **devoir approuver une action**. Une session
 * peut rester bloquee des heures parce que personne n'a vu la demande. Pouvoir repondre depuis
 * le telephone est la raison d'etre premiere d'une app compagne.
 *
 * ### Pourquoi on montre tout
 * ⚠️ On affiche **l'action**, **la ressource**, et le **message** du serveur, pas un resume
 * arrange. Un utilisateur qui approuve doit savoir exactement ce qu'il approuve : c'est le seul
 * endroit de l'app ou une decision a des consequences reelles sur sa machine.
 *
 * Formes relevees sur le serveur (2026-09-25) :
 * `{id: "per…", sessionID: "ses…", action, resources[], save[], metadata{}, source{}, message}`.
 */
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    /** L'action soumise : `bash`, `edit`, `external_directory`… */
    val action: String,
    /** Sur quoi elle porte. Plusieurs ressources possibles pour une meme demande. */
    val resources: List<String> = emptyList(),
    /** Ressources pour lesquelles « toujours » serait memorise. */
    val save: List<String> = emptyList(),
    /** Message libre du serveur, quand il en donne un. */
    val message: String? = null,
)

/**
 * La reponse a une demande.
 *
 * ⚠️ Trois valeurs, et **pas** un booleen (formes serveur : `once | always | reject`) :
 *  - [Once] approuve cette fois seulement ;
 *  - [Always] approuve **et memorise** — la demande ne reviendra pas, donc le choix engage ;
 *  - [Reject] refuse.
 *
 * ⚠️ L'ordre d'affichage suit la consequence : `once` est le choix par defaut propose, `always`
 * demande une lecture plus attentive, `reject` est explicite. Un utilisateur doit pouvoir
 * approuver **sans** accorder un droit permanent.
 */
enum class PermissionDecision(val wire: String) {
    Once("once"),
    Always("always"),
    Reject("reject"),
}
