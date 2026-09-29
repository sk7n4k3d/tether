/**
 * Le routage des evenements — **pur, aucun opencode**.
 *
 * ## Ce que ce fichier protege
 *
 * Trois decisions, et chacune a un cout quand elle est fausse :
 *
 * 1. **`session.tool.called` seul publie l'avancement.** Prendre tout `session.tool.*` publie
 *    trois fois par outil, et rater `called` prive l'etape de son entree reelle ;
 * 2. **`permission.asked` porte `action`, pas `tool`.** Sans cette lecture, le corps de la
 *    notification d'approbation ne dit plus ce qui est demande ;
 * 3. **la fin de tour est exactement `session.execution.*`** : elargir ferait sonner le
 *    telephone a chaque evenement de la session.
 *
 * Les formes testees ici sont **mesurees** sur un vrai serveur (voir les commentaires de
 * `index.ts`) : `{id, input, executed}` pour `called`, `{id, name}` pour `input.started`,
 * `{id, sessionID, action, resources, save, source}` pour `permission.asked`.
 */

import { test } from "node:test"
import assert from "node:assert/strict"

import { classify, sessionIdOf } from "./classify.ts"

test("seul `session.tool.called` publie l'avancement", () => {
  // ⚠️ Mesure : un outil produit `input.started`, `called`, `progress`, `success`. Publier sur
  // tout ce qui commence par `session.tool.` ferait trois pushes chiffres pour une seule etape.
  assert.deepEqual(classify({ type: "session.tool.called", data: { id: "call_1", input: {} } }), { kind: "progress" })
  assert.equal(classify({ type: "session.tool.input.started", data: { id: "call_1", name: "shell" } }).kind, null)
  assert.equal(classify({ type: "session.tool.success", data: { id: "call_1" } }).kind, null)
  assert.equal(classify({ type: "session.tool.progress", data: { id: "call_1" } }).kind, null)
})

test("l'attention lit l'action, parce que `permission.asked` n'a pas de `tool`", () => {
  // ⚠️ Forme reelle : `{id, sessionID, action, resources, save, source}`. Chercher `name` ou
  // `tool` ne trouverait rien, et le corps resterait « approbation requise » — l'utilisateur ne
  // saurait plus ce qu'il autorise.
  const evenement = {
    type: "permission.asked",
    data: { id: "per_1", sessionID: "ses_1", action: "shell", resources: ["echo x"], save: ["echo *"] },
  }
  assert.deepEqual(classify(evenement), { kind: "attention", tool: "shell" })
})

test("l'attention reste reconnue quand la forme change", () => {
  // Un `tool` ou un `name` explicite doit aussi etre lu : la forme mesuree aujourd'hui n'est pas
  // un contrat fige, et refuser les deux autres ferait perdre le nom sans rien gagner.
  assert.equal(classify({ type: "permission.asked", data: { name: "edit" } }).tool, "edit")
  assert.equal(classify({ type: "permission.asked", data: { tool: "read" } }).tool, "read")
  assert.equal(classify({ type: "permission.asked", data: {} }).tool, undefined)
  assert.equal(classify({ type: "form.created", data: {} }).kind, "attention")
})

test("une fin de tour est exactement `session.execution.*`", () => {
  assert.equal(classify({ type: "session.execution.succeeded" }).kind, "turnEnd")
  assert.equal(classify({ type: "session.execution.failed" }).kind, "turnEnd")
  assert.equal(classify({ type: "session.execution.interrupted" }).kind, "turnEnd")
  assert.equal(classify({ type: "session.execution.started" }).kind, null)
  assert.equal(classify({ type: "session.idle" }).kind, null, "un tour fini n'est pas une fin de tour notifiable")
})

test("tout le reste ne notifie rien", () => {
  for (const type of ["message.updated", "text.delta", "reasoning.delta", "session.tool.input.ended", ""]) {
    assert.equal(classify({ type }).kind, null, `${type} ne doit rien declencher`)
  }
  assert.equal(classify({}).kind, null)
  assert.equal(classify(null).kind, null)
})

test("sessionIdOf lit les trois enveloppes, V2 d'abord", () => {
  assert.equal(sessionIdOf({ data: { sessionID: "ses_v2" } }), "ses_v2")
  assert.equal(sessionIdOf({ sessionID: "ses_v1" }), "ses_v1")
  assert.equal(sessionIdOf({ properties: { sessionID: "ses_legacy" } }), "ses_legacy")
  assert.equal(sessionIdOf({ data: {} }), undefined)
  assert.equal(sessionIdOf(null), undefined)
})
