package app.appglance

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// In-memory stand-ins for everything the core needs from the host. The JVM tests drive the real
// `Client` through these: no Android, no network, no sleeping - time is a number the test moves.

/**
 * A settable clock: the session timeout and heartbeat spacing are wall-clock rules, so tests move
 * time instead of sleeping through it.
 */
internal class FakeClock(@Volatile var now: Long = 1_700_000_000_000L) {
    fun advance(ms: Long) {
        now += ms
    }

    fun advance(d: Duration) {
        now += d.inWholeMilliseconds
    }
}

/**
 * Runs `post` inline and keeps delayed tasks in a list; [advance] moves the clock and runs
 * whatever comes due, in time order - so "the heartbeat's first tick" or "60 seconds later" are
 * one call, not a sleep.
 */
internal class FakeScheduler(private val clock: FakeClock) : Scheduler {
    private class Delayed(val at: Long, val seq: Long, val task: Runnable)

    private val delayed = ArrayList<Delayed>()
    private var seq = 0L

    override fun post(task: Runnable) {
        task.run()
    }

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        delayed += Delayed(clock.now + delayMillis, seq++, task)
    }

    override fun cancel(task: Runnable) {
        delayed.removeAll { it.task === task }
    }

    /** Move time forward by `ms`, running every task that comes due on the way (in order). */
    fun advance(ms: Long) {
        val target = clock.now + ms
        while (true) {
            val due = delayed.filter { it.at <= target }
            val next = due.minWithOrNull(compareBy<Delayed>({ it.at }, { it.seq })) ?: break
            delayed.remove(next)
            if (next.at > clock.now) clock.now = next.at
            next.task.run()
        }
        clock.now = target
    }

    fun advance(d: Duration) = advance(d.inWholeMilliseconds)

    /** Lets the heartbeat get its first (immediate) tick in. */
    fun settle() = advance(0)

    val pendingCount: Int get() = delayed.size
}

/**
 * Answers 202 to everything (unless scripted) and records the signals of every batch it
 * received, in order - the server's view of what the client did.
 */
internal class RecordingTransport : Transport {
    private val lock = Any()
    private val sent = ArrayList<String>() // signals of accepted (2xx) batches, in order
    private val sizes = ArrayList<Int>() // events per request, in order
    private val sessions = ArrayList<String?>() // per accepted event, in order
    private val received = ArrayList<List<Event>>() // every batch, accepted or not
    private val statuses = ArrayDeque<Int>() // scripted answers; 202 once exhausted

    /** When set, every send waits on it first. */
    @Volatile var gate: CountDownLatch? = null

    /** When set, counted down as a send begins. */
    @Volatile var arrived: CountDownLatch? = null

    /**
     * Run as a send begins, before the answer: the seam for whatever a real request's time on the
     * wire lets happen while the client believes a batch is still out.
     */
    @Volatile var onSend: () -> Unit = {}

    /** When set, no connection is ever established. */
    @Volatile var offline = false

    /** When set, reported as the numeric Retry-After (seconds) of every response. */
    @Volatile var retryAfterSeconds: Long? = null

    override fun lastRetryAfterSeconds(): Long? = retryAfterSeconds

    /** When set, reported as the `heartbeat_interval` (seconds) of every 2xx response body. */
    @Volatile var heartbeatIntervalSeconds: Long? = null

    override fun lastHeartbeatIntervalSeconds(): Long? = heartbeatIntervalSeconds

    /**
     * When set, reported as the `accepted` count of every 2xx response body - the ingest saying it
     * took fewer rows than were sent, which is what an account past its grace ceiling gets. Null
     * (the default) is an answer that says nothing about it.
     */
    @Volatile var acceptedCount: Int? = null

    override fun lastAcceptedCount(): Int? = acceptedCount

    fun script(vararg s: Int) = synchronized(lock) {
        statuses.clear()
        statuses.addAll(s.toList())
    }

    fun signals(): List<String> = synchronized(lock) { sent.toList() }

    fun requestSizes(): List<Int> = synchronized(lock) { sizes.toList() }

    fun sessionIds(): List<String?> = synchronized(lock) { sessions.toList() }

    fun batches(): List<List<Event>> = synchronized(lock) { received.toList() }

    override fun send(body: ByteArray): Int {
        onSend()
        arrived?.countDown()
        gate?.await()
        val batch = EventCoding.decode(String(body, Charsets.UTF_8))
        return synchronized(lock) {
            // `offline` models a device with no connectivity: nothing leaves the device, which is
            // the one failure where presence pings are safe to keep. A scripted NO_RESPONSE (-1)
            // models the ambiguous case - a timeout, or a connection dropped part-way.
            val status = if (offline) Transport.NEVER_CONNECTED else statuses.removeFirstOrNull() ?: 202
            received += batch
            sizes += batch.size
            if (status in 200..299) {
                sent += batch.map { it.signal }
                sessions += batch.map { it.sessionId }
            }
            status
        }
    }
}

internal class InMemoryIdentityStore(
    initial: String? = null,
    private val locked: Boolean = false,
    /** A store that keeps nothing and says so: a device whose data partition is full. */
    private val dropsWrites: Boolean = false,
) : IdentityStore {
    var value: String? = initial

    override fun lookup(): IdentityLookup = when {
        locked -> IdentityLookup.Unavailable
        !value.isNullOrEmpty() -> IdentityLookup.Found(value!!)
        else -> IdentityLookup.Absent
    }

    override fun save(id: String): Boolean {
        if (dropsWrites) return false
        value = id
        return true
    }
}

