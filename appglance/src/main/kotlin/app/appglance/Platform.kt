package app.appglance

import java.util.UUID

// The seams between the framework-free core (`Client`, `AnonymousIdentity`, `EventCoding`) and
// Android. Production wires `AndroidPlatform`, `HandlerScheduler` and `HttpTransport`; the JVM
// tests wire in-memory fakes and a settable clock.

/** What an identity store found. */
internal sealed class IdentityLookup {
    class Found(val id: String) : IdentityLookup()

    /** Nothing stored: a fresh install. Mint an id. */
    object Absent : IdentityLookup()

    /**
     * The store exists but cannot be read at this moment (credential-encrypted storage before the
     * user's first unlock, if the app runs in Direct Boot). Do not mint - try again later.
     */
    object Unavailable : IdentityLookup()
}

/** Where the install id is kept. */
internal interface IdentityStore {
    fun lookup(): IdentityLookup

    /**
     * Writes the id, and says whether it is really on disk. False means the next launch will not
     * find it, so this id belongs to this run and to no install - see [AnonymousIdentity.current].
     */
    fun save(id: String): Boolean
}

/** Small persistent key/value state: last-active stamps, session id, user properties. */
internal interface KeyValueStore {
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String)

    /** A marker whose presence is the whole value; false when the key was never written. */
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

/** The offline queue's home on disk. */
internal interface QueueStore {
    fun load(): String?

    /**
     * Writes the file, answering whether the bytes actually landed. The client remembers the last
     * write it landed so it can skip an identical one, and a write it only believes in would have
     * it skip the write that repairs a full disk.
     */
    fun save(json: String): Boolean

    /**
     * Whether the file is there right now. The client skips a write whose bytes match the one it
     * last landed and asks this before it does, so a store whose file has gone since is never left
     * with nothing on disk.
     */
    fun exists(): Boolean

    /** Removes the file entirely, for a consent withdrawal. */
    fun delete()
}

/** Minimal, non-identifying device context attached to every event. */
internal interface DeviceInfo {
    val osName: String
    val osVersion: String
    /** Marketing version of the app (`versionName`). */
    val appVersion: String
    /** The app's package name - the default `appId`. */
    val defaultAppId: String
    /**
     * When this app was first installed on this device, in epoch milliseconds, or null when the
     * package manager cannot say. Uninstalling resets it and a new handset starts its own, so it
     * answers "new on this device"; see [InstallOrigin].
     */
    val firstInstalledAt: Long?
    /** The device's region setting as an ISO 3166-1 alpha-2 code, or null. Never GPS or IP. */
    fun country(): String?
    /** Where this build is running; `override` picks the channel when detection cannot. */
    fun environment(override: AppEnvironment?): AppEnvironment
}

/** Everything the core needs from the host, in one place. */
internal interface Platform {
    val identity: IdentityStore
    val prefs: KeyValueStore
    val device: DeviceInfo
    fun queueStore(appId: String): QueueStore
}

/**
 * Deferred work on the SDK's own thread - the heartbeat and the flush timer. Tasks run on the
 * same single thread the client is driven from, in order.
 */
internal interface Scheduler {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

/**
 * One HTTP POST of a JSON batch. Returns the status code, or one of the sentinels below when
 * there was no status to return.
 */
internal interface Transport {
    fun send(body: ByteArray): Int

    /**
     * The `Retry-After` of the most recent response, in whole seconds, when the server sent a
     * numeric one; null otherwise (absent header, HTTP-date form, or no response at all). The
     * client reads it right after a 429 from [send]; sends are serial, so "most recent" is
     * unambiguous.
     */
    fun lastRetryAfterSeconds(): Long? = null

    /**
     * The `heartbeat_interval` (seconds) carried by the most recent 2xx response body, when the
     * server sent one; null otherwise. The client reads it right after a successful [send]; sends
     * are serial, so "most recent" is unambiguous.
     */
    fun lastHeartbeatIntervalSeconds(): Long? = null

