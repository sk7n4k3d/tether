/**
 * Configuration du plugin Tether.
 *
 * ## Aucun défaut ne pointe sur l'infra de qui que ce soit
 *
 * C'est la règle du projet, et elle a une raison concrète : la version precedente
 * avait `https://ntfy.example.com/…`, `http://192.0.2.20:8099/…` et un mot de passe en
 * valeur par defaut. Publie, cela donnait le relais de l'auteur a quiconque
 * installait le plugin — y compris son mot de passe.
 *
 * Ici, un defaut absent signifie « la fonction est desactivee, et un log l'explique ».
 * C'est moins agreable que « ca marche direct », et c'est exactement le bon
 * compromis : une configuration qui fonctionne vraiment ne peut pas etre une
 * coincidence.
 *
 * ## Priorite : options opencode, puis environnement, puis defaut
 *
 * `ctx.options` n'est renseigne que si le plugin est declare dans `opencode.json(c)`
 * sous sa forme objet. Un plugin depose dans `~/.config/opencode/plugins/` a
 * `options = {}` — mesure le 2026-09-28, pas suppose.
 */

/** Ce que le plugin sait faire sans configuration. */
export interface TetherConfig {
  /** Distributeur de secours, si aucun appareil n'est appaire. Vide = desactive. */
  fallbackTopicUrl: string | null
  /** Seuil de duree, en secondes, avant de notifier une fin de tour. */
  minSeconds: number
  /** Troncature du texte, en octets. */
  maxBytes: number
  /** Active le journal de diagnostic. */
  debug: boolean
  /** Fichier du journal de diagnostic. */
  debugLogFile: string | null
  /** Endpoint OpenAI-compatible du resumeur. Vide = pas de resume. */
  summaryUrl: string | null
  /** Fichier contenant la cle du resumeur. Chemin, jamais la cle. */
  summaryKeyFile: string | null
  /** Cle P-256 pour VAPID, en PEM. Vide = pas d'en-tete VAPID envoye. */
  vapidPrivateKeyFile: string | null
}

const num = (raw: string | undefined, fallback: number): number => {
  if (raw === undefined) return fallback
  const parsed = Number(raw)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback
}

const str = (raw: string | undefined): string | null => {
  if (raw === undefined) return null
  const trimmed = raw.trim()
  return trimmed.length === 0 ? null : trimmed
}

/**
 * Resout la configuration. **Pure** : ni `fs`, ni `fetch`, ni log.
 *
 * Les trois parametres ont une valeur par defaut pour que la fonction soit
 * appelable sans argument dans un test — et pour qu'un test puisse passer un
 * `env` factice, ce qui est le seul moyen de prouver qu'une variable est lue.
 *
 * ## Trois couches, dans cet ordre
 *
 * 1. **`stores`** — ce que l'utilisateur a regle depuis `/tether config`. C'est la couche
 *    haute : c'est un geste explicite, fait dans l'interface, et il doit gagner.
 * 2. **`options`** — la config opencode, pour qui declare le plugin en forme objet.
 * 3. **`env`** — pour un service, ou un shell.
 *
 * ⚠️ Une option **vide** dans `stores` ne doit pas ecraser une couche basse. Le TUI retire
 * une option de `stores` quand on la remet a son defaut, donc une chaine vide signifie
 * « niee » — pas « vide ». Sans cette distinction, un reset effacerait une variable
 * d'environnement au lieu de la rendre au defaut, et le comportement serait inexplicable.
 */
export function resolveConfig(
  options: any = {},
  env: NodeJS.ProcessEnv = process.env,
  stores: Record<string, string | undefined> = {},
): TetherConfig {
  // La couche haute gagne, mais **seulement** si elle dit quelque chose.
  const couche = (cle: string, ...suivants: (string | undefined)[]): string | undefined => {
    const depuisStore = str(stores[cle])
    if (depuisStore !== null) return depuisStore
    for (const suivant of suivants) {
      const v = str(suivant)
      if (v !== null) return v
    }
    return null
  }

  const debugStore = stores.debug
  const debug = debugStore === "1" || (debugStore === undefined && (options.debug === true || env.TETHER_DEBUG === "1"))

  return {
    fallbackTopicUrl: couche("fallbackTopicUrl", options.fallbackTopicUrl, env.TETHER_FALLBACK_TOPIC_URL),
    minSeconds: num(couche("minSeconds", options.minSeconds, env.TETHER_MIN_SECONDS), 0),
    maxBytes: num(couche("maxBytes", options.maxBytes, env.TETHER_MAX_BYTES), 3800),
    debug,
    debugLogFile: couche("debugLogFile", options.debugLogFile, env.TETHER_DEBUG_LOG_FILE),
    summaryUrl: couche("summaryUrl", options.summaryUrl, env.TETHER_SUMMARY_URL),
    summaryKeyFile: couche("summaryKeyFile", options.summaryKeyFile, env.TETHER_SUMMARY_KEY_FILE),
    vapidPrivateKeyFile: couche("vapidPrivateKeyFile", options.vapidPrivateKeyFile, env.TETHER_VAPID_KEY_FILE),
  }
}

/** Les fonctions desactivees, avec l'option a definir. Alimente le log de `setup`. */
export function disabledBy(config: TetherConfig): string[] {
  const disabled: string[] = []
  if (!config.fallbackTopicUrl) disabled.push("topic de secours (fallbackTopicUrl)")
  if (!config.summaryUrl || !config.summaryKeyFile) disabled.push("resume des notifications (summaryUrl + summaryKeyFile)")
  if (!config.vapidPrivateKeyFile) disabled.push("VAPID (vapidPrivateKeyFile) — requis seulement par les distributeurs FCM")
  return disabled
}
