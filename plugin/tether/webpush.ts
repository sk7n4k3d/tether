/**
 * Web Push — chiffrement du corps, **RFC 8291** (`aes128gcm`) et
 * authentification d'émetteur, **RFC 8292** (VAPID).
 *
 * ## Pourquoi ces deux fichiers
 *
 * UnifiedPush fournit un endpoint et un `PublicKeySet`. Le push standard, c'est un
 * corps **chiffré** que le distributeur sait déchiffrer. Parler le dialecte HTTP
 * d'un distributeur donné (ce que faisait la version precedente) ne fonctionne
 * qu'avec ce distributeur-la : les autres le dechiffrent, echouent, et n'emettent
 * aucune erreur.
 *
 * ## Zero dependance — c'est un choix, pas une chance
 *
 * Tout ce dont on a besoin est dans le runtime : `createECDH` (P-256),
 * `createHmac` (HKDF-SHA256), `createCipheriv` (AES-128-GCM), `createSign` (ES256
 * pour VAPID). Ajouter une bibliotheque de crypto serait le premier gain de
 * dependance du projet, donc il n'y en a pas.
 *
 * ## La forme choisie : en-tete INLINE, un seul record, nonce NON transmis
 *
 * La RFC 8291 §4 **impose** un seul record. L'annexe A montre la forme canonique :
 *
 *   salt(16) | rs(4) | idlen(1) | as_public(65) | AEAD(plaintext || 0x02)
 *            \\___________ en-tete aes128gcm, 86 octets ___________/
 *
 * On pourrait passer par les en-tetes `Encryption:` / `Crypto-Key:` de la RFC 8188
 * multi-records ; c'est equivalent et plus moderne, mais l'exemple de la RFC — donc
 * **les vecteurs de test** — porte sur la forme inline. On code sur la forme qu'on
 * sait prouver.
 *
 * 🔴 **LE NONCE N'EST PAS DANS LE CORPS.** C'est le point le pluspiegeux de l'implementation,
 * et l'exemple de la RFC ne l'explique nulle part. Mesure faite sur le vecteur de l'annexe A
 * (voir `webpush.test.mjs`) :
 *
 *   - le corps complet fait **144** octets, alors que la RFC annonce `Content-Length: 145`
 *     — **l'exemple de la RFC est faux de un octet** ;
 *   - les 58 octets suivant l'en-tete de 86 octets valent exactement
 *     `41 (plaintext) + 1 (delimiteur 0x02) + 16 (tag AEAD)` ;
 *   - aucun nonce n'y figure, et le dechiffrement avec le seul NONCE derive (sans le lire
 *     dans le corps) redonne bien le texte, delimiteur compris.
 *
 * C'est coherent : le recepteur connait tout ce qu'il faut (`salt` dans l'en-tete,
 * `keyid` = notre cle publique, et les deux secrets), donc il **rederive** le nonce. Le
 * transmettre serait de la redondance — et aurait produit un corps de 156 octets,
 * c'est-a-dire un message que le recepteur dechiffrerait en echouant.
 *
 * ⚠️ **Les en-tetes HTTP ne sont pas proteges** par ce chiffrement (RFC 8291 §7).
 * Un distributeur voit tout ce qui n'est pas dans le corps. Aucun secret ne doit
 * transiter dans un en-tete.
 */

import { createCipheriv, createECDH, createHmac, createSign, createPrivateKey, randomBytes } from "node:crypto"

/** Abonnement push, tel que l'app l'enregistre via `subscribe`. */
export interface PushSubscription {
  /** URL du distributeur. Une **capacite d'ecriture** : ne jamais journaliser. */
  endpoint: string
  keys: { p256dh: string; auth: string }
}

const enc = new TextEncoder()

/** Base64url sans remplissage. `Buffer` gere les deux sens. */
const b64u = (bytes: Uint8Array): string => Buffer.from(bytes).toString("base64url")
const unb64u = (text: string): Buffer => Buffer.from(text, "base64url")

/** Taille maximale de corps acceptee par un distributeur (RFC 8030 §7.2). */
export const MAX_BODY = 4096

/** Header de l'exemple de la RFC 8291 §5 : 16 + 4 + 1 + 65. */
const HEADER_BYTES = 86

/** Delimiteur de remplissage. Toute autre valeur doit faire rejeter le message. */
const PADDING_DELIMITER = 0x02

/**
 * HKDF (RFC 5869) avec SHA-256, en une seule fonction.
 *
 * `extract(salt, ikm)` puis `expand(prk, info, L)`. Les trois derivations de la
 * RFC 8291 §3.4 sont exactement cette fonction avec des entrees differentes :
 * la cle de contenu, l'IV, et le IKM combine.
 */
