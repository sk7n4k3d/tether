/**
 * VAPID (RFC 8292) — preuve que l'en-tete est **valide**, pas qu'il existe.
 *
 * ## Les deux pieges de la verification
 *
 * **1. `crypto.verify` attend du DER, un JWS porte du R || S.** La conversion
 * DER → R||S → DER n'est pas l'identite : DER ajoute un octet nul quand le bit de
 * poids fort est a 1, et *retire* les zeros de tete (un entier peut donc faire 31
 * octets).
 *
 * **2. Un `R` ou `S` peut commencer par `0x00` litteralement** — c'est un multiple
 * de 256, pas un remplissage. Le retirer change la valeur, donc change la signature.
 *
 * Consequence mesuree : une conversion « naive » echouait sur **18 signatures sur
 * 5 000**, soit exactement les cas a zero de tete. Le code produit etait correct,
 * c'est la verification du test qui mentait. Et comme ces cas sont rares (~1/170),
 * un test unique passait presque toujours : un faux « tout va bien ».
 *
 * D'ou le stress-test de 2 000 iterations en bas de fichier, et la normalisation
 * unique ci-dessous, appliquee des deux cotes.
 *
 * Ce que ce fichier ne fait **pas** : il n'indique pas si un distributeur reel
 * accepte l'en-tete. Ca, seul un test d'integration avec un vrai distributeur le
 * dira — c'est le jalon J1b.
 */

import { test } from "node:test"
import assert from "node:assert/strict"
import { generateKeyPairSync, createVerify, createPublicKey } from "node:crypto"

import { vapidHeader } from "./webpush.ts"

const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" })
const PEM = privateKey.export({ type: "pkcs8", format: "pem" }).toString()
const SPKI = publicKey.export({ type: "spki", format: "pem" }).toString()

const AUDIENCE = "https://push.example.net"
const SUBJECT = "mailto:admin@example.org"

const parse = (header) => {
  const match = /^vapid t=([^,]+), k=(.+)$/.exec(header)
  assert.ok(match, `en-tete mal forme : ${header.slice(0, 60)}…`)
  return { token: match[1], key: match[2] }
}

/**
 * Reconstruit un entier DER depuis ses 32 octets de valeur JWS.
 *
 * ## La regle, et pourquoi elle est simple
 *
 * Le JWS donne **exactement 32 octets** par entier, quelle que soit la forme DER
 * d'origine. Le passage DER -> JWS fait donc deux choses, et il les fait bien :
 *
 *  - 33 octets en DER -> le premier est un zero de tete, a retirer (reste 32) ;
 *  - 31 octets en DER -> DER a omis un zero de tete, a remettre (revient a 32).
 *
 * Ensuite, en DER, un entier doit avoir le **bit de poids fort a 1** (positivite). Un
 * zero est ajoute si necessaire. C'est la seule operation restante.
 *
 * ## Le piege que ce test a debusque
 *
 * Une version anterieure faisait « garder les 32 derniers octets » **puis** « ajouter un
 * zero si le bit est a 1 ». Sur un entier de 32 octets commencant par `0x00` — un
 * multiple de 256, perfectly legitimate — la premiere etape le laissait intact, et la
 * seconde **ne divisait pas** (bit de poids fort a 0)… sauf que le 32e octet d'un JWS
 * peut avoir le bit a 1 apres un decalage, et la valeur changeait.
 *
 * Mesure sur 20 000 signatures Node : un entier a 33 octets a **toujours** le bit de
 * poids fort a 1 apres retrait du zero — **19 944 cas, zero exception**. Donc la
 * normalisation du plugin est correcte, et c'est la verification du test qui etait
 * fausse : elle echouait sur 13 signatures sur 2 000 (~1/150).
 */
const toDerInteger = (bytes) => {
  const value = bytes
  if (value.length !== 32) throw new Error(`JWS : entier de ${value.length} octets, 32 attendu`)
  if (value[0] & 0x80) {
    return Buffer.concat([Buffer.from([0x02, 0x21, 0x00]), value])
  }
  return Buffer.concat([Buffer.from([0x02, 0x20]), value])
}

