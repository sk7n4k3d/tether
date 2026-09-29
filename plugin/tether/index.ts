/**
 * Le plugin Tether — cote **serveur**.
 *
 * Il fait deux choses, et rien d'autre :
 *
 *  1. **Enregistrer les appareils.** L'app appelle `subscribe` avec son endpoint et
 *     ses cles. On les garde dans une liste (modele Misskey, pas Mastodon).
 *  2. **Pousser.** Quand un tour se termine ou qu'une decision attend, on chiffre un
 *     resume (Web Push, RFC 8291) et on le poste a chaque appareil concerne.
 *
 * ## Ce que ce plugin ne fait plus
 *
 * La version precedente passait par un topic ntfy en **ecriture anonyme** : l'endpoint
 * du telephone transiait par un bus, avec un mot de passe partage et un cache TTL a
 * surveiller. Desormais l'endpoint arrive par l'API que l'app **authentifie deja**,
 * et le telephone n'est plus joignable que par le canal qu'il a lui-meme enregistre.
 *
 * ## Le sens est unique
 *
 * L'app envoie, le serveur n'envoie **rien** vers le telephone en dehors d'un push.
 * Aucune configuration du serveur ne redescend vers l'app. C'est une regle de
 * conception, pas une precaution : l'inverse ferait du telephone un terminal
 * pilotable, ce qu'il n'est pas.
 */

import { appendFileSync, readFileSync } from "node:fs"
import { homedir } from "node:os"
import { timingSafeEqual as nodeTimingSafeEqual } from "node:crypto"

import { resolveConfig, disabledBy, type TetherConfig } from "./config.js"
import { encrypt, pushHeaders, vapidHeader, type PushSubscription } from "./webpush.js"
import {
  upsert,
  remove,
  shouldUnsubscribe,
  unsubscribeReason,
  publicList,
  recipientsFor,
  ALL_ALERTS,
  type Device,
  type Alerts,
} from "./registry.js"
import { encode, decode, attentionTitle, type Decoded } from "./protocol.js"
import { TOOL_NAMED, classify, sessionIdOf } from "./classify.js"
import {
  progressText,
  summarize,
  truncateBytes,
  turnCompletedAt,
  turnMaterial,
  turnStartedAt,
  type Resume,
  type Resumeur,
} from "./summary.js"
import {
  newToken,
  pairingLink,
  isValid,
  remainingMs,
  PAIRING_TTL_MS,
  type Pairing,
} from "./pairing.js"
import { Tether } from "./rpc.js"

const STORAGE_KEY = "devices"

/**
 * La cle du stockage ou le TUI ecrit la configuration.
 *
 * ⚠️ Le serveur ne la **lisait pas**. `resolveConfig(ctx.options ?? {})` ne passait que les
 * options d'`opencode.jsonc`, donc tout ce qui se reglait depuis `/tether config` —
 * `serverUrl`, `minSeconds`, `summaryUrl` — partait dans un stockage que personne ne relisait.
 * C'est le meme defaut que `summaryUrl` lui-meme : une option declaree, documentee, et morte.
 */
const CONFIG_KEY = "config"

