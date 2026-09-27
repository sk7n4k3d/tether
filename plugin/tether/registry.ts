/**
 * Le registre d'appareils — **fonction pure**, aucun accès réseau ni stockage.
 *
 * ## Pourquoi une liste, et pas une entrée unique
 *
 * Mastodon fait « un token = une subscription » : le `POST` suivant détruit la
 * précédente, donc **réinstaller l'app efface silencieusement l'enregistrement de
 * l'autre appareil** du même compte. C'est un bug de conception assumé là-bas.
 * On copie Misskey, qui garde une liste et itère.
 *
 * Conséquence directe sur la forme : `upsert` est **idempotent sur `deviceId`**,
 * donc réenregistrer après un redémarrage du distributeur met à jour au lieu de
 * dupliquer. C'est ce qui rend la réinscription sans risque.
 *
 * ## Ce qui ne sort JAMAIS d'ici
 *
 * `endpoint` et `keys` sont des **capacités d'écriture** : qui les possède peut
 * pousser vers le téléphone. `publicView` est donc la seule forme destinée à
 * quiconque que ce soit — y compris les logs et l'écran d'appairage.
 */

/** Un appareil enregistré, tel que stocké. */
export interface Device {
  /** Identifiant stable, choisi par l'app et persiste sur l'appareil. */
  deviceId: string
  /** URL du distributeur. Capacité d'écriture. */
  endpoint: string
  /** Clés duschéma Web Push. `p256dh` est une clé publique, `auth` un secret. */
  keys: { p256dh: string; auth: string }
  /** Ce que l'appareil veut recevoir. */
  alerts: Alerts
  /** Package du distributeur, pour l'affichage. */
  distributor?: string
  /** Libellé libre, pour distinguer deux téléphones dans l'écran. */
  label?: string
  /** ms epoch. */
  registeredAt: number
}

/** Sous-ensemble des types de notification. Aligné sur ce que `PushPolicy.kt` sait faire. */
export interface Alerts {
  /** Fin de tour : le travail est terminé. */
  turnEnd: boolean
  /** Une décision attend l'utilisateur (permission ou formulaire). */
  attention: boolean
  /** Une étape d'avancement, dans un tour en cours. */
  progress: boolean
}

export const ALL_ALERTS: Alerts = { turnEnd: true, attention: true, progress: true }

/** Vue publique : **jamais** de clé, **jamais** d'endpoint. */
export interface PublicDevice {
  deviceId: string
  label?: string
  distributor?: string
  registeredAt: number
  alerts: Alerts
}

/** Cle de subscription : `deviceId` d'abord, `endpoint` en dépannage. */
export function deviceKey(device: Pick<Device, "deviceId">): string {
  return device.deviceId
}

export function isEmpty(registry: Device[]): boolean {
  return registry.length === 0
}

/**
 * Ajoute ou **met à jour** un appareil.
 *
 * ⚠️ Si le même `deviceId` revient avec un `endpoint` différent, on remplace : c'est
 * le cas normal d'un distributeur qui a renouvelé son endpoint. On ne garde jamais
 * deux lignes pour un même appareil.
 */
export function upsert(registry: Device[], device: Device): Device[] {
  const index = registry.findIndex((d) => d.deviceId === device.deviceId)
  if (index === -1) return [...registry, device]

  const next = [...registry]
  // `registeredAt` est celui de la **première** inscription : c'est l'ancienneté qui
  // compte pour l'affichage, pas la date du dernier rafraîchissement.
  next[index] = { ...device, registeredAt: registry[index].registeredAt }
  return next
}

/** Retire un appareil. Renvoie la liste inchangée si l'id est inconnu. */
export function remove(registry: Device[], deviceId: string): Device[] {
  return registry.filter((d) => d.deviceId !== deviceId)
}

/**
 * Faut-il désabonner après un code HTTP ?
 *
 * Règle du brevet ③ : **4xx sauf 408 et 429 → désabonner**. C'est ainsi qu'on rattrape
 * « app désinstallée » et « distributeur révoqué » **sans jamais parler à l'API du
 * distributeur** — on ne fait que lire son code de retour.
 *
 * Les deux exceptions ne sont pas des cas particuliers :
 *  - **408 Request Timeout** et **429 Too Many Requests** disent que le distributeur
 *    est débordé, pas que l'abonnement est mort. Se désabonner dessus perdrait la
 *    notification *et* l'enregistrement, définitivement, pour un problème temporaire.
 *
 * Un 5xx est une panne du distributeur : on garde l'abonnement, on réessaiera.
 */
export function shouldUnsubscribe(status: number): boolean {
  if (status === 408 || status === 429) return false
  return status >= 400 && status < 500
}

/**
 * Raison lisible du désabonnement. Aucun secret dedans : ni endpoint, ni clés.
 */
export function unsubscribeReason(status: number): string {
  switch (status) {
    case 404:
      return "L'appareil a ete desabonne cote distributeur."
    case 410:
      return "Le distributeur a declare l'abonnement expire."
    case 401:
    case 403:
      return "Le distributeur refuse cet abonnement (cle VAPID absente ou expiree ?)."
    default:
      return `Le distributeur a repondu ${status}.`
  }
}

/** La vue publique d'un appareil. Point de passage unique vers l'extérieur. */
export function publicView(device: Device): PublicDevice {
  return {
    deviceId: device.deviceId,
    label: device.label,
    distributor: device.distributor,
    registeredAt: device.registeredAt,
    alerts: { ...device.alerts },
  }
}

/** Tous les appareils, en vue publique. C'est ce que renvoie le RPC `devices`. */
export function publicList(registry: Device[]): PublicDevice[] {
  return registry.map(publicView)
}

/** Les appareils qui veulent un type d'alerte. */
export function recipientsFor(registry: Device[], alert: keyof Alerts): Device[] {
  return registry.filter((d) => d.alerts[alert] === true)
}