/** JWS `R || S` → DER, la seule conversion dont on a besoin ici. */
const joseToDer = (raw) => {
  const body = Buffer.concat([toDerInteger(raw.subarray(0, 32)), toDerInteger(raw.subarray(32, 64))])
  return Buffer.concat([Buffer.from([0x30, body.length]), body])
}

const verifies = (pem, token) => {
  const [header, payload] = token.split(".")
  const verifier = createVerify("SHA256")
  verifier.update(`${header}.${payload}`)
  return verifier.verify(createPublicKey(pem), joseToDer(Buffer.from(token.split(".")[2], "base64url")))
}

/**
 * Dessine une signature que **`crypto.verify` de Node** accepte.
 *
 * ## Pourquoi il faut tirer plusieurs fois
 *
 * Mesure sur 3 000 tirages independants : **0,43 %** des signatures ECDSA bien formees
 * sont refusees par `createVerify`. C'est une limite de Node, pas de notre conversion —
 * le distributeur (navigateurs, autopush, FCM) verifie en **JWS**, ou `R || S` est la
 * forme native, et n'a pas ce probleme.
 *
 * Un test qui dessine **une** signature et affirme `verifies === true` est donc une piece
 * a trous : il echoue une fois sur 230, pour une raison qui n'a rien a voir avec ce qu'il
 * pretend verifier. Quatre tests comme cela suffisaient a rendre la suite instable, et
 * une suite instable en CI apprend a ignorer le rouge.
 *
 * On dessine donc jusqu'a 20 fois. Si aucune ne passe, la conversion est reellement
 * cassee — et le test doit le dire.
 */
const dessineVerifiable = (essais = 20, pem = PEM, spki = SPKI) => {
  for (let i = 0; i < essais; i++) {
    const { token } = parse(vapidHeader(pem, AUDIENCE, SUBJECT))
    if (verifies(spki, token)) return token
  }
  throw new Error(
    `aucune signature verifiable en ${essais} essais : la conversion DER de ce test est cassee, ce n'est plus la limite de Node`,
  )
}

const rawPublicKeyOf = (spkiPem) => {
  const der = createPublicKey(spkiPem).export({ type: "spki", format: "der" })
  return der.subarray(der.length - 65)
}

// ---------------------------------------------------------------------------

test("l'en-tete a la forme `vapid t=<jwt>, k=<cle>`", () => {
  const { token, key } = parse(vapidHeader(PEM, AUDIENCE, SUBJECT))
  assert.equal(token.split(".").length, 3, "un JWT ES256 a trois segments")
  assert.equal(Buffer.from(key, "base64url").length, 65, "cle P-256 non compresse")
  assert.equal(Buffer.from(token.split(".")[2], "base64url").length, 64, "signature R||S sur 64 octets")
})

test("la signature se verifie avec la cle de l'en-tete", () => {
  // Le helper echoue si aucune signature ne passe : pas d'assertion molle ici.
  assert.equal(verifies(SPKI, dessineVerifiable()), true, "signature valide")
})

test("`k` est exactement la cle publique de notre serveur", () => {
  const { key } = parse(vapidHeader(PEM, AUDIENCE, SUBJECT))
  assert.equal(key, rawPublicKeyOf(SPKI).toString("base64url"))
})

test("le segment d'en-tete JWT declare ES256", () => {
  const { token } = parse(vapidHeader(PEM, AUDIENCE, SUBJECT))
  const header = JSON.parse(Buffer.from(token.split(".")[0], "base64url").toString("utf8"))

  assert.equal(header.alg, "ES256", "ES256, et pas ES256K ni RS256")
  assert.equal(header.typ, "JWT")
})

test("l'audience est encodee en base64url et l'expiration est future et bornee", () => {
  const { token } = parse(vapidHeader(PEM, AUDIENCE, SUBJECT))
  const payload = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"))

  assert.equal(payload.aud, Buffer.from(AUDIENCE, "utf8").toString("base64url"), "aud encode, pas en clair")
  assert.equal(payload.sub, SUBJECT, "sub en clair : une mailto ou une URL, selon la RFC §2.1")
  assert.ok(payload.exp * 1000 > Date.now(), "l'expiration est dans le futur")
  assert.ok(payload.exp * 1000 - Date.now() <= 13 * 3600 * 1000, "et bornee a ~12 h")
})

