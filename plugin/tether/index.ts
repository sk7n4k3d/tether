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

import { readFileSync } from "node:fs"

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
import { Tether } from "./rpc.js"

const STORAGE_KEY = "devices"

/** evenements V2 : la forme reelle, mesuree. `session.idle` ne suffit plus. */
const TURN_END = new Set(["session.execution.succeeded", "session.execution.failed", "session.execution.interrupted"])
const ATTENTION = new Set(["permission.asked", "form.created"])

/** Ce qu'on a decide de notifier, en pur. Teste sans opencode. */
export function classify(event: any): { kind: "turnEnd" | "attention" | "progress" | null; tool?: string } {
  const type: string | undefined = event?.type

  if (ATTENTION.has(type ?? "")) {
    return { kind: "attention", tool: toolOf(event) }
  }
  if (TURN_END.has(type ?? "")) {
    return { kind: "turnEnd" }
  }
  if (typeof type === "string" && type.startsWith("session.tool.")) {
    return { kind: "progress", tool: toolOf(event) }
  }
  return { kind: null }
}

/**
 * Le nom de l'outil. ⚠️ Mesure : `session.tool.called` **ne porte pas** `name`, il vient
 * de `session.tool.input.started`. Sans cette memorisation, la carte affiche « outil
 * inconnu » — et c'est le bug qui a fait porter lattention sur ce chemin en 2026-09.
 */
function toolOf(event: any): string | undefined {
  const payload = event?.data ?? {}
  if (typeof payload.name === "string" && payload.name) return payload.name
  if (typeof payload.tool === "string" && payload.tool) return payload.tool
  return undefined
}

/** L'identifiant de session, ou la forme V2 (`data.sessionID`) ou le legacy. */
export function sessionIdOf(event: any): string | undefined {
  return event?.data?.sessionID ?? event?.sessionID ?? event?.properties?.sessionID
}

export default {
  id: "tether",

  // Sans `features.rpc`, l'enregistrement plus bas est ignore. Mesure sur 2.0.x.
  features: { rpc: true, tui: true },

  async setup(ctx: any) {
    const config = resolveConfig(ctx?.options ?? {})
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
    // ⚠️ Declare **avant** le RPC : la fermeture `pairingStatus` le capture, et une
    // `const` plus bas serait en zone morte au moment de l'appel. Une declaration
    // apres usage se voit au premier `ReferenceError` en production, pas a la
    // compilation.
    // ------------------------------------------------------------------

    const pairing = new Map<string, number>()

    const pairingExpiryMs = (): number => {
      const deadlines = [...pairing.values()]
      if (deadlines.length === 0) return 0
      return Math.max(0, Math.min(...deadlines) - Date.now())
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

        const devices = await load()
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
       * L'app demande le lien d'appairage en cours.
       *
       * Le jeton n'est **jamais** renvoye par ce RPC : il vit dans le QR, que seul
       * l'utilisateur scanne. Renvoyer l'etat suffit a l'app, qui n'a pas besoin de
       * le jeton — c'est elle qui le *presente*.
       */
      async pairingStatus(_input: any, _mctx: any) {
        return { active: pairing.get() !== null, expiresInMs: pairingExpiryMs() }
      },
    }

    const handle = await ctx.rpc.register(Tether, subscribe)

    log("info", { event: "rpc_registered", methods: Object.keys(subscribe) })

    // ------------------------------------------------------------------
    // Le flux d'evenements
    // ------------------------------------------------------------------

    /** Sessions notifiees, pour ne pas renvoyer deux fois la meme fin de tour. */
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

    const controller = new AbortController()
    void (async () => {
      try {
        for await (const event of ctx.event.subscribe({ signal: controller.signal })) {
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
            await pushAll("progress", {
              text: decision.tool ? `${decision.tool} en cours` : "Etape en cours",
              ...(sessionID ? { sessionID } : {}),
              progress: true,
            })
            continue
          }

          if (sessionID) {
            if (seen.has(sessionID)) continue
            seen.add(sessionID)
            trimSeen()
          }

          await pushAll("turnEnd", { text: "Tour termine", ...(sessionID ? { sessionID } : {}) })
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

function makeLogger(config: TetherConfig) {
  return (level: "info" | "warn" | "debug", payload: Record<string, unknown>) => {
    if (level === "debug" && !config.debug) return
    // Un seul point de sortie. Aucun secret n'y passe : les appels ci-dessus ne
    // mettent jamais `endpoint` ni `keys` dans le payload.
    const line = JSON.stringify({ level, ...payload })
    if (level === "warn") console.error(`[tether] ${line}`)
    else console.info(`[tether] ${line}`)
  }
}

export { upsert, remove, shouldUnsubscribe, publicList, ALL_ALERTS }
export type { Device, Alerts, Decoded }