/**
 * HKDF-Extract (RFC 5869 §2.2) : `PRK = HMAC-SHA-256(salt, IKM)`.
 *
 * Expose separement de [hkdf] parce que la RFC 8291 nomme `PRK_key` comme valeur
 * intermediaire, et que le confondre avec la sortie de `hkdf()` (qui fait
 * extract **puis** expand) produit un test qui echoue pour une bonne raison.
 */
export function extract(salt: Uint8Array, ikm: Uint8Array): Buffer {
  return createHmac("sha256", salt).update(ikm).digest()
}

export function hkdf(salt: Uint8Array, ikm: Uint8Array, info: Uint8Array, length: number): Buffer {
  const prk = extract(salt, ikm)

  const blocks: Buffer[] = []
  let previous = Buffer.alloc(0)
  let counter = 1
  let total = 0

  while (total < length) {
    previous = createHmac("sha256", prk)
      .update(Buffer.concat([previous, Buffer.from(info), Buffer.from([counter])]))
      .digest()
    blocks.push(previous)
    total += previous.length
    counter += 1
  }

  return Buffer.concat(blocks).subarray(0, length)
}

/**
 * Cle de contenu + IV derives de l'abonnement et du sel.
 *
 * La cle publique de l'UA entre dans `key_info` : c'est ce qui lie le message a
 * cet abonnement-la. Sans elle, deux appareils distincts partageant `auth_secret`
 * deriveraient la meme cle.
 */
export function deriveKeys(
  ecdhSecret: Uint8Array,
  authSecret: Uint8Array,
  uaPublic: Uint8Array,
  asPublic: Uint8Array,
  salt: Uint8Array,
): { ikm: Buffer; cek: Buffer; nonce: Buffer } {
  const keyInfo = Buffer.concat([
    enc.encode("WebPush: info"),
    Buffer.from([0x00]),
    Buffer.from(uaPublic),
    Buffer.from(asPublic),
  ])

  // RFC 8291 §3.3 : le secret d'authentification est le SALT de l'extraction.
  const ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32)

  // RFC 8188, extrait dans la RFC 8291 §3.4.
  const cek = hkdf(salt, ikm, Buffer.concat([enc.encode("Content-Encoding: aes128gcm"), Buffer.from([0x00])]), 16)
  const nonce = hkdf(salt, ikm, Buffer.concat([enc.encode("Content-Encoding: nonce"), Buffer.from([0x00])]), 12)

  return { ikm, cek, nonce }
}

/**
 * La cle publique de l'UA doit etre reellement sur P-256 (RFC 8291 §7).
 *
 * Sans cette validation, un attaquant peut envoyer une cle de courbe低级 et extraire
 * notre cle privee. On verifie la forme ET on laisse `computeSecret` rejeter ce qui
 * n'est pas sur la courbe — c'est lui qui fait le travail lourd, on evite de le
 * reimplementer (et de le rater).
 */
export function assertValidPublicKey(uaPublic: Buffer): void {
  if (uaPublic.length !== 65) {
    throw new Error(`cle publique de mauvaise longueur : ${uaPublic.length} octets, 65 attendu`)
  }
  if (uaPublic[0] !== 0x04) {
    throw new Error("cle publique non compressee attendue (prefixe 0x04)")
  }
}

/** Resultat d'un chiffrement, pret a etre poste. */
export interface EncryptedPush {
  body: Buffer
  /** 65 octets, forme non compressee — c'est le `keyid` de l'en-tete. */
  asPublic: Buffer
}

/**
 * Chiffre un message pour un abonnement.
 *
 * `salt` et `asPrivate` sont injectes pour que le test puisse reproduire
 * l'exemple de la RFC §5 octet pour octet. En usage normal, on les laisse a
 * `undefined` : le sel est tire au hasard et une paire P-256 est generee.
 */
