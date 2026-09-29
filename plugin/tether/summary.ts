/**
 * Les resumes : **l'entree d'un outil, le materiau d'un tour, l'appel au resumeur.**
 *
 * ## Pourquoi un module separe
 *
 * Trois publics, trois fonctions pures, et une seule chose impure :
 *
 * 1. `progressText` — ce qu'affiche une etape d'avancement (`shell : npm install`) ;
 * 2. `turnMaterial` — ce qu'on donne a lire au resumeur (ce qui a ete dit et fait) ;
 * 3. `summarize` — le seul appel reseau, isole pour que le reste se teste sans lui.
 *
 * ⚠️ **La regle du projet tient dans la premiere fonction : « ne jamais mentir ».** Un libelle
 * generique invente serait pire que pas de detail — la notification doit dire ce qui se passe
 * vraiment, ou se taire. C'est pour ca que `summarizeToolInput` renvoie `null` plutot qu'un
 * texte de remplissage quand l'entree ne porte aucune cle connue.
 */

/** Tronque une ligne a [max] caracteres, sans casser un caractere UTF-8. */
export function oneLine(value: string, max = 120): string {
  const flat = value.replace(/\s+/g, " ").trim()
  // `slice` travaille sur les unites UTF-16 : couper au milieu d'une paire de substitution
  // produit un U+FFFD dans la notification. `Array.from` itere les points de code, donc
  // chaque element est un caractere entier.
  const points = Array.from(flat)
  if (points.length <= max) return flat
  return points.slice(0, max).join("") + "…"
}

/** Un chemin se lit par sa FIN : `/a/b/c/d.kt` -> `…/c/d.kt` (le debut ne distingue rien). */
export function shortenPath(path: string): string {
  const parts = path.trim().replace(/\/+$/, "").split("/").filter((p) => p.length > 0)
  if (parts.length <= 2) return path
  return "…/" + parts.slice(-2).join("/")
}

/**
 * Les cles reconnues, dans l'ordre de lecture.
 *
 * ⚠️ Elles suivent les outils **reellement** utilises (`bash` -> `command`, `read` -> `path`,
 * `grep`/`glob` -> `pattern`), pas une liste exhaustive imaginee. `description` est en dernier :
 * c'est la cle la plus vague, donc celle qu'on prefere quand rien de precis n'est disponible.
 */
const CLEFS_ENTREE = ["command", "path", "filePath", "pattern", "query", "url", "description"]

/**
 * **Resume l'entree d'un outil en une ligne**, sans jamais rien inventer.
 *
 * Renvoie `null` si aucune cle connue n'est presente : l'appelant se contente alors du nom de
 * l'outil. Un detail faux serait pire qu'un detail absent.
 */
export function summarizeToolInput(input: unknown): string | null {
  if (!input || typeof input !== "object") return null
  const objet = input as Record<string, unknown>
  for (const cle of CLEFS_ENTREE) {
    const valeur = objet[cle]
    if (typeof valeur !== "string" || valeur.trim().length === 0) continue
    if (cle === "path" || cle === "filePath") return shortenPath(valeur)
    return oneLine(valeur)
  }
  return null
}

/**
 * **Le texte d'une etape d'avancement** : `shell : npm install`.
 *
 * ⚠️ Le nom vient de l'evenement qui le porte (`session.tool.input.started`), le detail de
 * l'entree reelle (`session.tool.called`). Le repli `"Etape en cours"` n'existe que pour le cas
 * ou un appel d'outil arriverait sans nom memorise — un cas de course, pas un cas normal.
 */
export function progressText(name: string | undefined, input: unknown): string {
  const outil = (name ?? "").trim()
  const detail = summarizeToolInput(input)
  if (outil.length === 0) return detail ?? "Etape en cours"
  return detail ? `${outil} : ${detail}` : outil
}

