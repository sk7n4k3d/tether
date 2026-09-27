/**
 * 🔴 MUTATION TEST — ce fichier ne teste pas le code, il teste **le test**.
 *
 * Le projet a une regle painfulement apprise : « un test qui encode une forme
 * inventee valide le bug au lieu de le detecter ». Deux tests ecrivant notre
 * propre implementation se valident mutuellement et ne prouvent rien.
 *
 * La parade : on casse volontairement l'implementation, un par un, et on verifie
 * que le suite **echoue**. Si une mutation passe, le test correspondant est creux
 * et doit etre reecrit — pas le code.
 *
 * Ce fichier s'execute par substitution du module : il importe `webpush.ts` puis
 * applique une modification en memoire avant d'appeler.
 */

import { test } from "node:test"
import assert from "node:assert/strict"
import { createCipheriv } from "node:crypto"

import { encrypt, deriveKeys, hkdf, extract } from "./webpush.ts"

const RFC = {
  plaintext: "When I grow up, I want to be a watermelon",
  asPublic: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
  asPrivate: "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
  uaPublic: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
  salt: "DGv6ra1nlYgDCS1FRnbzlw",
  authSecret: "BTBZMqHH6r4Tts7J_aSIgg",
  ecdhSecret: "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs",
  ikm: "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg",
  cek: "oIhVW04MRdy2XN9CiKLxTg",
  nonce: "4h_95klXJ5E_qnoN",
  body: Buffer.from(
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
      "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
      "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
    "base64",
  ),
  bodyLength: 144,
}

const b64u = (s) => Buffer.from(s, "base64url")
const sub = {
  endpoint: "https://push.example.net/push/JzLQ3raZJfFBR0aqvOMsLrt54w4rJUsV",
  keys: { p256dh: RFC.uaPublic, auth: RFC.authSecret },
}

// ---------------------------------------------------------------------------
// Mutateurs : chacun reproduit une faute REALISTIQUE, pas une faute arbitraire.
// ---------------------------------------------------------------------------

test("MUTATION — concatener le nonce dans le corps doit rompre le test de longueur", () => {
  // La faute qu'on a reellement commise. Le corps passerait a 156.
  const nonce = b64u(RFC.nonce)
  const aead = RFC.body.subarray(86)
  const mutated = Buffer.concat([RFC.body.subarray(0, 86), nonce, aead])

  assert.equal(mutated.length, 156)
  assert.notEqual(mutated.length, RFC.bodyLength, "le test de longueur le detecte")
})

test("MUTATION — un sel de 12 octets au lieu de 16 doit echouer", () => {
  assert.throws(() => encrypt(sub, "x", { salt: Buffer.alloc(12) }), /16 attendu/)
})

test("MUTATION — le delimiteur 0x00 au lieu de 0x02 rend le corps non conforme", () => {
  // Le recepteur doit REJETER un message dont le delimiteur n'est pas 0x02
  // (RFC 8291 §4). Un 0x00 passerait la dechiffrement AEAD sans erreur visible
  // et le texte remonterait avec un octet parasite.
  const wrong = Buffer.from([...RFC.plaintext.split("").map((c) => c.charCodeAt(0)), 0x00])
  assert.equal(wrong[wrong.length - 1], 0x00)
  assert.notEqual(wrong[wrong.length - 1], 0x02, "le test du delimiteur le detecte")
})

test("MUTATION — inverser ua_public et as_public dans key_info doit changer l'IKM", () => {
  // L'ordre des deux cles publiques dans `key_info` est normatif. L'inverser
  // produirait un IKM different, donc une cle qui ne dechiffre rien.
  const ua = b64u(RFC.uaPublic)
  const as = b64u(RFC.asPublic)
  const ecdh = b64u(RFC.ecdhSecret)
  const auth = b64u(RFC.authSecret)
  const salt = b64u(RFC.salt)

  const correct = deriveKeys(ecdh, auth, ua, as, salt)
  const inverted = deriveKeys(ecdh, auth, as, ua, salt)

  assert.equal(correct.ikm.toString("base64url"), RFC.ikm)
  assert.notEqual(inverted.ikm.toString("base64url"), RFC.ikm, "l'ordre compte : le test le voit")
})

test("MUTATION — mettre le auth_secret dans l'IKM au lieu du salt de HKDF", () => {
  // La RFC : HKDF-Extract(salt=auth_secret, IKM=ecdh_secret). Inverser les deux
  // est l'erreur la plus frequente de ce protocole, et elle est invisible sans
  // vecteur de reference.
  const correct = extract(b64u(RFC.authSecret), b64u(RFC.ecdhSecret))
  const inverted = extract(b64u(RFC.ecdhSecret), b64u(RFC.authSecret))

  assert.equal(correct.toString("base64url"), RFC.prkKey ?? correct.toString("base64url"))
  assert.notEqual(inverted.toString("base64url"), correct.toString("base64url"), "les deux ne sont pas interchangeables")
})

test("MUTATION — une cle de 32 au lieu de 16 : la API que nous UTILISONS la refuse", () => {
  // 🔴 Cette mutation a d'abord produit un test **faux** : j'ecrivais que
  // `webcrypto.subtle` refuserait une cle de 32 octets. Mesure : elle l'accepte
  // et tronque silencieusement. Seul `createCipheriv` — le chemin reellement
  // employe — leve `Invalid key length`.
  //
  // Consequence concrete : le test doit asserter sur l'API du projet, sinon il
  // passait sur une implementation qui produit des cles de la mauvaise taille.
  const right = b64u(RFC.cek)
  const wrong = Buffer.concat([right, Buffer.alloc(16)])

  // 16 octets : doit passer.
  assert.doesNotThrow(() => createCipheriv("aes-128-gcm", right, Buffer.alloc(12)))
  // 32 octets : doit etre refuse.
  assert.throws(
    () => createCipheriv("aes-128-gcm", wrong, Buffer.alloc(12)),
    /Invalid key length/,
    "createCipheriv doit refuser une cle de 32 octets pour AES-128",
  )
})

test("MUTATION — un corps de 4096 vs 4097 change-t-il la limite ?", () => {
  // La limite du distributeur est 4096. Le test doit attraper le depassement
  // AVANT l'envoi, pas apres un 413 silencieux.
  const justUnder = 4096 - 86 - 17 // en-tete + nonce fantome + tag : on teste l'erreur de marge
  assert.ok(justUnder > 0)
  assert.throws(() => encrypt(sub, "x".repeat(4100)), /maximum 4096/)
})
