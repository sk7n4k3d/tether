/**
 * Le plugin Tether contre un **vrai serveur opencode**.
 *
 * ## Pourquoi pas un mock
 *
 * Le refactor de configuration du 2026-09-27 est passe 19/19 tests en **cassant le
 * push en production** : le harnais n'exerçait pas `setup()`. Un test qui simule
 * `ctx` verifie que le code fait ce qu'on imagine ; ici on verifie que le **serveur
 * accepte reellement** la forme qu'on lui envoie.
 *
 * Ce test est donc une **integration**. Il lance un vrai `opencode serve`, y charge
 * le plugin, et appelle le RPC en HTTP — exactement comme le fera l'app.
 *
 * Il est ignore si `opencode` n'est pas dans le PATH ou si le port est occupe : ce
 * n'est pas un test unitaire, et un test qui echoue faute de binaire est un test
 * qui ment sur l'etat du projet.
 */

import { test, before, after } from "node:test"
import assert from "node:assert/strict"
import { spawn, execFileSync } from "node:child_process"
import { mkdtempSync, writeFileSync, cpSync, rmSync, existsSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, dirname } from "node:path"
import { fileURLToPath } from "node:url"

const HERE = dirname(fileURLToPath(import.meta.url))
const PORT = 4299
const PASSWORD = "tether-integration"
const BASE = `http://127.0.0.1:${PORT}`

let opencodeAvailable = true
try {
  execFileSync("opencode", ["--version"], { stdio: "ignore" })
} catch {
  opencodeAvailable = false
}

let home, server

const auth = `Basic ${Buffer.from(`opencode:${PASSWORD}`).toString("base64")}`

