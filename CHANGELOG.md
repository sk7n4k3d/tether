# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- In-app QR scanner for pairing: read the `/tether` code without leaving Tether, then the
  same consent screen as `opencode://pair` (CameraX + ZXing, no Google service involved,
  and the camera stays optional)
- Native Android client for OpenCode V2: sessions, live chat, diffs, files, worktrees,
  server inventory, approvals
- Tether plugin: device registry, Web Push over UnifiedPush (RFC 8291/8292), VAPID signing,
  `/tether` pairing QR, paired-devices palette entry
- Pairing by QR with a mandatory confirmation screen showing the server address
- Distributor selection in-app, for any UnifiedPush provider
- Automatic re-declaration of a rotated push endpoint, without a new pairing token
- Six selectable accent colours, with brightness computed to keep the 3:1 contrast ratio
- English and French, following the phone's language, overridable in Settings
- `/tether-config` in the TUI, writing to the plugin's own store rather than
  `opencode.jsonc`
- Instrumentation tests: the contrast measured on the device, the locale actually
  applied to the context
- Full internationalisation: 433 strings in English and French, including error messages
  prepared outside the component tree
- Turn-end summaries: the plugin sends the turn to an OpenAI-compatible endpoint and puts
  the returned title and sentence in the notification. Unconfigured, the raw list of
  actions is sent instead — never a bare "turn finished"
- Progress steps name the tool **and** its input (`bash : npm install`), read from the
  tool call itself rather than from a generic label

### Fixed

- The notification summariser was declared in the configuration, documented in the README,
  and never wired: every turn ended with the literal text "Tour termine". It is now called,
  and a failure of the summariser falls back to the raw turn rather than losing the alert
- Turn-end de-duplication was keyed on the session, so a session notified **once** whatever
  the number of turns. The key is now (session, end of turn)
- Every `session.tool.*` event published: three encrypted pushes per tool for a single
  displayed step. Only `session.tool.called` publishes now
- Configuration set from the TUI was never read back by the server: `resolveConfig` was
  called with `opencode.jsonc` options only, so `serverUrl`, `minSeconds` and the summary
  settings went into a store nobody read
- `debugLogFile` was documented and never written, which made the diagnostic log
  unusable in practice — a background service does not expose its plugins' stdout
- After connecting or pairing, the app stayed on the screen that had just finished:
  `popBackStack()` from the connection screen does nothing when it is the start
  destination, and a successful pairing did not navigate at all. Both now land on sessions
- Pairing from the TUI failed with `RPC pair : HTTP 401`: the plugin called
  `/api/rpc/tether/...` with a hand-rolled `fetch` that resolved authentication from
  `ctx.client.getConfig()` — a method the TUI client does not have. Every request went
  out without an `Authorization` header. It now calls the client's own `rpc`, which
  carries the session's credentials
- The TUI plugin failed to load with `Keymap.Provider is missing`: `keymap.layer` was
  called from `setup`, which runs after an `await` and therefore outside the component
  tree. It now runs from a slot that is mounted inside it.
- The accent colour was a compile-time constant in 26 files; it is now a
  `CompositionLocal` fed by the user's choice, so it survives a restart and can change
- A corrupted preferences file killed the app at startup: `DataStore` propagates read
  failures. Preference reads now fall back to defaults and the app starts.
- A missing space between two concatenated strings, in 20 places: Android trims
  whitespace at the edges of a string resource, so the separator belongs in the code
- `approvalsSummary` displayed a literal `%1$s`: the string expected a count the branch
  did not pass
- About a hundred French strings that were missed by the first extraction, showing as
  French fragments inside an English screen
- Twelve strings showing a literal `%1$s`: the argument was lost during extraction, and
  no compilation error says so
- « sur 1 sessions »: the `(s)` workaround replaced by real French plurials, where the
  singular covers zero as well as one

### Security

- Pairing link validation: `https` only except loopback, whole-host comparison, strict
  token shape, malformed percent-escaping rejects the whole link
- An endpoint is treated as a write capability: distributor choice is a visible setting
- No default in the plugin points at any infrastructure, and no password has a default value
