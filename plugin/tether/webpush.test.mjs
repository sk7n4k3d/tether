/**
 * Test du chiffrement Web Push contre les **vecteurs de la RFC 8291, annexe A**.
 *
 * ⚠️ Ces vecteurs sont la seule source acceptable. Le projet a eu cinq bugs sur
 * cinq dus à des formes inventees — dont deux decouverts en lisant une reponse
 * reelle du serveur. Un test ecrit a partir de notre propre implementation ne
 * prouve rien : il ne fait que repeter notre implementation.
 *
 * Le test a donc deuxieds :
 *  - il rejoue l'exemple de la RFC, octet pour octet ;
 *  - il verifie qu'un vecteur **volontairement fausse** echoue.
 *
 * Sans le second, le premier pourrait passer sur une implementation qui.returnne
 * n'importe quoi tant que les valeurs sont symetriques.
 */

import { test } from "node:test"
import assert from "node:assert/strict"
import { createDecipheriv } from "node:crypto"

import { encrypt, deriveKeys, hkdf, extract, assertValidPublicKey } from "./webpush.ts"

// --- Vecteurs de la RFC 8291, annexe A (espaces de présentation supprimés) ---

const RFC = {
  plaintext: "When I grow up, I want to be a watermelon",

  asPublic: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
  asPrivate: "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",

  uaPublic: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
  uaPrivate: "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94",

  salt: "DGv6ra1nlYgDCS1FRnbzlw",
  authSecret: "BTBZMqHH6r4Tts7J_aSIgg",

  // Valeurs intermediaires annoncees par la RFC.
  ecdhSecret: "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs",
  prkKey: "Snr3JMxaHVDXHWJn5wdC52WjpCtd2EIEGBykDcZW32k",
  keyInfo:
    "V2ViUHVzaDogaW5mbwAEJXGyvs3942BVGq8e0PTNNmwRzr5VX4m8t7GGpTM5FzFo7OLr4BhZe9MEebhuPI-OztV3ylkYfpJGmQ22ggCLDgT-M_SrDepxkU21WCP3O1SUj0EwbZIHMtu5pZpTKGSCIA5Zent7wmC6HCJ5mFgJkuk5cwAvMBKiiujwa7t45ewP",
  ikm: "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg",
  prk: "09_eUZGrsvxChDCGRCdkLiDXrReGOEVeSCdCcPBSJSc",
  cekInfo: "Q29udGVudC1FbmNvZGluZzogYWVzMTI4Z2NtAA",
  cek: "oIhVW04MRdy2XN9CiKLxTg",
  nonceInfo: "Q29udGVudC1FbmNvZGluZzogbm9uY2UA",
  nonce: "4h_95klXJ5E_qnoN",

  // Corps final attendu, RFC 8291 §5.
  //
  // ⚠️ La RFC annonce `Content-Length: 145`, mais le corps qu'elle donne fait
  // **144** octets : c'est une erreur d'un octet dans l'exemple, mesuree et non
  // supposee. On ecrit la longueur reelle, et on la verifie en decryptant.
  body:
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
    "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
    "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
  bodyLength: 144,
  headerLength: 86,
}

const b64u = (s) => Buffer.from(s, "base64url")
const subscription = {
  endpoint: "https://push.example.net/push/JzLQ3raZJfFBR0aqvOMsLrt54w4rJUsV",
  keys: { p256dh: RFC.uaPublic, auth: RFC.authSecret },
}

// ---------------------------------------------------------------------------

