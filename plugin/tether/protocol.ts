/**
 * Le protocole entre le serveur et l'app — **charge utile structurée, versionnée**.
 *
 * ## Pourquoi du JSON alors que le texte passait très bien
 *
 * La v0 envoyait du texte brut avec des marqueurs de transport en annexe :
 * `tether:session=<id>` et `tether:progress=1`, un par ligne. Ça marche **jusqu'à ce
 * qu'un texte utilisateur contienne la même chaîne**, et là, c'est indécidable :
 *
 * ```
 * texte : « commande copiée :\ntether:progress=1 »
 * ```
 *
 * Aucune analyse ne peut dire si cette ligne est un marqueur ou du contenu. Les
 * troissolutions qu'on a essayees (motif multiligne ancré, « ligne exactement égale »,
 * « ligne à l'index 1 ») **tombent toutes** : la première classe le texte comme un
 * marqueur, les deux autres acceptent un texte multi-lignes dont la 2ᵉ ligne est le
 * mot. Le test qui l'a démontré est dans `registry.test.mjs`.
 *
 * Le point de sortie est là : **le corps est chiffré**. Un distributeur voit une
 * suite d'octets illisible, donc une structure n'expose rien. On peut enfin
 * distinguer le transport du contenu — et l'envoyer en JSON.
 *
 * ```
 * { "v": 1, "text": "…", "title": "…", "sessionID": "ses_…", "progress": false }
 * ```
 *
 * `v` est obligatoire : le jour où le format change, l'app sait qu'elle a affaire à
 * autre chose au lieu de deviner.
 *
 * `title` est **optionnel et additif** (ajouté en v1, pas en v2) : une app qui ne le connaît
 * pas l'ignore et recompose son titre comme avant. C'est le seul moyen d'envoyer le titre d'un
 * résumé sans casser les téléphones non mis à jour — l'en-tête `Title` de ntfy, lui, ne
 * traverse pas UnifiedPush (voir `TetherNotifier`).
 *
 * ## Compatibilite
 *
 * L'app **lit les deux** : JSON d'abord, marqueurs en repli. Ça coûte quelques
 * lignes et évite qu'une mise à jour du serveur muette les notifications d'un
 * téléphone qui n'a pas encore été mis à jour. Le repli sera retiré à la version
 * suivante du format.
 */

export const PROTOCOL_VERSION = 1

/** Ce que le serveur envoie dans le corps chiffré. */
export interface Payload {
  v: number
  /** Texte lisible par l'humain. Jamais une instruction : un avertissement. */
  text: string
  /** Titre du resume, quand il y en a un. L'app le prefere a son titre recompose. */
  title?: string
  /** Session concernée, pour que le tap ouvre la bonne. */
  sessionID?: string
  /** `true` si c'est une étape d'avancement dans un tour en cours. */
  progress?: boolean
}

/** Ce que l'app sait reconstruire d'un corps recu. */
export interface Decoded {
  text: string
  title?: string
  sessionID?: string
  progress: boolean
}

/** Construit la charge utile. `text` est nettoye : pas de saut de ligne final. */
export function encode(options: { text: string; title?: string; sessionID?: string; progress?: boolean }): string {
  // ⚠️ Un titre fait de blanc vaut un titre absent : ecrit vide, il ecraserait cote app le
  // titre recompose et la notification n'aurait plus d'en-tete du tout.
  const titre = options.title?.trim() ?? ""
  const payload: Payload = {
    v: PROTOCOL_VERSION,
    text: options.text.trimEnd(),
    ...(titre.length > 0 ? { title: titre } : {}),
    ...(options.sessionID ? { sessionID: options.sessionID } : {}),
    ...(options.progress ? { progress: true } : {}),
  }
  return JSON.stringify(payload)
}

/**
 * Décode un corps. **JSON d'abord, marqueurs en repli** — voir l'en-tête.
 *
 * Renvoie `null` si le corps n'est ni l'un ni l'autre : mieux vaut ne pas notifier
 * qu'afficher une notification vide dont l'utilisateur ne comprendra pas l'origine.
 */
export function decode(body: string): Decoded | null {
  const trimmed = body.trimStart()

  // Un corps qui commence par `{` est **voulu** du JSON. Si l'analyse echoue, on ne
  // retombe pas sur le parseur de marqueurs : aucun corps v0 ne commence par `{`, et
  // le repli afficherait litteralement `{"v":1}` dans la notification.
  if (trimmed.startsWith("{")) return tryDecodeJson(body)

  return tryDecodeLegacy(body)
}

function tryDecodeJson(body: string): Decoded | null {
  // Un corps texte ne commence pas par `{` : inutile de tenter le parse.
  if (!body.trimStart().startsWith("{")) return null

  let raw: unknown
  try {
    raw = JSON.parse(body)
  } catch {
    return null
  }

  if (typeof raw !== "object" || raw === null) return null
  const candidate = raw as Partial<Payload>

  if (typeof candidate.text !== "string") return null
  if (candidate.text.trim().length === 0) return null

  return {
    text: candidate.text,
    ...(typeof candidate.title === "string" && candidate.title.trim() ? { title: candidate.title } : {}),
    ...(typeof candidate.sessionID === "string" && candidate.sessionID ? { sessionID: candidate.sessionID } : {}),
    progress: candidate.progress === true,
  }
}

/** Repli v0 : le texte, puis `tether:progress=1`, puis `tether:session=<id>`. */
function tryDecodeLegacy(body: string): Decoded | null {
  const lines = body.split("\n")
  const text: string[] = []
  let sessionID: string | undefined
  let progress = false

  for (const line of lines) {
    if (line === PROGRESS_MARKER) {
      progress = true
    } else if (line.startsWith(SESSION_PREFIX)) {
      const value = line.slice(SESSION_PREFIX.length).trim()
      // Une session vide donnerait un deep link `opencode://session/` : un appel qui
      // echoue a coup sur, que l'app ne distingue pas d'une absence d'identifiant.
      if (value) sessionID = value
    } else {
      text.push(line)
    }
  }

  if (text.length === 0) return null

  // Un corps vide ou fait de blanc ne vaut pas une notification vide : l'utilisateur
  // verrait une pastille sans texte et n'aurait aucun moyen de comprendre d'ou elle
  // vient. Mieux vaut ne rien afficher.
  const joined = text.join("\n")
  if (joined.trim().length === 0) return null

  return { text: joined, ...(sessionID ? { sessionID } : {}), progress }
}

const PROGRESS_MARKER = "tether:progress=1"
const SESSION_PREFIX = "tether:session="

/** Ce que l'app affiche quand une décision attend. */
export function attentionTitle(tool?: string): string {
  return tool ? `Tether — approbation : ${tool}` : "Tether — approbation requise"
}

/** Lien profond d'ouverture d'une session. Le schéma est aussi un contrat public. */
export function deepLink(sessionID: string): string {
  return `opencode://session/${sessionID}`
}