    /**
     * The `accepted` count carried by the most recent 2xx response body: how many of the rows sent
     * the server actually took. Null when the answer said nothing about it - a backend that
     * replies with no body at all - which is not a claim that anything was dropped. A batch can be
     * answered 2xx and still be counted short, past the plan's grace ceiling or under the ingest's
     * per-install rate limiter, and the client must not treat that as an acknowledgement of the
     * user properties it carried.
     */
    fun lastAcceptedCount(): Int? = null

    /**
     * The `throttled` count carried by the most recent 2xx response body: well-formed rows the
     * ingest's per-install rate limiter dropped before storage, because one install sent faster
     * than any real usage does. Null when the answer said nothing about it. The rows are not
     * re-sent (that would feed the loop that caused them) and never count against the plan; the
     * client's only use for the number is to say it out loud in debug mode.
     */
    fun lastThrottledCount(): Int? = null

    /**
     * The `over_quota_dropped` count carried by the most recent 2xx response body: well-formed
     * rows the plan gate stopped storing, because the account is past its cap and its grace.
     * Null when the answer said nothing about it. A re-send cannot succeed until the month rolls
     * or the plan changes, so the client only says it out loud in debug mode.
     */
    fun lastOverQuotaDroppedCount(): Int? = null

    companion object {
        /**
         * The request failed after a connection existed - a timeout, or a connection dropped
         * part-way. Ambiguous on purpose: the server may have processed the batch and the reply
         * been lost on the way back, and nothing here can tell the difference.
         */
        const val NO_RESPONSE: Int = -1

        /**
         * No connection was ever established (offline, DNS failure, connection refused, a timeout
         * while connecting). Nothing on the other end could have seen the batch, which is what
         * makes it safe to retry presence pings - see `Client.requeue`.
         */
        const val NEVER_CONNECTED: Int = -2
    }
}

/**
 * The SDK's log output. Everything goes through here so it can be filtered (`[AppGlance] …` in
 * logcat); the Android facade points [sink] at `android.util.Log`, the JVM tests at stdout.
 * Nothing is logged unless `debug` is on - except the one-shot "not sending" hint for a gated
 * environment.
 */
internal object Log {
    @Volatile
    var sink: (String) -> Unit = { println("[AppGlance] $it") }

    fun line(message: String) = sink(message)
}

/**
 * The per-install anonymous identifier: a random UUID minted on first launch. Not the advertising
 * id, not derived from the device or an account, no personal data.
 */
internal object AnonymousIdentity {
    class Identity(val id: String, val isNew: Boolean)

    /**
     * `isNew` is true exactly once per install - the launch that minted the id, which is when the
     * SDK records `install`. It is claimed only for an id the store both kept and hands back,
     * because an id nothing kept belongs to this run and to no install. Returns null when the
     * store cannot be read right now; minting then would invent a second user and a phantom
     * `install`, so the caller retries later.
     */
    fun current(store: IdentityStore): Identity? = when (val found = store.lookup()) {
        is IdentityLookup.Found -> Identity(found.id, isNew = false)

        IdentityLookup.Unavailable -> null

        IdentityLookup.Absent -> {
            val generated = UUID.randomUUID().toString()
            // A save that did not land is the store that cannot be read, one step later: the id is
            // usable for this run, but it is not this install's id, because the next launch finds
            // nothing and mints another. Claiming it as new would record an `install` per launch
            // under a different user id, and nothing on the server can collapse those - each one
            // carries a fresh id, so a single device arrives as an unbounded stream of users. The
            // run uses the id and claims nothing by it instead, exactly as the unreadable case
            // does. Two questions, because they catch different failures: the store says whether
            // the write reached disk, and reading the id back says whether what is there is this
            // one.
            val written = store.save(generated)
            val readBack = store.lookup()
            val kept = written && readBack is IdentityLookup.Found && readBack.id == generated
            Identity(generated, isNew = kept)
        }
    }
}
