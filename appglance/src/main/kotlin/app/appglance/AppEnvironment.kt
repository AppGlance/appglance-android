package app.appglance

/**
 * Where the app is running. Every event carries it, and it keeps development traffic out of your
 * numbers: by default only [PRODUCTION] and [BETA] builds send (see
 * [AppGlance.Configuration.enabledEnvironments]), and the dashboard's Live scope shows production
 * only. Debug mode lets the current build send regardless; the tag stays truthful either way.
 *
 * The wire values are the platform-neutral names the ingest API understands (`production`, `beta`,
 * `emulator`, `debug`); it stores them alongside the Apple tiers, so a Play build shows up under
 * Live exactly like an App Store build does.
 */
public enum class AppEnvironment(public val wireValue: String) {
    /** A store build in users' hands (Google Play, or any other channel you ship through). */
    PRODUCTION("production"),

    /**
     * A beta / internal-testing build. Android has no reliable runtime signal for Play's testing
     * tracks, so this is opt-in: pass `environment = AppEnvironment.BETA` in the configuration of
     * the build you upload to a testing track (a build flavor is the usual place). Beta events are
     * kept out of the Live numbers by the dashboard's scope switch.
     */
    BETA("beta"),

    /** Running on an emulator (detected from the device fingerprint and hardware). Never sends by default. */
    EMULATOR("emulator"),

    /** A debuggable build (`android:debuggable="true"`, i.e. the debug build type). Never sends by default. */
    DEBUG("debug"),
    ;

    /** "an emulator run" - for the not-sending hint. */
    internal val humanName: String
        get() = when (this) {
            PRODUCTION -> "a production build"
            BETA -> "a beta build"
            EMULATOR -> "an emulator run"
            DEBUG -> "a debuggable build"
        }
}