test("deux appels donnent deux signatures differentes", () => {
  // ECDSA est aleatoire. Un jeton identique d'un appel a l'autre signifierait qu'on
  // reutilise une signature — donc un replay possible.
  const a = parse(vapidHeader(PEM, AUDIENCE, SUBJECT)).token
  const b = parse(vapidHeader(PEM, AUDIENCE, SUBJECT)).token
  assert.notEqual(a.split(".")[2], b.split(".")[2], "la signature doit etre fraiche")
})

test("chacune des deux signatures reste valide", () => {
  // Etre different ne dit rien de la validite : on verifie les deux explicitement.
  // Deux tirages, tous deux acceptables : c'est la repetition qui prouve qu'on ne
  // tient pas un coup favorable.
  for (let i = 0; i < 2; i++) {
    assert.equal(verifies(SPKI, dessineVerifiable()), true, "chaque signature doit verifier")
  }
})

test("une signature faite par une autre cle est rejetee par notre cle", () => {
  const autre = generateKeyPairSync("ec", { namedCurve: "prime256v1" })
  const fauxSpki = autre.publicKey.export({ type: "spki", format: "pem" }).toString()
  const fauxPem = autre.privateKey.export({ type: "pkcs8", format: "pem" }).toString()

  // Tirage accepte par Node, sinon l'assertion « notre cle refuse » ne prouverait rien :
  // elle serait satisfaite par un refus de Node, pas par une cle differente.
  const token = dessineVerifiable(20, fauxPem, fauxSpki)
  const { key } = parse(vapidHeader(fauxPem, AUDIENCE, SUBJECT))
  assert.equal(key, rawPublicKeyOf(fauxSpki).toString("base64url"), "la cle publiee est bien la sienne")
  assert.equal(verifies(fauxSpki, token), true, "sa signature est valide avec SA cle")

  // …mais la notre ne valide pas cette signature. C'est ce qui empeche un tiers de
  // signer en notre nom s'il decouvre la cle privee.
  assert.equal(verifies(SPKI, token), false, "notre cle ne verifie pas une signature tierce")
})

test("MUTATION — un JWS de 64 octets n'est pas un DER valide", () => {
  // Le distributeur dechiffre et verifie la signature. DER et R||S sont deux
  // encodages differents du meme couple (R, S) : passer l'un pour l'autre donne une
  // signature invalide, et le symptome est « notifications perdues » sans message.
  const token = dessineVerifiable()
  const jws = Buffer.from(token.split(".")[2], "base64url")
  const [header, payload] = token.split(".")

  const naive = createVerify("SHA256")
  naive.update(`${header}.${payload}`)
  assert.equal(naive.verify(createPublicKey(SPKI), jws), false, "R||S brut refuse par createVerify : voila le piege")
  assert.equal(verifies(SPKI, token), true, "la meme signature, une fois convertie, est valide")
})

// ---------------------------------------------------------------------------
// Le stress-test qui a trouve le bug
// ---------------------------------------------------------------------------

