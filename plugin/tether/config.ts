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
 * Les deux parametres ont une valeur par defaut pour que la fonction soit
 * appelable sans argument dans un test — et pour qu'un test puisse passer un
 * `env` factice, ce qui est le seul moyen de prouver qu'une variable est lue.
 */
export function resolveConfig(options: any = {}, env: NodeJS.ProcessEnv = process.env): TetherConfig {
  return {
    fallbackTopicUrl: str(options.fallbackTopicUrl ?? env.TETHER_FALLBACK_TOPIC_URL),
    minSeconds: num(options.minSeconds ?? env.TETHER_MIN_SECONDS, 0),
    maxBytes: num(options.maxBytes ?? env.TETHER_MAX_BYTES, 3800),
    debug: (options.debug ?? env.TETHER_DEBUG) === true || env.TETHER_DEBUG === "1",
    debugLogFile: str(options.debugLogFile ?? env.TETHER_DEBUG_LOG_FILE),
    summaryUrl: str(options.summaryUrl ?? env.TETHER_SUMMARY_URL),
    summaryKeyFile: str(options.summaryKeyFile ?? env.TETHER_SUMMARY_KEY_FILE),
    vapidPrivateKeyFile: str(options.vapidPrivateKeyFile ?? env.TETHER_VAPID_KEY_FILE),
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
