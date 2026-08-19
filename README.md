# AppGlance for Android

[![CI](https://github.com/AppGlance/appglance-android/actions/workflows/ci.yml/badge.svg)](https://github.com/AppGlance/appglance-android/actions/workflows/ci.yml) ![minSdk](https://img.shields.io/badge/minSdk-21-blue) ![License](https://img.shields.io/badge/license-MIT-lightgrey)

The Kotlin SDK for [AppGlance](https://appglance.app): privacy-first, live analytics for apps.
It answers who is using your app right now, how many opened it today, where they are, and
anything you choose to track - with a random install id as the only identity, no advertising
id, and one call of setup. Sessions and presence are handled for you. The Swift SDK is at
[AppGlance/appglance-apple](https://github.com/AppGlance/appglance-apple).

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("app.appglance:appglance:1.2.0")
}
```

The library is on Maven Central under `app.appglance`, so `mavenCentral()` in your repositories
is all it needs. The `INTERNET` permission comes with the library's manifest.

| Requirement | Minimum |
|---|---|
| Android | 5.0 (API 21) |
| Kotlin / JDK for building | 2.x / 17 |
| Dependencies | `androidx.lifecycle:lifecycle-process` (2.8) |

The release AAR is about 100 KB (some 600 DEX method references) and pulls in nothing an
AndroidX app does not already have.

## Set up

Create an app in the [dashboard](https://appglance.app), copy its write key, and configure the
SDK in `Application.onCreate()`:

```kotlin
import android.app.Application
import app.appglance.AppGlance

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGlance.configure(this, "glance_live_…")
    }
}
```

Then name that class in your `AndroidManifest.xml`, or Android never builds it:

```xml
<application android:name=".MyApp" …>
```

Android instantiates `android.app.Application` unless the manifest says otherwise, so without
that attribute `onCreate` above never runs, `configure` is never called, and the SDK sends
nothing and logs nothing - there is no SDK running to log it. If your app already has an
`Application` subclass, put the `configure` call in that one rather than adding a second.

That is the whole setup. The SDK watches the app's foreground/background state through
`ProcessLifecycleOwner`: it records `session.start` when the app comes to the front after more
than five minutes away, sends a presence ping once the app has been in front for a minute with
nothing else sent - a real event proves presence exactly as a ping does, so an app that is
sending events never pings - and flushes when it leaves. The server may ask for a sparser cadence
for your account's plan, and the SDK then uses that. Brief interruptions do not start a new
session; neither does a kill and relaunch inside the timeout. Nothing to attach to activities.

**See yourself on the dashboard while integrating.** By default emulator runs and debuggable
builds send nothing, so your numbers only ever contain real installs. Turn on debug mode while
you wire things up:

```kotlin
AppGlance.configure(this, "glance_live_…", debug = BuildConfig.DEBUG)
```

This build now sends too - events tagged `emulator` / `debug`, visible under **All** in the
dashboard's scope switch, never in Live - and the SDK logs to logcat (tag `AppGlance`): the
environment and install id at configure, each event as it is queued, each send and what the
server said. Without debug mode, a gated build logs exactly one line saying it is not sending
and why.

## Track things

```kotlin
AppGlance.track("paywall.viewed", mapOf("source" to "settings"))   // any lowercase.dotted name; ≤ 20 string keys
AppGlance.trackScreen("paywall")                                     // records screen.paywall - the cheapest funnel step
```

Screens, wherever a screen appears:

```kotlin
// Compose
LaunchedEffect(Unit) { AppGlance.trackScreen("paywall") }

// Activity / Fragment
override fun onResume() { super.onResume(); AppGlance.trackScreen("paywall") }
```

Keep names short and stable, and never put personal data in a signal or its metadata.

## Who, if you choose

```kotlin
AppGlance.identify(id = account.id, email = account.email, name = account.name)   // labels on the install
AppGlance.setUserProperties(mapOf("plan" to "pro"))                                // free-form, filterable
AppGlance.reset()                                                                  // on sign-out
```

The install id stays the analytics identity; these are labels merged onto it. Calling
`identify` with the same values on every launch is free - only a change is sent, as
`user.identify` (never billable). Reserved keys `$id`, `$email`, `$name`; up to 20 keys of 40
characters, values up to 200; an empty string removes a key. Passing an email or a name changes
your Play Data safety answers - see below.

## Configuration

```kotlin
AppGlance.configure(this, AppGlance.Configuration(
    apiKey = "glance_live_…",
    enabledEnvironments = setOf(AppEnvironment.PRODUCTION),   // narrow: production only
    environment = if (BuildConfig.FLAVOR == "beta") AppEnvironment.BETA else null,
    heartbeatInterval = 120.seconds,
))
```

From Java, where Kotlin's default arguments and `Duration` are both out of reach, the same
knobs are on `AppGlance.Configuration.Builder`; the intervals are whole seconds:

```java
AppGlance.configure(this, new AppGlance.Configuration.Builder("glance_live_…")
    .enabledEnvironments(EnumSet.of(AppEnvironment.PRODUCTION))
    .heartbeatIntervalSeconds(120)
    .build());
```

| Option | Default | Notes |
|---|---|---|
| `flushInterval` | `10.seconds` | Wait before sending a partial batch. Clamped to 1 s - 1 h. |
| `maxBatchSize` | `20` | Send at once when this many events are queued. Clamped to 1 - 500, the largest batch the ingest API accepts. |
| `heartbeatInterval` | `60.seconds` | Seconds of silence in the foreground before a presence ping (drives "active now"). A real event resets it; the server may raise it for the account's plan. Never billable. Clamped to 15 s - 1 h: there is no way to switch presence off here. |
| `sessionTimeout` | `5.minutes` | Away longer than this and coming back is a new session - the dashboard splits on the same gap. Clamped to 1 s - 24 h. |
| `isEnabled` | `true` | Master off-switch (e.g. behind a user setting). Wins over everything, including `debug`. Turning it off also discards whatever an earlier run left queued on disk and the user properties `identify` stored, so a consent withdrawal covers what was already recorded, not just what comes next. |
| `collectsCountry` | `true` | The device's region *setting* (system locale) as a two-letter code. Not GPS, not IP. |
| `enabledEnvironments` | `{PRODUCTION, BETA}` | Which environments send; emulator runs and debuggable builds never do by default. |
| `environment` | `null` (auto) | Android cannot tell a Play testing track from production - pass `AppEnvironment.BETA` in that build (a flavor is the natural place). Emulator and debuggable are always detected. |
| `trackAppLifecycle` | `true` | Automatic sessions via `ProcessLifecycleOwner`. Off → call `AppGlance.setActive(true/false)` yourself. |
| `debug` | `false` | Sends from any environment (tag stays truthful) and logs to logcat. |
| `endpoint` | hosted ingest | Point it at your own deployment of the ingest service. |
| `appId`, `appVersion` | package name, `versionName` | Informational in hosted mode (the key identifies the app). |

Environments on the wire are the platform-neutral names `production` / `beta` / `emulator` /
`debug`; the ingest stores them beside the Apple tiers, so a Play build is "Live" in the
dashboard exactly like an App Store build.

## Guarantees

- Every public call is cheap and non-blocking. Calls apply strictly in call order on one
  background thread, timestamps are taken at call time, and calls made before `configure` (or
  before the user's first unlock under Direct Boot) are held - up to 200 - and replayed.
- The install id is a random UUID in the app's `SharedPreferences`, inside Auto Backup, so a
  reinstall on the same account usually keeps it. It is stored with a marker for the device that
  minted it, so a backup or a transfer restored onto a second handset mints a fresh id there
  rather than reporting two devices as one. `install` is recorded exactly once, first.
- Events are persisted to `noBackupFilesDir` as they are tracked, so a crash loses nothing. The
  queue is capped at 500 (oldest dropped), sent oldest-first in slices of 100, one send at a
  time. `429`, `5xx` and offline keep the batch for later, with exponential backoff between
  automatic retries (a numeric `Retry-After` on any answered status is honored, up to fifteen minutes); `413` halves it;
  any other `4xx` (a wrong key, say) drops that slice rather than wedging the queue.
- Retries never double-count. Every event carries a client-minted id and the ingest ignores
  replays; the presence ping - which is folded into rollups on arrival - is re-sent only when the
  server provably never saw it, and the on-disk queue never holds a ping that is in flight. A ping
  dropped instead of retried stops pacing the next one, so presence is proved again rather than
  waiting out a second interval.
- Collection runs in the app's main process only. `Application.onCreate` runs once per process, and
  one install's queue file, session and presence state cannot be shared by two of them, so
  `configure` from a component with `android:process` logs one line and records nothing. Track from
  the main process instead.

## Google Play Data safety

Default setup: **Device or other IDs** (the random install id) and **App interactions** -
collected, not shared, encrypted in transit, deletable on request (the dashboard's user page has
a delete), purpose Analytics. With `identify(email / name)`: add **Personal info → Name / Email
address**. If you set a plan or tier property: **Purchase history**. Never the advertising id,
IP, or precise location.

## Development

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # any JDK 17+
./gradlew :appglance:testDebugUnitTest      # JVM tests with in-memory fakes + Robolectric for the Android glue
./gradlew :appglance:assembleRelease        # appglance/build/outputs/aar/appglance-release.aar
```

`Client.kt` is the framework-free core (queue, delivery, sessions, properties); `AppGlance.kt`
is the public facade and its ordered command queue; `AndroidPlatform.kt` holds everything that
touches the framework, behind the small interfaces in `Platform.kt`, so the core is tested
without Android. `LiveIngestSmokeTest` runs only with `APPGLANCE_SMOKE_KEY` set and proves the
wire format against the real ingest with silent, non-billable signals.

## Documentation and support

- Guides and the HTTP API: [appglance.app/docs](https://appglance.app/docs)
- Release notes: [CHANGELOG.md](CHANGELOG.md) and [GitHub Releases](https://github.com/AppGlance/appglance-android/releases)
- Questions or problems: [open an issue](https://github.com/AppGlance/appglance-android/issues) or
  email [support@appglance.app](mailto:support@appglance.app)
- Contributing: [CONTRIBUTING.md](CONTRIBUTING.md) · Security: [SECURITY.md](SECURITY.md)

## License

MIT - see [LICENSE](LICENSE).
