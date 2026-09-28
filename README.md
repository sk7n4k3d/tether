<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="110" alt="Tether"/>
</p>

<h1 align="center">Tether</h1>

<p align="center">
  <strong>A native Android client for OpenCode V2 — with Web Push over UnifiedPush, and no Google account in sight</strong>
</p>

<p align="center">
  <a href="https://github.com/sk7n4k3d/tether/actions/workflows/ci.yml"><img src="https://github.com/sk7n4k3d/tether/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/sk7n4k3d/tether/blob/master/LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue?style=for-the-badge" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=for-the-badge&labelColor=0a0e1a" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?style=for-the-badge&labelColor=0a0e1a" alt="Kotlin">
  <img src="https://img.shields.io/badge/Compose-Material3-0175C2?style=for-the-badge&labelColor=0a0e1a" alt="Compose">
  <img src="https://img.shields.io/badge/push-UnifiedPush-00ff88?style=for-the-badge&labelColor=0a0e1a" alt="UnifiedPush">
</p>

---

## About

**Tether** is a native Android client for [OpenCode](https://github.com/sst/opencode) V2, built
in Kotlin and Jetpack Compose with no WebView — it talks directly to the server's V2 API.
Answer a blocked question, read a diff, grant an approval: all of it from your phone, over
your own network or not. Notifications use **standard Web Push (RFC 8291/8292)** through
[UnifiedPush](https://unifiedpush.org/), so there is no FCM, no Google account, and no
relay server to run.

---

## Why another client

| | **Tether** (this) | **[opencode web](https://github.com/sst/opencode)** | **[starburst](https://github.com/hiylo/starburst)** | **[opencode2-mobile](https://github.com/omnicus/opencode2-mobile)** |
|---|---|---|---|---|
| **Stack** | Kotlin, Compose, M3 | Web (bundled in the server) | Kotlin, Compose, M3 | Expo / React Native |
| **Platforms** | Android | Any browser | Android | Android + iOS |
| **Rendering** | Native, no WebView | Browser DOM | Native | Native |
| **Push** | **Any UnifiedPush distributor** | None | Not specified | Self-hosted encrypted push |
| **Google account** | **Not required** | Not required | Not required | Not required |
| **Pairing** | QR, server shown before consent | Type the URL | Type the URL | — |
| **Approvals** | In-app, with a queue | Browser tab | — | — |
| **Worktrees** | Yes | — | Workspace files | — |
| **Server plugin** | Yes (`/tether`) | — | — | — |

Two rows matter most. **Push without a Google account**: any UnifiedPush distributor works
— ntfy, Gotify, Conversations, Sunup — and you pick which one. **Pairing with consent**:
a QR is the only input in this app that *you* do not type, so it is the only one that can
be forged. Tether shows you the server address and sends nothing until you accept. The
comparison is [GHSA-2xqv-hwrf-983f](https://github.com/home-assistant/core/security/advisories/GHSA-2xqv-hwrf-983f),
where the Home Assistant Companion app executed automations on a bare NFC scan with no
human confirmation.

---

## Features

### Sessions and chat
- **Live streaming** over SSE, with reasoning, tool calls and diffs rendered natively
- **Interrupt** a running agent from the phone
- **Fork** and **compact** a session
- **Rename** and **delete** sessions
- **Send prompts** with model and agent selection, resolved by the server at first turn
- **Prompt attachments** — files by URI, referenced exactly as the server expects

### Approvals — the reason this app exists
- **Every pending request in one queue**: tool permissions and interactive forms side by
  side
- **Answer from the phone**: a session can sit blocked for hours because nobody saw the
  question. This is the first thing the app is for
- **Notifications while a decision waits** — a persistent alert, since an in-app icon that
  stays quiet is a lie by omission

### Diffs and files
- **Per-file diffs** with additions, deletions and renames
- **Worktrees** — try a change on an isolated branch without touching the repository
- **File explorer** with read-before-send: check a path before it leaves the machine
- **Commit and checkout** from the phone

### Server inventory
- **Models, agents and providers**, with usage per model
- **MCP servers** and their connection state
- **Plugins** and **skills** actually loaded
- **Granted permissions**, revocable one by one

### Notifications
- **Any UnifiedPush distributor** — ntfy, Gotify, Conversations, Sunup, the reference app
- **End-to-end encrypted** by the server with RFC 8291; the distributor never sees plaintext
- **Choose your distributor in-app** — it is a visible setting, not a hidden menu, because
  the endpoint is a write capability
- **Endpoint rotation handled**: distributors reissue their endpoint on restart, and Tether
  re-declares it automatically
- **Alerts for turn end, attention needed, and progress** — independently configurable

### Interface
- **Material 3**, dark theme only — no half-done light mode
- **Seven accent colours**, and the brightness is computed for you
- **English and French**, following the phone's language by default
- **Streaming and history are separate items** in the list, not two renderings of one
- **Offline screen** that says the server is unreachable instead of showing an empty list
- **Single-server model**, the way OpenCode itself scopes a directory

#### The accent colour is a hue, not a value

You pick a hue; the app computes the brightness. Seven are offered, and the one you
choose is adjusted until it clears the **3:1** contrast ratio against the app's own
backgrounds — the threshold WCAG sets for interface elements rather than text.

This is deliberate. A free colour picker hands out values nobody can evaluate: a pale
accent on a dark background, or a hue close to the background, and nothing looks wrong
until someone who cannot read it complains. Letting the hue be free and deriving the
brightness is the only way to offer the choice without offering an unreadable screen.

A test fails the build if any accent drops below the threshold, so an unreadable one
cannot be merged.

#### Language

The app follows the phone's language. You can override that in Settings, and on
Android 13 and later the app also appears in **Settings → Apps → Languages**, so the
system can switch it too.

Every string is a resource, in both languages — including the error messages a
`ViewModel` prepares and a `Service` logs, which are resolved outside the component
tree. A test reads the two XML files directly and fails if a key exists in one language
only, if a phrase is identical in both (a forgotten translation), or if a `${...}`
interpolation survived the extraction: Android cannot evaluate it, so it would show up
on screen as-is.

The choice is applied before the first screen is composed, which is why the app
restarts its activity on a change — a language is not something that can be swapped
into a tree that already exists.

---

## Requirements

| | |
|---|---|
| **Android** | 8.0 (API 26) or newer |
| **OpenCode** | V2, with the Tether plugin installed |
| **JDK** | 17, to build the app |
| **UnifiedPush distributor** | Optional — only for notifications |

Push is optional. Without a distributor the app works fully; it just receives no
notifications.

---

## Installation

### 1. The plugin

```bash
cp -r plugin/tether ~/.config/opencode/plugins/
```

That is the whole step. OpenCode discovers plugins in `~/.config/opencode/plugins/` — the
plural, and each plugin is a **directory** (or a symlink to one). A bare `.ts` file dropped
in that folder is not picked up; `opencode plugin list` will say "No plugins found". No
`opencode.jsonc` entry is needed to load it — the `plugins` field there is for npm packages
like `"cc-safety-net@latest"`.

Restart OpenCode, then:

```
/tether
```

A QR should appear. If it does not, see [Troubleshooting](#troubleshooting).

### 2. The app

```bash
./gradlew :app:installDebug
```

On first launch, fill in the server address and password. The default is
`http://127.0.0.1:4096`, which only works if the server runs on the phone — it does not.

OpenCode can print them for you:

```bash
opencode pair
```

It shows the URL, the username, the password, and a QR encoding all three. Type them in, or
scan the QR with any app. **That QR contains your server password** — treat the terminal as
a secret from that moment, and rotate the password if the output ever ends up in a
screenshot, a log, or a shell history.

### 3. Pairing

In the TUI:

```
/tether
```

Scan the QR. **Check the address it shows you** before accepting — that is the only check
that is worth anything. Nothing is transmitted until you press "Authorize".

#### This is not `opencode pair`

OpenCode has its own pairing command, and the two are easy to confuse. They do different
jobs:

| | `opencode pair` | `/tether` |
|---|---|---|
| **Purpose** | Give an app the server credentials | Authorize a device for **push** |
| **Payload** | JSON: `urls`, `username`, **`password`** | A one-time token, 128 bits |
| **Valid for** | Until the password changes | 30 minutes, then consumed |
| **Password leaves the terminal** | **Yes** | **Never** |
| **Run it** | Once, to configure the app | Once, to authorize notifications |

`opencode pair` puts the server password in a QR code. That is the master credential:
whoever photographs that screen can drive your agent, read your sessions and run tools.
`/tether` never transmits it — it mints a token that authorizes exactly one thing, on one
device, once.

You need **both**, in this order:

```bash
opencode pair          # scan with any app, to fill in address and password
```

then, in the TUI:

```
/tether                # scan with Tether, to authorize notifications
```

⚠️ Scanning the wrong one does nothing, because the formats are unrelated: `opencode pair`
encodes raw JSON, `/tether` encodes `opencode://pair?s=…&t=…`. There is deliberately no
automatic import of credentials from a QR — that is the pattern behind GHSA-2xqv-hwrf-983f.
Enter the address and password in Settings instead.

The palette entry *Tether: paired devices* lists what is registered and lets you remove
one.

### 4. A distributor, for notifications

Install any UnifiedPush distributor, then pick it in **Settings → Notifications → Change
distributor**.

---

## Configuration

The plugin runs with no configuration. Whatever is not configured is disabled, and the log
says so. No default points at anyone's infrastructure: no relay, no counter, no telemetry,
no password.

### Environment variables

| Variable | Effect |
|---|---|
| `TETHER_SERVER_URL` | The address written into the QR |
| `TETHER_MIN_SECONDS` | Minimum turn duration before it is worth notifying |
| `TETHER_MAX_BYTES` | Truncation of the notification text |
| `TETHER_VAPID_KEY_FILE` | P-256 VAPID key in PEM — required **only** by FCM-based distributors |
| `TETHER_DEBUG` | `1` enables the diagnostic log |
| `TETHER_DEBUG_LOG_FILE` | Where that log goes |
| `TETHER_SUMMARY_URL` | OpenAI-compatible endpoint for summarising notifications |
| `TETHER_SUMMARY_KEY_FILE` | File holding the key — the path, never the key |

`TETHER_SERVER_URL` matters more than the others: it is the address inside the QR, so it is
the address **your phone** must be able to reach. A `127.0.0.1` in a QR meant for another
device cannot work. It is also the only way to set that address without a config file, since
a plugin dropped in `plugins/` receives `options = {}`.

### `opencode.jsonc`

Options are only read when the plugin is declared in its object form:

```jsonc
{
  "tether": {
    "serverUrl": "https://opencode.example.com:4096"
  }
}
```

`serverUrl` beats `TETHER_SERVER_URL`, which beats what the TUI detects.

---

## Troubleshooting

**`/tether` does nothing.** The plugin did not load. Check that it sits in
`~/.config/opencode/plugins/tether/`, and that it exports `{ id, setup }` — that is the V2
contract. A V1 plugin (`{ tui }`) will not load.

**The QR contains `127.0.0.1`.** The TUI found no reachable address. Export
`TETHER_SERVER_URL` with something your phone can reach.

**"This QR expired or was already used."** Tokens are single-use and short-lived. Run
`/tether` again and rescan. The app will not retry by itself: a failure has to be visible.

**"No push distributor has provided an access point yet."** Install a UnifiedPush
distributor and come back. Without one the app has nothing to send through.

**"Android is blocking them."** Grant the `POST_NOTIFICATIONS` permission. Android 13+ does
not ask on its own, and without it nothing appears — silently.

**Registration fails with 401.** Tether uses the password from your settings and never
another. If the server in the QR wants a different one, the call fails and the error is
shown as-is.

**The confirmation screen does not open.** Check that the `opencode://pair` intent filter
is in the manifest — it is what delivers the intent to the foreground.

---

## Development

```bash
# Plugin — 139 tests under node, plus 5 under Bun for the dialog.
# List the files explicitly: the glob pulls in tui-setup.test.mjs, which imports tui.tsx
# and dies with ERR_UNKNOWN_FILE_EXTENSION outside an OpenCode install.
# 29 of the 139 run against a live `opencode serve` (port 4299) and skip if it is absent.
node --experimental-strip-types --test \
  plugin/tether/index.test.mjs plugin/tether/qr.test.mjs \
  plugin/tether/registry.test.mjs plugin/tether/tui-config.test.mjs \
  plugin/tether/tui-logic.test.mjs plugin/tether/ui-model.test.mjs \
  plugin/tether/vapid.test.mjs plugin/tether/webpush.mutation.test.mjs \
  plugin/tether/webpush.test.mjs

# The one test that needs the .tsx dialog, and therefore Bun and an OpenCode tree
bun test plugin/tether/tui-setup.test.mjs

# App — 523 JVM tests, plus 6 on a connected device
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest

# APK
./gradlew :app:assembleDebug
```

Three of these are worth reading:

- **`plugin/tether/qr-verify.sh`** checks the QR encoder against `com.google.zxing:core`,
  module by module, over 130 deterministic cases. zxing decodes but does not render, and no
  native dependency belongs in a server plugin — so the encoder is written from scratch.
- **`plugin/tether/vapid.test.mjs`** verifies VAPID signatures, including the DER-versus-R‖S
  trap that yields "notifications silently lost" with no error anywhere.
- **`scripts/check-publie.sh`** checks that no personal data is about to be published. The
  forbidden patterns come from `TETHER_GREP_FORBIDDEN`, not from the repository: a sensitive
  pattern stored in the repository is a leaked pattern.

---

## Project structure

```
app/                    Android client (Kotlin, Compose, Hilt, Ktor)
plugin/tether/
  index.ts              plugin registration, RPC, pairing ceremony
  pairing.ts            token minting and consumption, single-use
  webpush.ts            RFC 8291 encryption and VAPID signing
  registry.ts           device registry
  rpc.ts                RPC schema, as JSON Schema
  qr.ts                 QR encoder, dependency-free
  tui.tsx               dialog and palette
  tui-logic.ts          TUI logic without JSX — node:test cannot read JSX
  config.ts             options and environment variables
scripts/                pre-publication verification
```

---

## Contributing

1. Fork the repository
2. Create a branch
3. Make your change, with the tests
4. Open a pull request

```bash
node --experimental-strip-types --test plugin/tether/index.test.mjs   # …see Development
./gradlew :app:testDebugUnitTest
```

If your change touches push, pairing or cryptography, say what it fixes. The push round trip
has not been observed on a real phone, and the TUI dialog has never been rendered in a real
TUI: a test claiming otherwise deserves a careful read.

---

## Credits

| Resource | Description |
|---|---|
| [OpenCode](https://github.com/sst/opencode) | The server this client talks to |
| [UnifiedPush](https://unifiedpush.org/) | The push protocol, and the reason no Google account is needed |
| [zxing](https://github.com/zxing/zxing) | Reference implementation the QR encoder is checked against |
| [conversations.im](https://conversations.im/) | Federated X client, inspiration for the conversation model |

---

## License

MIT — Copyright (c) 2026 sk7n4k3d. See [LICENSE](LICENSE).
