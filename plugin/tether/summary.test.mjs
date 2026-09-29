/**
 * Tests des resumes — **purs, aucun reseau, aucun opencode**.
 *
 * ## Ce qui est verifie ici, et pourquoi
 *
 * Trois proprietes, dans l'ordre d'importance :
 *
 * 1. **ne jamais inventer** : `progressText` dit ce que l'outil a recu, ou se taire ;
 * 2. **le tour, pas la session** : `turnMaterial` ne resume que ce qui suit la derniere
 *    demande, et `turnCompletedAt` distingue deux tours de la meme session ;
 * 3. **une panne du resumeur n'est pas une panne de notification** : `summarize` rend `null`
 *    partout ou elle ne comprend pas, et l'appelant publie le materiau brut.
 *
 * Le `fetch` de `summarize` est injectable, donc on teste l'appel sans endpoint — y compris
 * ses echecs (HTTP 500, prose au lieu de JSON, exception reseau).
 */

import { test } from "node:test"
import assert from "node:assert/strict"

import {
  oneLine,
  progressText,
  shortenPath,
  summarize,
  summarizeToolInput,
  truncateBytes,
  turnCompletedAt,
  turnMaterial,
  turnStartedAt,
} from "./summary.ts"

// ---------------------------------------------------------------------------
// L'entree d'un outil — « ne jamais mentir »
// ---------------------------------------------------------------------------

test("progressText dit l'outil ET ce qu'il a recu", () => {
  assert.equal(progressText("bash", { command: "npm install" }), "bash : npm install")
  assert.equal(progressText("read", { path: "/home/x/projet/src/Main.kt" }), "read : …/src/Main.kt")
})

test("progressText se tait plutot que d'inventer un detail", () => {
  // Aucune cle connue : le nom de l'outil seul. Un libelle vague inventé serait pire.
  assert.equal(progressText("mystere", { inconnu: 42 }), "mystere")
  assert.equal(progressText("mystere", null), "mystere")
  assert.equal(progressText("mystere", "texte"), "mystere")
})

test("progressText ne perd pas l'information quand le nom manque", () => {
  // Cas de course : l'entree est arrivee avant que le nom soit memorise.
  assert.equal(progressText(undefined, { command: "ls -la" }), "ls -la")
  assert.equal(progressText(undefined, {}), "Etape en cours")
})

test("summarizeToolInput parcourt les cles dans l'ordre, description en dernier", () => {
  assert.equal(summarizeToolInput({ description: "lire le fichier", path: "/a/b/c.kt" }), "…/b/c.kt")
  assert.equal(summarizeToolInput({ pattern: "TODO", query: "autre" }), "TODO")
})

test("summarizeToolInput ignore une valeur vide ou d'un autre type", () => {
  assert.equal(summarizeToolInput({ command: "   " }), null)
  assert.equal(summarizeToolInput({ command: 42 }), null)
  assert.equal(summarizeToolInput(null), null)
})

test("shortenPath garde la fin, qui est ce qui distingue", () => {
  assert.equal(shortenPath("/a/b/c/d.kt"), "…/c/d.kt")
  assert.equal(shortenPath("/court/f.kt"), "/court/f.kt")
  assert.equal(shortenPath("f.kt"), "f.kt")
})

test("oneLine ne coupe pas une paire de substitution", () => {
  // Un emoji est deux unites UTF-16. `slice` le couperait et produirait un U+FFFD.
  const texte = "🙂".repeat(10)
  const coupe = oneLine(texte, 5)
  assert.equal(coupe.endsWith("\uFFFD"), false, "aucun caractere de remplacement")
  assert.equal(Array.from(coupe.replace("…", "")).length, 5)
})

test("oneLine aplatit les espaces et coupe avec une ellipse", () => {
  assert.equal(oneLine("a\n\t b   c"), "a b c")
  assert.equal(oneLine("x".repeat(200), 10), "x".repeat(10) + "…")
})

test("truncateBytes borne en octets, pas en caracteres", () => {
  // 12 caracteres, mais 24 octets : une borne a 12 octets doit couper.
  const texte = "é".repeat(12)
  const coupe = truncateBytes(texte, 12)
  assert.ok(Buffer.byteLength(coupe, "utf8") <= 12 + Buffer.byteLength("\n\n… (tronque)"), "borne respectee")
  assert.equal(coupe.endsWith("\uFFFD"), false, "aucun caractere casse")
  assert.ok(coupe.includes("(tronque)"))
})

test("truncateBytes ne touche pas un texte qui tient", () => {
  assert.equal(truncateBytes("court", 100), "court")
  assert.equal(truncateBytes("court", 0), "court", "0 = ne pas tronquer")
})

// ---------------------------------------------------------------------------
// Le tour — pas la session
// ---------------------------------------------------------------------------

/** Un message assistant, avec ses parts. */
const assistant = (parts, temps = { created: 1000, completed: 1000 }) => ({ type: "assistant", content: parts, time: temps })
const utilisateur = (created) => ({ type: "user", text: "demande", time: { created } })
const textePart = (text) => ({ type: "text", text })
const outilPart = (name, input, status = "completed") => ({ type: "tool", name, state: { status, input } })

test("turnMaterial ne prend que le dernier tour", () => {
  const messages = [
    utilisateur(1),
    assistant([textePart("ancien tour")]),
    utilisateur(2000),
    assistant([textePart("tour courant"), outilPart("bash", { command: "npm test" })], { created: 2100, completed: 3000 }),
  ]
  const materiau = turnMaterial(messages)

  assert.ok(materiau.includes("tour courant"), "le texte du tour est la")
  assert.ok(materiau.includes("- bash : npm test"), "l'action du tour est la")
  assert.equal(materiau.includes("ancien tour"), false, "le tour precedent ne remonte pas")
})