export default {
  id: "tether",

  // Sans `features.rpc`, l'enregistrement plus bas est ignore. Mesure sur 2.0.x.
  features: { rpc: true, tui: true },

  async setup(ctx: any) {
    // ⚠️ Trois couches, dans l'ordre : ce que l'utilisateur a regle depuis `/tether config`
    // (le stockage), puis les options d'`opencode.jsonc`, puis l'environnement. Lire le
    // stockage est le correctif : sans lui, le reglage fait dans le TUI n'atteignait jamais
    // le serveur (voir `CONFIG_KEY`).
    const reglages = await ctx.storage.get(CONFIG_KEY)
    const config = resolveConfig(
      ctx?.options ?? {},
      process.env,
      (reglages && typeof reglages === "object" ? reglages : {}) as Record<string, string>,
    )
    const log = makeLogger(config)

    log("info", {
      event: "setup",
      features: { rpc: true, tui: true },
      // Un seul recapitulatif. Pas de secret dedans, jamais d'endpoint.
      desactive: disabledBy(config),
    })

    /** Charge le registre. Le stockage du plugin est une valeur JSON, pas une table. */
    const load = async (): Promise<Device[]> => {
      const stored = await ctx.storage.get(STORAGE_KEY)
      return Array.isArray(stored) ? (stored as Device[]) : []
    }

    const save = async (devices: Device[]): Promise<void> => {
      await ctx.storage.set(STORAGE_KEY, devices as any)
    }

    // ------------------------------------------------------------------
    // La poussee
    // ------------------------------------------------------------------

    /**
     * Poste a un appareil et applique la regle de desabonnement.
     *
     * ⚠️ C'est la fonction la plus sensible du plugin : un 4xx mal interprete efface
     * un appareil definitivement, et rien ne le signale a l'utilisateur. D'ou
     * [shouldUnsubscribe], qui isole la regle et la teste.
     */
    const pushOne = async (device: Device, decoded: Decoded): Promise<void> => {
      const subscription: PushSubscription = { endpoint: device.endpoint, keys: device.keys }

      const { body } = encrypt(subscription, encode(decoded))
      const headers: Record<string, string> = {
        ...pushHeaders(),
        TTL: "3600",
        Urgency: decoded.progress ? "low" : "normal",
        // L'identifiant de topic sert au distributeur a regrouper, et nous evite
        // d'ecrire l'endpoint dans un log partage.
        Topic: `tether-${device.deviceId.slice(0, 8)}`,
      }

      if (config.vapidPrivateKeyFile) {
        const pem = readFileSync(config.vapidPrivateKeyFile, "utf8")
        const audience = new URL(device.endpoint).origin
        headers.Authorization = vapidHeader(pem, pem, audience, "mailto:admin@example.org")
      }

      let status: number
      try {
        const response = await fetch(device.endpoint, { method: "POST", headers, body })
        status = response.status
        response.body?.cancel()
      } catch (error) {
        // Panne reseau : on garde l'appareil. Unlike a 4xx, ce n'est pas une preuve
        // que l'abonnement est mort.
        log("warn", { event: "push_failed", deviceId: device.deviceId, reason: "network" })
        return
      }

      if (status >= 200 && status < 300) {
        log("debug", { event: "push_ok", deviceId: device.deviceId, status })
        return
      }

      if (shouldUnsubscribe(status)) {
        const devices = await load()
        const next = remove(devices, device.deviceId)
        await save(next)
        log("warn", { event: "unsubscribed", deviceId: device.deviceId, status, reason: unsubscribeReason(status) })
        return
      }

      // 408, 429, 5xx : on garde l'appareil et on retente plus tard.
      log("warn", { event: "push_retry_later", deviceId: device.deviceId, status })
    }

    /** Pousse a tous les abonnes d'un type d'alerte. */
    const pushAll = async (alert: keyof Alerts, decoded: Decoded): Promise<void> => {
      const devices = await load()
      const targets = recipientsFor(devices, alert)
      if (targets.length === 0) {
        log("debug", { event: "push_no_recipient", alert })
        return
      }
      await Promise.all(targets.map((device) => pushOne(device, decoded)))
    }

    // ------------------------------------------------------------------
    // L'appairage — le jeton a usage unique que porte le QR
    //
    // ⚠️ Un jeton a la fois, pas une `Map`. La version precedente gardait une
    // `Map<string, number>` et lisait `pairing.get()` **sans argument** : une `Map`
    // rend `undefined` pour une cle absente, donc `active` etait toujours `false` et
    // aucun jeton n'etait jamais cree. Le type ne lattrape pas — `Map.get()` accepte
    // une cle facultative — et rien ne leve : le statut disait juste « inactif » en
    // permanence. Un seul objet `Pairing | null` ne permet pas cette erreur.
    // ------------------------------------------------------------------

    let current: Pairing | null = null

    const pairingExpiryMs = (): number => remainingMs(isValid(current) ? current : null)

    /** Le jeton courant s'il est encore valable, sinon `null`. */
    const livePairing = (): Pairing | null => (isValid(current) ? current : null)

    /**
     * Emet un jeton, **en remplacant** le precedent.
     *
     * Un seul a la fois est volontaire : deux QR affiches simultanement pourreraient
     * l'utilisateur, et le second serait invalide sans qu'il puisse le savoir. La
     * regle cote serveur est « un jeton vivant a la fois ».
     */
    const mintPairing = (server: string): Pairing => {
      current = { token: newToken(), expiresAt: Date.now() + PAIRING_TTL_MS }
      log("info", { event: "pairing_minted", expiresInMs: PAIRING_TTL_MS })
      return current
    }

    /**
     * Consomme le jeton present par l'app : a usage unique.
     *
     * Consommer **avant** d'eregistrer est ce qui rend l'usage unique vrai. Si
     * l'enregistrement echoue apres, le jeton est perdu et l'utilisateur doit rescanner
     * — c'est le bon compromis : preferer un jeton mort a un jeton reutilisable.
     */
    const consumePairing = (token: unknown): string | null => {
      if (typeof token !== "string" || token.length === 0) return "jeton d'appairage manquant"
      const vivant = livePairing()
      if (!vivant) return "jeton d'appairage expire ou deja utilise"
      // Comparaison a temps constant : le jeton est un secret, et sa longueur est fixe.
      if (!timingSafeEqual(token, vivant.token)) return "jeton d'appairage invalide"
      current = null
      return null
    }

    // ------------------------------------------------------------------
    // Le RPC — c'est par la que l'app remonte son endpoint
    // ------------------------------------------------------------------

    /**
     * `subscribe` — l'app annonce son endpoint et ses cles.
     *
     * ⚠️ **Le schema ne filtre pas les cles surnumeraires** (`additionalProperties: false`
     * n'est pas applique par opencode 2.0.x — mesure). On valide donc nous-memes, et
     * `ctx.error` leve une erreur typee propre. Le client voit `rpc.invalid_input`.
     *
     * ⚠️ Le succes repond un `deviceId` **public** et rien d'autre. Ni endpoint, ni
     * cles : ce sont des capacites d'ecriture, et la reponse traverse le reseau.
     */
    const subscribe = {
      async subscribe(input: any, mctx: any) {
        // ⚠️ `mctx.error(type, message, data)` et **pas** `throw new Error`.
        // Mesure : un `throw` classique remonte en `rpc.internal` **HTTP 500**, avec le
        // message remplace par « RPC call failed » — l'app ne peut ni distinguer le
        // cas, ni afficher pourquoi. Les erreurs declarees dans `rpc.ts` sortent en
        // 400 avec leur `type`, donc l'app peut les traiter nommement.
        const problem = validateSubscription(input)
        if (problem) return mctx.error("invalid", problem, { reason: problem })

        const devices = await load()

        // ⚠️ Le jeton d'appairage n'est exige que pour un appareil **inconnu**. C'est la
        // condition pour que « idempotent sur deviceId » soit vrai dans les faits, et sans
        // elle l'app **perd ses notifications au premier renouvellement d'endpoint** — le
        // distributeur redistribue son point d'acces au redemarrage, l'app n'a plus de
        // jeton a fournir, et l'ancien endpoint reste enregistre jusqu'a pourrir.
        //
        // Pourquoi un appareil deja connu n'a pas a re-prouver quoi que ce soit : cet
        // appel ne lui accorde **aucune capacite nouvelle**. Le serveur detient deja
        // l'endpoint de ce `deviceId`, donc celui qui le remplace n'obtient rien de plus
        // que ce qu'il avait — il deplace un point d'ecriture qui lui est deja acquis.
        // La vraie barriere reste l'authentification HTTP basic de l'appel : sans le mot
        // de passe du serveur, `deviceId` seul ne donne rien. Exiger en plus un QR que
        // l'utilisateur n'a pas sous la main rendrait le canal cassable par le
        // fonctionnement normal du distributeur, pas plus sur.
        const dejaEnregistre = devices.some((d) => d.deviceId === String(input.deviceId).slice(0, 64))
        const jetonFourni = typeof input.pairingToken === "string" && input.pairingToken.length > 0

        if (jetonFourni || !dejaEnregistre) {
          // Un jeton **fourni** est toujours valide ou refuse — que l appareil soit connu
          // ou non. Accepter un jeton fabrique pour un appareil deja enregistre
          // reviendrait a dire « jeton inutile ici » : c'est faux, et ca rendrait le
          // controle de la ceremonie decoratif. Un jeton mort reste un jeton mort.
          const jeton = consumePairing(input.pairingToken)
          if (jeton) return mctx.error("unpaired", jeton, { reason: jeton })
        }
        // Sinon : appareil connu, aucun jeton fourni. C'est le renouvellement d'endpoint
        // du distributeur, il n'a rien a prouver de nouveau.

        const device: Device = {
          deviceId: String(input.deviceId).slice(0, 64),
          endpoint: String(input.endpoint),
          keys: { p256dh: String(input.keys.p256dh), auth: String(input.keys.auth) },
          alerts: {
            turnEnd: input.alerts?.turnEnd !== false,
            attention: input.alerts?.attention !== false,
            progress: input.alerts?.progress !== false,
          },
          ...(input.label ? { label: String(input.label).slice(0, 64) } : {}),
          ...(input.distributor ? { distributor: String(input.distributor).slice(0, 128) } : {}),
          registeredAt: Date.now(),
        }

        const next = upsert(devices, device)
        await save(next)

        log("info", {
          event: "subscribed",
          deviceId: device.deviceId,
          // Le nombre d'appareils, et lui. Jamais l'endpoint, jamais les cles.
          total: next.length,
        })

        return { ok: true, total: next.length }
      },

      async unsubscribe(input: any, mctx: any) {
        const deviceId = String(input?.deviceId ?? "")
        if (deviceId.length === 0) return mctx.error("invalid", "deviceId manquant", { reason: "deviceId manquant" })

        const devices = await load()
        const next = remove(devices, deviceId)
        await save(next)

        log("info", { event: "unsubscribe_requested", deviceId, removed: next.length !== devices.length })
        return { ok: true, total: next.length }
      },

      /** La liste des appareils, **en vue publique**. */
      async devices(_input: any, _mctx: any) {
        return { devices: publicList(await load()) }
      },

      /**
       * L'etat d'un appairage en cours. Ne renvoie **jamais** le jeton : l'app n'en a
       * pas besoin, c'est elle qui le *presente*.
       */
      async pairingStatus(_input: any, _mctx: any) {
        return { active: livePairing() !== null, expiresInMs: pairingExpiryMs() }
      },

      /**
       * Le TUI demande un jeton, pour l'afficher en QR.
       *
       * C'est le seul point ou le jeton transite en clair, et il n'est disponible que
       * depuis le TUI **sur la machine ou l' utilisateur a deja entre le mot de passe**
       * — donc derriere la meme authentification que tout le reste de l'API. Le QR est
       * la seule chose que l'utilisateur transporte, et il expire en 30 minutes.
       */
      async pair(input: any, mctx: any) {
        const server = typeof input?.server === "string" ? input.server.trim() : ""
        if (server.length === 0) return mctx.error("invalid", "adresse du serveur manquante", { reason: "adresse du serveur manquante" })
        // Meme contrainte que `endpoint` : un lien d'appairage en `http://` ou en
        // `file://` ferait pointer le telephone vers un service interne.
        if (!server.startsWith("https://") && !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?\/?$/.test(server)) {
          return mctx.error("invalid", "adresse du serveur refusee (https, ou localhost en local)", { reason: "adresse du serveur refusee" })
        }

        const p = mintPairing(server)
        return { link: pairingLink(server, p.token), expiresInMs: PAIRING_TTL_MS }
      },
    }

    const handle = await ctx.rpc.register(Tether, subscribe)

    log("info", { event: "rpc_registered", methods: Object.keys(subscribe) })

    // ------------------------------------------------------------------
    // Le flux d'evenements
    // ------------------------------------------------------------------

    /** Tours notifies, pour ne pas renvoyer deux fois la meme fin de tour. */
    const seen = new Set<string>()
    const trimSeen = (): void => {
      if (seen.size <= 200) return
      // `Set` preserve l'ordre d'insertion : les premieres entrees sont les plus anciennes.
      const drop = seen.size - 200
      let i = 0
      for (const key of seen) {
        if (i++ >= drop) break
        seen.delete(key)
      }
    }

    /**
     * Le nom de chaque appel d'outil, par son `id`.
     *
     * ⚠️ Indispensable, et pas un confort : `session.tool.called` ne porte pas `name`. Sans
     * cette memoire, l'etape s'affiche `Etape en cours` — c'est-a-dire sans rien dire.
     * Elague a 200 entrees : un tour n'en produit jamais autant.
     */
    const toolNames = new Map<string, string>()

    /**
     * Le resumeur, tel que la configuration le decrit — ou `null` si elle ne le decrit pas.
     *
     * La cle est lue **a chaque resume**, pas au demarrage : une rotation de cle ne demande
     * alors aucun redemarrage, et la valeur ne stagne pas en memoire du serveur.
     */
    const resumeur = (): Resumeur | null => {
      const url = config.summaryUrl
      const fichier = config.summaryKeyFile
      if (!url || !fichier) return null
      if (!url.startsWith("http://") && !url.startsWith("https://")) return null
      let cle = ""
      try {
        cle = readFileSync(fichier.replace(/^~(?=\/|$)/, homedir()), "utf8").trim()
      } catch (error) {
        log("warn", { event: "summary_key_unreadable", reason: String(error) })
        return null
      }
      if (cle.length === 0) return null
      return { url, cle, modele: config.summaryModel }
    }

    /** Le resume du tour, ou `null` — et `null` n'est jamais une erreur : c'est le repli brut. */
    const resumer = async (material: string): Promise<Resume | null> => {
      const cible = resumeur()
      if (!cible) return null
      const resume = await summarize(material, cible)
      if (!resume) log("debug", { event: "summary_fallback" })
      return resume
    }

    /**
     * **La fin d'un tour : lire la session, resumer, publier.**
     *
     * ⚠️ Le texte publie n'est plus `"Tour termine"`. Quatre mots qui ne disent rien du travail
     * fait — et le resume etait deja declare dans la configuration depuis le premier commit,
     * sans avoir jamais ete branche.
     *
     * ⚠️ Les **sous-sessions** (delegations de sous-agents) sont ignorees : leur travail remonte
     * sous le nom de la session parente, et notifier les deux ferait deux alertes pour un tour.
     */
    const notifierFinDeTour = async (event: any, sessionID: string | undefined): Promise<void> => {
      if (!sessionID) return

      let session: any
      try {
        session = await ctx.session.get({ sessionID })
      } catch (error) {
        // Sans la session, on ne peut ni filtrer les sous-sessions ni lire le tour. On notifie
        // quand meme : une notification pauvre vaut mieux qu'un silence inexplique.
        log("warn", { event: "session_get_failed", reason: String(error) })
        await pushAll("turnEnd", { text: "Tour termine", sessionID })
        return
      }

      if (session?.parentID) {
        log("debug", { event: "turn_end_child_skipped", sessionID })
        return
      }

      let messages: unknown = []
      try {
        messages = await ctx.session.context({ sessionID })
      } catch (error) {
        log("warn", { event: "session_context_failed", sessionID, reason: String(error) })
      }

      // ⚠️ La cle dit **quel** tour, pas **quelle** session : dedupliquer sur la session ne
      // notifiait qu'une fois par session, quel que soit le nombre de tours.
      const fin = turnCompletedAt(messages)
      const cle = `${sessionID}:${fin ?? "?"}`
      if (seen.has(cle)) {
        log("debug", { event: "turn_end_duplicate", sessionID })
        return
      }
      seen.add(cle)
      trimSeen()

      const debut = turnStartedAt(messages)
      const duree = debut !== null && fin !== null ? Math.round((fin - debut) / 1000) : null
      if (config.minSeconds > 0 && duree !== null && duree < config.minSeconds) {
        log("debug", { event: "turn_end_too_short", sessionID, duree })
        return
      }

      const material = turnMaterial(messages, config.maxBytes)
      if (material.trim().length === 0) {
        log("debug", { event: "turn_end_empty", sessionID })
        return
      }

      const resume = await resumer(material)
      const echec = String(event?.type ?? "").includes("failed")

      // ⚠️ Repli sur le materiau brut, tronque : sans resumeur configure, c'est la liste des
      // actions du tour. C'est moins lisible qu'une phrase, et c'est exact — la regle du projet
      // est de ne jamais inventer un texte a la place de ce qui s'est passe.
      const corps = resume?.corps ?? truncateBytes(material, config.maxBytes)
      const texte = echec ? "⚠ Tour en echec\n\n" + corps : corps

      await pushAll("turnEnd", {
        text: texte,
        ...(resume?.titre ? { title: resume.titre } : {}),
        sessionID,
      })
      log("info", {
        event: "turn_end_notified",
        sessionID,
        duree,
        resume: resume !== null,
        octets: texte.length,
      })
    }

    const controller = new AbortController()
    void (async () => {
      try {
        for await (const event of ctx.event.subscribe({ signal: controller.signal })) {
          const type: string | undefined = event?.type

          // Le nom de l'outil arrive **dans un autre evenement** que son entree : on le
          // memorise ici, on publie plus bas. Sans ce `continue`, l'evenement tomberait dans
          // `classify`, qui ne le reconnait pas.
          if (type === TOOL_NAMED) {
            const id = event?.data?.id
            const name = event?.data?.name
            if (typeof id === "string" && typeof name === "string") {
              toolNames.set(id, name)
              if (toolNames.size > 200) {
                const plusAncien = toolNames.keys().next().value
                if (plusAncien !== undefined) toolNames.delete(plusAncien)
              }
            }
            continue
          }

          const decision = classify(event)
          if (!decision.kind) continue

          const sessionID = sessionIdOf(event)

          if (decision.kind === "attention") {
            await pushAll("attention", {
              text: attentionTitle(decision.tool),
              ...(sessionID ? { sessionID } : {}),
            })
            continue
          }

          if (decision.kind === "progress") {
            const toolID = event?.data?.id
            const nom = typeof toolID === "string" ? toolNames.get(toolID) : undefined
            await pushAll("progress", {
              // `bash : npm install` plutot que `bash en cours` — le detail vient de l'entree
              // reelle de l'outil, jamais d'un libelle de notre cru.
              text: progressText(nom, event?.data?.input),
              ...(sessionID ? { sessionID } : {}),
              progress: true,
            })
            continue
          }

          await notifierFinDeTour(event, sessionID)
        }
      } catch (error) {
        if (!controller.signal.aborted) log("warn", { event: "event_stream_failed", reason: String(error) })
      }
    })()

    return () => {
      controller.abort()
      handle.dispose().catch(() => {})
    }
  },
}

