# Changelog

All notable changes to the AppGlance Android SDK (`app.appglance:appglance`). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org). Every version tag also gets a
[GitHub Release](https://github.com/AppGlance/appglance-android/releases) with the same notes.
The Swift SDK has [its own changelog](https://github.com/AppGlance/appglance-apple/blob/main/CHANGELOG.md).

## [Unreleased]

### Fixed

- A dropped presence ping's replacement keeps its distance when the visit ends in between. The
  15 second floor between a dropped ping and the ping that replaces it was enforced only by the
  running presence timer, and the timer runs only while the app is in front: a ping dropped by
  the flush on the way to the background left nothing behind but the rolled-back stamp, so coming
  back seconds later ticked at once, and a kill and relaunch inside the interval did the same
  with no live state at all. If the dropped ping had in fact been counted - the ambiguity that
  makes dropping the safe choice - the two ticks landed seconds apart in rollups the server folds
  additively and never dedupes. The floor now lives in the stamp itself, which survives both: the
  replacement is due 15 seconds after the ping it replaces, however the visit ends. The Swift SDK
  makes the same change.
- A cadence floor that lands after the client is retired is no longer adopted. A second
  `configure` retires the client, but a request already on the wire cannot be recalled, and its
  answer can carry the server's `heartbeat_interval` - which was written to a preference key the
  replacement client had already read at its own init. Every other answer that can land after
  shutdown was already refused; this one now is too. The Swift SDK closes the same gap, along
  with its user-properties twin, which this SDK already guarded.

## [1.2.1] - 2026-08-19

### Changed

- Automatic delivery backs off further during a long outage. The retry ceiling stays at 60 seconds
  for the first ten consecutive failures and widens to five minutes past that: ten attempts in, a
  server is having an outage rather than a blip, and retrying every 30 to 60 seconds for the length
  of it re-uploads the same head slice at an ingest that can least absorb the herd. Nothing is lost
  by waiting, because the queue is on disk and `flush()`, the flush on the way to the background
  and the next tracked event all ignore the window. The Swift SDK has widened its ceiling at the
  same streak since 1.2.0, so the two agree again.

### Fixed

- A queue the process was killed in the middle of writing is recovered instead of read as empty.
  `AtomicFile` moves the previous copy to a `.bak` name for the length of a write and restores it
  on the next read, so a kill between those two moments leaves the only good queue under that name
  and nothing under the one the store looked for. The store answered from the base file's presence
  alone and reported an empty queue in exactly the case the backup exists to survive, dropping
  every event in it.
- A queue write the device could not finish is no longer reported as landed. `AtomicFile.finishWrite`
  logs a failure to close and swallows it, so a full disk or a container made read-only could leave
  the store saying the bytes were written. The client records what a write reported landing and
  skips an identical write against that record, so the cost was not one lost queue but every repair
  of it declined until the bytes changed. The write is flushed and synced where an `IOException`
  still reaches the caller.
- The 500-event queue cap holds on the launch after a crash. The file holds what is OWED, which is
  the queue plus the non-ping half of the slice that was on the wire, so it can carry a whole
  request more than the cap. It was restored whole and the queue started that launch at up to 600,
  and stayed there until something else was tracked. It is trimmed on the way in, oldest first,
  the same end the cap drops from everywhere else. The Swift SDK trims on the same path.
- The documented `Retry-After` rule matches what the SDK does. 1.2.0 widened the header to any
  answered status, but its own note and the README both described the narrower `429`-only rule
  that the SDK had up to 1.1.0, and the Swift changelog repeated it. The code was right and three
  documents were wrong. They now say any answered status.

## [1.2.0] - 2026-08-19

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
- A first launch that cannot send no longer costs the install its `install` event. The id is
  minted and stored before anything knows whether this build collects, and a launch behind a
  closed environment gate (the default excludes debuggable builds and emulators) or with
  `isEnabled = false` while the app waits for consent records nothing. Every later launch found
  the stored id, so the one launch that could have recorded the event was already over and the
  install never appeared at all. A launch that mints the id and cannot record now notes the debt,
  and the first launch that is collecting records `install`, stamped with its own `configure`.
  The Swift SDK holds the same line.
- The user-properties snapshot records what the server acknowledged, not what was queued.
  `identify` committed the merged set the moment it queued the `user.identify` carrying it, and
  only a change is ever sent, so any event lost after that froze the install's properties for
  good: the 500-event cap trimming the oldest, a permanent `4xx` dropping the slice, or the ingest
  answering `202` while storing nothing (past the plan's grace ceiling, or under the per-install
  rate limiter). The install's page in the dashboard then stayed blank however often the app
  called `identify` with the same values, which the documentation tells it to do at every launch.
  The snapshot now moves only when a batch carrying that event comes back accepted and counted
  whole; what an event still owed will leave behind is read from the queue itself, so an
  `identify` made while an earlier one is in flight merges on top of it instead of re-sending it,
  and a repeat of values the server really has is still free.
- Withdrawing consent now clears the user properties as well as the queue. `isEnabled = false`
  discarded the queued events, but `$email`, `$name` and `$id` stayed in SharedPreferences, inside
  Auto Backup, and `reset()` - the only other thing that clears them - records nothing on a client
  that is not collecting. So the natural order of honouring a withdrawal, turn collection off and
  then forget the person, left them on disk indefinitely. They are deleted with the queue now,
  keyed on `isEnabled` alone: a closed environment gate is not a withdrawal and still leaves both
  alone. The install id is untouched, so turning collection back on is the same install rather
  than a new one.
- A presence stamp the clock has not reached yet no longer silences the heartbeat. The last-ping,
  last-event and last-active stamps were restored from disk without a sanity check, unlike the
  server's cadence floor beside them, so a device whose clock was hours ahead when they were
  written measured a negative silence after the correction and never owed a ping again, on that
  launch and on every launch after it. Presence is now measured from a stamp only while that stamp
  can be true, the wait between pings is never longer than one interval whatever the arithmetic
  says, and a stamp from the future is neither restored nor rolled back to. A visit that records
  nothing at all now ends with the closing ping the Swift SDK already sent, so both SDKs report
  the same session length for the same visit.
- A second `configure` while the app is in the foreground no longer leaves the replacement client
  inactive for the rest of the visit when the app drives `setActive` itself. The lifecycle bridge
  hands the replacement the foreground state it can see, but `trackAppLifecycle = false` detaches
  it, and the app's own `onStart` fired long before the consent switch was touched - so a client
  built on the settings screen recorded no `session.start`, sent no presence ping and did not
  flush on the way to the background. The last foreground state anybody reported now travels with
  the swap, whichever side reported it.
- A session id pre-minted by a process that died before its first foreground is adopted by the
  next launch whatever the gap since the last activity looks like. The gap was measured first and
  won, so a clock corrected backwards between the two processes left that id unadopted and the
  events already queued under it in a session the server was never told about. The Swift SDK has
  always read the unadopted id first.
- A batch rejected for good no longer costs the install a whole interval of presence. A permanent
  `4xx` drops the slice rather than putting it back, and the ingest rejects a batch like that
  before it reads a row, so any presence pings in it were provably never counted - but their stamp
  was left in place, so the next ping was not due for a full interval. At the four-minute cadence a
  free-plan account is asked for, two of those back to back are longer than the dashboard's
  five-minute presence window, so an install that never left the foreground dropped out of "active
  right now" for about three minutes and its session was cut short. The stamp is now rolled back to
  the last ping the server acknowledged, which is what the retryable path has done since 1.2.0.
- An automatic flush no longer retries inside the backoff it should be obeying. The flush timer
  read the backoff on the command thread and then asked the send loop to drain, so a timer that
  fired while a request was still on the wire saw no backoff at all: the drain it queued waited
  behind that request and then ran the instant it failed, going straight at a server that had just
  asked for room. One outage counted as two consecutive failures, so the streak, and the window it
  sets, doubled every cycle. The backoff is now read where the send actually starts, and a drain
  that finds a window in force re-arms the flush timer instead of sending. An explicit `flush()`
  still always attempts.
- `install` is no longer stamped later than the calls that were made before `configure`. Calls made
  before the SDK is configured are held and replayed, and each keeps the moment the app made it,
  while `install` was stamped with `configure` - so an app that tracks from a `ContentProvider` or
  a library initializer, both of which run before `Application.onCreate`, sent an `install` dated
  after an event that provably preceded it, and the platform's first-seen rollup takes the smallest
  timestamp an install ever sends. `install` now carries the earliest moment the SDK holds for that
  install, which is `configure` unless calls made before it are still waiting.
- User-property keys and values are no longer cut through the middle of a surrogate pair. The cut
  is made in UTF-16 code units, which is the unit the ingest counts in, so the two agree on where
  it falls - except when it falls between the two halves of a character outside the basic plane, an
  emoji or a flag. What was left ended in a lone high surrogate, which UTF-8 encoding turns into
  `?` on the way out and which the ingest strips on the way in, so the server could only store
  something the SDK did not have; and because only a change is ever sent, no later `identify` with
  the same values could correct it. The orphaned half is dropped now, which is what the ingest and
  the Swift SDK both do with it.
- A minted install id that the store did not keep is no longer reported as a new install. The id
  was claimed as new without asking whether the write landed, and a device that cannot write - a
  full data partition - then minted a different id on every launch and recorded an `install` for
  each, so one device arrived as an unbounded stream of users that nothing on the server could
  collapse: every one of them carries a different id. The store now reports whether the id reached
  disk, which is the one thing `SharedPreferences.commit` can say and the in-memory value cannot,
  and the id is read back before it is claimed. A run whose id nothing kept uses it for this
  launch's events and records no install, the same trade the unreadable-store case already made.
- A second session opened inside one process now writes its id down before the event that carries
  it. `track` persists the queue as it records, and the `session.start` was queued, and written,
  ahead of the id reaching preferences - so a process killed in that window, a force-quit as the
  app is coming back, left a start for a session nothing on disk named, and the next launch inside
  the timeout resumed the id before it and filed the whole visit under a session whose start was
  never sent. The pre-minted id was already written in this order; the in-process mint was the one
  that was not.
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

### Added

- `AppGlance.Configuration.Builder`, so a Java-only app can configure the SDK at all. Kotlin
  default arguments and `kotlin.time.Duration` are both invisible from Java, so a `Configuration`
  could be built only from Kotlin: a Java app could pass the write key and `debug` to `configure`
  and reach nothing else - not `isEnabled`, not `enabledEnvironments`, not the intervals. The
  builder takes whole seconds for the three intervals, goes through the same constructor and so
  the same clamps, and is compiled against from Java in the test suite, so the surface cannot
  quietly go away again. It adds about 3 KB to the AAR.

### Changed

- A burst of events leaves the device as one delivery rather than one request per event. The send
  loop is fed by the same queue the app is writing to, so an app recording as fast as the network
  answers - a screenful of items, a replayed queue of user actions - had every round after the
  first find exactly the one event tracked during the last round trip and send it on its own: a
  full set of request headers and a round trip each. A delivery now sends what was owed when it
  began, and the batch-size trigger asks for one delivery at a time rather than one for every
  event past the threshold. Anything tracked after a delivery begins goes with the next one, which
  the delivery arms before it returns. The Swift SDK bounds its drain the same way.
- A retryable failure arms its own retry. Asking for a delivery cancels the flush timer on the way
  in, and the batch-size trigger asks for one delivery at a time, so the batch a failure handed
  back, and any slice the delivery's bound did not reach, had no trigger left of its own: an app
  that went quiet after a burst sat on a full queue until something else happened to it, which on
  a device put down for the night is the next launch. The timer is armed for the window the
  backoff chose before the delivery returns.
- The offline queue file is no longer rewritten by a delivery that cannot change what is owed.
  Claiming a slice with no presence ping in it, and handing that same slice back after a
  transient failure, both leave the file saying exactly what it already said, and each used to
  pay a full atomic rewrite, which costs about as much for four hundred bytes as for two hundred
  kilobytes. What is written, and the moment at which a tracked event becomes durable, are
  unchanged: the record the client skips against is the bytes a write reported landing, so a
  store that refuses one repairs the file on the next write rather than skipping it. The Swift
  SDK makes the same change.
- Publishing a release is now gated on CI: the release workflow runs the lint, build and test job
  first and publishes only when it passes, as the Swift SDK has done since 1.0.1. A version tag
  runs that job exactly once, through the release workflow.
- The `Signal.HEARTBEAT` documentation describes the presence ping the SDK actually sends: one
  after `heartbeatInterval` of silence in the foreground, and none while real events are flowing.
  It still promised a ping every interval, which the SDK stopped sending in 1.1.0. The README's
  setup section was corrected in 1.2.0; this is the doc comment beside it.
- A numeric `Retry-After` is honoured on any answered status, not on a `429` alone. A `503` that
  states one was ignored, and the SDK backed off on its own schedule instead, which is the case
  the header exists for: a maintenance window or a load shed asking for room. The fifteen minute
  clamp still bounds it, and `flush()` and the flush on the way to the background still ignore the
  window entirely. The Swift SDK makes the same change.
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
