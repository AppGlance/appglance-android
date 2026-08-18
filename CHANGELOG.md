# Changelog

All notable changes to the AppGlance Android SDK (`app.appglance:appglance`). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org). Every version tag also gets a
[GitHub Release](https://github.com/AppGlance/appglance-android/releases) with the same notes.
The Swift SDK has [its own changelog](https://github.com/AppGlance/appglance-apple/blob/main/CHANGELOG.md).

## [Unreleased]

### Fixed

- Turning collection off now discards what was already queued. `isEnabled = false` is how an app
  honours a consent withdrawal, but the events recorded before the switch was flipped stayed in
  the on-disk queue: an explicit `flush()` shipped them, and turning the switch back on brought
  them back. A client that is not collecting drops the persisted queue and deletes the file at
  startup, and `flush()` and the send loop are gated on the same switch, as every other command
  already was. A closed environment gate is not a withdrawal of consent and still leaves the file
  alone: it stops the queue being loaded, and the build that owns it keeps what it saved during an
  outage. The Swift SDK has held this line since 1.2.0.
- A restored device no longer inherits the install it was restored from. The install id is
  device-bound - it is honoured only where the marker for the device that minted it still
  matches - so a second handset correctly mints its own; the session, the presence stamps and the
  user properties beside it live in the same SharedPreferences, which Auto Backup and a
  device-to-device transfer both carry. The new install read that state as its own. It continued
  the old device's session when the app was opened within `sessionTimeout` of that device's last
  use, so its first visit recorded no `session.start`; and, with no time limit at all, it began
  believing the server already held the old device's user properties, so `identify` with those
  values sent nothing and the install's page in the dashboard stayed empty however often the app
  called it. State left by an install that is not this one is now dropped when an id is minted.

## [1.2.0] - 2026-08-18

### Fixed

- Sessions and presence keep working when the host app removes androidx.startup's
  `InitializationProvider` from its manifest, a documented cold-start trim. `ProcessLifecycleOwner`
  reports nothing without it, so the SDK saw no foreground transition at all: no `session.start`, no
  presence ping and no flush on background, while `install` and every `track` call still shipped.
  The dashboard looked healthy and was quietly wrong. The SDK now notices, watches the app's
  activities directly instead, and logs one line naming the missing provider.
- `trackAppLifecycle = false` on a later `configure` detaches the lifecycle observer. It was honored
  only on the first call, so an app that turned it off to draw its own session boundaries with
  `setActive` kept getting the platform's transitions interleaved with its own.
- A presence ping that is dropped rather than retried no longer spends a whole fresh interval. The
  stamp that paces the next ping is written when the ping is queued, so a batch answered with a
  `5xx` or a `429`, or one whose connection died after connecting, left the install silent for two
  intervals. At the four-minute cadence a free-plan account is asked for, that is longer than the
  dashboard's five-minute presence window, so an app in the foreground the whole time dropped out of
  "active right now" for about three minutes. The next ping is now measured from the last ping the
  server acknowledged.
- The last-real-event stamp is persisted alongside the last-ping stamp, so a relaunch or a second
  `configure` inside the session timeout no longer pings the moment it comes up. A visit shorter than
  one interval leaves no ping stamp behind and a resumed session records no `session.start`, so the
  fresh process had no proof of presence of its own however recently the server had heard from that
  install. Low-RAM devices relaunch inside a visit routinely.
- The install id no longer follows a backup onto a second device. It lives in SharedPreferences
  so that a reinstall on the same account keeps it, but Auto Backup and a device-to-device
  transfer carry those preferences onto a new handset too, where the same id was adopted and two
  devices in use reported as one install. The id is now stored with a hashed marker for the
  device that minted it and is honoured only where that marker still matches; an id stored
  without one adopts the current device rather than being renumbered, so an install set up by an
  earlier version stays the same install. The marker is never sent anywhere.
- A server's `Retry-After` is bounded. It was obeyed as given, so `Retry-After: 86400` parked
  automatic delivery for a day (an explicit `flush()` and the flush on backgrounding still sent).
  It is capped at 15 minutes, the same ceiling the Swift SDK applies: past that it is an outage,
  not rate limiting, and the on-disk queue is the better answer.
- `configure` outside the app's main process records nothing and logs why. `Application.onCreate`
  runs once per process, so an app with an `android:process` component got a second, fully
  independent client on the same queue file and the same preference keys: two writers of one file,
  each rewriting it from its own in-memory queue, and on a first launch two install ids and two
  `install` events for one device.

### Changed

- Configuration values are clamped instead of refused. `heartbeatInterval = 0.seconds` (a
  plausible guess for "no presence pings", which is not a thing the SDK offers) and a zero batch
  size threw out of the `Configuration` constructor, so a number computed at runtime could take
  the host app down on the call that sets analytics up. `flushInterval` is now clamped to 1 s - 1
  h, `heartbeatInterval` to 15 s - 1 h, `sessionTimeout` to 1 s - 24 h and `maxBatchSize` to
  1 - 500, the same bounds the Swift SDK applies: an app that ships a bad number keeps working,
  with a cadence it can live with.
- The README's setup section describes the presence ping the SDK actually sends (one after an
  interval of silence, none while real events are flowing, and a sparser cadence when the server
  asks for one), and its Gradle snippet is at the current version.

## [1.1.0] - 2026-08-17

### Changed

- The presence ping now measures silence, not time. A real event proves the app is in front of
  someone exactly as a ping does (the server moves the same "last seen" and session stamps for
  both), so a `heartbeat` is sent only after `heartbeatInterval` with nothing else sent: the tick
  that used to fire at the start of every session alongside `session.start` is gone, an install
  that keeps sending events never pings, and a quiet one pings once per interval of quiet.
  Nothing on the dashboard changes: "active right now" and session length read the same stamps as
  before. What changes is the bill behind the free presence promise, on the server's side:
  roughly half of all pings were the redundant first one.
- Leaving the foreground after more than a minute of silence sends one closing ping with the flush
  the SDK already does, so a session's length ends where the visit ended instead of at the last
  thing that happened to be sent. At the default cadence the stamp is never that old, so nothing
  extra is sent; it matters when the server asks for a sparser cadence (below).
- The last-ping stamp is persisted, so a kill-and-relaunch inside the interval no longer pings
  again at once (the Swift SDK has done this since 1.0.2).

### Added

- The server may ask for a sparser presence cadence for the account's plan by answering a batch
  with `heartbeat_interval` (seconds). The SDK obeys it as a floor (the effective interval is the
  larger of the configured `heartbeatInterval` and the server's value, so an app that configured a
  longer interval keeps it), remembers it across launches, and ignores values outside 15 s to 1 h.
  Servers that send nothing leave the configured interval in force, so this is fully additive on
  the wire.

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
