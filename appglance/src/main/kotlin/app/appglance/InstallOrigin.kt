package app.appglance

/**
 * What was already true about this app on the device before AppGlance ever ran on it: when the app
 * first arrived, and where that date came from.
 *
 * An app that adds the SDK years after launch has a user base the SDK has never seen. Every one of
 * those installs mints its id on the day that build ships, so every one of them reads as a new user
 * on that day and the real arrivals are buried among them. This is the evidence that tells the two
 * apart, and it is free: `firstInstallTime` rides the same `PackageInfo` the SDK already reads for
 * the app's `versionName`.
 *
 * The SDK sends the evidence and the server decides what counts as pre-existing. Apps pin SDK
 * versions and stay on them for months, so a threshold that lives in the SDK is one nobody can
 * change.
 */
internal data class InstallOrigin(val firstInstalledAt: Long, val evidence: Evidence) {
    /**
     * Which question the date answers. The sources answer different ones, so the server is told
     * which rather than left to guess from the platform.
     */
    internal enum class Evidence(val wireValue: String) {
        /**
         * The package manager's record of when this app was first installed on this device. It is
         * reset by uninstalling, and a new handset starts its own, so it answers "new on this
         * device" rather than "new to the app, ever". The Apple SDK's `store` evidence answers the
         * second question; the difference is why the value travels alongside the date.
         */
        PACKAGE("package"),

        /**
         * Passed by the app through `Configuration.firstInstalledAt`. Ranked above the package
         * manager's answer because an app that keeps its own signup date knows things no platform
         * API can see, including users who predate every device they now own.
         */
        APP("app"),
    }

    /**
     * The metadata a carrier event travels with. `$`-prefixed, the same way every SDK-owned key in
     * the user-properties dictionary is, so an app's own property can never collide with one.
     */
    fun metadata(): Map<String, String> = linkedMapOf(
        KEY_INSTALLED_AT to Iso8601.format(firstInstalledAt),
        KEY_EVIDENCE to evidence.wireValue,
    )

    /**
     * A date the future cannot be trusted with. A device with its clock pushed forward would
     * otherwise claim an install date after the moment it was read, which reads downstream as a
     * user who arrived tomorrow. Rejected rather than clamped: a nonsense date is not evidence, and
     * no evidence is a state the server already knows how to handle.
     */
    fun isPlausible(now: Long): Boolean =
        firstInstalledAt <= now + ONE_DAY_MILLIS && firstInstalledAt > EARLIEST_PLAUSIBLE_MILLIS

    internal companion object {
        const val KEY_INSTALLED_AT: String = "\$install_at"
        const val KEY_EVIDENCE: String = "\$install_evidence"

        private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
        /** 2008-09-23, the day the first Android handset shipped. Nothing installed before it. */
        private const val EARLIEST_PLAUSIBLE_MILLIS = 1_222_128_000_000L

        /** The signals the origin is allowed to ride; see [Client.track]. */
        fun carriedBy(signal: String): Boolean = signal == Signal.INSTALL || signal == Signal.SESSION_START
    }
}
