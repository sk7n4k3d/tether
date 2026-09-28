# Tether

Un client Android natif pour [opencode](https://github.com/sst/opencode) V2.

Kotlin et Compose, sans WebView : l'app parle directement à l'API V2 du serveur. Sessions,
chat, diffs, fichiers, statistiques et approbations sont en accès direct. Le push passe par
Web Push standard (RFC 8291/8292) via [UnifiedPush](https://unifiedpush.org/) — sans FCM,
sans compte Google, sans relais.

## ⚠️ État du projet

**L'app n'a jamais tourné sur un téléphone physique.** Le plugin n'a jamais été exécuté
dans un vrai TUI, et aucun push n'a été chiffré puis déchiffré de bout en bout par un
appareil. Tout ce qui suit est vérifié statiquement — 626 tests, dont 25 d'intégration
contre un vrai `opencode serve` — et dynamiquement non prouvé.

Considérez-vous comme quelqu'un qui débugge. Les problèmes que vous rencontrerez sont
probablement des bugs, pas des erreurs d'installation.

Ce qui est stable : l'API V2 du serveur, l'analyse du lien d'appairage, l'encodeur de QR
(contre zxing, module par module), la cryptographie Web Push, et le typage des réponses.
Ce qui ne l'est pas : tout le parcours utilisateur, par définition.

## Prérequis

| | |
|---|---|
| **Android** | 8.0 (API 26) ou plus |
| **opencode** | V2, avec le plugin installé |
| **JDK** | 17 |
| **Distributeur push** | [UnifiedPush](https://unifiedpush.org/) — facultatif |

Le push est facultatif : sans distributeur, l'app fonctionne, elle ne recevra pas de
notification. Le reste est en accès direct.

## Installation

### 1. Le plugin

```bash
cp -r plugin/tether ~/.config/opencode/plugins/
```

C'est tout. opencode découvre automatiquement `~/.config/opencode/plugin/` et
`~/.config/opencode/plugins/`, fichier ou répertoire ([`PluginSourceDirectory.discover`]).
Aucune entrée de configuration n'est nécessaire pour le charger — le champ `plugins` de
`opencode.jsonc` sert aux plugins npm (`"cc-safety-net@latest"`).

Redémarrez opencode, puis :

```
/tether
```

Un QR devrait s'afficher. S'il ne s'affiche pas, voir [Dépannage](#dépannage).

### 2. L'app

```bash
./gradlew :app:installDebug
```

Au lancement, allez dans les réglages et renseignez l'adresse du serveur et le mot de
passe. L'adresse par défaut est `http://127.0.0.1:4096`, qui ne fonctionne que si le
serveur tourne sur le téléphone — il ne le fait pas.

### 3. L'appairage

Dans le TUI :

```
/tether
```

Scannez le QR avec l'appareil photo. **Vérifiez l'adresse affichée** avant d'accepter : c'est
le seul contrôle qui vaille. Rien n'est transmis avant « Autoriser ».

## Configuration

Le plugin fonctionne sans configuration. Ce qui n'est pas configuré est désactivé, et le
journal le dit. Aucun défaut ne pointe vers l'infrastructure de l'auteur : ni relais, ni
compteur, ni télémétrie, ni mot de passe.

### Variables d'environnement

| Variable | Effet |
|---|---|
| `TETHER_SERVER_URL` | Adresse inscrite dans le QR |
| `TETHER_MIN_SECONDS` | Durée minimale d'un tour avant notification |
| `TETHER_MAX_BYTES` | Troncature du texte dans la notification |
| `TETHER_VAPID_KEY_FILE` | Clé P-256 VAPID en PEM — requise **seulement** par les distributeurs FCM |
| `TETHER_DEBUG` | `1` active le journal de diagnostic |
| `TETHER_DEBUG_LOG_FILE` | Destination du journal |
| `TETHER_SUMMARY_URL` | Endpoint OpenAI-compatible pour le résumé |
| `TETHER_SUMMARY_KEY_FILE` | Fichier contenant la clé — le chemin, jamais la clé |

`TETHER_SERVER_URL` compte plus que les autres : c'est cette adresse que le QR contient,
donc celle que **le téléphone** doit pouvoir atteindre. Un `127.0.0.1` dans un QR destiné à
un autre appareil ne peut pas fonctionner. C'est aussi la seule façon de régler l'adresse
sans fichier de configuration, un plugin déposé dans `plugins/` recevant `options = {}`.

### Options de `opencode.jsonc`

Les options ne sont lues que si le plugin est déclaré sous sa forme objet :

```jsonc
{
  "tether": {
    "serverUrl": "https://opencode.exemple.fr:4096"
  }
}
```

L'option `serverUrl` l'emporte sur `TETHER_SERVER_URL`, qui l'emporte sur la détection
automatique du TUI.

## Notifications

Aucun service particulier n'est requis. N'importe lequel de ces distributeurs fonctionne :

[ntfy](https://ntfy.sh/) (auto-hébergé ou non) · [Gotify](https://gotify.net/) ·
[Conversations](https://conversations.im/) · [Sunup](https://sunup.sh/) ·
UnifiedPush Distributor

Le choix se fait dans **Réglages → Notifications → Changer de distributeur**. Ce n'est pas
caché dans un menu parce que l'endpoint est une **capacité d'écriture** : changer de
distributeur, c'est donner à un autre service le droit de pousser sur votre téléphone.

Un distributeur redistribue son point d'accès au redémarrage. L'app le déclare alors au
serveur sans nouveau jeton : le jeton d'appairage est à usage unique, il est mort à ce
stade. Le serveur n'accepte cette forme que pour un appareil qu'il connaît déjà.

## Dépannage

**`/tether` ne fait rien.** Le plugin n'est pas chargé. Vérifiez qu'il est dans
`~/.config/opencode/plugins/tether/`, et qu'il exporte `{ id, setup }` — c'est le contrat
V2. Un plugin V1 (`{ tui }`) ne se charge pas.

**Le QR contient `127.0.0.1`.** Le TUI n'a pas trouvé d'adresse joignable. Exportez
`TETHER_SERVER_URL` avec une adresse que le téléphone atteint.

**« Ce QR a expiré ou a déjà servi. »** Les jetons sont à usage unique et de durée de vie
courte. Relancez `/tether` et rescanez. L'app ne rejoue pas l'envoi toute seule : un
échec doit se voir.

**« Aucun distributeur push n'a encore fourni de point d'accès. »** Installez un
distributeur UnifiedPush, puis revenez. L'app ne peut rien envoyer sans.

**« Android les bloque »** dans les notifications. Accordez la permission `POST_NOTIFICATIONS`
— Android 13+ ne la demande pas tout seul, et sans elle rien ne s'affiche sans message.

**L'app refuse de s'enregistrer avec 401.** Elle utilise le mot de passe des réglages
actuels, jamais un autre. Si le serveur du QR demande un autre mot de passe, l'appel échoue
et l'erreur s'affiche telle quelle.

**L'écran de confirmation ne s'ouvre pas.** Vérifiez que l'`intent-filter` pour
`opencode://pair` est présent : c'est lui qui fait venir l'`Intent` au premier plan.

## Développer

```bash
# Plugin — 124 tests, dont 25 d'intégration contre un vrai `opencode serve` (port 4299)
node --experimental-strip-types --test plugin/tether/*.test.mjs

# App — 502 tests JVM
./gradlew :app:testDebugUnitTest

# APK
./gradlew :app:assembleDebug
```

Trois tests méritent qu'on s'y arrête :

- **`plugin/tether/qr-verify.sh`** confronte l'encodeur à `com.google.zxing:core`, module
  par module, sur 130 cas déterministes. zxing décode mais n'affiche pas, et aucune
  dépendance native n'a sa place dans un plugin serveur. L'encodeur est écrit de zéro.
- **`plugin/tether/vapid.test.mjs`** vérifie les signatures VAPID, y compris le piège DER
  contre R‖S, qui donne « notifications perdues » sans le moindre message d'erreur.
- **`scripts/check-publie.sh`** vérifie qu'aucune donnée personnelle n'est publiée. Les
  motifs interdits viennent de `TETHER_GREP_FORBIDDEN`, pas du dépôt : un motif sensible
  écrit dans le dépôt est un motif fuite.

## Contribuer

Les contributions sont bienvenues. Avant d'ouvrir une pull request :

```bash
node --experimental-strip-types --test plugin/tether/*.test.mjs
./gradlew :app:testDebugUnitTest
```

Si votre contribution touche au push, l'appairage ou la cryptographie, dites ce qu'elle
corrige. Ces trois-là n'ont pas encore été exercés sur du matériel : un test qui prétend le
contraire a besoin d'être lu de près.

## Structure

```
app/                    client Android (Kotlin, Compose, Hilt, Ktor)
plugin/tether/
  index.ts              enregistrement du plugin, RPC, cérémonie d'appairage
  pairing.ts            émission et consommation du jeton, à usage unique
  webpush.ts            chiffrement RFC 8291 et signature VAPID
  registry.ts           registre des appareils
  rpc.ts                schéma du RPC, en JSON Schema
  qr.ts                 encodeur de QR, sans dépendance
  tui.tsx               dialogue et palette
  tui-logic.ts          la logique du TUI, sans JSX — node:test ne lit pas le JSX
  config.ts             options et variables d'environnement
docs/                   spécifications de conception et notes de diagnostic
scripts/                vérification avant publication
```

## Licence

MIT — voir [`LICENSE`](LICENSE).