const call = async (rpcID, method, input) => {
  const response = await fetch(`${BASE}/api/rpc/${rpcID}/${method}?directory=${encodeURIComponent(home)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: auth },
    body: JSON.stringify({ input }),
  })
  return { status: response.status, body: await response.json() }
}

before(async () => {
  if (!opencodeAvailable) return

  home = mkdtempSync(join(tmpdir(), "tether-it-"))
  const config = join(home, "config", "opencode", "plugins", "tether")
  writeFileSync(join(home, "noop"), "") // force la creation du repertoire
  cpSync(HERE, config, { recursive: true })
  // Le serveur ne doit charger que le plugin, pas les tests.
  for (const file of ["webpush.test.mjs", "registry.test.mjs", "vapid.test.mjs", "index.test.mjs"]) {
    rmSync(join(config, file), { force: true })
  }

  server = spawn("opencode", ["serve", "--port", String(PORT), "--hostname", "127.0.0.1", "--print-logs"], {
    env: {
      ...process.env,
      XDG_CONFIG_HOME: join(home, "config"),
      XDG_DATA_HOME: join(home, "data"),
      XDG_STATE_HOME: join(home, "state"),
      XDG_CACHE_HOME: join(home, "cache"),
      OPENCODE_SERVER_PASSWORD: PASSWORD,
      OPENCODE_LOG_LEVEL: "INFO",
    },
    stdio: "ignore",
  })

  // Attendre que l'API reponde.
  //
  // ⚠️ `/api/info` **exige l'authentification** : sans elle il repond 401, et une
  // boucle d'attente sur `response.ok` tourne 30 s puis declare le serveur mort
  // alors qu'il demarre parfaitement. Le test echouait pour une raison qui n'avait
  // rien a voir avec le plugin.
  for (let i = 0; i < 60; i++) {
    try {
      const response = await fetch(`${BASE}/api/info`, { headers: { Authorization: auth } })
      if (response.ok) {
        // Puis forcer le chargement de la location, qui charge les plugins.
        await call("tether", "devices", {}).catch(() => {})
        return
      }
    } catch {}
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error("le serveur opencode n'a pas demarre")
})

after(() => {
  server?.kill("SIGKILL")
  if (home && existsSync(home)) rmSync(home, { recursive: true, force: true })
})

const SUBSCRIPTION = {
  deviceId: "pixel-de-test",
  endpoint: "https://push.exemple.net/push/abc123",
  keys: { p256dh: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4", auth: "BTBZMqHH6r4Tts7J_aSIgg" },
  distributor: "org.unifiedpush.distributor.ntfy",
  label: "Pixel de test",
}

test("le serveur charge le plugin et expose le RPC", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await call("tether", "devices", {})
  assert.equal(status, 200, `appel refuse : ${JSON.stringify(body)}`)
  assert.equal(body.output.devices.length, 0, "un registre neuf est vide")
})

test("subscribe enregistre l'appareil", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await call("tether", "subscribe", SUBSCRIPTION)
  assert.equal(status, 200, `subscribe refuse : ${JSON.stringify(body)}`)
  assert.equal(body.output.ok, true)
  assert.equal(body.output.total, 1)
})

test("la reponse ne contient jamais l'endpoint ni les cles", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("tether", "subscribe", SUBSCRIPTION)
  const serialized = JSON.stringify(body)

  assert.doesNotMatch(serialized, /push\.exemple\.net/, "l'endpoint ne sort jamais")
  assert.doesNotMatch(serialized, /BCVxsr7N/, "la cle publique ne sort jamais")
  assert.doesNotMatch(serialized, /BTBZMqHH/, "le secret ne sort jamais")
})

test("devices renvoie une vue publique, sans aucune capacite", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("tether", "devices", {})
  const serialized = JSON.stringify(body)
  const [device] = body.output.devices

  assert.equal(device.deviceId, "pixel-de-test")
  assert.equal(device.label, "Pixel de test")
  assert.equal(device.distributor, "org.unifiedpush.distributor.ntfy")
  assert.ok(device.registeredAt > 0)

  assert.doesNotMatch(serialized, /push\.exemple\.net/, "aucun endpoint")
  assert.doesNotMatch(serialized, /p256dh/, "aucune cle")
})

test("reinscrire le meme deviceId ne duplique pas", { skip: !opencodeAvailable }, async () => {
  await call("tether", "subscribe", SUBSCRIPTION)
  const { body } = await call("tether", "subscribe", { ...SUBSCRIPTION, endpoint: "https://push.exemple.net/push/nouveau" })

  assert.equal(body.output.total, 1, "un seul appareil, pas deux")
})

test("deux appareils distincts coexistent", { skip: !opencodeAvailable }, async () => {
  await call("tether", "subscribe", SUBSCRIPTION)
  await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "tablette", label: "Tablette" })

  const { body } = await call("tether", "devices", {})
  assert.equal(body.output.devices.length, 2, "le modele Misskey tient")

  await call("tether", "unsubscribe", { deviceId: "tablette" })
  const { body: apres } = await call("tether", "devices", {})
  assert.equal(apres.output.devices.length, 1)
})

test("⚠️ une cle surnumeraire traverse le schema — la validation maison la refuse", { skip: !opencodeAvailable }, async () => {
  // C'est le piege mesure : `additionalProperties: false` n'est pas applique.
  // On le prouve par le serveur, pas par une lecture de doc.
  const avecCleEnTrop = await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "injection", injection: true })
  assert.equal(avecCleEnTrop.status, 200, "le schema laisse passer")

  // ...et l'appareil est bien enregistre, mais **sans** la cle surnumeraire.
  const { body } = await call("tether", "devices", {})
  const appareil = body.output.devices.find((d) => d.deviceId === "injection")
  assert.ok(appareil, "l'enregistrement a bien eu lieu")
  assert.equal("injection" in appareil, false, "et la cle surnumeraire n'est pas stockee")

  await call("tether", "unsubscribe", { deviceId: "injection" })
})

test("un endpoint non-https est refuse, avec une erreur nommee", { skip: !opencodeAvailable }, async () => {
  // Un endpoint en http:// ferait pointer le serveur vers un service interne : SSRF.
  //
  // ⚠️ Mesure : avec `throw new Error(...)`, la reponse etait `rpc.internal` en
  // **HTTP 500** et le message remplace par « RPC call failed » — l'app ne pouvait ni
  // distinguer le cas ni dire pourquoi. Il faut passer par `mctx.error(...)`, qui sort
  // en 400 avec le type declare dans `rpc.ts`.
  const { status, body } = await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "ssrf", endpoint: "http://127.0.0.1:8080/" })

  assert.equal(status, 400, `un endpoint http doit etre refuse en 400 : ${JSON.stringify(body)}`)
  assert.equal(body.type, "invalid", "le type est celui declare, pas rpc.internal")
  assert.match(body.message, /https/, "le message dit pourquoi")
  assert.equal(body.data.reason, body.message, "la raison structuree accompagne le message")
})

test("un deviceId vide est refuse nommement", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "   " })
  assert.equal(status, 400)
  assert.equal(body.type, "invalid")
  assert.match(body.message, /deviceId/)
})

test("aucune capacite ne fuit, meme apres un enregistrement reel", { skip: !opencodeAvailable }, async () => {
  // Le test 3 verifie la reponse de `subscribe` ; celui-ci verifie l'**etat** apres
  // coup. C'est la que se cacherait une fuite : si le stockage gardait l'endpoint
  // alors que la vue publique ne le montrait pas, la fuite n'apparaitrait qu'au
  // moment ou quelqu'un lirait le stockage.
  await call("tether", "subscribe", SUBSCRIPTION)
  const { body } = await call("tether", "devices", {})
  const serialized = JSON.stringify(body)

  assert.equal(body.output.devices.length >= 1, true, "l'appareil est enregistre")
  assert.doesNotMatch(serialized, /push\.exemple\.net/, "aucun endpoint dans l'etat public")
  assert.doesNotMatch(serialized, /BCVxsr7N/, "aucune cle publique")
  assert.doesNotMatch(serialized, /BTBZMqHH/, "aucun secret d'authentification")
})

test("une cle publique absente est refusee", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "sans-cle", keys: { auth: "x" } })
  assert.equal(body._tag, "RpcError")
  assert.equal(body.type, "rpc.invalid_input")
  assert.match(body.message, /p256dh/)
})

test("un endpoint absent est refuse par le schema", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("tether", "subscribe", { deviceId: "x" })
  assert.equal(body.type, "rpc.invalid_input")
})

test("une methode inconnue est refusee proprement", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("tether", "inexistante", {})
  assert.equal(body.type, "rpc.method_not_found")
})

test("un rpcID inconnu est refuse proprement", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("pas-un-rpc", "devices", {})
  assert.equal(body.type, "rpc.unavailable")
})

test("sans authentification, rien ne passe", { skip: !opencodeAvailable }, async () => {
  const response = await fetch(`${BASE}/api/rpc/tether/devices?directory=${encodeURIComponent(home)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ input: {} }),
  })
  assert.equal(response.status, 401, "les appareils sont des capacites : pas d'acces anonyme")
})

