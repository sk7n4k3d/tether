/**
 * La logique **pure** de l'ecran d'appairage — ce que le TUI affiche, sans le TUI.
 *
 * ## Pourquoi ce module existe separe
 *
 * `tui.tsx` contient du JSX : on ne peut pas l'importer dans un test `node:test`, ni
 * le charger hors d'un TUI. Toute la logique qui **decide** de quelque chose — le compte
 * a rebours, le libelle d'un appareil, ce qu'on dit quand il n'y a aucun appareil —
 * vit donc ici, et `tui.tsx` ne fait plus que la mettre en page.
 *
 * C'est aussi la seule facon de tester le compte a rebours sans attendre 30 minutes.
 */

/** Ce que l'appairage affiche, une fois le jeton emis. */
export interface Appairage {
  /** Le lien complet, tel qu'il sera encode dans le QR. */
  readonly link: string
  /** Le QR, en texte, pret a afficher. */
  readonly qr: string
  /** A quel instant le jeton cesse d'etre valable. */
  readonly expiresAt: number
  /** La duree de vie totale, en millisecondes. */
  readonly ttlMs: number
}

/** Un appareil, vu par l'utilisateur. Exactement ce que le RPC `devices` renvoie. */
export interface Appareil {
  readonly deviceId: string
  readonly label?: string
  readonly distributor?: string
  readonly registeredAt: number
}

/**
 * Le nom affichable d'un appareil.
 *
 * Le `label` est ce que l'app a declare — souvent le modele du telephone. A defaut on
 * montre le `deviceId`, qui lui est toujours present : un appareil sans nom affiche
 * `inconnu` serait pire qu'un identifiant, parce qu'on ne pourrait plus le retrouver.
 */
export function nomAppareil(appareil: Appareil): string {
  const label = appareil.label?.trim()
  if (label) return label
  const id = appareil.deviceId.trim()
  return id.length > 0 ? id : "(sans identifiant)"
}

/** Le distributeur, en nom lisible plutot qu'en paquet Android. */
export function distributeurLisible(appareil: Appareil): string {
  const brut = appareil.distributor?.trim()
  if (!brut) return "distributeur inconnu"
  // `org.unifiedpush.distributor.ntfy` -> `ntfy`
  const point = brut.lastIndexOf(".")
  return point >= 0 ? brut.slice(point + 1) : brut
}

/** Depuis quand l'appareil est enregistre, en clair. */
export function depuis(appareil: Appareil, maintenant: number = Date.now()): string {
  const secondes = Math.max(0, Math.round((maintenant - appareil.registeredAt) / 1000))
  if (secondes < 60) return `il y a ${secondes} s`
  const minutes = Math.round(secondes / 60)
  if (minutes < 60) return `il y a ${minutes} min`
  const heures = Math.round(minutes / 60)
  if (heures < 24) return `il y a ${heures} h`
  return `il y a ${Math.round(heures / 24)} j`
}

/**
 * Le compte a rebours, en texte court.
 *
 * ⚠️ **On ne propose pas de rafraichir l'affichage chaque seconde.** Le TUI rerend le
 * dialogue a chaque changement d'etat, et un rafraichissement par seconde dans un
 * dialogue contenant un QR de 37 lignes provoque un scintillement Visible pour un
 * compte qui, lui, ne demande aucune action. La precision au dela de la minute n'a
 * aucun interet : le jeton expire dans 30 minutes et l'utilisateur le scanne en dix
 * secondes. On rafraichit donc sur un **seuil de minute**.
 */
export function compteARebours(expiresAt: number, maintenant: number = Date.now()): string {
  const reste = expiresAt - maintenant
  if (reste <= 0) return "expiré — relancez l'appairage"
  const minutes = Math.floor(reste / 60_000)
  const secondes = Math.floor((reste % 60_000) / 1000)
  if (minutes >= 1) return `expire dans ${minutes} min ${String(secondes).padStart(2, "0")} s`
  return `expire dans ${secondes} s`
}

/** Faut-il rerendre le dialogue ? Seulement quand la minute affichee change. */
export function seuilDeRafraichissement(expiresAt: number, maintenant: number = Date.now()): number {
  if (expiresAt - maintenant <= 0) return 0
  return Math.floor((expiresAt - maintenant) / 60_000)
}

/** Les options du select d'appareils, dans l'ordre d'affichage. */
export function optionsAppareils(appareils: readonly Appareil[], maintenant: number = Date.now()) {
  return [...appareils]
    .sort((a, b) => a.registeredAt - b.registeredAt)
    .map((appareil) => ({
      title: nomAppareil(appareil),
      value: appareil.deviceId,
      description: `${distributeurLisible(appareil)} · ${depuis(appareil, maintenant)}`,
    }))
}

/** Le message quand il n'y a aucun appareil. */
export const AUCUN_APPAREIL = "Aucun appareil appairé. Lancez /tether pour appairer un téléphone."
