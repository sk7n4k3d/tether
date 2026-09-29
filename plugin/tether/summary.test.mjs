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
  CacheFragments,
  PLAFOND_MATERIAU,
  TAILLE_FRAGMENT,
  empreinte,
  fragmenter,
  oneLine,
  progressText,
  shortenPath,
  summarize,
  summarizeFragmente,
  summarizeToolInput,
  truncateBytes,
  turnCompletedAt,
  turnMaterial,
  turnStartedAt,
  tronquerParLaFin,
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

// ---------------------------------------------------------------------------
// Les tours longs — decoupe, cache, combinaison
// ---------------------------------------------------------------------------

/**
 * Un `fetch` factice qui consomme une reponse par appel.
 *
 * - une chaine = 200 avec ce contenu ;
 * - un nombre = un code HTTP (donc un echec) ;
 * - une `Error` = une panne reseau.
 *
 * Chaque appel est enregistre dans `capture.appels`, avec le corps **parse** : c'est ce qui
 * permet de verifier le nombre de requetes et ce qui est reellement parti vers le modele.
 */
const fetchSequence = (reponses, capture = { appels: [] }) => {
  return async (_url, options) => {
    capture.appels.push(JSON.parse(options.body))
    const r = reponses.shift()
    if (r === undefined) return reponseJson('{"titre":"t","resume":"r"}')
    if (r instanceof Error) throw r
    if (typeof r === "number") {
      return { ok: false, status: r, json: async () => ({}), body: { cancel: async () => {} } }
    }
    return reponseJson(r)
  }
}

const octets = (texte) => Buffer.byteLength(texte, "utf8")

test("fragmenter decoupe sur des frontieres de ligne, sans rien perdre", () => {
  // ⚠️ La regle du projet : ne jamais couper une commande en deux. Un fragment qui finit sur
  // `npm instal` ferait dire au resume que c'est ce qui a ete lance.
  const lignes = Array.from({ length: 40 }, (_, i) => `- shell : commande numero ${i}`)
  const materiau = lignes.join("\n")
  const fragments = fragmenter(materiau, 100)

  assert.ok(fragments.length > 1, "il faut vraiment decouper")
  for (const fragment of fragments) {
    assert.ok(octets(fragment) <= 100, `fragment trop gros : ${octets(fragment)}`)
  }
  // Chaque fragment est une tranche CONTIGUE des lignes d'origine, et l'ensemble les restitue
  // toutes, dans l'ordre : decouper ne perd rien.
  const aplati = fragments.flatMap((f) => f.split("\n"))
  assert.deepEqual(aplati, lignes)
})

test("fragmenter ne coupe jamais une ligne, meme trop longue", () => {
  // L'unique entorse, et elle doit etre VISIBLE : une base64 de 200 Ko ne part pas telle quelle.
  const geante = "x".repeat(500)
  const fragments = fragmenter(geante, 100)

  assert.equal(fragments.length, 1)
  assert.ok(octets(fragments[0]) <= 100, `fragment trop gros : ${octets(fragments[0])}`)
  assert.ok(fragments[0].includes("(tronque)"), "la coupure est annoncee, pas silencieuse")
})

test("fragmenter absorbe les cas vides", () => {
  assert.deepEqual(fragmenter("", 100), [])
  assert.deepEqual(fragmenter("a", 0), ["a"], "une taille nulle laisse passer tel quel")
  assert.deepEqual(fragmenter("court", 6000), ["court"])
})

test("tronquerParLaFin garde la fin et annonce ce qu'elle a coupe", () => {
  const texte = `${"a".repeat(500)}FINALE`
  const coupe = tronquerParLaFin(texte, 100)

  assert.ok(coupe.includes("FINALE"), "la fin du tour est ce qui parle")
  assert.ok(coupe.includes("du debut du tour omis"), "et le debut coupe est dit, pas efface")
  assert.equal(tronquerParLaFin("court", 100), "court", "un texte qui tient n'est pas touche")
  assert.equal(tronquerParLaFin("court", 0), "court", "un plafond nul desactive la coupe")
})

test("empreinte : stable, et differente des que le contenu change", () => {
  assert.equal(empreinte("abc"), empreinte("abc"))
  assert.notEqual(empreinte("abc"), empreinte("abd"))
  assert.equal(empreinte("abc").length, 40, "sha1 hex : le cache ne doit pas dependre d'un hash maison")
})

test("le cache est borne, et evince le plus ancien", () => {
  // ⚠️ Sans plafond, c'est une fuite : des heures de travail = des milliers de fragments.
  const cache = new CacheFragments(2)
  cache.set("a", "1")
  cache.set("b", "2")
  cache.set("c", "3")

  assert.equal(cache.taille, 2)
  assert.equal(cache.get("a"), undefined, "le plus ancien est sorti")
  assert.equal(cache.get("b"), "2")
  assert.equal(cache.get("c"), "3")

  cache.vider()
  assert.equal(cache.taille, 0)
  assert.throws(() => new CacheFragments(0), "une capacite nulle serait un cache qui ne cache rien")
})

test("un tour court ne paie qu'une requete", async () => {
  // Le cas courant : pas de carte en plus, pas de fragment, pas de these intermediaire.
  const capture = { appels: [] }
  const resume = await summarizeFragmente("un petit tour", RESUMEUR, options(), fetchSequence([], capture))

  assert.equal(capture.appels.length, 1)
  assert.equal(resume.corps, "r")
})