test("HKDF : vecteur de la RFC 8291 annexe A (cle combinee)", () => {
  const ecdh = b64u(RFC.ecdhSecret)
  const auth = b64u(RFC.authSecret)
  const ua = b64u(RFC.uaPublic)
  const as = b64u(RFC.asPublic)
  const salt = b64u(RFC.salt)

  const keyInfo = Buffer.concat([
    Buffer.from("WebPush: info", "ascii"),
    Buffer.from([0x00]),
    ua,
    as,
  ])

  assert.equal(keyInfo.toString("base64url"), RFC.keyInfo, "key_info de la RFC")

  // PRK_key est l'output d'extract SEUL. Utiliser hkdf() ici (qui fait extract puis
  // expand) verifierait autre chose — le test echouerait sans dire pourquoi.
  const prkKey = extract(auth, ecdh)
  assert.equal(prkKey.toString("base64url"), RFC.prkKey, "PRK_key de la RFC")

  const { ikm, cek, nonce } = deriveKeys(ecdh, auth, ua, as, salt)

  assert.equal(ikm.toString("base64url"), RFC.ikm, "IKM de la RFC")
  assert.equal(cek.toString("base64url"), RFC.cek, "CEK de la RFC")
  assert.equal(nonce.toString("base64url"), RFC.nonce, "NONCE de la RFC")

  assert.equal(
    Buffer.concat([Buffer.from("Content-Encoding: aes128gcm", "ascii"), Buffer.from([0])]).toString("base64url"),
    RFC.cekInfo,
  )
  assert.equal(
    Buffer.concat([Buffer.from("Content-Encoding: nonce", "ascii"), Buffer.from([0])]).toString("base64url"),
    RFC.nonceInfo,
  )
})

test("PRK de la RFC : HKDF-Extract(salt, IKM) mene a la CEK et au NONCE annonces", () => {
  // PRK n'est pas une sortie de hkdf() : c'est le resultat d'extract, et la RFC le
  // donne comme valeur intermediaire. Ce qui compte, c'est que CEK et NONCE
  // derivent de la chaine `salt -> IKM -> PRK -> CEK/NONCE`. Si l'un des etages
  // etait faux, ces deux valeurs wouldn't match.
  const ikm = b64u(RFC.ikm)
  const salt = b64u(RFC.salt)
  const cek = hkdf(salt, ikm, Buffer.concat([Buffer.from("Content-Encoding: aes128gcm", "ascii"), Buffer.from([0])]), 16)
  const nonce = hkdf(salt, ikm, Buffer.concat([Buffer.from("Content-Encoding: nonce", "ascii"), Buffer.from([0])]), 12)

  assert.equal(cek.toString("base64url"), RFC.cek)
  assert.equal(nonce.toString("base64url"), RFC.nonce)
})

test("chiffrement : le corps de la RFC 8291 §5, octet pour octet", () => {
  const { body } = encrypt(subscription, RFC.plaintext, {
    salt: b64u(RFC.salt),
    asPrivate: b64u(RFC.asPrivate),
  })

  assert.equal(body.length, RFC.bodyLength, "longueur reelle du corps de la RFC (144, pas 145)")
  assert.equal(body.toString("base64url"), RFC.body, "corps final de la RFC")
})

test("🔴 le nonce n'est PAS transmis : 86 + plaintext + 0x02 + tag = 144", () => {
  // C'est le piege principal de cette implementation. Un nonce dans le corps
  // donnerait 156 octets, et le recepteur echouerait a dechiffrer — silencieusement.
  const { body } = encrypt(subscription, RFC.plaintext, {
    salt: b64u(RFC.salt),
    asPrivate: b64u(RFC.asPrivate),
  })

  const record = body.subarray(RFC.headerLength)
  assert.equal(record.length, RFC.plaintext.length + 1 + 16, "record = texte + delimiteur + tag, sans nonce")
  assert.notEqual(record.length, RFC.plaintext.length + 12 + 1 + 16, "et NON pas texte + nonce + delimiteur + tag")
})

