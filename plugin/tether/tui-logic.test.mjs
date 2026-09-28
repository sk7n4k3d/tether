import { test } from "node:test"
import assert from "node:assert/strict"

import { rpc, adresseServeur, declarationsCommandes } from "./tui-logic.ts"

/**
 * Un `client` factice qui expose `rpc(definition)`, comme l'`OpenCodeClient` du TUI.
 *
 * ⚠️ C'est la forme **reelle**, pas un `fetch` : la version precedente testait un `fetch`
 * factice, donc validait le bug. Le client porte l'authentification, et c'est ce passage
 * qu'on verifie ici.
 */
function fauxClient(resultat = {}) {
  const appels = []
  const rpc = (definition) =>
    Object.fromEntries(
      Object.keys(definition.methods).map((name) => [
        name,
        async (input, options) => {
          appels.push({ id: definition.id, method: name, input, options })
          if (resultat.erreur) throw resultat.erreur
          return resultat.corps ?? {}
        },
      ]),
    )
  return { client: { rpc }, appels }
}

const ctxDe = (client, directory) => ({ client, location: directory ? { directory } : undefined })

test("rpc passe par le client, avec l'identifiant du RPC et le repertoire", async () => {
  const { client, appels } = fauxClient({ corps: { ok: true } })
  const sortie = await rpc(ctxDe(client, "/home/user/projet"), "devices", {})

  assert.deepEqual(sortie, { ok: true })
  assert.equal(appels.length, 1)
  assert.equal(appels[0].id, "tether")
  assert.equal(appels[0].method, "devices")
  assert.deepEqual(appels[0].input, {})
  assert.deepEqual(appels[0].options, { location: { directory: "/home/user/projet" } })
})

test("rpc omet le repertoire quand il n'y en a pas", async () => {
  const { client, appels } = fauxClient()
  await rpc(ctxDe(client), "devices", {})
  assert.equal(appels[0].options, undefined)
})

test("rpc remonte l'erreur declaree, telle que le client la leve", async () => {
  // C'est tout l'interet des erreurs nommees : l'utilisateur doit pouvoir distinguer
  // « QR expire » de « serveur injoignable ». `makeRpc` leve `{ type, message }`, pas un
  // `Error` — et `tui.tsx` affiche `message`.
  const { client } = fauxClient({ erreur: { type: "unpaired", message: "jeton d'appairage expire ou deja utilise" } })
  // ⚠️ `makeRpc` leve un objet `{ type, message }`, pas un `Error` : un `assert.rejects`
  // avec une RegExp testerait `String(objet)` = `[object Object]`. On lit `message`.
  await assert.rejects(
    () => rpc(ctxDe(client), "subscribe", {}),
    (cause) => {
      assert.equal(cause.type, "unpaired")
      assert.match(cause.message, /expire ou deja utilise/)
      return true
    },
  )
})

test("rpc refuse un client sans rpc, au lieu de partir sans authentification", async () => {
  // ⚠️ C'est le bug d'origine : sans `rpc`, un `fetch` sans en-tete `Authorization`
  // partait, et le serveur repondait 401. On echoue ici, avec une raison lisible.
  await assert.rejects(() => rpc(ctxDe({}), "pair", {}), /ne sait pas appeler/)
})

test("rpc refuse une methode que la definition ne declare pas", async () => {
  const { client } = fauxClient()
  await assert.rejects(() => rpc(ctxDe(client), "inconnu", {}), /inconnu/)
})

test("adresseServeur : la configuration gagne sur le defaut", () => {
  assert.equal(adresseServeur(undefined, {}), "http://127.0.0.1:4096")
  // Un telephone ne peut pas joindre 127.0.0.1 : c'est pourquoi la configuration existe.
  assert.equal(adresseServeur({ serverUrl: "https://opencode.exemple.fr" }), "https://opencode.exemple.fr")
  assert.equal(adresseServeur({ tether: { serverUrl: "https://nid.exemple.fr/" } }), "https://nid.exemple.fr")
  // Une configuration vide ne doit pas casser le defaut.
  assert.equal(adresseServeur({ serverUrl: "" }, {}), "http://127.0.0.1:4096")
})

test("adresseServeur : l'environnement complete, quand le plugin est depose tel quel", () => {
  // ⚠️ Un plugin depose dans `~/.config/opencode/plugins/` recoit `options = {}` — mesure
  // le 2026-09-28, pas suppose. Sans l'environnement, cette adresse n'aurait aucun moyen
  // d'etre reglee, et le QR porterait un `127.0.0.1` que le telephone ne peut pas joindre.
  assert.equal(adresseServeur(undefined, {}), "http://127.0.0.1:4096")
  assert.equal(
    adresseServeur(undefined, { TETHER_SERVER_URL: "https://opencode.exemple.fr/" }),
    "https://opencode.exemple.fr",
  )
  // La configuration explicite passe avant l'environnement : c'est elle qui est
  // intentionnelle.
  assert.equal(
    adresseServeur({ serverUrl: "https://choisi.fr" }, { TETHER_SERVER_URL: "https://env.fr" }),
    "https://choisi.fr",
  )
})

test("adresseServeur : le reglage du TUI gagne sur la config et l'environnement", () => {
  // Le geste recent, explicite, doit gagner.
  assert.equal(
    adresseServeur({ serverUrl: "https://config.fr" }, { TETHER_SERVER_URL: "https://env.fr" }, { serverUrl: "https://tui.fr/" }),
    "https://tui.fr",
  )
})

test("adresseServeur : une valeur vide dans le magasin est une negation, pas une valeur", () => {
  // Le TUI retire une option quand on la remet a son defaut ; une chaine vide doit
  // laisser la config parler, pas ecraser avec une adresse inexistante.
  assert.equal(adresseServeur({ serverUrl: "https://config.fr" }, {}, { serverUrl: "" }), "https://config.fr")
  assert.equal(adresseServeur({}, {}, { serverUrl: "   " }), "http://127.0.0.1:4096")
})

test("les trois commandes sont declarees, et visibles la ou il faut", () => {
  const commandes = declarationsCommandes()
  assert.deepEqual(commandes.map((c) => c.id), ["tether.pair", "tether.config", "tether.devices"])
  for (const commande of commandes) {
    assert.ok(commande.title.length > 0, `${commande.id} : un titre`)
    assert.ok(commande.description.length > 0, `${commande.id} : une description`)
    assert.equal(commande.palette, true, `${commande.id} : doit etre dans la palette`)
    assert.equal(commande.group, "Tether", `${commande.id} : groupe coherent`)
    // `run` n'est volontairement pas ici : c'est le .tsx qui le branche, et une
    // declaration sans implementation donnerait au TUI une commande qui ne fait rien.
    assert.equal("run" in commande, false, `${commande.id} : le run est branche dans tui.tsx`)
  }
  // Les index sont fragiles : ajouter une commande les decale tous. On cherche par id.
  const par = (id) => commandes.find((c) => c.id === id)
  assert.deepEqual(par("tether.pair").slash, { name: "tether" }, "/tether est le point d'entree")
  assert.deepEqual(par("tether.config").slash, { name: "tether-config" }, "la config a son propre slash")
  // La liste d'appareils reste en palette seulement : c'est une action de nettoyage, pas
  // une action qu'on lance par megarde en tapant /tether-appareils.
  assert.equal("slash" in par("tether.devices"), false, "la liste d'appareils reste en palette")
})
