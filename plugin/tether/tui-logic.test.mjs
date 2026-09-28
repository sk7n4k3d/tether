import { test } from "node:test"
import assert from "node:assert/strict"

import { rpc, adresseServeur, declarationsCommandes } from "./tui-logic.ts"

/** Un `fetch` factice qui repond ce qu'on lui dit, et note ce qu'il a recu. */
function fauxFetch(reponse) {
  const appels = []
  const f = async (url, options) => {
    appels.push({ url, options })
    return {
      ok: reponse.ok ?? true,
      status: reponse.status ?? 200,
      json: async () => reponse.corps ?? { output: {} },
    }
  }
  return { f, appels }
}

/** Un `fetch` dont `json()` echoue, comme le fait une page d'erreur de proxy. */
const fetchCassee = async () => ({
  ok: false,
  status: 502,
  json: async () => {
    throw new Error("pas du JSON")
  },
})

const ctxDe = (config, directory) => ({
  client: { getConfig: () => config },
  location: directory ? { directory } : undefined,
})

test("rpc construit l'URL avec le repertoire, et sans barre finale en trop", async () => {
  const { f, appels } = fauxFetch({ corps: { output: { ok: true } } })
  await rpc(ctxDe({ baseUrl: "http://127.0.0.1:4096/" }, "/home/user/projet"), "devices", {}, { fetch: f })

  assert.equal(appels[0].url, "http://127.0.0.1:4096/api/rpc/tether/devices?directory=%2Fhome%2Fuser%2Fprojet")
  assert.equal(appels[0].options.method, "POST")
  assert.deepEqual(JSON.parse(appels[0].options.body), { input: {} })
})

test("rpc omet le repertoire quand il n'y en a pas", async () => {
  const { f, appels } = fauxFetch({ corps: { output: {} } })
  await rpc(ctxDe({ baseUrl: "http://127.0.0.1:4096" }), "devices", {}, { fetch: f })
  assert.equal(appels[0].url, "http://127.0.0.1:4096/api/rpc/tether/devices")
})

test("rpc resout l'authentification comme le SDK : identifiants bruts, prefixe en Basic", async () => {
  const { f, appels } = fauxFetch({ corps: { output: {} } })
  const config = {
    baseUrl: "http://x",
    auth: async () => "opencode:motdepasse",
  }
  await rpc(ctxDe(config), "devices", {}, { fetch: f })

  const attendu = `Basic ${btoa("opencode:motdepasse")}`
  assert.equal(appels[0].options.headers.Authorization, attendu)
  assert.equal(appels[0].options.headers["Content-Type"], "application/json")
})

test("rpc n'invente pas d'authentification quand il n'y en a pas", async () => {
  const { f, appels } = fauxFetch({ corps: { output: {} } })
  await rpc(ctxDe({ baseUrl: "http://x" }), "devices", {}, { fetch: f })
  assert.equal("Authorization" in appels[0].options.headers, false)
})

test("rpc accepte un en-tete deja forme, sans le prefixer deux fois", async () => {
  const { f, appels } = fauxFetch({ corps: { output: {} } })
  await rpc(ctxDe({ baseUrl: "http://x", auth: "Basic deja-la" }), "devices", {}, { fetch: f })
  assert.equal(appels[0].options.headers.Authorization, "Basic deja-la")
})

test("rpc remonte le message de l'erreur declaree, pas un code HTTP nu", async () => {
  // C'est tout l'interet des erreurs nommees : l'utilisateur doit pouvoir distinguer
  // « QR expire » de « serveur injoignable ».
  const { f } = fauxFetch({
    ok: false,
    status: 400,
    corps: { type: "unpaired", message: "jeton d'appairage expire ou deja utilise" },
  })
  await assert.rejects(
    () => rpc(ctxDe({ baseUrl: "http://x" }), "subscribe", {}, { fetch: f }),
    /expire ou deja utilise/,
  )
})

test("rpc retombe sur un message lisible quand le corps n'est pas du JSON", async () => {
  await assert.rejects(
    () => rpc(ctxDe({ baseUrl: "http://x" }), "devices", {}, { fetch: fetchCassee }),
    /HTTP 502/,
  )
})

test("adresseServeur : la configuration gagne sur la detection", () => {
  const ctx = ctxDe({ baseUrl: "http://127.0.0.1:4096" })
  assert.equal(adresseServeur(ctx), "http://127.0.0.1:4096")
  // Un telephone ne peut pas joindre 127.0.0.1 : c'est pourquoi la configuration existe.
  assert.equal(adresseServeur(ctx, { serverUrl: "https://opencode.exemple.fr" }), "https://opencode.exemple.fr")
  assert.equal(adresseServeur(ctx, { tether: { serverUrl: "https://nid.exemple.fr/" } }), "https://nid.exemple.fr")
  // Une configuration vide ne doit pas casser la detection.
  assert.equal(adresseServeur(ctx, { serverUrl: "" }), "http://127.0.0.1:4096")
})

test("les deux commandes sont declarees, et visibles la ou il faut", () => {
  const commandes = declarationsCommandes()
  assert.deepEqual(commandes.map((c) => c.id), ["tether.pair", "tether.devices"])
  for (const commande of commandes) {
    assert.ok(commande.title.length > 0, `${commande.id} : un titre`)
    assert.ok(commande.description.length > 0, `${commande.id} : une description`)
    assert.equal(commande.palette, true, `${commande.id} : doit etre dans la palette`)
    assert.equal(commande.group, "Tether", `${commande.id} : groupe coherent`)
    // `run` n'est volontairement pas ici : c'est le .tsx qui le branche, et une
    // declaration sans implementation donnerait au TUI une commande qui ne fait rien.
    assert.equal("run" in commande, false, `${commande.id} : le run est branche dans tui.tsx`)
  }
  assert.deepEqual(commandes[0].slash, { name: "tether" }, "/tether est le point d'entree")
  assert.equal("slash" in commandes[1], false, "la liste d'appareils reste en palette")
})