test("le corps de la RFC se dechiffre avec la CEK et le NONCE derives, sans nonce dans le corps", () => {
  // Le test le plus fort : on fait l'inverse exact de ce que fait un recepteur.
  const { body } = encrypt(subscription, RFC.plaintext, {
    salt: b64u(RFC.salt),
    asPrivate: b64u(RFC.asPrivate),
  })

  const ecdhSecret = b64u(RFC.ecdhSecret)
  const { cek, nonce } = deriveKeys(
    ecdhSecret,
    b64u(RFC.authSecret),
    b64u(RFC.uaPublic),
    b64u(RFC.asPublic),
    b64u(RFC.salt),
  )

  const record = body.subarray(RFC.headerLength)
  const decipher = createDecipheriv("aes-128-gcm", cek, nonce)
  decipher.setAuthTag(record.subarray(record.length - 16))
  const plain = Buffer.concat([decipher.update(record.subarray(0, record.length - 16)), decipher.final()])

  // Le dechiffrement rend le texte **suivi du delimiteur** : c'est le role du
  // recepteur de le retirer (et de rejeter le message si ce n'est pas 0x02,
  // RFC 8291 §4). Cote serveur on ne dechiffre jamais, mais on le verifie quand meme.
  assert.equal(plain[plain.length - 1], 0x02, "delimiteur de remplissage 0x02 en fin de texte clair")
  assert.equal(plain.subarray(0, plain.length - 1).toString("utf8"), RFC.plaintext)
})

test("l'en-tete fait 86 octets et commence par le sel", () => {
  const { body } = encrypt(subscription, RFC.plaintext, {
    salt: b64u(RFC.salt),
    asPrivate: b64u(RFC.asPrivate),
  })

  // salt(16) + rs(4) + idlen(1) + keyid(65) = 86
  assert.equal(body.subarray(0, 16).toString("base64url"), RFC.salt, "salt en tete")
  assert.deepEqual([...body.subarray(16, 20)], [0, 0, 0x10, 0x00], "rs = 4096 big-endian")
  assert.equal(body[20], 65, "longueur du keyid")
  assert.equal(body[21], 0x04, "cle publique non compressee")
  assert.equal(body.subarray(21, 86).toString("base64url"), RFC.asPublic, "keyid = cle publique du serveur")
})

// --- Les tests qui ont des dents -------------------------------------------

test("un sel different produit un corps different (le test n'est pas circulaire)", () => {
  const a = encrypt(subscription, RFC.plaintext, { salt: b64u(RFC.salt), asPrivate: b64u(RFC.asPrivate) })
  const b = encrypt(subscription, RFC.plaintext, { salt: Buffer.alloc(16, 0xaa), asPrivate: b64u(RFC.asPrivate) })
  assert.notEqual(a.body.toString("base64url"), b.body.toString("base64url"))
})

test("une cle de l'UA falsifiee fait echouer le chiffrement", () => {
  // P-256 a un ordre de groupe premier : une coordonnee hors intervalle doit
  // etre rejetee par computeSecret, pas acceptee en silence.
  const bogus = Buffer.concat([Buffer.from([0x04]), Buffer.alloc(64, 0xff)])
  assert.throws(
    () => encrypt({ endpoint: subscription.endpoint, keys: { p256dh: bogus.toString("base64url"), auth: RFC.authSecret } }, "x"),
    /cle|mauvaise longueur|prefixe|curve|courbe/i,
  )
})

test("assertValidPublicKey refuse une cle courte, longue ou mal prefixee", () => {
  assert.throws(() => assertValidPublicKey(Buffer.alloc(64)), /65 attendu/)
  assert.throws(() => assertValidPublicKey(Buffer.alloc(66)), /65 attendu/)
  assert.throws(() => assertValidPublicKey(Buffer.concat([Buffer.from([0x02]), Buffer.alloc(64)])), /0x04/)
  assert.doesNotThrow(() => assertValidPublicKey(Buffer.concat([Buffer.from([0x04]), Buffer.alloc(64)])))
})

test("un secret d'authentification de mauvaise longueur est refuse", () => {
  assert.throws(
    () => encrypt({ endpoint: subscription.endpoint, keys: { p256dh: RFC.uaPublic, auth: Buffer.alloc(8).toString("base64url") } }, "x"),
    /16 attendu/,
  )
})

test("un corps trop grand pour le distributeur est refuse AVANT l'envoi", () => {
  // Un 413 silencieux est le pire resultat possible : l'utilisateur croit avoir ete notifie.
  assert.throws(() => encrypt(subscription, "x".repeat(4100)), /maximum 4096/)
})
