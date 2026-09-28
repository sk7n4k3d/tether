/**
 * L'appairage : un jeton a usage unique, echange contre un abonnement.
 *
 * ## Pourquoi un jeton, alors que l'app a deja un mot de passe
 *
 * Parce que l'**endpoint UnifiedPush est une capacite d'ecriture** : qui le connait
 * peut pousser une notification sur le telephone. Et le mot de passe du serveur n'est pas
 * une preuve de consentement a recevoir des notifications — c'est une preuve qu'on peut
 * piloter opencode.
 *
 * Le jeton rend la ceremonies **volontaire** : il n'apparait que dans un QR affiche
 * dans le TUI, sur la machine ou l'utilisateur a deja entre le mot de passe. Le scanner
 * est un geste deliberé, et le jeton ne sert qu'une fois.
 *
 * ## Sens unique
 *
 * L'app envoie le jeton, le serveur consomme le jeton. Le serveur ne renvoie **jamais**
 * de configuration vers l'app : il ne redescend que ce que l'app a deja fourni — son
 * propre `deviceId`. C'est une regle de conception, pas une precaution (cf. §3 du spec).
 *
 * ## L'ecran de confirmation n'est pas optionnel
 *
 * Home Assistant, `GHSA-2xqv-hwrf-983f` (2026-07-31) : l'app Companion transmettait un
 * scan NFC/QR **sans confirmation humaine**, ce qui permettait l'execution silencieuse
 * d'automatisations par un tiers. Un scan non confirme est une surface d'attaque ; d'ou
 * l'ecran de confirmation obligatoire cote app, teste en J4.
 */

/** Duree de validite d'un jeton. Assez pour scanner, trop court pour qu'il traine. */
export const PAIRING_TTL_MS = 30 * 60 * 1000

/**
 * Taille du jeton : **16 octets**, soit 128 bits.
 *
 * ⚠️ On pourrait mettre 32 octets (256 bits) — c'est le reflexe. Mais le jeton doit
 * tenir dans un **QR de version 3** pour rester sur un seul bloc Reed-Solomon : l'encodeur
 * de `qr.ts` ne gere pas les versions a blocs multiples, et un encodeur a blocs pour un
 * gain d'entropie qui n'a aucun sens ici serait bien plus long a ecrire qu'a verifier.
 *
 * 128 bits, un usage, 30 minutes : lassaut d'un jeton vaut des millions de trillions.
 * Un attaquant devrait deviner en moyenne 2^127 tentatives, contre ~10^10 par seconde
 * sur toute l'histoire de l'univers.
 */
export const TOKEN_BYTES = 16

/** Jeton d'appairage en cours. Volontairement en memoire. */
export interface Pairing {
  token: string
  expiresAt: number
}

/**
 * Genere un jeton : 16 octets aleatoires en base64url. Voir [TOKEN_BYTES] pour
 * pourquoi 128 bits suffisent — et pourquoi c'est aussi une contrainte de taille.
 */
export function newToken(random: () => Uint8Array = defaultRandom): string {
  return Buffer.from(random()).toString("base64url")
}

function defaultRandom(): Uint8Array {
  // `crypto` est un global de Node 19+ ; l'import dynamique evite d'en faire une
  // dependance de module pour un test qui fournit son propre generateur.
  return globalThis.crypto.getRandomValues(new Uint8Array(TOKEN_BYTES))
}

/** Le jeton est-il encore valable ? Une seconde de tolerance, pour l'horloge. */
export function isValid(pairing: Pairing | null, now: number = Date.now()): pairing is Pairing {
  return pairing !== null && pairing.expiresAt > now
}

/** Millisecondes restantes, 0 si expire. */
export function remainingMs(pairing: Pairing | null, now: number = Date.now()): number {
  return isValid(pairing, now) ? pairing.expiresAt - now : 0
}

/**
 * Ce que l'app voit quand elle scanne.
 *
 * ⚠️ Le jeton est encode dans l'URL — c'est le seul moyen de le faire passer par un
 * QR code sans serveur intermediaire. `s` et `t` sont donc en clair dans le lien. C'est
 * acceptable : le lien n'est visible que par la caméra de l'utilisateur, et le jeton
 * expire en 30 minutes et ne sert qu'une fois.
 *
 * Le format est volontairement minimal et **sans version** : un format sans numero de
 * version ne peut pas evoluer sans casser, donc on n'en met pas.
 */
export interface PairingLink {
  server: string
  token: string
}

export const PAIRING_HOST = "pair"
export const PAIRING_SCHEME = "opencode"

export function pairingLink(server: string, token: string): string {
  // Le serveur vient de la configuration de l'app : c'est une URL **encodee**, sinon
  // `://` et `?` casseraient le parsing du deep link cote Android.
  //
  // ⚠️ Les noms de champs sont courts (`s`, `t`) pour une raison **mesuree** : avec
  // `server` et `token`, le lien fait 66 caracteres ; version 3 du QR (55 octets de
  // donnees) oblige a monter en version 5, ou l'encodeur doit gerer les blocs multiples.
  // Sur un lien scanne une fois par session, chaque caractere compte.
  return `${PAIRING_SCHEME}://${PAIRING_HOST}?s=${encodeURIComponent(server)}&t=${encodeURIComponent(token)}`
}

/**
 * L'URL telle que `deepLinkDestination()` la renvoie apres extraction du `data:`.
 *
 * On la reutilise pour le rendu du QR : une seule definition du format, donc le QR et
 * l'analyseur ne peuvent pas diverger.
 */
export function pairingSchemeHost(): string {
  return `${PAIRING_SCHEME}://${PAIRING_HOST}`
}
