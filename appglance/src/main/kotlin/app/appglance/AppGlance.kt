package app.appglance

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The AppGlance SDK.
 *
 * ```kotlin
 * // In Application.onCreate():
 * AppGlance.configure(this, "glance_live_…")
 *
 * // While wiring it up - sends from the emulator and debug builds too, and logs to logcat:
 * AppGlance.configure(this, "glance_live_…", debug = true)
 *
 * // Anywhere:
 * AppGlance.track("paywall.viewed", mapOf("source" to "settings"))
 *
 * // Optional - put a name on the install once someone signs in:
 * AppGlance.identify(id = account.id, email = account.email, name = account.name)
 * AppGlance.setUserProperties(mapOf("plan" to "pro"))
 * AppGlance.reset()   // on sign-out
 * ```
 *
 * Sessions are automatic: the SDK watches the whole app's foreground/background state through
 * `ProcessLifecycleOwner`, records `session.start` when the app comes to the front after more than
 * `sessionTimeout` away, keeps the presence heartbeat running while it is in front, and flushes
 * when it leaves. Nothing to attach - unless you turn [Configuration.trackAppLifecycle] off and
 * call [setActive] yourself.
 *
 * Every call here is cheap and non-blocking: it stamps the time, drops a command on an ordered
 * queue, and returns. Commands are applied strictly in call order, so `track("a"); track("b")` and
 * `setActive(false); setActive(true)` mean what they say. Calls made before [configure] - or while
 * the install id cannot be read yet, such as a Direct Boot launch before the user's first unlock -
 * are held (up to 200) and replayed once the SDK is ready.
 */
public object AppGlance {

    /** The SDK version, sent as the `User-Agent` of every request. */
    public const val VERSION: String = BuildConfig.VERSION

    /**
     * Everything [AppGlance.configure] needs: the app's write key from the dashboard's Setup page,
     * and optionally the knobs below.
     *
     * ```kotlin
     * AppGlance.configure(this, AppGlance.Configuration(
     *     apiKey = "glance_live_…",
     *     enabledEnvironments = setOf(AppEnvironment.PRODUCTION),
     * ))
     * ```
     */
    public class Configuration(
        /** The app's write key from the dashboard's Setup page (`glance_live_…`). */
        public val apiKey: String,
        /**
         * A stable identifier for this app; defaults to the package name. The hosted ingest derives
         * the app from the key, so this is informational.
         */
        public val appId: String? = null,
        /** The ingest endpoint. Override to use a self-hosted deployment of the ingest service. */
        public val endpoint: String = DEFAULT_ENDPOINT,
        /** Marketing version of the app; defaults to the package's `versionName`. */
        public val appVersion: String? = null,
        /** How long to wait before sending a partial batch. Must be positive. Default 10 s. */
        public val flushInterval: Duration = 10.seconds,
        /** Send at once when this many events are queued. 1 to 500, the ingest API's per-request maximum. Default 20. */
        public val maxBatchSize: Int = 20,
        /**
         * How long the app can be in the foreground with nothing sent before a presence ping goes
         * out. Pings power "active right now" and session length and are never billable; a real
         * event proves presence just as well, so a ping is only sent after this long of silence.
         * At least 15 seconds. Default 60 s. The server may ask for a sparser cadence for the
         * account's plan; the SDK then uses the larger of the two, so this is a floor you can
         * raise, not lower. One extra ping goes out when the app leaves the foreground after more
         * than a minute of silence, so a session's length is exact whatever the cadence.
         */
        public val heartbeatInterval: Duration = 60.seconds,
        /**
         * How long the app can be away - backgrounded, or killed and relaunched - before coming back
         * starts a new session (`session.start`). Must be positive. Default 5 minutes, the same gap
         * the dashboard uses to split an install's events into sessions.
         */
        public val sessionTimeout: Duration = 5.minutes,
        /** Master switch. `false` records and sends nothing (e.g. behind a user setting). Default true. */
        public val isEnabled: Boolean = true,
        /**
         * Attach the device's region setting (e.g. "US") to events - a locale, never GPS or IP, so
         * it adds nothing to your Data safety answers. `false` sends no country at all and the
         * dashboard's map stays empty. Default true.
         */
        public val collectsCountry: Boolean = true,
        /**
         * Which environments actually send. Default `{PRODUCTION, BETA}`: beta events are tagged
         * `beta` and kept out of the dashboard's Live numbers; emulator runs and debuggable builds
         * send nothing unless [debug] is on.
         */
        public val enabledEnvironments: Set<AppEnvironment> = setOf(AppEnvironment.PRODUCTION, AppEnvironment.BETA),
        /**
         * The release channel of this build, for what the SDK cannot detect. Android has no runtime
         * signal for Play's testing tracks, so a build you upload to internal or closed testing
         * should pass `environment = AppEnvironment.BETA` (a build flavor is the natural place).
         * Emulator runs and debuggable builds are always detected as such regardless. Default null:
         * `PRODUCTION` unless detected otherwise.
         */
        public val environment: AppEnvironment? = null,
        /**
         * Watch the app's foreground/background state automatically (sessions, heartbeat, flush on
         * background). Default true. Turn off only if you drive [setActive] yourself.
         */
        public val trackAppLifecycle: Boolean = true,
        /**
         * Debug mode, for while you wire the SDK up. Default false. When on:
         *
         * - **This build sends**, whatever its environment. Events keep their real tag (`emulator` /
         *   `debug`), so they appear under **All** in the dashboard and never in Live.
         * - **The SDK logs** to logcat (tag `AppGlance`): environment and install id at configure,
         *   each event as it is queued, each send and the server's answer.
         *
         * `isEnabled = false` still wins. Gate it on your own `BuildConfig.DEBUG`.
         */
        public val debug: Boolean = false,
    ) {
        // Values in these ranges could only be mistakes - a zero heartbeat is a tight send loop, a
        // zero batch size could never send - so they fail here, loudly, where the stack trace
        // points at the call that passed them, instead of misbehaving quietly in the field.
        init {
            require(flushInterval.isPositive()) { "flushInterval must be positive, got $flushInterval" }
            require(heartbeatInterval >= 15.seconds) {
                "heartbeatInterval must be at least 15 seconds, got $heartbeatInterval: presence needs no finer " +
                    "resolution, and anything shorter only spends the user's battery and data"
            }
            require(sessionTimeout.isPositive()) { "sessionTimeout must be positive, got $sessionTimeout" }
            require(maxBatchSize in 1..500) {
                "maxBatchSize must be between 1 and 500, got $maxBatchSize: the ingest API accepts at most " +
                    "500 events per request"
            }
        }

        public companion object {
            /** The hosted ingest endpoint. */
            public const val DEFAULT_ENDPOINT: String = "https://api.appglance.app/v1/events"
        }
    }

