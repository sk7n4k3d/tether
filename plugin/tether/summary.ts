/**
 * Les resumes : **l'entree d'un outil, le materiau d'un tour, l'appel au resumeur.**
 *
 * ## Pourquoi un module separe
 *
 * Trois publics, trois fonctions pures, et la seule impure :
 *
 * 1. `progressText` — ce qu'affiche une etape d'avancement (`shell : npm install`) ;
 * 2. `turnMaterial` — ce qu'on donne a lire au resumeur (ce qui a ete dit et fait) ;
 * 3. `summarize` / `summarizeFragmente` — l'appel reseau, isole pour que tout le reste se
 *    teste sans lui.
 *
 * ⚠️ **La regle du projet tient dans la premiere fonction : « ne jamais mentir ».** Un libelle
 * generique invente serait pire que pas de detail — la notification doit dire ce qui se passe
 * vraiment, ou se taire. C'est pour ca que `summarizeToolInput` renvoie `null` plutot qu'un
 * texte de remplissage quand l'entree ne porte aucune cle connue.
 *
 * ## Les tours longs : on fragmente, on met en cache, on combine
 *
 * Mesure du 2026-09-29 sur une session reelle : le tour le plus lourd faisait **6 704 octets**
 * pour un plafond de troncature a 3 800, avec 173 appels d'outils — donc **45 % du travail
 * n'atteignait jamais le resumeur**, et le resume qu'il produisait ne pouvait pas en parler. Ce
 * n'est pas un cas limite : c'est le tour normal d'un agent qui travaille.
 *
 * La suite de fonctions ci-dessous traite ce cas par **reduction par etapes** :
 *
 * 1. [fragmenter] decoupe le materiau sur des **frontieres de ligne** (jamais au milieu d'une
 *    commande, d'une URL ou d'un chemin) ;
 * 2. chaque fragment est resume en **une phrase**, et le resultat est **mis en cache** par
 *    empreinte du contenu — le meme fragment ne passe donc jamais deux fois au modele ;
 * 3. les phrases sont reunies, et la these finale (`{titre, resume}`) sort de la meme maniere
 *    que pour un tour court.
 */

import { createHash } from "node:crypto"

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
 * **La taille d'un fragment de resume.**
 *
 * ⚠️ Un nombre, et sa raison : 6 000 octets ≈ 1 500 jetons. C'est ce qu'un **petit modele local**
 * lit entierement en une passe, et c'est assez grand pour qu'un tour de dix outils ne parte pas
 * en dix requetes. Au-dela, la these finale perdrait le debut ; en dessous, on paierait un
 * aller-retour par ligne.
 */
export const TAILLE_FRAGMENT = 6000

/**
 * **Le plafond du materiau**, pour qu'un tour de plusieurs heures ne produise pas cinquante
 * requetes.
 *
 * ⚠️ Passe ce plafond, on garde la **FIN** du tour : pour « qu'a fait l'agent », la fin est ce qui
 * parle, et c'est elle qui porte le resultat. Le debut coupe est **annonce**, jamais efface en
 * silence — sinon le resume promettrait un travail entier alors qu'il n'en a vu qu'un morceau.
 */
export const PLAFOND_MATERIAU = 120_000

/** Combien de fragments on garde en memoire avant d'evicter le plus ancien. */
export const CAPACITE_CACHE = 200

/**
 * **L'empreinte d'un fragment** — la cle du cache.
 *
 * ⚠️ `sha1`, pas un hash 32 bits maison : le cache sert a **reconnaitre**, mais une collision
 * attribuerait le resume d'un fragment a un autre, et le resume dirait alors faux. Un vrai hash
 * coute trois lignes ; un resume faux coute la confiance dans tous les autres.
 */
export function empreinte(texte: string): string {
  return createHash("sha1").update(texte, "utf8").digest("hex")
}

/** La mention posee sur une ligne trop longue, et sur un materiau trop long. */
const MARQUE_TRONCATURE = "… (tronque)"

/**
 * **Decoupe un materiau en fragments, sur des frontieres de ligne.**
 *
 * ⚠️ Jamais au milieu d'une ligne. Une ligne ici est soit une action (`- shell : …`), soit un
 * paragraphe du modele : la couper en deux produirait un fragment qui se termine sur une commande
 * incomplete, et le resume dirait « lance `npm instal` ».
 *
 * ⚠️ Une ligne plus longue que [taille] est **tronquee** plutot que laissee deborder : c'est la
 * seule entorse a la regle, et elle est visible. Le budget de la mention est **deduit** de la
 * coupe, sinon le fragment qui annonce sa troncature depasse lui-meme la taille qu'on s'etait
 * fixee — et l'invariant « un fragment tient dans la taille » devient faux.
 */