/**
 * A store whose write reports success and whose next read does not hand the id back. The Direct
 * Boot window is the concrete case: the write is unchecked and the read is unlock-checked, so the
 * two disagree until the device is unlocked. `save` returning true is therefore not on its own
 * proof that the next launch will find the id.
 */
internal class UnkeptIdentityStore(
    /** What `lookup` answers once the write has reported success; null is "still nothing there". */
    private val readsBackAfterSave: String? = null,
) : IdentityStore {
    private var written = false

    override fun lookup(): IdentityLookup = when {
        !written || readsBackAfterSave == null -> IdentityLookup.Absent
        else -> IdentityLookup.Found(readsBackAfterSave)
    }

    override fun save(id: String): Boolean {
        written = true
        return true
    }
}

internal class InMemoryKeyValueStore : KeyValueStore {
    val map = HashMap<String, Any>()

    override fun getLong(key: String): Long? = map[key] as? Long

    override fun putLong(key: String, value: Long) {
        map[key] = value
    }

    override fun getString(key: String): String? = map[key] as? String

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: false

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

internal class InMemoryQueueStore : QueueStore {
    @Volatile var json: String? = null
    var writes = 0

    /** Called as each write happens, for a test that asks what else was already on disk by then. */
    @Volatile var onSave: (String) -> Unit = {}

    /** When true the store reports every write as failed and keeps what it already held. */
    @Volatile var failWrites = false

    override fun load(): String? = json

    override fun save(json: String): Boolean {
        writes++
        if (failWrites) return false
        this.json = json
        onSave(json)
        return true
    }

    override fun exists(): Boolean = json != null

    override fun delete() {
        json = null
    }
}

internal class FakeDeviceInfo(
    private val env: AppEnvironment = AppEnvironment.PRODUCTION,
    private val country: String? = "US",
) : DeviceInfo {
    override val osName = "Android"
    override val osVersion = "14"
    override val appVersion = "1.2.3"
    override val defaultAppId = "app.appglance.test"

    override fun country(): String? = country

    override fun environment(override: AppEnvironment?): AppEnvironment = when {
        env == AppEnvironment.EMULATOR || env == AppEnvironment.DEBUG -> env
        else -> override ?: env
    }
}

/**
 * One "device": prefs, queue files and identity survive across the clients created against it,
 * so a relaunch is a new Client on the same platform.
 */
internal class InMemoryPlatform(
    override val identity: IdentityStore = InMemoryIdentityStore(),
    override val device: DeviceInfo = FakeDeviceInfo(),
) : Platform {
    override val prefs = InMemoryKeyValueStore()
    val queues = HashMap<String, InMemoryQueueStore>()
    override fun queueStore(appId: String): QueueStore = queues.getOrPut(appId) { InMemoryQueueStore() }
}

/** Counts what reaches the send executor: how many drains a burst of triggers really costs. */
internal class CountingExecutor(private val delegate: Executor) : Executor {
    private val count = AtomicInteger()

    val submitted: Int get() = count.get()

    override fun execute(command: Runnable) {
        count.incrementAndGet()
        delegate.execute(command)
    }
}

/** Runs the send loop inline, on the caller's thread - deterministic and instant. */
internal val directExecutor = Executor { it.run() }

/** A hosted-mode configuration that accepts every environment and, by default, only sends on explicit flushes. */
internal fun testConfiguration(
    appId: String = "app.appglance.test",
    apiKey: String = "glance_live_test",
    endpoint: String = "https://ingest.invalid/v1/events",
    sessionTimeout: Duration = 5.minutes,
    enabledEnvironments: Set<AppEnvironment> = AppEnvironment.values().toSet(),
    isEnabled: Boolean = true,
    debug: Boolean = false,
    maxBatchSize: Int = 500, // the highest valid value, so size never triggers a send below 500 queued
    flushInterval: Duration = 1.hours, // and the timer never does either
    heartbeatInterval: Duration = 60.seconds,
) = AppGlance.Configuration(
    apiKey = apiKey,
    appId = appId,
    endpoint = endpoint,
    flushInterval = flushInterval,
    maxBatchSize = maxBatchSize,
    heartbeatInterval = heartbeatInterval,
    sessionTimeout = sessionTimeout,
    isEnabled = isEnabled,
    enabledEnvironments = enabledEnvironments,
    debug = debug,
)

/**
 * A client wired to the fakes; `userId` is fixed so tests can reason about it, and the backoff
 * jitter is pinned to the bottom of its window so waits are exact numbers.
 */
internal fun makeClient(
    platform: InMemoryPlatform,
    clock: FakeClock,
    scheduler: FakeScheduler,
    transport: Transport,
    config: AppGlance.Configuration = testConfiguration(),
    userId: String = "u-test",
    isNewInstall: Boolean = false,
    installAt: Long? = null,
    executor: Executor = directExecutor,
    random: () -> Double = { 0.0 },
) = Client(
    config = config,
    userId = userId,
    isNewInstall = isNewInstall,
    installAt = installAt,
    platform = platform,
    scheduler = scheduler,
    sendExecutor = executor,
    transport = transport,
    now = { clock.now },
    random = random,
)