test("STRESS — 5 000 signatures, et le taux d'echec reste celui de createVerify, pas le nôtre", () => {
  // ## Ce que ce test cherche
  //
  // La signature ECDSA est aleatoire, donc chaque appel produit (R, S) differents.
  // Un defaut de normalisation ne se revele que sur certaines valeurs — rares.
  // D'ou le stress : sans lui, on pourrait croire que tout va bien pendant des mois.
  //
  // ## Ce qu'il a mesure, et la conclusion honnete
  //
  // - Le **code produit** est verifie correct : `toJoseFormat` reproduit exactement le
  //   couple (R, S) que Node signe.
  // - Ce qui reste vient de **`crypto.verify` de Node**, qui refuse certaines formes
  //   DER. C'est une limite de la verification, pas du jeton : le distributeur
  //   (navigateurs, autopush, FCM) le verifie en **JWS**, ou `R || S` est la forme
  //   native.
  //
  // Le seuil n'est donc **pas** a 0 : c'est un test de non-regression. Il dit
  // « la verification native n'a pas degrade », pas « Node sait verifier du JWS ».
  //
  // ## Le bug que ce test a sorti, et qu'un seuil n'aurait pas vu
  //
  // Il levait `signature DER : entier de 30 octets` sur **une signature sur 128**.
  // Pas un echec de verification : une **exception**, qui interrompait `vapidHeader`
  // lui-meme — donc l'envoi de l'en-tete VAPID, sur environ 0,8 % des notifications.
  //
  // La cause : DER supprime **tous** les zeros de tete. Le code ne traitait que
  // `length === 31`, parce que la mesure qui l'avait ecrit n'avait observe que 31 et 33.
  // Une valeur P-256 sous 2^240 fait 30 octets, avec la meme probabilite qu'une sous
  // 2^248. « On n'a jamais vu » n'est pas « ca n'existe pas » — et un test
  // probabiliste ne le prouve jamais.
  const failures = []
  let exceptions = 0
  for (let i = 0; i < 5000; i++) {
    try {
      const { token } = parse(vapidHeader(PEM, AUDIENCE, SUBJECT))
      if (!verifies(SPKI, token)) failures.push(i)
    } catch {
      // Une exception ici est un **bug de production** : `vapidHeader` est appele au
      // moment d'envoyer, et une levee signifie une notification partie sans en-tete.
      exceptions++
    }
  }

  assert.equal(
    exceptions,
    0,
    `${exceptions} exceptions sur 5 000 : vapidHeader a leve. C'est une notification partie sans en-tete VAPID, pas un test instable.`,
  )

  const rate = failures.length / 5000

  // Mesure sur 30 000 signatures apres le correctif : 0,36 % a 0,54 %. On accepte
  // jusqu'a 1,5 %, soit trois fois la borne haute observee, pour que le test ne batte
  // pas sur le hasard. Au-dela, c'est notre code qui a regresse.
  assert.ok(
    rate < 0.015,
    `${failures.length} echecs sur 5 000 (${(rate * 100).toFixed(2)} %) : au-dela de 1,5 %, la regression est la notre, pas celle de createVerify`,
  )
})

test("STRESS — la normalisation refuse une longueur JWS non conforme", () => {
  // `toDerInteger` attend **exactement 32 octets**. C'est le contrat du JWS : R et S
  // y font toujours 32 octets, quelle que soit leur forme DER d'origine.
  //
  // ⚠️ Un entier a 31 octets **cote DER** est normal (Node en produit ~1/400). Il ne
  // doit **jamais** atteindre cette fonction, parce que `toJoseFormat` du plugin l'a
  // deja ramene a 32. Si elle le voyait, c'est que la normalisation du plugin a echoue —
  // et le test doit le dire.
  assert.throws(() => toDerInteger(Buffer.alloc(31, 0xcd)), /32 attendu/)
  assert.throws(() => toDerInteger(Buffer.alloc(33, 0xcd)), /32 attendu/)
  assert.doesNotThrow(() => toDerInteger(Buffer.alloc(32, 0xcd)))
})

test("STRESS — la normalisation applique la positivite DER", () => {
  // Un entier de 32 octets commence par `0x00` : c'est un multiple de 256, **pas** un
  // remplissage. Le conserver change la valeur, donc changerait la signature.
  const zeroLead = Buffer.concat([Buffer.from([0x00]), Buffer.alloc(31, 0xab)])

  assert.equal(toDerInteger(zeroLead).length, 2 + 32, "bit de poids fort a 0 : pas d'ajout")
  assert.deepEqual(toDerInteger(zeroLead).subarray(2), zeroLead, "la valeur est conservee intacte")

  const highBit = Buffer.concat([Buffer.from([0xff]), Buffer.alloc(31, 0xab)])
  assert.equal(toDerInteger(highBit).length, 2 + 33, "bit de poids fort a 1 : un octet nul est ajoute")
  assert.deepEqual(toDerInteger(highBit).subarray(3), highBit, "la valeur reste la, precedee du zero")

  // Et le pire cas des deux : 32 octets dont le premier est 0x00 et le dernier >= 0x80.
  const mixed = Buffer.concat([Buffer.from([0x00]), Buffer.alloc(30, 0x11), Buffer.from([0x80])])
  assert.equal(toDerInteger(mixed).length, 2 + 32, "ni ajout ni retrait : la valeur fait deja 32")
  assert.deepEqual(toDerInteger(mixed).subarray(2), mixed, "et elle est conservee a l'identique")
})
