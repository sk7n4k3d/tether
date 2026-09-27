/**
 * Tests du registre et du protocole — **pur**, aucun réseau, aucun opencode.
 *
 * Le registre porte la règle métier qui decide de la **survie** d'un appareil :
 * `shouldUnsubscribe`. C'est la fonction la plus dangereuse du plugin, parce
 * qu'une erreur n'est jamais visible — un désabonnement à tort est définitif et
 * silencieux, et l'utilisateur croit simplement que les notifications ont cessé.
 */

import { test } from "node:test"
import assert from "node:assert/strict"

import {
  upsert,
  remove,
  shouldUnsubscribe,
  unsubscribeReason,
  publicView,
  publicList,
  recipientsFor,
  isEmpty,
  ALL_ALERTS,
} from "./registry.ts"
import { encode, decode, attentionTitle, deepLink, PROTOCOL_VERSION } from "./protocol.ts"

const device = (id, overrides = {}) => ({
  deviceId: id,
  endpoint: `https://push.example/${id}`,
  keys: { p256dh: "pub", auth: "authsecret" },
  alerts: { ...ALL_ALERTS },
  registeredAt: 1_000,
  ...overrides,
})

// ---------------------------------------------------------------------------
// Le registre
// ---------------------------------------------------------------------------

test("deux appareils distincts coexistent", () => {
  let registry = upsert([], device("a"))
  registry = upsert(registry, device("b"))

  assert.equal(registry.length, 2, "le modele Misskey : une liste, pas une entree unique")
  assert.deepEqual(registry.map((d) => d.deviceId), ["a", "b"])
  assert.equal(isEmpty(registry), false)
})

test("reinscrire le meme deviceId met a jour au lieu de dupliquer", () => {
  // C'est le cas normal : le distributeur renouvelle son endpoint apres un redemarrage.
  let registry = upsert([], device("a", { endpoint: "https://push.example/v1" }))
  registry = upsert(registry, device("a", { endpoint: "https://push.example/v2" }))

  assert.equal(registry.length, 1, "pas de doublon")
  assert.equal(registry[0].endpoint, "https://push.example/v2", "l'endpoint a bien ete remplace")
})

test("reinscrire conserve l'anciennete d'origine", () => {
  // registeredAt sert a afficher « apppare il y a 3 jours ». Si on le remettait a
  // chaque push, l'ecran d'appairage dirait « a l'instant » en permanence.
  let registry = upsert([], device("a", { registeredAt: 1_000 }))
  registry = upsert(registry, device("a", { endpoint: "https://new", registeredAt: 9_999_999 }))

  assert.equal(registry[0].registeredAt, 1_000, "l'anciennete est celle de la premiere inscription")
})

test("upsert ne mute pas la liste d'origine", () => {
  // Non-regression : un `registry` partage et modifie en place ferait corrire les
  // operations concurrentes. Le snapshot reste intact.
  const original = upsert([], device("a"))
  const before = original.length
  upsert(original, device("b"))

  assert.equal(original.length, before, "la liste d'origine n'a pas bouge")
})

test("remove retire le bon appareil et ignore un id inconnu", () => {
  let registry = upsert(upsert([], device("a")), device("b"))

  assert.deepEqual(remove(registry, "a").map((d) => d.deviceId), ["b"])
  assert.deepEqual(remove(registry, "b").map((d) => d.deviceId), ["a"])
  assert.equal(remove(registry, "zzz").length, 2, "un id inconnu ne doit rien changer")
})

// ---------------------------------------------------------------------------
// La règle de désabonnement — la plus dangereuse du fichier
// ---------------------------------------------------------------------------

test("4xx desabonne", () => {
  for (const status of [400, 401, 402, 403, 404, 405, 410, 413, 422]) {
    assert.equal(shouldUnsubscribe(status), true, `${status} doit desabonner`)
  }
})