    // region The command queue

    private sealed class Command {
        class Configure(val context: Context, val configuration: Configuration, val at: Long) : Command()
        class Track(val signal: String, val metadata: Map<String, String>?, val at: Long) : Command()
        class Identify(val patch: Map<String, String>, val at: Long) : Command()
        class Reset(val at: Long) : Command()
        class SetActive(val active: Boolean, val at: Long) : Command()
        object Flush : Command()
    }

    /** One thread, strict FIFO, alive for the process. All state below `// state` is confined to it. */
    private class Pump {
        val thread = HandlerThread("AppGlance", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        val handler = Handler(thread.looper)
        val scheduler: Scheduler = HandlerScheduler(handler)
    }

    @Volatile
    private var pump = Pump()
    private val handler: Handler get() = pump.handler
    private val scheduler: Scheduler get() = pump.scheduler

    /** Network sends, one at a time, off the command thread. */
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppGlance-send").apply { isDaemon = true }
    }

    // Seams for tests; production values by default.
    internal var transportFactory: (Configuration) -> Transport = { HttpTransport(it.endpoint, it.apiKey) }
    internal var platformFactory: (Context) -> Platform = { AndroidPlatform(it) }
    internal var now: () -> Long = System::currentTimeMillis
    internal var processName: () -> String? = { currentProcessName() }

    // state (command thread only)
    private var client: Client? = null
    /** `configure` ran but the install id could not be read yet; retried on the next command. */
    private var pending: Command.Configure? = null
    /** Commands that arrived before the SDK could take them. */
    private val waiting = ArrayDeque<Command>()
    private const val MAX_WAITING = 200

    init {
        Log.sink = { android.util.Log.i("AppGlance", it) }
    }

    // endregion

    // region Public API

