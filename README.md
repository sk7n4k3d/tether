<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="110" alt="Tether"/>
</p>

<h1 align="center">Tether</h1>

<p align="center">
  <strong>A native Android client for OpenCode V2 — Web Push over UnifiedPush, no Google account</strong>
</p>

<p align="center">
  <a href="https://github.com/sk7n4k3d/tether/actions/workflows/ci.yml"><img src="https://github.com/sk7n4k3d/tether/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/sk7n4k3d/tether/blob/master/LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue?style=for-the-badge" alt="License"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=for-the-badge&labelColor=0a0e1a" alt="Android">
  <img src="https://img.shields.io/badge/push-UnifiedPush-00ff88?style=for-the-badge&labelColor=0a0e1a" alt="UnifiedPush">
</p>

**Tether** is a native Android client for [OpenCode](https://github.com/sst/opencode) V2 — no
WebView, it talks directly to the server's API. Approvals, chat, diffs and files from your
phone. Notifications are standard **Web Push (RFC 8291/8292)** through
[UnifiedPush](https://unifiedpush.org/): no FCM, no Google account, no relay to run. The
server encrypts every push itself — the distributor never sees plaintext.

The app answers one question first: *the agent is blocked and asking for something.* A
session can wait for hours because nobody saw the question. Tether makes the phone ring.

---

## Install in four steps

**Requirements**: Android 8.0+, OpenCode V2 running as a server, JDK 17 to build.

### 1. Install the plugin (on the server)

```bash
opencode plugin add git+https://github.com/sk7n4k3d/tether.git
```

Restart OpenCode. Do **not** copy the plugin files by hand — `plugin add` also installs
the two dependencies the in-terminal dialog needs, a plain `cp -r` loads half a plugin.

### 2. Install the app (on your machine, phone connected by ADB)

```bash
./gradlew :app:installDebug
```

### 3. Connect and pair

```bash
opencode pair
```

This prints the server address and password — scan the QR or type them into the app.
**That QR contains your server password**: treat the terminal as a secret from that
moment.

Then, in the OpenCode TUI, run `/tether` and scan the QR **with Tether** (its scanner
lives in **Settings → Pair a device**). Check the address shown on screen before
accepting — nothing is sent until you press *Authorize*.

> `/tether` and `opencode pair` are two different QRs, and mixing them up does nothing:
> the first authorizes **push** on one device, once, with a 128-bit token — the password
> never leaves the terminal. The second carries the credentials themselves.

### 4. A distributor, for notifications (optional)

Install any UnifiedPush distributor — [ntfy](https://ntfy.sh) is the common one — then
pick it in **Settings → Notifications → Change distributor**. Without one, the app works
fully; it just receives no notifications.

**Done.** Everything below is optional configuration.

---

## Features

- **Live chat over SSE** — reasoning, tool calls and diffs rendered natively; interrupt the
  agent from the phone; send prompts with model and agent selection
- **Approvals in one queue** — tool permissions and interactive forms side by side,
  answered from the phone, with a persistent notification while a decision waits
- **Diffs, worktrees and files** — per-file diffs, isolated worktrees, read a file before
  it leaves the machine
- **Server inventory** — models, agents, MCP servers, plugins, granted permissions
- **Notifications that say what happened** — turn-end pushes carry a title and a
  summary; progress steps are silent and replace each other
- **Dark Material 3, English and French** — seven accent colours, brightness computed to
  clear 3:1 contrast (a test fails the build otherwise)

Push details worth knowing: any UnifiedPush distributor works, endpoints rotate
automatically, and each alert type (turn end, attention needed, progress) is configured
independently.

---

## Configuration

The plugin runs with no configuration: anything unconfigured is disabled and the log
says so. No default points at anyone's infrastructure.

### The one required setting

The address inside the `/tether` QR must be reachable **from the phone** — not
`127.0.0.1`. Set it:

```bash
export TETHER_SERVER_URL=https://opencode.example.com:4096
```

### Turn-end summaries (recommended)

A notification that says what the turn *did* is worth waking up for. With a summary
endpoint configured, the plugin sends the turn — assistant text plus tool calls — to any
OpenAI-compatible endpoint and puts the returned title and sentence in the notification:

```jsonc
// opencode.jsonc — the entry MUST be an object, not a bare string, not a ["pkg", {}] pair
{
  "plugins": [
    {
      "package": "git+https://github.com/sk7n4k3d/tether.git",
      "options": {
        "serverUrl": "https://opencode.example.com:4096",
        "summaryUrl": "http://localhost:8080/v1/chat/completions",
        "summaryKeyFile": "/home/you/.config/opencode/summary-key",
        "summaryModel": ""
      }
    }
  ]
}
```

⚠️ **Only this shape works.** A bare string gives `options = {}`; the `["spec", {…}]` pair
is rejected as `kind=invalid` and the plugin is skipped **silently** — measured on
OpenCode 2.0.12, not assumed.

How summaries behave:

- **The push leaves before the summary.** You get the turn's actions immediately; the
  title and sentence replace it when the model answers. A slow summariser never delays
  the notification.
- **No default endpoint, no default model** — nothing points at anyone's infrastructure.
- **A failed summary is not a lost notification**: any error falls back to the raw turn.
- **Long turns are split, not truncated** — fragments are cut on line boundaries,
  summarised one sentence each (cached by content hash), then joined; the thesis falls
  back to the sentences, never to raw kilobytes in a notification.

The same settings can be set from the TUI with `/tether config`, or via environment
variables.

### All environment variables

| Variable | Effect |
|---|---|
| `TETHER_SERVER_URL` | The address written into the QR — must be reachable from the phone |
| `TETHER_MIN_SECONDS` | Minimum turn duration before notifying |
| `TETHER_MAX_BYTES` | Truncation of the notification text |
| `TETHER_SUMMARY_URL` | OpenAI-compatible endpoint for summaries |
| `TETHER_SUMMARY_KEY_FILE` | File holding the key — the path, never the key |
| `TETHER_SUMMARY_MODEL` | Model to ask for; empty lets the endpoint route |
| `TETHER_VAPID_KEY_FILE` | P-256 VAPID key in PEM — required **only** by FCM-based distributors |
| `TETHER_DEBUG` | `1` enables the diagnostic log |
| `TETHER_DEBUG_LOG_FILE` | Where that log goes |

---

## Troubleshooting

**`/tether` does nothing.** The plugin did not load — reinstall it with
`opencode plugin add` (not a manual copy), then restart OpenCode.

**The QR contains `127.0.0.1`.** Export `TETHER_SERVER_URL` with an address your phone
can reach, and run `/tether` again.

**"This QR expired or was already used."** Tokens are single-use and short-lived. Run
`/tether` again and rescan.

**"No push distributor has provided an access point yet."** Install a UnifiedPush
distributor (step 4 above), then come back.

**"Android is blocking them."** Grant `POST_NOTIFICATIONS` — Android 13+ never asks on
its own, and without it nothing appears, silently.

**Registration fails with 401.** The password in your app settings does not match the
server. Re-run `opencode pair` and retype it.

**After editing `opencode.jsonc`, the plugin ignores the change.** The config watcher
reloads plugins, but if the plugin was reinstalled by hand, clear the install cache
first: `rm -rf ~/.cache/opencode/npm/git-tether-*`, then remove and re-add the plugin.
And any plugin update needs the same cache clear — otherwise the lock file reinstalls
the old commit.

---

## Development

```bash
# Plugin — 180 tests under node (29 need a live `opencode serve` and skip otherwise),
# plus 5 under Bun for the JSX dialog. Use `npm test`, the file list is explicit:
npm test
bun test plugin/tether/tui-setup.test.mjs

# App — 551 JVM tests
./gradlew :app:testDebugUnitTest

# APK
./gradlew :app:assembleDebug
```

- `plugin/tether/qr-verify.sh` checks the QR encoder against `com.google.zxing:core`
  over 130 deterministic cases — zxing decodes but does not render, and a server plugin
  carries no native dependency.
- `plugin/tether/vapid.test.mjs` verifies VAPID signatures, including the DER-versus-R‖S
  trap that yields "notifications silently lost" with no error anywhere.
- `scripts/check-publie.sh` blocks publication of personal data; the forbidden patterns
  live in `TETHER_GREP_FORBIDDEN`, outside the repository.

```
app/                    Android client (Kotlin, Compose, Hilt, Ktor)
plugin/tether/          server plugin: RPC, pairing, Web Push, QR encoder, TUI dialog
scripts/                pre-publication verification
```

Contributions: fork, branch, change with tests, PR. If your change touches push, pairing
or cryptography, say what it fixes.

---

## Credits

- [OpenCode](https://github.com/sst/opencode) — the server this client talks to
- [UnifiedPush](https://unifiedpush.org/) — the push protocol, and the reason no Google account is needed
- [zxing](https://github.com/zxing/zxing) — reference implementation the QR encoder is checked against

## License

MIT — Copyright (c) 2026 sk7n4k3d. See [LICENSE](LICENSE).