/**
 * Validation de l'entree de `subscribe`.
 *
 * Necessaire parce que le schema **n'applique pas** `additionalProperties: false`
 * (mesure sur 2.0.x) : sans ca, une cle surnumeraire traverserait jusqu'au stockage.
 * Le message est destine a l'utilisateur via `rpc.invalid_input`, donc il dit ce
 * qui manque plutot que ce qui a echoue.
 */
function validateSubscription(input: any): string | null {
  if (!input || typeof input !== "object") return "payload attendu"

  const deviceId = input.deviceId
  if (typeof deviceId !== "string" || deviceId.trim().length === 0) return "deviceId manquant"
  if (deviceId.length > 64) return "deviceId trop long (64 octets maximum)"

  const endpoint = input.endpoint
  if (typeof endpoint !== "string") return "endpoint manquant"
  // Le push part **la** ou l'app l'a dit. Refuser autre chose qu'https empeche qu'un
  // enregistrement pointe le serveur vers un service interne — un SSRF, en fait.
  if (!endpoint.startsWith("https://")) return "endpoint https requis"

  const keys = input.keys
  if (!keys || typeof keys !== "object") return "cles manquantes"
  if (typeof keys.p256dh !== "string" || keys.p256dh.length === 0) return "cle publique p256dh manquante"
  if (typeof keys.auth !== "string" || keys.auth.length === 0) return "secret d'authentification manquant"

  if (input.alerts != null && typeof input.alerts !== "object") return "alerts doit etre un objet"

  return null
}