test("408 et 429 NE desabonnent PAS", () => {
  // Un 429 dit que le distributeur est deborde, pas que l'abonnement est mort.
  // Se desabonner dessus serait definitif et silencieux : l'utilisateur perdrait
  // ses notifications pour un problemme temporaire, sans aucun message.
  assert.equal(shouldUnsubscribe(408), false, "408 = timeout du distributeur")
  assert.equal(shouldUnsubscribe(429), false, "429 = distributeur sature")
})

test("2xx et 3xx ne desabonnent pas", () => {
  for (const status of [200, 201, 202, 204, 301, 302, 304]) {
    assert.equal(shouldUnsubscribe(status), false, `${status} doit etre considere comme un succes`)
  }
})

test("5xx ne desabonne pas — c'est une panne du distributeur, pas un mort", () => {
  for (const status of [500, 502, 503, 504]) {
    assert.equal(shouldUnsubscribe(status), false, `${status} : garder l'abonnement, on reessaiera`)
  }
})

test("MUTATION — oublier les exceptions 408/429 ferait perdre des appareils", () => {
  const sansExceptions = (status) => status >= 400 && status < 500

  // Ce que donnerait la regle naive, et pourquoi c'est faux.
  assert.equal(sansExceptions(429), true, "la regle naive desabonnerait sur 429")
  assert.equal(shouldUnsubscribe(429), false, "notre regle le preserve")
  assert.notEqual(sansExceptions(429), shouldUnsubscribe(429), "la difference est ce qui nous protege")

  assert.equal(sansExceptions(408), true, "la regle naive desabonnerait sur 408")
  assert.equal(shouldUnsubscribe(408), false, "notre regle le preserve")
})

