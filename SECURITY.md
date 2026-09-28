# Security

## Reporting

Report a vulnerability privately through GitHub's
[security advisory](https://github.com/sk7n4k3d/tether/security/advisories/new) rather than
as an issue.

There is no formal support window for a project at this stage. Fixes land as they are
found.

## Threat model

Tether is a client for a server you run yourself. The interesting boundary is not the
network — everything is HTTP basic auth — but the **pairing link**.

`opencode://pair?s=<server>&t=<token>` is the only input in the app that the user does not
type. Everything else comes from the screen or from an authenticated request. A QR can be
manufactured by a third party: a photo, a screen, a camera. The endpoint Tether sends is a
**write capability** — whoever holds it can push a notification to that phone — so a forged
QR pointing at a third-party server would leak everything the agent notifies.

Three rules follow, and all three are enforced:

1. Nothing is transmitted until the user presses "Authorize".
2. The server address is displayed prominently. Validation prevents the mistake; only
   display lets the user catch the intent.
3. "Refuse" does nothing at all — no request, no write. A refusal must be the easy path.

The same validation runs in the plugin. If only one side validated, the other would be the
hole.

The comparison is GHSA-2xqv-hwrf-983f, where the Home Assistant Companion app executed
automations on a bare scan with no human confirmation.

## What is deliberately absent

No telemetry, no analytics, no crash reporting, no remote configuration. A function that is
not configured is disabled and says so in the log — less pleasant than "it just works", and
the right trade: a configuration that truly works cannot be a coincidence.