// ---------------------------------------------------------------------------

/**
 * Egalite a temps constant, sur deux chaines de **longueur egale**.
 *
 * Un jeton d'appairage est un secret : le comparer avec `===` laisse fuiter, par la
 * duree de la comparaison, combien de caracteres premiers sont corrects. Sur 22
 * caracteres base64url, ca reduit le travail de brute force de 22 a 21 — peu, mais le
 * cout de la correctitude est nul, alors autant le payer.
 *
 * La longueur est verifiee avant : `timingSafeEqual` de `node:crypto` exige des buffers
 * de meme taille, et un jeton d'une autre longueur n'est de toute facon pas valide.
 */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  return nodeTimingSafeEqual(Buffer.from(a, "utf8"), Buffer.from(b, "utf8"))
}

function makeLogger(config: TetherConfig) {
  return (level: "info" | "warn" | "debug", payload: Record<string, unknown>) => {
    if (level === "debug" && !config.debug) return
    // Un seul point de sortie. Aucun secret n'y passe : les appels ci-dessus ne
    // mettent jamais `endpoint` ni `keys` dans le payload.
    const line = JSON.stringify({ level, ...payload })
    if (level === "warn") console.error(`[tether] ${line}`)
    else console.info(`[tether] ${line}`)

    // ⚠️ `debugLogFile` etait declare et documente, mais **jamais ecrit** : le service de fond
    // n'expose pas la sortie standard de ses plugins, donc le journal de diagnostic annonce
    // dans le README etait inutilisable en pratique. Meme famille de defaut que `summaryUrl`.
    //
    // ⚠️ Une erreur d'ecriture n'est **jamais fatale** : le journal sert a diagnostiquer, il ne
    // doit pas devenir une panne de plus.
    if (config.debugLogFile) {
      try {
        appendFileSync(config.debugLogFile, `${new Date().toISOString()} ${line}\n`)
      } catch {}
    }
  }
}

export { upsert, remove, shouldUnsubscribe, publicList, ALL_ALERTS }
export type { Device, Alerts, Decoded }
