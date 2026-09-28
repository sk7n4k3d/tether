# Tether

Un client Android natif pour [opencode](https://github.com/sst/opencode) V2, et le
plugin qui le relie à ton serveur.

Tether est écrit en Kotlin et Compose, sans WebView : il parle directement à l'API V2 du
serveur. Une session bloquée sur une question, un diff à relire, une autorisation à
accorder — tout se répond depuis le téléphone, y compris hors du Wi-Fi.

## Ce qu'il faut avant de commencer

| | |
|---|---|
| **Android** | 8.0 (API 26) ou plus |
| **opencode** | V2, avec le plugin Tether installé |
| **Distributeur push** | [UnifiedPush](https://unifiedpush.org/) — voir plus bas |

Le push est **entièrement optionnel** : sans distributeur, l'app fonctionne, elle ne
recevra simplement aucune notification. Tout le reste — sessions, chat, diffs, fichiers,
statistiques, approbations — est en accès direct.

## Les notifications ne dépendent d'aucun service particulier

Tether utilise **Web Push standard** (RFC 8291/8292) via UnifiedPush. Il n'y a ni FCM
requis, ni compte Google, ni serveur relais à faire tourner.

Concrètement, n'importe lequel de ces distributeurs fonctionne :

- [ntfy](https://ntfy.sh/) (auto-hébergé ou pas)
- [Gotify](https://gotify.net/)
- [Conversations](https://conversations.im/)
- [Sunup](https://sunup.sh/)
- UnifiedPush Distributor (l'app de référence)

Le choix se fait dans **Réglages → Notifications → Changer de distributeur**. L'endpoint
est une capacité d'écriture : changer de distributeur, c'est donner à un autre service le
droit de pousser sur ton téléphone. C'est pour ça que ce n'est pas caché dans un menu.

## Installation

### 1. Le plugin, côté serveur

Copie `plugin/tether/` dans `~/.config/opencode/plugins/tether/`, puis redémarre opencode.

Le plugin ne fonctionne **sans aucune configuration** : ce qui n'est pas configuré est
désactivé, et le journal le dit. Il n'existe aucun défaut qui pointe vers l'infra de
quelqu'un d'autre.

Optionnel, via `~/.config/opencode/opencode.jsonc` :

```jsonc
{
  // ⚠️ Le champ est `plugins`. Le `plugin` de V1 est ignoré par opencode 2.x.
  "plugins": ["./plugins/tether"],
  "tether": {
    "serverUrl": "https://opencode.exemple.fr:4096"  // joignable depuis le téléphone
  }
}
```

ou par variable d'environnement :

| Variable | Effet |
|---|---|
| `TETHER_SERVER_URL` | Adresse à mettre dans le QR |
| `TETHER_MIN_SECONDS` | Seuil de durée avant de notifier une fin de tour |
| `TETHER_MAX_BYTES` | Troncature du texte dans la notification |
| `TETHER_VAPID_KEY_FILE` | Clé P-256 VAPID en PEM — **requis seulement par les distributeurs FCM** |
| `TETHER_DEBUG` | `1` pour le journal de diagnostic |

`serverUrl` compte : c'est cette adresse que le QR contient, donc celle que le téléphone
doit pouvoir atteindre. Un `127.0.0.1` dans un QR destiné à un autre appareil ne peut pas
marcher.

### 2. L'app

```bash
./gradlew :app:installDebug
```

Sur ton téléphone : ouvrir Tether, aller dans les réglages, renseigner l'adresse du
serveur et le mot de passe.

### 3. L'appairage

Dans le TUI opencode :

```
/tether
```

Un QR s'affiche, avec l'adresse du serveur. Tu le scannes avec l'appareil photo, et
l'app te montre **l'adresse exacte** avant de faire quoi que ce soit. Rien n'est transmis
avant ton « Autoriser ».

Un détail qui n'est pas négociable : le lien de scan est la seule entrée de l'app que
**l'utilisateur ne saisit pas**. Tout le reste passe par l'écran ou par une demande
authentifiée ; ici, le contenu vient d'une image qu'un tiers a pu fabriquer. Alors :

- rien n'est envoyé tant que tu n'as pas accepté ;
- l'adresse du serveur est affichée en grand, parce que c'est la seule chose que tu puisses
  vérifier — une validation évite l'erreur, pas l'intention ;
- « Refuser » ne fait rien du tout.

La comparaison vient de [GHSA-2xqv-hwrf-983f](https://github.com/home-assistant/core/security/advisories/GHSA-2xqv-hwrf-983f) : l'app Companion exécutait des automatisations sur simple scan NFC, sans confirmation humaine.

Après l'appairage :

```
/tether          # affiche un nouveau QR
```

Et, dans la palette : *Tether : appareils appairés* pour lister et retirer un appareil.

### Le renouvellement d'endpoint

Un distributeur redistribue son point d'accès au redémarrage. L'app le déclare alors au
serveur **sans nouveau jeton** — le jeton d'appairage est à usage unique, il est mort à ce
stade. Le serveur n'accepte cette forme que pour un appareil qu'il connaît déjà ; un
`deviceId` inconnu sans scan est refusé.

## Le plugin n'a pas de headquarters

Aucun défaut ne pointe vers l'infrastructure de l'auteur. Pas de relais, pas de
compteur, pas de télémétrie, pas de mot de passe par défaut. Une fonction non configurée
est désactivée et le dit dans le journal — c'est moins agréable que « ça marche direct »,
et c'est le bon compromis : une configuration qui fonctionne vraiment ne peut pas être une
coïncidence.

## Développer

```bash
# Plugin — 123 tests, dont 25 d'intégration contre un vrai `opencode serve`
node --experimental-strip-types --test plugin/tether/*.test.mjs

# App — 501 tests JVM
./gradlew :app:testDebugUnitTest

# L'APK
./gradlew :app:assembleDebug
```

Deux tests méritent qu'on s'y arrête :

- `plugin/tether/qr-verify.sh` confronte l'encodeur de QR à `com.google.zxing:core`,
  module par module, sur 130 cas déterministes. zxing décode mais n'affiche pas, et
  aucune dépendance native n'a sa place dans un plugin serveur.
- `plugin/tether/vapid.test.mjs` vérifie les signatures VAPID, y compris le piège DER
  contre R‖S qui donne « notifications perdues » sans aucun message d'erreur.

## Structure

```
app/                    client Android (Kotlin, Compose, Hilt, Ktor)
plugin/tether/
  index.ts              enregistrement du plugin, RPC, ceremonie d'appairage
  pairing.ts            emission et consommation du jeton, a usage unique
  webpush.ts            chiffrement RFC 8291 et signature VAPID
  registry.ts           registre des appareils
  qr.ts                 encodeur de QR, sans dependance
  tui.tsx               dialogue et palette
  tui-logic.ts          la logique du TUI, sans JSX (testable par node:test)
  config.ts             options et variables d'environnement
docs/                   notes de conception et de diagnostic
```

## Licence

MIT — voir [`LICENSE`](LICENSE).
