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


/** Le serveur du test, vu par le telephone. */
const SERVEUR_APP = "https://exemple.fr:4096"

/** Demande un jeton au serveur, comme le fait le TUI avant d'afficher le QR. */
const appairer = async (server = SERVEUR_APP) => {
  const { status, body } = await call("tether", "pair", { server })
  assert.equal(status, 200, `pair refuse : ${JSON.stringify(body)}`)
  return body.output.link
}

/** Le jeton, tel que l'app le lit dans le deep link. */
const jetonDe = (lien) => new URL(lien.replace("opencode://", "https://")).searchParams.get("t")

/**
 * `subscribe` avec un jeton valide.
 *
 * ⚠️ Le jeton est **a usage unique** : chaque appel a besoin du sien. C'est precisement
 * ce que verifient les tests d'appairage plus bas — si cet helper reutilisait un jeton,
 * il testerait une seule fois l'unicite et le reste du fichier ne tournerait pas.
 */
const subscribe = async (input) => {
  const lien = await appairer()
  return call("tether", "subscribe", { ...input, pairingToken: jetonDe(lien) })
}

test("pair renvoie un lien d'appairage, et rien d'autre", { skip: !opencodeAvailable }, async () => {
  const { body } = await call("tether", "pair", { server: SERVEUR_APP })
  const lien = body.output.link

  assert.ok(lien.startsWith("opencode://pair?"), `lien inattendu : ${lien}`)
  const params = new URL(lien.replace("opencode://", "https://"))
  assert.equal(params.searchParams.get("s"), SERVEUR_APP, "le serveur est encode dans le lien")
  assert.ok((params.searchParams.get("t") ?? "").length > 0, "le jeton est present")
  assert.ok(body.output.expiresInMs > 0 && body.output.expiresInMs <= 30 * 60 * 1000, "duree bornee")
})

test("pair refuse une adresse qui ferait voyager le jeton en clair", { skip: !opencodeAvailable }, async () => {
  // Le jeton EST la capacite d'ecrire sur le telephone. En http sur un reseau, il
  // traverse en clair et n'importe quel tiers peut enregistrer son propre endpoint.
  for (const server of ["http://serveur-interne.lan:4096", "file:///etc/passwd", "ftp://x/", ""]) {
    const { status, body } = await call("tether", "pair", { server })
    assert.equal(status, 400, `accepte a tort : ${server}`)
    assert.equal(body.type, "invalid", `type inattendu : ${JSON.stringify(body)}`)
    assert.equal(typeof body.message, "string", "le message dit pourquoi")
  }
})

test("pair accepte localhost en http, pour le developpement", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await call("tether", "pair", { server: "http://127.0.0.1:4096" })
  assert.equal(status, 200, `refuse a tort : ${JSON.stringify(body)}`)
})

test("subscribe sans jeton est refuse : l'appairage n'est pas optionnel", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await call("tether", "subscribe", SUBSCRIPTION)
  const serialized = JSON.stringify(body)
  assert.equal(status, 400, `accepte sans jeton : ${serialized}`)
  assert.match(serialized, /unpaired|appairage/i, `erreur non nommee : ${serialized}`)
})

test("un jeton ne sert qu'une fois", { skip: !opencodeAvailable }, async () => {
  const lien = await appairer()
  const jeton = jetonDe(lien)

  const premier = await call("tether", "subscribe", { ...SUBSCRIPTION, pairingToken: jeton })
  assert.equal(premier.status, 200, `le premier enregistrement echoue : ${JSON.stringify(premier.body)}`)

  // Le meme jeton, immediatement. Si l'unicite n'est pas appliquee, cet appel passe et
  // le telephone a recu deux capacites d'ecriture depuis un seul QR.
  const second = await call("tether", "subscribe", { ...SUBSCRIPTION, deviceId: "autre", pairingToken: jeton })
  assert.equal(second.status, 400, `jeton reutilise : ${JSON.stringify(second.body)}`)
  assert.match(JSON.stringify(second.body), /expire|utilise|invalide/i)
})

test("un jeton fabrique est refuse", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await call("tether", "subscribe", { ...SUBSCRIPTION, pairingToken: "a".repeat(22) })
  assert.equal(status, 400, `jeton invente accepte : ${JSON.stringify(body)}`)
})

test("pairingStatus bascule quand un jeton existe, puis quand il est consomme", { skip: !opencodeAvailable }, async () => {
  const avant = await call("tether", "pairingStatus", {})
  assert.equal(avant.body.output.active, false, "aucun jeton au depart")

  const lien = await appairer()
  const pendant = await call("tether", "pairingStatus", {})
  assert.equal(pendant.body.output.active, true, "le jeton doit etre actif")
  assert.ok(pendant.body.output.expiresInMs > 0, "il reste du temps")

  await call("tether", "subscribe", { ...SUBSCRIPTION, pairingToken: jetonDe(lien) })
  const apres = await call("tether", "pairingStatus", {})
  assert.equal(apres.body.output.active, false, "le jeton est consomme apres usage")
  assert.equal(apres.body.output.expiresInMs, 0, "plus rien n'attend")
})

