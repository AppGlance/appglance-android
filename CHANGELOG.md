# Changelog

All notable changes to the AppGlance Android SDK (`app.appglance:appglance`). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org). Every version tag also gets a
[GitHub Release](https://github.com/AppGlance/appglance-android/releases) with the same notes.
The Swift SDK has [its own changelog](https://github.com/AppGlance/appglance-apple/blob/main/CHANGELOG.md).

## [Unreleased]

## [1.0.1] - 2026-08-17

### Fixed

- The `install` event, and anything else tracked before the app first reaches the foreground, now
  carries the same `session_id` the first `session.start` carries. The coming session's id is
  minted at configure whenever the next foreground will start a new session, and persisted until
  a `session.start` adopts it, so even a launch that never reaches the foreground hands it to the
  next one. It used to be minted only at the first foreground, which left those early events
  without a session and made the server create a second session row on every first launch.
- Calling `configure` again while the app is in the foreground (the documented way to apply a
  consent change) resumes the session immediately. The replacement client used to stay inactive
  until the app was backgrounded and reopened.
- A device whose build fingerprint is just `unknown` (some OEM and custom ROM builds) is no
  longer classified as an emulator on that alone. The hardware and product checks still catch
  real emulators that report it.

### Added

- Configuration validation at construction: `heartbeatInterval` at least 15 seconds,
  `maxBatchSize` between 1 and 500, `flushInterval` and `sessionTimeout` positive. A zero
  interval was a tight send loop; failing fast with a clear message beats misbehaving quietly.
- Exponential backoff between automatic delivery retries after a transient failure, jittered and
  capped at 60 seconds, honoring a numeric `Retry-After` on 429 as the floor. A queue past
  `maxBatchSize` used to retry on every new event with no throttle. An explicit `flush()` still
  sends immediately, and the first successful send resets the backoff.

### Changed

- Environment detection checks the debuggable flag before the emulator heuristics, the order the
  platform contract lists. A debuggable build on an emulator is now tagged `debug` rather than
  `emulator`; both are kept out of your numbers by default, so nothing changes unless you send
  from one with `debug = true`.

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
