# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Native Android client for OpenCode V2: sessions, live chat, diffs, files, worktrees,
  server inventory, approvals
- Tether plugin: device registry, Web Push over UnifiedPush (RFC 8291/8292), VAPID signing,
  `/tether` pairing QR, paired-devices palette entry
- Pairing by QR with a mandatory confirmation screen showing the server address
- Distributor selection in-app, for any UnifiedPush provider
- Automatic re-declaration of a rotated push endpoint, without a new pairing token

### Security

- Pairing link validation: `https` only except loopback, whole-host comparison, strict
  token shape, malformed percent-escaping rejects the whole link
- An endpoint is treated as a write capability: distributor choice is a visible setting
- No default in the plugin points at any infrastructure, and no password has a default value