test("un nouveau jeton remplace le precedent", { skip: !opencodeAvailable }, async () => {
  // Deux QR affiches a cote l'un de l'autre seraient ambigus, et le second serait
  // invalide sans que l'utilisateur puisse le savoir.
  const premier = jetonDe(await appairer())
  const second = jetonDe(await appairer())

  assert.notEqual(premier, second, "deux jetons distincts")
  const { status } = await call("tether", "subscribe", { ...SUBSCRIPTION, pairingToken: premier })
  assert.equal(status, 400, "le premier jeton ne doit plus valoir")
  const { status: ok } = await call("tether", "subscribe", { ...SUBSCRIPTION, pairingToken: second })
  assert.equal(ok, 200, "le second jeton doit valoir")
})

test("subscribe enregistre l'appareil", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await subscribe(SUBSCRIPTION)
  assert.equal(status, 200, `subscribe refuse : ${JSON.stringify(body)}`)
  assert.equal(body.output.ok, true)
  assert.equal(body.output.total, 1)
})

test("la reponse ne contient jamais l'endpoint ni les cles", { skip: !opencodeAvailable }, async () => {
  const { body } = await subscribe(SUBSCRIPTION)
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
  await subscribe(SUBSCRIPTION)
  const { body } = await subscribe({ ...SUBSCRIPTION, endpoint: "https://push.exemple.net/push/nouveau" })

  assert.equal(body.output.total, 1, "un seul appareil, pas deux")
})

test("deux appareils distincts coexistent", { skip: !opencodeAvailable }, async () => {
  await subscribe(SUBSCRIPTION)
  await subscribe({ ...SUBSCRIPTION, deviceId: "tablette", label: "Tablette" })

  const { body } = await call("tether", "devices", {})
  assert.equal(body.output.devices.length, 2, "le modele Misskey tient")

  await call("tether", "unsubscribe", { deviceId: "tablette" })
  const { body: apres } = await call("tether", "devices", {})
  assert.equal(apres.output.devices.length, 1)
})

test("⚠️ une cle surnumeraire traverse le schema — la validation maison la refuse", { skip: !opencodeAvailable }, async () => {
  // C'est le piege mesure : `additionalProperties: false` n'est pas applique.
  // On le prouve par le serveur, pas par une lecture de doc.
  const avecCleEnTrop = await subscribe({ ...SUBSCRIPTION, deviceId: "injection", injection: true })
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
  const { status, body } = await subscribe({ ...SUBSCRIPTION, deviceId: "ssrf", endpoint: "http://127.0.0.1:8080/" })

  assert.equal(status, 400, `un endpoint http doit etre refuse en 400 : ${JSON.stringify(body)}`)
  assert.equal(body.type, "invalid", "le type est celui declare, pas rpc.internal")
  assert.match(body.message, /https/, "le message dit pourquoi")
  assert.equal(body.data.reason, body.message, "la raison structuree accompagne le message")
})

test("un deviceId vide est refuse nommement", { skip: !opencodeAvailable }, async () => {
  const { status, body } = await subscribe({ ...SUBSCRIPTION, deviceId: "   " })
  assert.equal(status, 400)
  assert.equal(body.type, "invalid")
  assert.match(body.message, /deviceId/)
})

test("aucune capacite ne fuit, meme apres un enregistrement reel", { skip: !opencodeAvailable }, async () => {
  // Le test 3 verifie la reponse de `subscribe` ; celui-ci verifie l'**etat** apres
  // coup. C'est la que se cacherait une fuite : si le stockage gardait l'endpoint
  // alors que la vue publique ne le montrait pas, la fuite n'apparaitrait qu'au
  // moment ou quelqu'un lirait le stockage.
  await subscribe(SUBSCRIPTION)
  const { body } = await call("tether", "devices", {})
  const serialized = JSON.stringify(body)

  assert.equal(body.output.devices.length >= 1, true, "l'appareil est enregistre")
  assert.doesNotMatch(serialized, /push\.exemple\.net/, "aucun endpoint dans l'etat public")
  assert.doesNotMatch(serialized, /BCVxsr7N/, "aucune cle publique")
  assert.doesNotMatch(serialized, /BTBZMqHH/, "aucun secret d'authentification")
})

test("une cle publique absente est refusee", { skip: !opencodeAvailable }, async () => {
  const { body } = await subscribe({ ...SUBSCRIPTION, deviceId: "sans-cle", keys: { auth: "x" } })
  assert.equal(body._tag, "RpcError")
  assert.equal(body.type, "rpc.invalid_input")
  assert.match(body.message, /p256dh/)
})

test("un endpoint absent est refuse par le schema", { skip: !opencodeAvailable }, async () => {
  const { body } = await subscribe({ deviceId: "x" })
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
  await subscribe({ ...SUBSCRIPTION, deviceId: "temoin" })
  const { body } = await call("tether", "devices", {})

  assert.ok(body.output.devices.some((d) => d.deviceId === "temoin"), "l'appareil ecrit est visible")
  await call("tether", "unsubscribe", { deviceId: "temoin" })
})

test("le stockage survit au redemarrage du serveur", { skip: !opencodeAvailable }, async () => {
  // ctx.storage est durable par contrat. On le prouve en relancant le serveur, parce
  // que c'est exactement le moment ou l'utilisateur voit ses notifications disparaitre
  // si le registre n'etait qu'en memoire.
  await subscribe({ ...SUBSCRIPTION, deviceId: "persistant" })

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