test("la raison de desabonnement ne contient ni endpoint ni cle", () => {
  // Elle finit dans un log et sur l'ecran d'appairage.
  for (const status of [400, 401, 403, 404, 410, 500]) {
    const reason = unsubscribeReason(status)
    assert.ok(reason.length > 0)
    assert.doesNotMatch(reason, /https?:\/\//, `${status} : pas d'URL dans la raison`)
    assert.doesNotMatch(reason, /p256dh|auth/i, `${status} : pas de cle dans la raison`)
  }
})

// ---------------------------------------------------------------------------
// La fuite de capacité — la vraie raison d'avoir une vue publique
// ---------------------------------------------------------------------------

test("la vue publique ne contient ni endpoint ni cle", () => {
  const view = publicView(device("a", { label: "Pixel", distributor: "org.unifiedpush.ntfy" }))

  assert.equal(view.deviceId, "a")
  assert.equal(view.label, "Pixel")
  assert.equal(view.distributor, "org.unifiedpush.ntfy")
  assert.equal("endpoint" in view, false, "l'endpoint est une capacite d'ecriture")
  assert.equal("keys" in view, false, "les cles sont des secrets")
})

test("MUTATION — la vue publique ne doit jamais laisser fuir une capacite", () => {
  // Un seul oubli, et la liste des appareils Connected devient une liste de cibles.
  const serialized = JSON.stringify(publicList([device("a"), device("b")]))

  assert.doesNotMatch(serialized, /push\.example/, "aucun endpoint")
  assert.doesNotMatch(serialized, /p256dh/, "aucune cle")
  assert.doesNotMatch(serialized, /authsecret/, "aucun secret d'authentification")
})

test("publicView copie les alert, il ne les partage pas", () => {
  const source = device("a")
  const view = publicView(source)

  view.alerts.turnEnd = false
  assert.equal(source.alerts.turnEnd, true, "muter la vue ne doit pas muter le registre")
})

// ---------------------------------------------------------------------------
// Le routage par type d'alerte
// ---------------------------------------------------------------------------

test("chaque type d'alerte va a ses seuls abonnes", () => {
  const registry = [
    device("veut-tout", { alerts: { turnEnd: true, attention: true, progress: true } }),
    device("fin-de-tour-seule", { alerts: { turnEnd: true, attention: false, progress: false } }),
    device("silencieux", { alerts: { turnEnd: false, attention: false, progress: false } }),
  ]

  assert.deepEqual(recipientsFor(registry, "turnEnd").map((d) => d.deviceId), ["veut-tout", "fin-de-tour-seule"])
  assert.deepEqual(recipientsFor(registry, "attention").map((d) => d.deviceId), ["veut-tout"])
  assert.deepEqual(recipientsFor(registry, "progress").map((d) => d.deviceId), ["veut-tout"])
})

test("un appareil qui a tout desactive ne recoit rien", () => {
  const registry = [device("silencieux", { alerts: { turnEnd: false, attention: false, progress: false } })]
  for (const alert of ["turnEnd", "attention", "progress"]) {
    assert.equal(recipientsFor(registry, alert).length, 0, `${alert} : aucun destinataire`)
  }
})

// ---------------------------------------------------------------------------
// Le protocole — contrat de fil avec l'app
// ---------------------------------------------------------------------------

test("aller-retour JSON : texte, session, avancement", () => {
  const body = encode({ text: "Le travail est terminé", sessionID: "ses_abc", progress: true })
  const decoded = decode(body)

  assert.equal(decoded.text, "Le travail est terminé")
  assert.equal(decoded.sessionID, "ses_abc")
  assert.equal(decoded.progress, true)
})

test("la charge utile porte un numero de version", () => {
  // Sans lui, l'app devinerait le format d'un corps inconnu. Avec, elle sait.
  const parsed = JSON.parse(encode({ text: "x" }))
  assert.equal(parsed.v, PROTOCOL_VERSION)
})

test("le JSON resout le probleme que les marqueurs ne pouvaient pas resoudre", () => {
  // Le test qui a fait changer le format. Un texte utilisateur **contenant** le
  // marqueur ne peut plus etre confondu avec un marqueur de transport : il vit
  // dans une chaine JSON, donc il echappe a l'analyse.
  const tricky = "commande copiee :\ntether:progress=1"
  const decoded = decode(encode({ text: tricky, progress: false }))

  assert.equal(decoded.text, tricky, "le texte est rendu intact")
  assert.equal(decoded.progress, false, "et n'a pas ete pris pour un marqueur")
})

test("un texte qui contient du JSON valide reste du texte", () => {
  const body = encode({ text: '{"v":1,"text":"faux","progress":true}' })
  const decoded = decode(body)

  assert.equal(decoded.text, '{"v":1,"text":"faux","progress":true}')
  assert.equal(decoded.progress, false, "le faux progress est du texte, pas un ordre")
})

test("repli v0 : l'app lit encore les marqueurs", () => {
  // Un telephone pas encore mis a jour ne doit pas devenir muet.
  const legacy = "Le travail est terminé\ntether:progress=1\ntether:session=ses_abc"
  const decoded = decode(legacy)

  assert.equal(decoded.text, "Le travail est terminé")
  assert.equal(decoded.progress, true)
  assert.equal(decoded.sessionID, "ses_abc")
})

test("repli v0 : un marqueur de session vide est ignore, pas accepte", () => {
  // `tether:session=` donnerait un deep link `opencode://session/` : un appel qui
  // echoue a coup sur, que l'app ne distingue pas d'une absence d'identifiant.
  const decoded = decode("texte\ntether:session=")
  assert.equal(decoded.sessionID, undefined, "pas de deep link casse")
  assert.equal(decoded.text, "texte")
})

test("un corps illisible renvoie null plutot qu'une notification vide", () => {
  assert.equal(decode(""), null)
  assert.equal(decode("   \n  "), null)
  assert.equal(decode('{"v":1}'), null, "un JSON sans texte n'est pas exploitable")
  assert.equal(decode('{"text":42}'), null, "un texte non-chaine ne l'est pas non plus")
})

test("les accents et les emojis passent dans le JSON", () => {
  const text = "shell : sleep 12 && echo ✓ — autorisation « shell » 🚀"
  assert.equal(decode(encode({ text })).text, text)
})

test("le titre d'attention nomme l'outil quand il est connu", () => {
  assert.equal(attentionTitle("shell"), "Tether — approbation : shell")
  assert.equal(attentionTitle(undefined), "Tether — approbation requise")
})

test("le lien profond encode l'identifiant de session", () => {
  assert.equal(deepLink("ses_abc"), "opencode://session/ses_abc")
})
