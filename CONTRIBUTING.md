# Contributing

Small, focused pull requests are welcome; open an issue first for anything larger so we can
agree on the shape.

- `./gradlew :appglance:testDebugUnitTest` must pass (JDK 17+; CI runs it on Ubuntu, along with
  `assembleRelease` and `ktlint`). The code style is `.editorconfig`'s (`intellij_idea`, 120
  columns); `ktlint -F "appglance/src/**/*.kt" "*.kts" "appglance/*.kts"` formats it.
- Keep the public API small. Explicit-API mode is on, and every public symbol needs a doc comment
  written for an app developer.
- **On-disk names are frozen**: the `app.appglance` preferences file, its `installId` key, and the
  queue file under `noBackupFilesDir/appglance`. Changing any of them orphans every existing
  install's state and silently doubles the user count.
- This SDK and the [Swift SDK](https://github.com/AppGlance/appglance-apple) implement one
  wire-format contract. Anything that changes what goes over the wire - field names, environment
  values, retry semantics - has to change in both. Open an issue before starting.
- `LiveIngestSmokeTest` is skipped unless `APPGLANCE_SMOKE_KEY` is set. Point it at a throwaway
  app of your own; it sends only silent, non-billable signals.
- Commit messages: `feat:` / `fix:` / `docs:` / `chore:`, with the *why* in the body.

## Releasing

1. Bump `version` in `appglance/build.gradle.kts`, move the `[Unreleased]` entries in
   `CHANGELOG.md` under a new `## [x.y.z] - YYYY-MM-DD` heading, and commit.
2. Re-measure the footprint. The README quotes it ("a little over 100 KB", "~600 DEX methods",
   one AndroidX dependency), and a stale number is worse than none:

   ```bash
   ./gradlew :appglance:assembleRelease
   ls -l appglance/build/outputs/aar/appglance-release.aar        # 1.0.0: 83 KB, 1.2.1: 107 KB
   ./gradlew :appglance:dependencies --configuration releaseRuntimeClasspath   # still only lifecycle-process?
   ```

   If it moves past the quoted figure, update the README (and tell whoever maintains the site).
3. `git tag x.y.z && git push origin main x.y.z`.
4. The Release workflow runs CI (lint, build, tests) and, only if it passes, publishes a GitHub
   Release with that changelog section as its notes.
5. `./gradlew :appglance:publishAndReleaseToMavenCentral` from a machine with the Central Portal
   credentials and the signing key (see below). This step is deliberately by hand, so the artifact
   apps actually resolve is not covered by the gate in step 4: run the suite locally from the
   tagged commit before you publish.

`./gradlew :appglance:publishToMavenLocal` publishes to `~/.m2` for local use (no signing key
needed). Maven Central publishing is wired through `com.vanniktech.maven.publish` - javadoc and
sources jars, POM, signing when a key is configured - and needs, once: a Central Portal account
with the `app.appglance` namespace verified, and the portal credentials plus a GPG key in
`~/.gradle/gradle.properties` (`mavenCentralUsername`, `mavenCentralPassword`,
`signingInMemoryKey`, `signingInMemoryKeyPassword`). Then
`./gradlew :appglance:publishAndReleaseToMavenCentral`.
