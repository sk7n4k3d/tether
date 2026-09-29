/**
 * La configuration du plugin, editee depuis le TUI.
 *
 * ## Pourquoi une logique pure a part
 *
 * `node:test` ne lit pas le JSX, donc `tui.tsx` n'est jamais exerce par les tests. Tout ce
 * qui **decide** — quelles options existent, comment les valider, comment les decrire, quel
 * ordre de saisie — vit ici, et se teste. `tui.tsx` ne fait qu'appeler `dialog.prompt` et
 * `storage.store` avec le resultat.
 *
 * C'est la meme separation que `tui-logic.ts`, et pour la meme raison.
 *
 * ## Ce qui est stocke, et ou
 *
 * `ctx.storage.store("config")` — un etat JSON durable, synchronise entre instances de TUI
 * ouvertes. **Pas** dans `opencode.jsonc` : le plugin n'a pas le droit d'ecrire la config de
 * l'utilisateur, et une valeur par defaut qui « fonctionne » ne doit pas dependre d'un
 * fichier que le plugin modifie tout seul.
 *
 * La resolution reste : `storage` > variable d'environnement > defaut. Le TUI ecrit donc la
 * couche haute, et `resolveConfig` la lit en premier.
 */

/** Une option, telle que le TUI la presente. */
export interface Option {
  /** Identifiant stable : c'est la cle dans le stockage et dans l'environnement. */
  readonly cle: string
  readonly titre: string
  readonly description: string
  /** Variante d'edition. `nombre` refuse une saisie non numerique. */
  readonly type: "texte" | "nombre" | "chemin"
  /** Defaut affiche quand rien n'est renseigne. `null` = pas de defaut, champ vide. */
  readonly defaut: string | null
  /**
   * Une option obligatoire n'a pas de « laisser vide » : la vider doit la remettre a son
   * defaut plutot que de la supprimer. C'est ce qui evite qu'un reset laisse le plugin
   * dans un etat ou il ne sait plus rien faire.
   */
  readonly optionnel: boolean
}

/**
 * Les options, dans l'ordre ou le TUI les propose.
 *
 * `serverUrl` est en tete parce que c'est la seule qui soit frequente : sans elle, le QR
 * porte `127.0.0.1` et le telephone ne peut pas joindre le serveur. Les variables
 * d'environnement restent listees pour qui preferent la config systeme.
 */
export const OPTIONS: readonly Option[] = [
  {
    cle: "serverUrl",
    titre: "Adresse du serveur",
    description: "L'adresse mise dans le QR — doit être joignable depuis le téléphone. 127.0.0.1 ne l'est pas.",
    type: "texte",
    defaut: null,
    optionnel: true,
  },
  {
    cle: "minSeconds",
    titre: "Seuil de notification (secondes)",
    description: "Durée minimale d'un tour avant de notifier. 0 notifie tout. Ignoré si 0.",
    type: "nombre",
    defaut: "0",
    optionnel: true,
  },
  {
    cle: "maxBytes",
    titre: "Troncature du texte (octets)",
    description: "Au-delà, la notification est coupée. 0 pour ne pas tronquer.",
    type: "nombre",
    defaut: "3800",
    optionnel: true,
  },
  {
    cle: "summaryUrl",
    titre: "Résumeur : adresse (endpoint OpenAI-compatible)",
    description: "Ex. http://hote:8080/v1/chat/completions. Vide = pas de résumé, le texte brut est publié.",
    type: "texte",
    defaut: null,
    optionnel: true,
  },
  {
    cle: "summaryKeyFile",
    titre: "Résumeur : fichier de clé",
    description: "Le CHEMIN, jamais la clé. Lu à chaque résumé, donc une rotation ne demande rien d'autre.",
    type: "chemin",
    defaut: null,
    optionnel: true,
  },
  {
    cle: "summaryModel",
    titre: "Résumeur : modèle",
    description: "Vide = on laisse l'endpoint router. Aucun nom par défaut : ce plugin ne connaît aucune infra.",
    type: "texte",
    defaut: null,
    optionnel: true,
  },
  {
    cle: "vapidPrivateKeyFile",
    titre: "Clé VAPID (chemin du fichier PEM)",
    description: "Le CHEMIN, jamais la clé. Requis seulement par les distributeurs FCM.",
    type: "chemin",
    defaut: null,
    optionnel: true,
  },
  {
    cle: "debug",
    titre: "Journal de diagnostic",
    description: "Écrire un journal détaillé. Activé ou non.",
    type: "texte",
    defaut: "non",
    optionnel: true,
  },
]