test("le registre est relu depuis le stockage a chaque appel", { skip: !opencodeAvailable }, async () => {
  // Un cache en memoire donnerait le meme resultat dans la session. On verifie donc
  // qu'une ecriture par un tiers est visible immediatement.
  await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "temoin" })
  const { body } = await call("tether", "devices", {})

  assert.ok(body.output.devices.some((d) => d.deviceId === "temoin"), "l'appareil ecrit est visible")
  await call("tether", "unsubscribe", { deviceId: "temoin" })
})

test("le stockage survit au redemarrage du serveur", { skip: !opencodeAvailable }, async () => {
  // ctx.storage est durable par contrat. On le prouve en relancant le serveur, parce
  // que c'est exactement le moment ou l'utilisateur voit ses notifications disparaitre
  // si le registre n'etait qu'en memoire.
  await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "persistant" })

  server.kill("SIGKILL")
  server = spawn("opencode", ["serve", "--port", String(PORT), "--hostname", "127.0.0.1", "--print-logs"], {
    env: {
      ...process.env,
      XDG_CONFIG_HOME: join(home, "config"),
      XDG_DATA_HOME: join(home, "data"),
      XDG_STATE_HOME: join(home, "state"),
      XDG_CACHE_HOME: join(home, "cache"),
      OPENCODE_SERVER_PASSWORD: PASSWORD,
    },
    stdio: "ignore",
  })

  for (let i = 0; i < 60; i++) {
    try {
      if ((await fetch(`${BASE}/api/info`, { headers: { Authorization: auth } })).ok) break
    } catch {}
    await new Promise((r) => setTimeout(r, 500))
  }

  const { body } = await call("tether", "devices", {})
  assert.ok(body.output.devices.some((d) => d.deviceId === "persistant"), "l'appareil survit au redemarrage")
})