test("turnMaterial rend le texte ET les actions, parce qu'un tour agentique n'a presque pas de texte", () => {
  // Mesure sur une session reelle : 2 messages sur 31 portaient du texte. Resumer le seul
  // texte aurait rendu une phrase pour un tour de 23 commandes.
  const messages = [
    utilisateur(1),
    assistant([outilPart("read", { path: "/a/b/c.kt" })]),
    assistant([outilPart("bash", { command: "npm install" })]),
  ]
  const materiau = turnMaterial(messages)

  assert.ok(materiau.includes("- read : …/b/c.kt"))
  assert.ok(materiau.includes("- bash : npm install"))
  assert.equal(materiau.includes("Texte de l'assistant"), false, "pas de section vide")
})

test("turnMaterial est vide quand il n'y a rien a dire", () => {
  assert.equal(turnMaterial([]), "")
  assert.equal(turnMaterial(null), "")
  assert.equal(turnMaterial([utilisateur(1)]), "")
})

test("turnStartedAt et turnCompletedAt encadrent le tour", () => {
  const messages = [
    utilisateur(5000),
    assistant([textePart("x")], { created: 5100, streamed: 6000, completed: 6500 }),
  ]
  assert.equal(turnStartedAt(messages), 5000)
  assert.equal(turnCompletedAt(messages), 6500)
})

test("turnCompletedAt retombe sur `streamed` quand le tour n'est pas termine", () => {
  // ⚠️ Sans ce repli, un tour interrompu rendrait `null` — donc une cle de deduplication
  // instable, et une fin de tour qui repasse.
  const messages = [assistant([textePart("x")], { created: 10, streamed: 20 })]
  assert.equal(turnCompletedAt(messages), 20)
  assert.equal(turnCompletedAt([utilisateur(1)]), null)
})

// ---------------------------------------------------------------------------
// L'appel au resumeur — une panne ne doit pas perdre la notification
// ---------------------------------------------------------------------------

/** Un `fetch` factice qui rend la reponse qu'on lui donne et retient la requete. */
const fetchQui = (reponse, capture = {}) => async (url, options) => {
  capture.url = url
  capture.options = options
  if (reponse instanceof Error) throw reponse
  return reponse
}

const reponseJson = (contenu, ok = true, status = 200) => ({
  ok,
  status,
  json: async () => ({ choices: [{ message: { content: contenu } }] }),
  body: { cancel: async () => {} },
})

const RESUMEUR = { url: "https://resumeur.test/v1/chat/completions", cle: "secret", modele: "petit-modele" }

test("summarize lit le JSON demande et rend titre + corps", async () => {
  const fetchFn = fetchQui(reponseJson('{"titre":"Mise a jour Kotlin","resume":"Trois fichiers modifies."}'))
  const resume = await summarize("materiau", RESUMEUR, fetchFn)

  assert.equal(resume.titre, "Mise a jour Kotlin")
  assert.equal(resume.corps, "Trois fichiers modifies.")
})

test("summarize envoie la cle en en-tete, et le modele quand il est configure", async () => {
  const capture = {}
  await summarize("materiau", RESUMEUR, fetchQui(reponseJson('{"titre":"t","resume":"r"}'), capture))

  assert.equal(capture.options.headers.Authorization, "Bearer secret")
  assert.equal(JSON.parse(capture.options.body).model, "petit-modele")
})

test("summarize omet le modele quand aucun n'est configure", async () => {
  // ⚠️ Aucun nom par defaut : ecrire un modele ici ferait dependre le plugin d'une infra
  // precise, ce que la regle du projet interdit.
  const capture = {}
  await summarize("materiau", { ...RESUMEUR, modele: null }, fetchQui(reponseJson('{"titre":"t","resume":"r"}'), capture))

  assert.equal("model" in JSON.parse(capture.options.body), false)
})

test("summarize tolere une reponse enveloppee dans un bloc de code", async () => {
  const fetchFn = fetchQui(reponseJson('```json\n{"titre":"t","resume":"r"}\n```'))
  const resume = await summarize("materiau", RESUMEUR, fetchFn)
  assert.equal(resume.corps, "r")
})

test("summarize accepte les cles anglaises", async () => {
  const fetchFn = fetchQui(reponseJson('{"title":"t","summary":"r"}'))
  const resume = await summarize("materiau", RESUMEUR, fetchFn)
  assert.equal(resume.titre, "t")
  assert.equal(resume.corps, "r")
})

test("summarize rend null sur une panne, sans jamais lever", async () => {
  assert.equal(await summarize("m", RESUMEUR, fetchQui(reponseJson("", false, 500))), null)
  assert.equal(await summarize("m", RESUMEUR, fetchQui(reponseJson("pas de json du tout"))), null)
  assert.equal(await summarize("m", RESUMEUR, fetchQui(reponseJson('{"titre":"t"}'))), null, "corps vide = pas de resume")
  assert.equal(await summarize("m", RESUMEUR, fetchQui(new Error("reseau mort"))), null)
  assert.equal(await summarize("m", RESUMEUR, fetchQui({ ok: true, json: async () => { throw new Error("illisible") }, body: { cancel: async () => {} } })), null)
})

test("summarize n'appelle pas le reseau quand il n'y a rien a resumer", async () => {
  let appele = false
  const fetchFn = async () => { appele = true; return reponseJson("{}") }
  assert.equal(await summarize("   ", RESUMEUR, fetchFn), null)
  assert.equal(appele, false)
})