test("un tour long : un resume par fragment, puis la these", async () => {
  const materiau = tourLong()
  const capture = { appels: [] }
  const fetchFn = fetchSequence(["phrase A", "phrase B", "phrase C", '{"titre":"Grand tour","resume":"Trois fragments."}'], capture)

  const resume = await summarizeFragmente(materiau, RESUMEUR, options(100), fetchFn)

  assert.equal(fragmenter(materiau, 100).length, 3, "12 lignes de 20 octets a 100 octets = 3 fragments")
  assert.equal(capture.appels.length, 4, "3 fragments + 1 these")
  // Les 3 premiers appels demandent UNE phrase (max_tokens 120), le dernier la these.
  assert.deepEqual(capture.appels.slice(0, 3).map((a) => a.max_tokens), [120, 120, 120])
  assert.equal(capture.appels[3].max_tokens, 300)
  for (const phrase of ["phrase A", "phrase B", "phrase C"]) {
    assert.ok(capture.appels[3].messages[0].content.includes(phrase), `la these doit porter « ${phrase} »`)
  }
  assert.equal(resume.titre, "Grand tour")
  assert.equal(resume.corps, "Trois fragments.")
})

test("un fragment deja resume ne repasse pas au modele", async () => {
  // C'est le cache qui rend l'operation tenable quand un tour en produit cinquante.
  const materiau = tourLong()
  const cache = new CacheFragments()

  const premier = { appels: [] }
  await summarizeFragmente(materiau, RESUMEUR, { ...options(100), cache }, fetchSequence(["A", "B", "C", '{"titre":"t","resume":"r"}'], premier))
  assert.equal(premier.appels.length, 4)

  const second = { appels: [] }
  await summarizeFragmente(materiau, RESUMEUR, { ...options(100), cache }, fetchSequence([], second))
  assert.equal(second.appels.length, 1, "seule la these repasse")
  assert.equal(cache.taille, 3)
})

test("un fragment en echec ne disparait pas de la these", async () => {
  // ⚠️ Le piege : perdre un fragment silencieusement ferait dire au resume que le tour = la
  // moitie de ce qui existe. Une moitie du travail, c'est un mensonge.
  const materiau = tourLong()
  const capture = { appels: [] }
  const fetchFn = fetchSequence([500, "phrase B", "phrase C", '{"titre":"t","resume":"r"}'], capture)

  const resume = await summarizeFragmente(materiau, RESUMEUR, options(100), fetchFn)

  assert.equal(resume.corps, "r", "le tour est bien resume")
  assert.ok(capture.appels[3].messages[0].content.includes("fragment illisible"), "le fragment mort est signale")
})

test("une these finale en echec rend quand meme les phrases des fragments", async () => {
  // Rendre `null` ferait republier des dizaines de kilo-octets bruts dans une notification.
  const capture = { appels: [] }
  const fetchFn = fetchSequence(["phrase A", "phrase B", "phrase C", new Error("reseau mort")], capture)

  const resume = await summarizeFragmente(tourLong(), RESUMEUR, options(100), fetchFn)

  assert.notEqual(resume, null, "on ne rend jamais rien")
  assert.equal(resume.titre, null)
  for (const phrase of ["phrase A", "phrase B", "phrase C"]) {
    assert.ok(resume.corps.includes(phrase), `« ${phrase} » manque dans ${resume.corps}`)
  }
})

test("le materiau d'un tour long n'est plus coupe a 3 800 octets", () => {
  // ⚠️ La mesure qui a declenche tout : un tour de 173 appels d'outils perdait 45 % de son
  // materiau avant le resumeur. La fragmentation remplace la troncature — donc le materiau
  // monte jusqu'au plafond, et c'est le resume qui se fragmente.
  const gros = [utilisateur(1), assistant(Array.from({ length: 500 }, (_, i) => textePart(`ligne ${i} ${"x".repeat(40)}`)))]
  const materiau = turnMaterial(gros)

  assert.ok(octets(materiau) > 6000, `le materiau devrait depasser 6 000 octets, il en fait ${octets(materiau)}`)
  assert.ok(octets(materiau) <= PLAFOND_MATERIAU + 200, `et rester sous le plafond : ${octets(materiau)}`)
  assert.ok(materiau.includes("ligne 499"), "la derniere ligne est la")
})

test("un materiau enorme garde la FIN, et le dit", () => {
  // Pour « qu'a fait l'agent », la fin porte le resultat — mais le debut coupe doit etre dit.
  const enorme = [utilisateur(1), assistant([textePart("x".repeat(500) + " FINALE")])]
  const materiau = turnMaterial(enorme, 200)

  assert.ok(octets(turnMaterial(enorme)) > 200, "le materiau de depart est bien au-dessus du plafond")
  assert.ok(materiau.includes("FINALE"), "la fin est la")
  assert.ok(materiau.includes("du debut du tour omis"), "et la coupe est annoncee")
  assert.ok(octets(materiau) <= 200 + 60, `le materiau doit rester borne : ${octets(materiau)}`)
})

/**
 * 12 lignes de 19 caracteres, donc 20 octets chacune avec la fin de ligne.
 *
 * A 100 octets par fragment, `fragmenter` en fait **3** (5 + 5 + 2). Le compte est calcule ici a
 * la main : un test qui appelle la fonction qu'il teste ne prouve rien.
 */
function tourLong() {
  return Array.from({ length: 12 }, (_, i) => `- shell : aaaa${String(i).padStart(3, "0")}bbbbbb`).join("\n")
}

function options(tailleFragment = TAILLE_FRAGMENT) {
  return { tailleFragment, cache: new CacheFragments() }
}
