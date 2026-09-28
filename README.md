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

> ⚠️ **The app has never run on a physical device.** The plugin has never been rendered in
> a real TUI, and no push has been encrypted then decrypted end-to-end by an actual phone.
> Everything is verified statically — 626 tests, 25 of them against a live `opencode serve`
> — and dynamically unproven.
>
> Stable: the V2 API surface, pairing-link parsing, the QR encoder (checked against zxing,
> module by module), Web Push cryptography, response typing. Not stable: the user journey,
> by definition. If your push never arrives, that is the most likely place to be looking.

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
- **Streaming and history are separate items** in the list, not two renderings of one
- **Offline screen** that says the server is unreachable instead of showing an empty list
- **Single-server model**, the way OpenCode itself scopes a directory

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

That is the whole step. OpenCode auto-discovers `~/.config/opencode/plugin/` and
`~/.config/opencode/plugins/`, file or directory. No `opencode.jsonc` entry is needed to
load it — the `plugins` field is for npm packages like `"cc-safety-net@latest"`.

Restart OpenCode, then:

```
/tether
```

A QR should appear. If it does not, see [Troubleshooting](#troubleshooting).

### 2. The app

```bash
./gradlew :app:installDebug
```

On first launch, open the settings and enter the server address and password. The default is
`http://127.0.0.1:4096`, which only works if the server runs on the phone — it does not.

### 3. Pairing

In the TUI:

```
/tether
```

Scan the QR. **Check the address it shows you** before accepting — that is the only check
that is worth anything. Nothing is transmitted until you press "Authorize".

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
# Plugin — 124 tests, 25 of them against a live `opencode serve` (port 4299)
node --experimental-strip-types --test plugin/tether/*.test.mjs

# App — 502 JVM tests
./gradlew :app:testDebugUnitTest

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
docs/                   design specifications and diagnostic notes
scripts/                pre-publication verification
```

---

## Contributing

1. Fork the repository
2. Create a branch
3. Make your change, with the tests
4. Open a pull request

```bash
node --experimental-strip-types --test plugin/tether/*.test.mjs
./gradlew :app:testDebugUnitTest
```

If your change touches push, pairing or cryptography, say what it fixes. None of those three
has run on hardware yet: a test claiming otherwise deserves a careful read.

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