export function fragmenter(material: string, taille: number): string[] {
  if (material.length === 0) return []
  if (taille <= 0) return [material]

  const fragments: string[] = []
  let courant: string[] = []
  // ⚠️ Pas `octets` : ce nom est celui de la fonction de ce fichier, et une variable locale
  // portant le meme nom la masque — l'appel echoue sans que l'analyse de types le voie.
  let courantOctets = 0

  for (const ligne of material.split("\n")) {
    const poids = Buffer.byteLength(ligne, "utf8") + 1
    if (courantOctets > 0 && courantOctets + poids > taille) {
      fragments.push(courant.join("\n"))
      courant = []
      courantOctets = 0
    }
    courant.push(
      Buffer.byteLength(ligne, "utf8") > taille
        ? `${truncateBytes(ligne, taille - octets(MARQUE_TRONCATURE)).split("\n")[0]}${MARQUE_TRONCATURE}`
        : ligne,
    )
    courantOctets += poids
  }
  if (courant.length > 0) fragments.push(courant.join("\n"))

  return fragments
}

/** Les octets d'un texte, en UTF-8. Nomme parce que c'est l'UNITE de toutes les bornes d'ici. */
function octets(texte: string): number {
  return Buffer.byteLength(texte, "utf8")
}

/**
 * **Garde la fin du materiau, et dit combien de debut a disparu.**
 *
 * ⚠️ Le motif est visible par le modele : c'est ce qui l'empeche de resumer « 200 Ko de travail »
 * alors qu'il n'en a lu que 30.
 */
export function tronquerParLaFin(texte: string, plafond: number): string {
  if (plafond <= 0) return texte
  const buf = Buffer.from(texte, "utf8")
  if (buf.byteLength <= plafond) return texte
  const coupe = buf.byteLength - plafond
  const garde = buf.subarray(coupe).toString("utf8").replace(/^�/, "")
  return `… (${Math.round(coupe / 1000)} Ko du debut du tour omis)\n${garde}`
}

/**
 * **Un cache borne des resumes de fragments.**
 *
 * ⚠️ **Borne, sinon c'est une fuite.** Un agent qui travaille des heures produit des milliers de
 * fragments, et un `Map` sans plafond garderait des phrases en memoire pour toute la vie du
 * service. L'eviction retire le **plus ancien insere** : `Map` preserve l'ordre d'insertion, et
 * un fragment ancien est de toute facon le moins susceptible de revenir.
 *
 * ⚠️ Le cache vit dans l'**instance** du plugin : il survit a un tour et a une rafale, pas a un
 * rechargement. C'est la bonne portee — ce qu'on evite, c'est de repayer le meme fragment dans la
 * meme minute, pas de reconstruire un historique de resumes a froid.
 */
export class CacheFragments {
  private readonly map = new Map<string, string>()
  private readonly capacite: number

  constructor(capacite: number = CAPACITE_CACHE) {
    if (!Number.isInteger(capacite) || capacite <= 0) throw new Error("capacite invalide")
    this.capacite = capacite
  }

  /** Nombre de fragments memorises. */
  get taille(): number {
    return this.map.size
  }

  get(cle: string): string | undefined {
    return this.map.get(cle)
  }

  set(cle: string, valeur: string): void {
    this.map.set(cle, valeur)
    while (this.map.size > this.capacite) {
      const plusAncien = this.map.keys().next().value
      if (plusAncien === undefined) return
      this.map.delete(plusAncien)
    }
  }

  vider(): void {
    this.map.clear()
  }
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
export function turnMaterial(messages: unknown, plafond = PLAFOND_MATERIAU): string {
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

  return tronquerParLaFin(morceaux.join("\n\n"), plafond)
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
  /**
   * Combien d'appels au modele ont ete necessaires (1 = un seul, > 1 = reduction
   * par etapes, autant de fragments). Expose pour le journal de production : c'est
   * la seule preuve observable qu'un tour long a bien ete fragmente, pas tronque.
   */
  readonly fragments: number
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
    fragments: 1,
  }
}