    /**
     * The whole hosted setup: one write key from the dashboard's Setup page. Call once, as early as
     * possible - `Application.onCreate()` is the place.
     *
     * By default emulator runs and debuggable builds send nothing, so your numbers only ever
     * contain real installs. While you are testing the integration, pass `debug = true`: this build
     * sends too (events tagged `emulator` / `debug`, visible under **All** in the dashboard, never
     * in Live) and the SDK logs what it does to logcat. See [Configuration.debug].
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, apiKey: String, debug: Boolean = false) {
        configure(context, Configuration(apiKey = apiKey, debug = debug))
    }

    /** Configure with full control (intervals, environments, endpoint). Call once, as early as possible. */
    @JvmStatic
    public fun configure(context: Context, configuration: Configuration) {
        val app = context.applicationContext ?: context
        if (!isMainProcess(app)) return
        val at = now()
        handler.post { onConfigure(Command.Configure(app, configuration, at)) }
        if (configuration.trackAppLifecycle) LifecycleBridge.install(app) else LifecycleBridge.uninstall()
    }

    /**
     * Collection happens in the app's main process only, and a secondary one says so and stops
     * here.
     *
     * `Application.onCreate` runs once per process, so an app that declares `android:process` on a
     * service or a provider - a crash reporter's own process, a `:sync` service - would otherwise
     * get a second, fully independent client on the same queue file and the same preference keys.
     * Neither can be shared: the file store rewrites the whole file from its own in-memory queue,
     * so whichever process writes last erases what the other still owed, and on a first launch
     * both find no install id, both mint one and both record `install`, leaving the dashboard a
     * phantom install and a phantom user for one real device. Making the file and every key carry
     * the process name would fix the collision, but the on-disk names are frozen (see
     * CONTRIBUTING.md), so that is a migration rather than a guard.
     */
    private fun isMainProcess(context: Context): Boolean {
        val process = processName() ?: return true   // no answer: assume the main process
        if (process == context.packageName) return true
        Log.line(
            "not collecting in this process: AppGlance runs in the app's main process only, and this is " +
                "\"$process\". Sessions, presence and the offline queue are one app's state and two processes " +
                "cannot share them. Track from the main process instead.",
        )
        return false
    }

    /** The running process's name, or null when the platform will not say. */
    private fun currentProcessName(): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            // No API for it before 28. `/proc/self/cmdline` is the process name, NUL-terminated.
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
                .ifEmpty { null }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Records an event. `signal` is a short, stable, lowercase name (`paywall.viewed`); `metadata`
     * is small string context (`mapOf("source" to "settings")`, at most 20 keys). Never put
     * personal data in either - that is what [identify] is for.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(signal: String, metadata: Map<String, String>? = null) {
        val cmd = Command.Track(signal, metadata?.let { LinkedHashMap(it) }, now())
        handler.post { dispatch(cmd) }
    }

    /**
     * Records `screen.<name>` - call it when a screen appears (`onResume`, a Compose
     * `LaunchedEffect`, a navigation destination). Screens are the cheapest funnel steps.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackScreen(name: String, metadata: Map<String, String>? = null) {
        track("screen.$name", metadata)
    }

    /**
     * Attaches who this install belongs to. Everything is optional; pass what you have. The install
     * id stays the analytics identity - these are labels on it, shown on the user's page in the
     * dashboard, searchable, and merged with anything set before. Calling this with the same values
     * on every launch is free: only a change is sent.
     *
     * Privacy: the moment you pass an email or a name, your Play Data safety answers change
     * (Personal info, linked to the user) - the dashboard's Setup page shows exactly how. Never
     * pass anything the person did not give you.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(
        id: String? = null,
        email: String? = null,
        name: String? = null,
        properties: Map<String, String>? = null,
    ) {
        val patch = LinkedHashMap<String, String>()
        properties?.let(patch::putAll)
        id?.let { patch[UserProperty.ID] = it }
        email?.let { patch[UserProperty.EMAIL] = it }
        name?.let { patch[UserProperty.NAME] = it }
        if (patch.isEmpty()) return
        val cmd = Command.Identify(patch, now())
        handler.post { dispatch(cmd) }
    }

    /**
     * Sets or updates custom properties on the current user (`mapOf("plan" to "pro")`). Merged with
     * existing ones; an empty string removes a key. Up to 20 keys of 40 characters, values up to
     * 200. They show up as filterable pills in the dashboard's Users tab.
     */
    @JvmStatic
    public fun setUserProperties(properties: Map<String, String>) {
        if (properties.isEmpty()) return
        val cmd = Command.Identify(LinkedHashMap(properties), now())
        handler.post { dispatch(cmd) }
    }

    /**
     * Forgets every property attached to this install - call it on sign-out. The install id (and
     * its history) stays; only the labels go.
     */
    @JvmStatic
    public fun reset() {
        val cmd = Command.Reset(now())
        handler.post { dispatch(cmd) }
    }