/** Les valeurs persistees, toutes optionnelles : vide = « je n'ai rien réglé ». */
export type ConfigStockee = Record<string, string>

/** Une saisie refusee, avec la raison — pour que le TUI dise *pourquoi*, pas « invalide ». */
export type Refus = { readonly cle: string; readonly raison: string }

/**
 * Valide une saisie contre le type de l'option.
 *
 * Pur, et c'est pour ca que le TUI peut le tester : la regle « un nombre, ou rien » ne doit
 * pasdependre d'un dialogue.
 */
export function valider(option: Option, saisie: string | undefined): { ok: true; valeur: string } | { ok: false; refus: Refus } {
  const brut = (saisie ?? "").trim()

  // Vide : on rend le defaut s'il y en a un, sinon on supprime.
  if (brut === "") {
    if (option.defaut === null) return { ok: true, valeur: "" }
    return { ok: true, valeur: option.defaut }
  }

  if (option.type === "nombre") {
    if (!/^\d+$/.test(brut)) {
      return { ok: false, refus: { cle: option.cle, raison: "Un nombre entier, sans signe ni décimale." } }
    }
    return { ok: true, valeur: String(Number(brut)) }
  }

  if (option.cle === "debug") {
    const normalise = brut.toLowerCase()
    if (["1", "oui", "o", "yes", "y", "true", "on"].includes(normalise)) return { ok: true, valeur: "1" }
    if (["0", "non", "n", "no", "false", "off", ""].includes(normalise)) return { ok: true, valeur: "0" }
    return { ok: false, refus: { cle: option.cle, raison: "Écrire oui ou non." } }
  }

  if (option.type === "chemin" && !brut.startsWith("/") && !brut.startsWith("~")) {
    // Un chemin relatif depend du repertoire de travail du serveur, qui n'est pas
    // forcement celui de l'utilisateur. On le refuse plutot que de l'accepter en silence.
    return { ok: false, refus: { cle: option.cle, raison: "Un chemin absolu, commençant par / ou ~." } }
  }

  return { ok: true, valeur: brut }
}

/**
 * Le texte a afficher pour une valeur stockee, dans l'ecran de configuration.
 *
 * Une valeur vide doit se lire « (non définie) » plutot qu'un champ vide : la difference
 * entre « je n'ai rien rule » et « j'ai efface » est invisible, et l'utilisateur ne sait
 * plus s'il a configure quelque chose.
 */
export function afficher(valeur: string | undefined): string {
  const v = (valeur ?? "").trim()
  if (v === "" || v === "0" || v === "non") return "(non définie)"
  return v
}

/** L'etat initial du stockage : rien de rule. */
export function configInitiale(): ConfigStockee {
  return {}
}

/**
 * Applique une saisie validee au stockage.
 *
 * Une option dont la valeur redonne son defaut est **retiree** plutot que stockee. C'est ce
 * qui fait qu'un reset redonne exactement l'etat « rien de rule », donc exactement le
 * comportement d'une installation fraiche.
 */
export function appliquer(config: ConfigStockee, option: Option, valeur: string): ConfigStockee {
  const suivant = { ...config }
  if (valeur === "" || (option.defaut !== null && valeur === option.defaut)) delete suivant[option.cle]
  else suivant[option.cle] = valeur
  return suivant
}