/** Ce qu'on demande au modele pour **un seul fragment**. */
const CONSIGNE_FRAGMENT =
  "Resume ce fragment de travail en UNE phrase factuelle (francais, 30 mots maximum).\n" +
  "- dis ce qui a ete fait, pas ce qu'il reste a faire\n" +
  "- pas de markdown, pas de guillemets, pas de retour a la ligne\n" +
  "- reponds par la phrase seule, sans preambule\n\n" +
  "Fragment :\n"

/**
 * **Resume un fragment en une phrase.**
 *
 * ⚠️ Rend `null` au moindre echec, comme [summarize] : c'est l'appelant qui decide du repli, et
 * ici le repli n'est pas de perdre le fragment mais de le citer (voir [summarizeFragmente]).
 */
async function resumerFragment(
  fragment: string,
  resumeur: Resumeur,
  fetchFn: typeof fetch,
): Promise<string | null> {
  let reponse: Response
  try {
    reponse = await fetchFn(resumeur.url, {
      method: "POST",
      headers: { "content-type": "application/json", Authorization: `Bearer ${resumeur.cle}` },
      body: JSON.stringify({
        ...(resumeur.modele ? { model: resumeur.modele } : {}),
        messages: [{ role: "user", content: CONSIGNE_FRAGMENT + fragment }],
        temperature: 0.2,
        max_tokens: 120,
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
  const phrase = oneLine(brut, 300)
  return phrase.length > 0 ? phrase : null
}

/** Les reglages de la reduction par etapes. */
export interface OptionsFragmente {
  /** Octets par fragment. [TAILLE_FRAGMENT] par defaut cote appelant. */
  readonly tailleFragment: number
  /** Le cache des resumes de fragments. */
  readonly cache: CacheFragments
}

/**
 * **Resume un materiau, quel que soit sa taille** — un tour, ou dix mille actions.
 *
 * ### Le chemin court
 * Un materiau qui tient dans un fragment part dans **une seule requete**, exactement comme
 * avant. C'est le cas courant, et il ne doit pas payer une carte de plus.
 *
 * ### Le chemin long : reduction par etapes
 * Un materiau trop long est **decoupe sur des frontieres de ligne**, chaque fragment est resume
 * en une phrase, les phrases sont mises en cache **par empreinte du contenu**, et la these
 * finale sort du meme [summarize] que pour un tour court.
 *
 * ### Les trois pannes qu'il ne doit pas avaler
 *
 * 1. **Un fragment qui echoue ne disparait pas.** Sans lui, la these ne parlerait que de la
 *    moitie du travail — et une moitie du travail, c'est un mensonge. On garde son debut, et on
 *    dit que c'est un debut.
 * 2. **La these finale qui echoue ne rend pas `null`.** Rendre `null` ferait republier des
 *    dizaines de kilo-octets de materiau brut dans une notification. Les phrases des fragments
 *    sont deja une synthese honnete : on la rend.
 * 3. **Un fragment deja resume ne repasse pas.** Le cache est ce qui rend cette operation
 *    tenable quand un tour en produit cinquante.
 */
export async function summarizeFragmente(
  material: string,
  resumeur: Resumeur,
  options: OptionsFragmente,
  fetchFn: typeof fetch = fetch,
): Promise<Resume | null> {
  if (material.trim().length === 0) return null

  const fragments = fragmenter(material, options.tailleFragment)
  if (fragments.length <= 1) return summarize(material, resumeur, fetchFn)

  const morceaux: string[] = []
  for (const fragment of fragments) {
    const cle = empreinte(fragment)
    const enCache = options.cache.get(cle)
    if (enCache !== undefined) {
      morceaux.push(enCache)
      continue
    }
    const phrase = await resumerFragment(fragment, resumeur, fetchFn)
    const valeur = phrase ?? `fragment illisible (${oneLine(fragment, 200)})`
    options.cache.set(cle, valeur)
    morceaux.push(valeur)
  }

  const these = await summarize(morceaux.join("\n"), resumeur, fetchFn)
  if (these) return { ...these, fragments: fragments.length }
  // ⚠️ La these finale a echoue : on rend la synthese des fragments plutot que `null`, parce que
  // `null` ferait republier le materiau brut — des dizaines de kilo-octets dans une notification.
  return { titre: null, corps: oneLine(morceaux.join(" "), 400), fragments: fragments.length }
}
