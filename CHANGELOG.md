# Changelog

All notable changes to the AppGlance Android SDK (`app.appglance:appglance`). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org). Every version tag also gets a
[GitHub Release](https://github.com/AppGlance/appglance-android/releases) with the same notes.
The Swift SDK has [its own changelog](https://github.com/AppGlance/appglance-apple/blob/main/CHANGELOG.md).

## [Unreleased]

## [1.0.0] - 2026-08-16

First public release, feature-equivalent with the Swift SDK 1.0.0 and sharing its wire format.

### Added

- `AppGlance.configure(context, apiKey[, debug])`, or `configure(context, Configuration)` for
  full control: intervals, `enabledEnvironments`, `environment` for beta channels,
  `trackAppLifecycle`, `debug`, `endpoint`.
- `track`, `trackScreen`, `identify`, `setUserProperties`, `reset`, `setActive`, `flush`;
  `AppEnvironment`, `Signal`, `UserProperty`.
- A random install id in `SharedPreferences` (inside Auto Backup, so a reinstall on the same
  account usually keeps it). `install` is recorded exactly once, first, stamped at configure.
- Sessions and presence via `ProcessLifecycleOwner`, with a `session_id` on every event and the
  same five-minute timeout the dashboard uses; a heartbeat every 60 s while foregrounded; a flush
  on background. The device's region setting as the country (`collectsCountry`); and anything you
  `track`.
- Optional user properties, merged and clamped to the server's limits (20 keys, 40 / 200
  characters), sent as `user.identify` only on change; `reset` on sign-out.
- Environments: every event is tagged `production`, `beta`, `emulator` or `debug` (stored by the
  ingest beside the Apple tiers). Emulator runs and debuggable builds are detected; a Play testing
  track is opt-in via `environment = AppEnvironment.BETA`. `enabledEnvironments` defaults to
  `{PRODUCTION, BETA}`.
- Debug mode (`debug = true`): the current build sends whatever its environment (the tag stays
  truthful, so those events appear under *All* in the dashboard and never in Live) and logs to
  logcat. Without it, a gated build logs one line explaining why nothing is sent.
- Delivery: events are persisted to `noBackupFilesDir` as they are tracked, sent oldest-first in
  slices of 100 through one serial sender, and retried after transient failures. Every event
  carries a client-minted `event_id` and the ingest ignores replays. Presence pings, which the
  server folds into rollups on arrival, are re-sent only when it provably never saw them -
  including after a process killed mid-request, since the on-disk queue never holds an in-flight
  ping.
- Calls apply strictly in call order on one background thread; calls made before `configure`, or
  before the user's first unlock under Direct Boot, are held (up to 200) and replayed.
- Permanent `4xx` responses drop the slice instead of retrying forever; `413` halves it.
- `minSdk 21`, one dependency (`androidx.lifecycle:lifecycle-process`), explicit-API mode,
  ktlint-clean.