    /**
     * Reports a foreground (`true`) / background (`false`) transition. Done for you while
     * [Configuration.trackAppLifecycle] is on. Safe to call redundantly: `true` after more than
     * `sessionTimeout` of `false` starts a new session; anything else is a no-op or a resume of the
     * current one.
     */
    @JvmStatic
    public fun setActive(active: Boolean) {
        val cmd = Command.SetActive(active, now())
        handler.post { dispatch(cmd) }
    }

    /** Sends any queued events now. They are also sent every `flushInterval` and when the app leaves the foreground. */
    @JvmStatic
    public fun flush() {
        handler.post { dispatch(Command.Flush) }
    }

    // endregion

    // region The pump (command thread)

    private fun onConfigure(cmd: Command.Configure) {
        client?.shutdown()
        client = null
        pending = cmd
        val started = startPendingIfPossible() ?: return   // id unreadable: hold commands until it isn't
        started.recordInstallIfNeeded()                    // `install` goes first, always
        replayWaiting(started)
    }

    /**
     * Builds the client from the pending configuration if the install id can be read now. `isNew`
     * is true exactly once per install, and the client stamps `install` with the moment `configure`
     * was called.
     */
    private fun startPendingIfPossible(): Client? {
        client?.let { return it }
        val cmd = pending ?: return null
        val platform = platformFactory(cmd.context)
        val identity = AnonymousIdentity.current(platform.identity) ?: return null   // Direct Boot, before unlock
        pending = null
        return Client(
            config = cmd.configuration,
            userId = identity.id,
            isNewInstall = identity.isNew,
            installAt = cmd.at,
            platform = platform,
            scheduler = scheduler,
            sendExecutor = sender,
            transport = transportFactory(cmd.configuration),
            now = now,
        ).also { client = it }
    }

    private fun dispatch(cmd: Command) {
        val c = startPendingIfPossible()
        if (c == null) {
            if (cmd !is Command.Flush) {                 // nothing to send yet: a flush is a no-op
                waiting.addLast(cmd)
                while (waiting.size > MAX_WAITING) waiting.removeFirst()
            }
            return
        }
        c.recordInstallIfNeeded()
        replayWaiting(c)
        apply(cmd, c)
    }

    private fun replayWaiting(c: Client) {
        while (waiting.isNotEmpty()) apply(waiting.removeFirst(), c)
    }

    /** Every case is a quick hop into the client; nothing here waits for the network. */
    private fun apply(cmd: Command, c: Client) {
        when (cmd) {
            is Command.Track -> c.track(cmd.signal, cmd.metadata, cmd.at)
            is Command.Identify -> c.identify(cmd.patch, cmd.at)
            is Command.Reset -> c.reset(cmd.at)
            is Command.SetActive -> c.setActive(cmd.active, cmd.at)
            Command.Flush -> c.flush()
            is Command.Configure -> onConfigure(cmd)
        }
    }

    // endregion

    // region Test hooks

    /** The command thread's looper. */
    internal val looper: Looper get() = pump.thread.looper

    /** Runs `block` on the command thread and returns its result, once every command issued before it has been applied. */
    internal fun <T> onCommandThread(timeoutMillis: Long = 10_000, block: () -> T): T {
        val latch = CountDownLatch(1)
        var result: T? = null
        var failure: Throwable? = null
        handler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            "AppGlance command thread did not drain in ${timeoutMillis}ms"
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Resolves once every command issued before it has been applied. */
    internal fun drain() {
        onCommandThread { }
    }

    /** Resolves once every send requested so far has finished (the sender is serial). */
    internal fun awaitSenderIdle(timeoutMillis: Long = 10_000) {
        sender.submit { }.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** The live client, if any. */
    internal fun currentClientForTesting(): Client? = onCommandThread { client }

    /**
     * Back to "never configured": drops the client, any pending configuration, and every buffered
     * command - on a fresh command thread, because Robolectric quits background loopers between
     * tests and would leave the old one dead.
     */
    internal fun resetForTesting() {
        val old = pump
        pump = Pump()
        old.thread.quitSafely()
        LifecycleBridge.uninstall()
        onCommandThread {
            client?.shutdown()
            client = null
            pending = null
            waiting.clear()
        }
        transportFactory = { HttpTransport(it.endpoint, it.apiKey) }
        platformFactory = { AndroidPlatform(it) }
        now = System::currentTimeMillis
        processName = { currentProcessName() }
    }

    // endregion
}