export function encrypt(
  subscription: PushSubscription,
  plaintext: string,
  options: { salt?: Buffer; asPrivate?: Buffer; asPublic?: Buffer } = {},
): EncryptedPush {
  const uaPublic = unb64u(subscription.keys.p256dh)
  assertValidPublicKey(uaPublic)

  const authSecret = unb64u(subscription.keys.auth)
  if (authSecret.length !== 16) {
    // 16 octets, RFC 8291 §3.2. Un secret plus court weaken la construction.
    throw new Error(`secret d'authentification : ${authSecret.length} octets, 16 attendu`)
  }

  const salt = options.salt ?? randomBytes(16)
  if (salt.length !== 16) throw new Error(`sel : ${salt.length} octets, 16 attendu`)

  const ecdh = createECDH("prime256v1")
  const asPublic = options.asPublic ?? (options.asPrivate ? derivePublic(options.asPrivate) : ecdh.generateKeys())
  if (options.asPrivate) ecdh.setPrivateKey(options.asPrivate)

  // Levement si la cle de l'UA n'est pas sur la courbe.
  const ecdhSecret = ecdh.computeSecret(uaPublic)

  const { cek, nonce } = deriveKeys(ecdhSecret, authSecret, uaPublic, asPublic, salt)

  // Delimiteur de remplissage, puis AEAD. Le corps de la RFC fait 86 + 12 + AEAD.
  const payload = Buffer.concat([Buffer.from(plaintext, "utf8"), Buffer.from([PADDING_DELIMITER])])
  const cipher = createCipheriv("aes-128-gcm", cek, nonce)
  const aead = Buffer.concat([cipher.update(payload), cipher.final(), cipher.getAuthTag()])

  // ⚠️ `nonce` n'est PAS concatene. Voir l'en-tete du fichier : le vecteur de la RFC
  // fait 144 octets, pas 156, et le recepteur redderive le nonce.
  const body = Buffer.concat([
    salt,
    Buffer.from([0, 0, 0x10, 0x00]), // rs = 4096, big-endian sur 4 octets
    Buffer.from([asPublic.length]),
    asPublic,
    aead,
  ])

  if (body.length > MAX_BODY) {
    // 4096 est la limite du distributeur ; depasser donne un 413 silencieux.
    throw new Error(`corps de ${body.length} octets, maximum ${MAX_BODY}`)
  }

  return { body, asPublic }
}

/** Reconstitue la cle publique depuis la cle privee — pour le vecteur de la RFC. */
function derivePublic(asPrivate: Buffer): Buffer {
  const ecdh = createECDH("prime256v1")
  ecdh.setPrivateKey(asPrivate)
  return ecdh.getPublicKey()
}

/** Valeurs attendues dans les en-tetes, pour que l'appelant n'ait rien a savoir. */
export function pushHeaders(): Record<string, string> {
  return {
    "Content-Encoding": "aes128gcm",
    "Content-Type": "application/octet-stream",
  }
}

// ---------------------------------------------------------------------------
// VAPID — RFC 8292
// ---------------------------------------------------------------------------

/**
 * Le distributor n'exige VAPID que s'il relaye par FCM. Un topic ntfy ou
 * autopush l'ignore. On l'active donc **seulement si une cle est fournie**, sinon
 * on n'envoie pas d'en-tete `Authorization` plutot qu'un en-tete invalide.
 */
export function vapidHeader(publicKeyPem: string, privateKeyPem: string, audience: string, subject: string): string {
  const audienceB64 = b64u(enc.encode(audience))
  const header = { typ: "JWT", alg: "ES256" }
  const payload = { aud: audienceB64, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: subject }

  const signingInput = `${b64u(enc.encode(JSON.stringify(header)))}.${b64u(enc.encode(JSON.stringify(payload)))}`

  const key = createPrivateKey(privateKeyPem)
  const signature = createSign("SHA256").update(signingInput).end().sign(key)
  // ES256 = R || S, chacune sur 32 octets. Node peut renvoyer DER : on retire
  // l'enveloppe ASN.1 avant de mettre le JWT au format attendu par les distillateurs.
  const raw = toJoseFormat(signature)

  return `vapid t=${signingInput}.${b64u(raw)}, k=${b64u(spkiToRaw(publicKeyPem))}`
}

/** Retire l'enveloppe DER et renvoie R || S sur 64 octets (format JWS "ES256"). */
function toJoseFormat(der: Buffer): Buffer {
  if (der.length === 64) return der // deja au format brut
  // Structure DER : SEQUENCE { INTEGER r, INTEGER s }. On extrait les deux entiers.
  let offset = der[1] & 0x80 ? 2 + (der[1] & 0x7f) : 2
  const readInt = (): Buffer => {
    if (der[offset] !== 0x02) throw new Error("DER inattendu : INTEGER manquant")
    const length = der[offset + 1]
    let value = der.subarray(offset + 2, offset + 2 + length)
    // Un INTEGER DER peut avoir un octet nul de tete pour rester positif.
    if (value.length > 32 && value[0] === 0x00) value = value.subarray(1)
    if (value.length < 32) value = Buffer.concat([Buffer.alloc(32 - value.length), value])
    offset += 2 + length
    return value
  }
  offset += 1 // 0x30, la longueur
  return Buffer.concat([readInt(), readInt()])
}

/** Point de curve non compresse (65 octets) depuis un SPKI PEM. */
function spkiToRaw(pem: string): Buffer {
  const der = Buffer.from(pem.replace(/-----(BEGIN|END) PUBLIC KEY-----|\s/g, ""), "base64")
  // SEQUENCE { SEQUENCE { OID, OID }, BIT STRING { 0x00, 0x04 || X || Y } }
  const bitString = der.subarray(der.length - 65)
  if (bitString[0] !== 0x04) throw new Error("cle publique P-256 non compressee attendue")
  return bitString
}
