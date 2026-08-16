package app.appglance

import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the event queue, batching and delivery, offline persistence, sessions, the foreground
 * heartbeat and the user-properties snapshot.
 *
 * Threading: every method is called from one thread (the facade's handler thread, or the test
 * thread), which owns all session and trait state. The send loop runs on [sendExecutor] and
 * touches only the queue, under [queueLock], never during the network call - so a slow send can
 * never delay a `track` or a `setActive(true)`.
 */
internal class Client(
    private val config: AppGlance.Configuration,
    private val userId: String,
    /** True exactly once per install: the launch that minted the id. */
    private val isNewInstall: Boolean,
    /** When `configure` ran; the `install` event is stamped with it. */
    private val installAt: Long?,
    private val platform: Platform,
    private val scheduler: Scheduler,
    private val sendExecutor: Executor,
    private val transport: Transport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val device = platform.device
    private val prefs = platform.prefs

    val appId: String = config.appId ?: device.defaultAppId
    private val appVersion: String = config.appVersion ?: device.appVersion
    val environment: AppEnvironment = device.environment(config.environment)
    private val queueStore = platform.queueStore(appId)

    /** Debug mode lifts the environment gate and nothing else; the developer's own off-switch wins. */
    val collecting: Boolean = config.isEnabled && (config.debug || environment in config.enabledEnvironments)

    // The queue is shared with the send loop, hence the lock; everything else is confined to the
    // calling thread.
    private val queueLock = Any()
    private val queue = ArrayDeque<Event>()
    /** The slice currently on the wire. See [persistLocked]. */
    private var inFlightBatch: List<Event> = emptyList()

    /** Set by [shutdown] once a later `configure` has replaced this client. */
    @Volatile
    private var retired = false

    private var flushTask: Runnable? = null
    private var heartbeatTask: Runnable? = null
    private val drainPending = AtomicBoolean(false)

    // Session state. A session is "the app is in front of the user"; it survives short
    // interruptions and ends only after `sessionTimeout` of absence - the same gap the dashboard
    // uses, so the two agree on what a session is. Persisted so a kill-and-relaunch inside the
    // timeout continues the session and a relaunch after it starts a new one. When init can
    // already tell that the next foreground will start a NEW session, its id is pre-minted right
    // there (see [premintSessionIdIfNeeded]), so `install` and everything else recorded before
    // the first foreground carry the id `session.start` will carry.
    private var isActive = false
    private var lastActiveAt: Long? = null
    private var lastHeartbeatAt: Long? = null
    private var sessionId: String? = null
    /** True while [sessionId] is pre-minted for the coming session and no `session.start` has adopted it yet. */
    private var sessionIdPreminted = false
    private val lastActiveKey = "lastActive.$appId"
    private val sessionKey = "session.$appId"
    private val pendingSessionKey = "session.pending.$appId"

    // User properties. The last snapshot the server has is mirrored here, so `identify` with the
    // same values on every launch sends nothing; only a change costs an event.
    private var traits: Map<String, String>
    private val traitsKey = "traits.$appId"

    private var installRecorded = false

    init {
        prefs.getLong(lastActiveKey)?.let {
            lastActiveAt = it
            sessionId = prefs.getString(sessionKey)
        }
        premintSessionIdIfNeeded()
        traits = prefs.getString(traitsKey)?.let(::decodeTraits) ?: emptyMap()
        queueStore.load()?.let { queue.addAll(EventCoding.decode(it)) }
        announce()
    }

    /**
     * One log line per `configure`, in two situations only: debug mode is on (say what this build
     * will do), or this build is silently not sending because of the environment gate - the "I hit
     * Run and the dashboard stayed empty" moment. A normal production or beta launch logs nothing,
     * and neither does `isEnabled = false`.
     */
    private fun announce() {
        val env = environment.wireValue
        if (config.debug) {
            var line = "debug mode on · environment: $env"
            if (collecting) {
                line += " · sending to ${hostOf(config.endpoint)} · install id $userId"
                val waiting = queue.size
                if (waiting > 0) line += " · $waiting event${if (waiting == 1) "" else "s"} waiting from an earlier run"
                Log.line(line)
                if (environment == AppEnvironment.EMULATOR || environment == AppEnvironment.DEBUG) {
                    Log.line(
                        "events from this build are tagged \"$env\" - they show under All in the dashboard, never in Live",
                    )
                }
            } else {
                Log.line("$line · NOT sending: isEnabled is false")
            }
        } else if (config.isEnabled && !collecting) {
            Log.line(
                "not sending: this is ${environment.humanName} and enabledEnvironments doesn't include " +
                    "AppEnvironment.${environment.name} (the default keeps development traffic out of your numbers). " +
                    "To test from here, pass debug = true to AppGlance.configure - events are then tagged \"$env\" " +
                    "and appear under All in the dashboard, never in Live.",
            )
        }
    }

    private fun hostOf(endpoint: String): String =
        Regex("""^[a-z]+://([^/]+)""").find(endpoint)?.groupValues?.get(1) ?: endpoint

    /** Debug-mode narration; a no-op unless `debug` is on. */
    private inline fun log(message: () -> String) {
        if (config.debug) Log.line(message())
    }

    // region Commands (called by the facade)

    /** Records `install`, exactly once per install, ahead of everything else. */
    fun recordInstallIfNeeded() {
        if (!isNewInstall || installRecorded) return
        installRecorded = true
        track(Signal.INSTALL, null, installAt)
    }

    /**
     * Stops the timers and retires the client: it records nothing further and, above all, never
     * writes the queue file again - a later `configure` has replaced it, and the replacement now
     * owns that file. A send already in flight completes on its own; what it carried is either
     * acknowledged (and deduplicated by event id if the replacement re-sends it) or already
     * persisted for the replacement to pick up.
     */
    fun shutdown() {
        heartbeatTask?.let(scheduler::cancel)
        heartbeatTask = null
        flushTask?.let(scheduler::cancel)
        flushTask = null
        synchronized(queueLock) {
            retired = true
            queue.clear()
        }
    }

    /**
     * `at` is when the app made the call - the facade stamps it before queueing - so events
     * tracked back to back keep their order even if they are applied a moment later.
     */
    fun track(signal: String, metadata: Map<String, String>?, at: Long? = null) {
        if (!collecting || retired) return
        val event = Event(
            eventId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            appId = appId,
            userId = userId,
            signal = signal,
            appVersion = appVersion,
            osName = device.osName,
            osVersion = device.osVersion,
            environment = environment.wireValue,
            country = if (config.collectsCountry) device.country() else null,
            clientTs = at ?: now(),
            metadata = metadata?.let { LinkedHashMap(it) },
        )
        val size = synchronized(queueLock) {
            queue.addLast(event)
            trimLocked()
            // Written now, not only after a failed send: a crash or a kill must not take the
            // session's events with it. A few KB, at most once a minute in steady state.
            persistLocked()
            queue.size
        }
        log {
            "▸ $signal" + (if (signal == Signal.HEARTBEAT) " (presence ping)" else "") +
                (metadata?.let { " $it" } ?: "")
        }
        if (size >= config.maxBatchSize) flush() else scheduleFlush()
    }

    /**
     * Merges `patch` into the user's properties; an empty-string value removes that key. Only a
     * change is sent - as `user.identify` whose metadata is the whole merged set, so the server
     * stores it as-is. Keys are clamped to 40 characters and values to 200, the limits the ingest
     * API applies, so what the SDK remembers is exactly what the server stored.
     */
    fun identify(patch: Map<String, String>, at: Long? = null) {
        if (!collecting || retired) return
        val merged = LinkedHashMap(traits)
        for ((key, value) in patch) {
            val k = key.trim().take(MAX_TRAIT_KEY_LENGTH)
            if (k.isEmpty()) continue
            val v = value.trim().take(MAX_TRAIT_VALUE_LENGTH)
            if (v.isEmpty()) merged.remove(k) else merged[k] = v
        }
        // The ingest API keeps at most 20 metadata keys per event; beyond that the extra
        // properties would be dropped server-side, so cap here - reserved keys first.
        val capped: Map<String, String> = if (merged.size > MAX_TRAITS) {
            val keep = merged.keys
                .sortedWith(compareBy<String> { !it.startsWith("$") }.thenBy { it })
                .take(MAX_TRAITS)
                .toSet()
            merged.filterKeys { it in keep }
        } else {
            merged
        }
        if (capped == traits) return
        traits = capped
        persistTraits()
        track(Signal.IDENTIFY, capped, at)
    }

    /**
     * Forgets every property attached to this install (sign-out) and sends `user.reset` if there
     * was anything to forget. The install id itself is untouched.
     */
    fun reset(at: Long? = null) {
        if (!collecting || retired || traits.isEmpty()) return
        traits = emptyMap()
        persistTraits()
        track(Signal.RESET, null, at)
    }

    /**
     * Foreground / background transitions. Idempotent: the platform reports both redundantly (two
     * activities starting, `onStart` after a configuration change), and each pair must act exactly
     * once. Active after more than `sessionTimeout` away starts a session, whether the app was
     * cold-launched or resumed; inactive stops the heartbeat and flushes.
     */
    fun setActive(active: Boolean, at: Long? = null) {
        if (!collecting || retired || active == isActive) return
        isActive = active
        val t = at ?: now()
        if (active) {
            val last = lastActiveAt
            val resumes = last != null && sessionId != null && !sessionIdPreminted &&
                t - last <= config.sessionTimeout.inWholeMilliseconds
            if (!resumes) {
                // A pre-minted id (already on every event recorded since init) becomes the
                // session's id, exactly once; without one this is an in-process new session (the
                // app backgrounded past the timeout and came back) and the id is minted here.
                if (!sessionIdPreminted) sessionId = UUID.randomUUID().toString()
                sessionIdPreminted = false
                prefs.remove(pendingSessionKey)
                track(Signal.SESSION_START, null, t)
            }
            rememberActive(t)
            startHeartbeat()
        } else {
            stopHeartbeat()
            rememberActive(t)
            flush()
        }
    }

    /**
     * Sends the queue now. Cancels the flush timer and asks the send loop to drain, joining a send
     * already in progress rather than racing it: the slice is claimed out of the queue before the
     * network call, so an overlapping flush never re-sends the same events.
     */
    fun flush() {
        flushTask?.let(scheduler::cancel)
        flushTask = null
        requestDrain()
    }

    // endregion

    // region Sessions and the heartbeat

    /**
     * Gives the coming session its id at init time, whenever init can already tell that the next
     * foreground will start a new session: there is no resumable session (a fresh install), or
     * the gap since the last activity is past `sessionTimeout`. `install` and everything else
     * recorded before the first foreground then carry the same id `session.start` adopts, so the
     * server sees one session per launch instead of minting a second row for id-less early events.
     *
     * The id is persisted under its own key the moment it is minted and stays there until a
     * `session.start` adopts it. A process that dies before ever reaching the foreground therefore
     * does not strand it: the next launch finds the unadopted id and reuses it instead of minting
     * another, and the events already queued under it keep their session. [setActive] adopts it
     * exactly once, re-checking the gap against its own clock; the within-timeout resume path
     * never involves it, because a live session's recent stamp means nothing was pre-minted.
     */
    private fun premintSessionIdIfNeeded() {
        if (!collecting) return   // a gated client records nothing and must not write state
        val last = lastActiveAt
        val resumes = last != null && sessionId != null && now() - last <= config.sessionTimeout.inWholeMilliseconds
        if (resumes) return
        sessionId = prefs.getString(pendingSessionKey) ?: UUID.randomUUID().toString().also {
            prefs.putString(pendingSessionKey, it)
        }
        sessionIdPreminted = true
    }

    /**
     * The moment we last knew the app was in front of the user; the session timeout counts from
     * here. Refreshed on every transition and every heartbeat, so a process killed while in the
     * foreground still leaves a recent stamp behind.
     */
    private fun rememberActive(t: Long) {
        lastActiveAt = t
        prefs.putLong(lastActiveKey, t)
        sessionId?.let { prefs.putString(sessionKey, it) } ?: prefs.remove(sessionKey)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        // Back from a brief interruption: finish the interval that was running rather than tick
        // again straight away.
        val interval = config.heartbeatInterval.inWholeMilliseconds
        val wait = lastHeartbeatAt?.let { (interval - (now() - it)).coerceAtLeast(0L) } ?: 0L
        scheduleHeartbeat(wait)
    }

    private fun scheduleHeartbeat(delayMillis: Long) {
        val task = object : Runnable {
            override fun run() {
                if (heartbeatTask !== this) return   // cancelled or replaced meanwhile
                heartbeat()
                scheduleHeartbeat(config.heartbeatInterval.inWholeMilliseconds)
            }
        }
        heartbeatTask = task
        scheduler.postDelayed(task, delayMillis)
    }

    private fun stopHeartbeat() {
        heartbeatTask?.let(scheduler::cancel)
        heartbeatTask = null
    }

    private fun heartbeat() {
        val t = now()
        lastHeartbeatAt = t
        rememberActive(t)
        track(Signal.HEARTBEAT, null, t)
    }

    // endregion

    // region Delivery

    private fun scheduleFlush() {
        if (flushTask != null) return
        val task = object : Runnable {
            override fun run() {
                if (flushTask !== this) return   // an explicit flush cancelled this timer
                flushTask = null
                flush()
            }
        }
        flushTask = task
        scheduler.postDelayed(task, config.flushInterval.inWholeMilliseconds)
    }

    /**
     * At most one drain runs at a time (the executor is serial) and at most one more is queued
     * behind it: a flush that arrives mid-send starts after the running one and finds whatever it
     * left.
     */
    private fun requestDrain() {
        if (drainPending.compareAndSet(false, true)) sendExecutor.execute(::drain)
    }

    /**
     * Sends the queue in request-sized slices, oldest first, until it is empty or the network says
     * "later". Each slice is claimed out of the queue before the request, so events tracked
     * meanwhile line up behind it.
     */
    private fun drain() {
        drainPending.set(false)
        var slice = MAX_EVENTS_PER_REQUEST
        while (true) {
            val batch: List<Event> = synchronized(queueLock) {
                if (retired || queue.isEmpty()) return
                val n = minOf(slice, queue.size)
                List(n) { queue.removeFirst() }.also {
                    inFlightBatch = it
                    persistLocked()
                }
            }
            val count = "${batch.size} event${if (batch.size == 1) "" else "s"}"
            log { "↑ sending $count…" }
            val status = transport.send(EventCoding.encodeBytes(batch))
            when {
                status in 200..299 -> {
                    synchronized(queueLock) {
                        inFlightBatch = emptyList()
                        persistLocked()
                    }
                    log { "✓ sent $count" }
                }

                status == 413 && batch.size > 1 -> {
                    // Too big for one request: put it back and go smaller for the rest of this
                    // drain. The server rejected the body without processing any of it, so its
                    // heartbeats were never counted and are safe to re-send.
                    log { "↩ 413 - the batch is too big for one request; retrying in smaller slices" }
                    requeue(batch, keepingHeartbeats = true)
                    slice = maxOf(1, batch.size / 2)
                }

                isPermanent(status) -> {
                    // A 4xx no retry will fix - unknown key, malformed, one oversized event.
                    // Dropping the slice beats a queue that can never drain again.
                    val keyHint = when (status) {
                        401, 403 -> "check the write key (Setup tab in the dashboard); "
                        else -> ""
                    }
                    log { "✕ HTTP $status - ${keyHint}a retry could never succeed, so this batch is dropped" }
                    synchronized(queueLock) {
                        inFlightBatch = emptyList()
                        persistLocked()
                    }
                }

                else -> {
                    // Offline, 5xx, 429: keep everything, in order, for a later attempt.
                    val why = if (status < 0) "no response" else "HTTP $status"
                    log { "⟳ couldn't send ($why) - keeping $count for the next try" }
                    requeue(batch, keepingHeartbeats = status == Transport.NEVER_CONNECTED)
                    return
                }
            }
        }
    }

    /**
     * Puts a batch back at the front of the queue, ahead of anything queued meanwhile.
     *
     * Heartbeats are kept only when the server definitely did not process the batch. Every other
     * signal carries an event id and the server ignores a replay, so retrying it is free;
     * heartbeats are folded into presence and session-length rollups on arrival, and a re-sent one
     * is counted twice, permanently. A dropped tick costs a minute of resolution on a chart that is
     * approximate by design; a doubled tick corrupts the numbers.
     */
    private fun requeue(batch: List<Event>, keepingHeartbeats: Boolean) {
        val retryable = if (keepingHeartbeats) batch else batch.filter { it.signal != Signal.HEARTBEAT }
        val dropped = batch.size - retryable.size
        if (dropped > 0) {
            val (noun, pronoun) = if (dropped == 1) "presence ping" to "it" else "presence pings" to "them"
            log { "· dropped $dropped $noun rather than risk counting $pronoun twice" }
        }
        synchronized(queueLock) {
            inFlightBatch = emptyList()
            for (i in retryable.indices.reversed()) queue.addFirst(retryable[i])
            trimLocked()
            persistLocked()
        }
    }

    /** Overflow drops the oldest - the newest events are the ones the dashboard is missing. */
    private fun trimLocked() {
        while (queue.size > MAX_QUEUED_EVENTS) queue.removeFirst()
    }

    /**
     * Writes what is still owed to disk: the queue, plus the in-flight slice's non-heartbeat
     * events. If the process is killed before the response arrives, the next launch re-sends those
     * (the server ignores replays by event id) and never the heartbeats, which may already have
     * been counted. Refused once retired, so a replaced client cannot clobber the file.
     */
    private fun persistLocked() {
        if (retired) return
        val owed = inFlightBatch.filter { it.signal != Signal.HEARTBEAT } + queue
        try {
            queueStore.save(EventCoding.encode(owed))
        } catch (_: Exception) {
            // Best effort: a full disk must not take the app down with it.
        }
    }

    // endregion

    // region User properties persistence

    private fun persistTraits() {
        if (traits.isEmpty()) prefs.remove(traitsKey) else prefs.putString(traitsKey, encodeTraits(traits))
    }

    // endregion

    // region Test hooks

    /** Signals waiting for the next flush, in order. */
    fun pendingSignals(): List<String> = synchronized(queueLock) { queue.map { it.signal } }

    /** The queued events themselves, in order. */
    fun pendingEvents(): List<Event> = synchronized(queueLock) { queue.toList() }

    /** The user properties as the SDK believes the server has them. */
    fun currentTraits(): Map<String, String> = traits

    /**
     * The session id every recorded event is stamped with: pre-minted at init when a new session
     * is coming, the resumed one otherwise. Null only for a gated client that never had one.
     */
    fun currentSessionId(): String? = sessionId

    // endregion

    companion object {
        /** Hard cap on the queue, so repeated failures cannot grow it without bound. */
        const val MAX_QUEUED_EVENTS: Int = 500

        /**
         * Events per request. The ingest API accepts up to 500 events / 256 KB per batch; a
         * long-offline queue drains in slices this size rather than as one oversized POST.
         */
        const val MAX_EVENTS_PER_REQUEST: Int = 100

        const val MAX_TRAITS: Int = 20
        const val MAX_TRAIT_KEY_LENGTH: Int = 40
        const val MAX_TRAIT_VALUE_LENGTH: Int = 200

        /**
         * Client errors that mean "this batch will never be accepted as is". 408 (timeout), 425
         * (too early) and 429 (rate limited) are the 4xx that do deserve a retry.
         */
        fun isPermanent(status: Int): Boolean = status in 400..499 && status !in setOf(408, 425, 429)

        fun encodeTraits(traits: Map<String, String>): String {
            val o = JSONObject()
            for ((k, v) in traits) o.put(k, v)
            return o.toString()
        }

        fun decodeTraits(json: String): Map<String, String>? = try {
            val o = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            for (key in o.keys()) if (!o.isNull(key)) map[key] = o.optString(key)
            map
        } catch (_: JSONException) {
            null
        }
    }
}