/** Tronque un texte en octets, sans couper un caractere au milieu. */
export function truncateBytes(text: string, maxBytes: number): string {
  if (maxBytes <= 0) return text
  const buf = Buffer.from(text, "utf8")
  if (buf.byteLength <= maxBytes) return text
  return buf.subarray(0, maxBytes).toString("utf8").replace(/\uFFFD$/, "") + "\n\n… (tronque)"
}

/**
 * **Le materiau d'un tour : ce qui a ete dit, et ce qui a ete fait.**
 *
 * ## Pourquoi pas seulement le texte de l'assistant
 *
 * La version precedente ne prenait que les parts `text` du dernier message assistant. Mesure du
 * 2026-09-29 sur une session reelle : **2 messages sur 31** portaient du texte — un tour
 * agentique est fait d'appels d'outils, pas de phrases. Resumer ce seul texte aurait rendu
 * « Je sonde leurs signatures reelles. » pour un tour qui a lu 8 fichiers et lance 23 commandes.
 *
 * On donne donc les deux au resumeur : le texte, puis les actions. Il resume ce qu'il voit.
 *
 * ⚠️ On part du **dernier message utilisateur**, pas du debut de la session : un tour est ce qui
 * suit la derniere demande. Resumer toute la session redonnerait le meme resume a chaque tour.
 */
export function turnMaterial(messages: unknown, limite = 6000): string {
  if (!Array.isArray(messages) || messages.length === 0) return ""

  let dernierUser = -1
  for (let i = messages.length - 1; i >= 0; i--) {
    if ((messages[i] as any)?.type === "user") {
      dernierUser = i
      break
    }
  }

  const textes: string[] = []
  const etapes: string[] = []
  for (const message of messages.slice(dernierUser + 1)) {
    if ((message as any)?.type !== "assistant") continue
    for (const part of ((message as any)?.content ?? []) as any[]) {
      if (part?.type === "text" && typeof part.text === "string" && part.text.trim().length > 0) {
        textes.push(part.text.trim())
      } else if (part?.type === "tool") {
        etapes.push(progressText(part?.name, part?.state?.input))
      }
    }
  }

  const morceaux: string[] = []
  if (textes.length > 0) morceaux.push("Texte de l'assistant :\n" + textes.join("\n\n"))
  if (etapes.length > 0) morceaux.push("Actions :\n" + etapes.map((e) => "- " + e).join("\n"))

  return truncateBytes(morceaux.join("\n\n"), limite)
}

/**
 * **Le debut du tour** : l'horodatage du dernier message utilisateur.
 *
 * Sert au seuil `minSeconds` — un tour de deux secondes n'apprend rien a personne.
 */
export function turnStartedAt(messages: unknown): number | null {
  if (!Array.isArray(messages)) return null
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i] as any
    if (message?.type !== "user") continue
    const cree = message?.time?.created
    if (typeof cree === "number") return cree
  }
  return null
}

/**
 * **La fin du tour** : l'horodatage du dernier message assistant.
 *
 * ⚠️ C'est aussi la **cle de deduplication**. La version precedente dedupliquait sur le seul
 * identifiant de session : une session ne notifiait donc **qu'une fois**, quel que soit le
 * nombre de tours. Mesure sur une session de 34 messages : 30 fins de tour possibles, une
 * seule alerte. La cle doit dire *quelle* fin de tour, pas *quelle* session.
 *
 * ⚠️ Repli en cascade `completed` -> `streamed` -> `created` : `completed` n'est ecrit qu'une
 * fois le message termine, et le lire seul rendrait `null` sur un tour interrompu — donc une
 * cle instable.
 */
export function turnCompletedAt(messages: unknown): number | null {
  if (!Array.isArray(messages)) return null
  let fin: number | null = null
  for (const message of messages) {
    if ((message as any)?.type !== "assistant") continue
    const temps = (message as any)?.time ?? {}
    const valeur = temps.completed ?? temps.streamed ?? temps.created
    if (typeof valeur === "number") fin = valeur
  }
  return fin
}

/** Ce qu'il faut pour appeler le resumeur. Aucun defaut : sans les trois, il n'y a pas de resume. */export interface Resumeur {
  /** Endpoint **OpenAI-compatible** (`/v1/chat/completions`). */
  readonly url: string
  /** La cle, lue du fichier — jamais transportee dans la configuration. */
  readonly cle: string
  /**
   * Le modele a demander, ou `null` pour **laisser l'endpoint router**.
   *
   * ⚠️ Pas de defaut : ecrire un nom de modele ici ferait dependre le plugin d'une infra
   * precise, ce que la regle du projet interdit.
   */
  readonly modele: string | null
}

/** Un resume pret a publier. */
export interface Resume {
  /** 3 a 6 mots, ou `null` si le resumeur n'en a pas fourni. */
  readonly titre: string | null
  /** 1 a 2 phrases. Jamais vide. */
  readonly corps: string
}

/** Ce qu'on demande au modele. Figé : un prompt qui derive donne des resumes qui derivent. */
const CONSIGNE =
  "Resume le travail ci-dessous pour une notification push.\n" +
  "Reponds UNIQUEMENT par un objet JSON, sans balise de code, de la forme :\n" +
  '{"titre":"3 a 6 mots","resume":"1 a 2 phrases"}\n\n' +
  "Contraintes :\n" +
  "- en francais, ton neutre et factuel\n" +
  "- dis CE QUI A ETE FAIT, pas que tu as fini\n" +
  "- pas de markdown, pas de guillemets internes, pas de retour a la ligne\n" +
  "- max 6 mots pour le titre, max 200 caracteres pour le resume\n\n" +
  "Travail :\n"

/**
 * **Resume un tour via un endpoint OpenAI-compatible.**
 *
 * ⚠️ **Ne leve jamais.** Toute panne — endpoint absent, timeout, reponse illisible, modele qui
 * repond en prose — rend `null`, et l'appelant publie le materiau brut. Une notification moins
 * jolie vaut mieux qu'une notification perdue : c'est la meme regle que partout dans le plugin.
 */
export async function summarize(
  material: string,
  resumeur: Resumeur,
  fetchFn: typeof fetch = fetch,
): Promise<Resume | null> {
  if (material.trim().length === 0) return null

  let reponse: Response
  try {
    reponse = await fetchFn(resumeur.url, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${resumeur.cle}`,
      },
      body: JSON.stringify({
        ...(resumeur.modele ? { model: resumeur.modele } : {}),
        messages: [{ role: "user", content: CONSIGNE + material }],
        temperature: 0.2,
        max_tokens: 300,
        stream: false,
      }),
      signal: AbortSignal.timeout(30_000),
    })
  } catch {
    return null
  }

  if (!reponse.ok) {
    reponse.body?.cancel().catch(() => {})
    return null
  }

  let charge: any
  try {
    charge = await reponse.json()
  } catch {
    return null
  }

  const brut: string = charge?.choices?.[0]?.message?.content ?? ""
  // On tolere un bloc ```json … ``` autour : certains modeles en ajoutent meme quand on
  // demande le contraire, et refuser la reponse pour deux backticks serait gratuit.
  const trouve = brut.match(/\{[\s\S]*\}/)
  if (!trouve) return null

  let objet: any
  try {
    objet = JSON.parse(trouve[0])
  } catch {
    return null
  }

  // ⚠️ Les deux orthographes, parce que la consigne est en francais et que le modele peut
  // repondre avec les cles anglaises. On accepte ce qu'on comprend plutot que d'exiger une
  // forme : le contrat utile est « un titre et un resume », pas « ces octets-la ».
  const titre = String(objet?.titre ?? objet?.title ?? "").trim()
  const corps = String(objet?.resume ?? objet?.summary ?? "").trim()
  if (corps.length === 0) return null

  return {
    titre: titre.length > 0 ? oneLine(titre, 80) : null,
    corps: oneLine(corps, 400),
  }
}
