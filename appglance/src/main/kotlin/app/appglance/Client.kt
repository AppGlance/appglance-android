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
    /** Uniform in `[0, 1)`; seam for the backoff jitter, so tests can pin it. */
    private val random: () -> Double = Math::random,
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

    // Automatic-retry backoff. Written by the send loop, read by the triggers on the command
    // thread, hence under [queueLock]. See [backOff].
    private var failureStreak = 0
    private var nextAttemptAt = 0L

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
    /**
     * The newest ping the server has acknowledged. Seeded in `init` and thereafter written and
     * read on the send loop only, which is serial; see [rollBackHeartbeatStamp].
     */
    private var deliveredHeartbeatAt: Long? = null
    /**
     * When this install last recorded a real (non-heartbeat) event. A real event proves presence
     * exactly as a ping does - the server moves the same "last seen" stamps for both - so the
     * heartbeat waits for `heartbeatInterval` of *silence*, measured from the later of this and
     * [lastHeartbeatAt]. Persisted for the same reason the ping stamp is: a relaunch inside
     * `sessionTimeout` resumes and records no `session.start`, and a session shorter than one
     * interval leaves no ping stamp behind either, so without this a fresh process would owe a
     * ping at once however recently the app was really heard from.
     */
    private var lastEventAt: Long? = null

    /**
     * The server's floor for the presence cadence, in milliseconds, from the ingest response
     * (`heartbeat_interval`, seconds). Plans differ in how often they need to hear from an install;
     * the effective interval is the larger of this and the configured one. Written by the send
     * loop, read on the command thread, hence volatile; persisted so a launch paces itself
     * correctly before its first response arrives.
     */
    @Volatile
    private var serverHeartbeatFloorMillis: Long? = null
    private var sessionId: String? = null
    /** True while [sessionId] is pre-minted for the coming session and no `session.start` has adopted it yet. */
    private var sessionIdPreminted = false
    private val lastActiveKey = "lastActive.$appId"
    private val lastHeartbeatKey = "lastHeartbeat.$appId"
    private val lastEventKey = "lastEvent.$appId"
    private val heartbeatFloorKey = "heartbeatFloor.$appId"
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
        // The ping stamp survives a relaunch for the same reason the session does: the interval is
        // a wall-clock promise, at most one presence ping per interval, and the server folds pings
        // into additive rollups that a doubled tick would inflate.
        lastHeartbeatAt = prefs.getLong(lastHeartbeatKey)
        // A ping an earlier process stamped is the best proof of delivery this one can have: the
        // queue file never holds a ping that was in flight, so there is nothing left to re-send
        // either way. A roll-back therefore undoes only this process's own unconfirmed pings.
        deliveredHeartbeatAt = lastHeartbeatAt
        // The event stamp comes back with it - it is the other half of the silence the interval
        // measures, and a resumed session records no event of its own to replace it.
        lastEventAt = prefs.getLong(lastEventKey)
        serverHeartbeatFloorMillis = prefs.getLong(heartbeatFloorKey)?.takeIf { isSaneHeartbeatFloorMillis(it) }
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
        // A real event is presence: the next ping is due only after an interval of silence from
        // here. (Ping stamps are kept separately, in [heartbeat].) Persisted like the ping stamp,
        // on the code path that just wrote the queue file anyway, so one small write more.
        if (signal != Signal.HEARTBEAT) {
            lastEventAt = event.clientTs
            prefs.putLong(lastEventKey, event.clientTs)
        }
        log {
            "▸ $signal" + (if (signal == Signal.HEARTBEAT) " (presence ping)" else "") +
                (metadata?.let { " $it" } ?: "")
        }
        if (size >= config.maxBatchSize) flushSoon() else scheduleFlush()
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
            // The closing tick. If the server has heard nothing from this install for a while, one
            // last ping goes out with the flush that follows, so the session's length on the
            // dashboard ends where the visit actually ended rather than at the last thing that
            // happened to be sent. Free at a one-minute cadence (the stamp is never that old); one
            // extra ping per silent visit at the sparser cadences a plan may ask for.
            if (t - (lastPresenceAt() ?: Long.MIN_VALUE) > CLOSING_TICK_AFTER_MILLIS) {
                stampHeartbeat(t)
                track(Signal.HEARTBEAT, null, t)
                log { "· closing presence ping (quiet for over a minute)" }
            }
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

    /**
     * The presence cadence in force: the larger of what the app configured and what the server
     * asked for. Exposed for tests.
     */
    internal fun heartbeatIntervalMillis(): Long =
        maxOf(config.heartbeatInterval.inWholeMilliseconds, serverHeartbeatFloorMillis ?: 0L)

    /**
     * The last moment the server has (or will have, once the queue flushes) proof that this
     * install was in front of someone: the later of the last ping and the last real event.
     */
    private fun lastPresenceAt(): Long? {
        val a = lastHeartbeatAt
        val b = lastEventAt
        return when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }
    }

    /**
     * Milliseconds until the next ping is due: a full interval of silence after the last proof
     * of presence. Zero means now - including the case where nothing has been sent yet, such as
     * a resumed session that started before this process.
     */
    private fun millisUntilNextHeartbeat(): Long {
        val last = lastPresenceAt() ?: return 0L
        return (heartbeatIntervalMillis() - (now() - last)).coerceAtLeast(0L)
    }

    /**
     * One timer per foreground stretch. It never fires blindly every interval: it is scheduled
     * for when the next ping is due, and when it fires it looks again, because in the meantime a
     * real event may have proved presence (pushing the due moment out) or the server may have
     * asked for a sparser cadence. Only when the install has been silent for a whole interval
     * does it tick.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        scheduleHeartbeat(millisUntilNextHeartbeat())
    }

    private fun scheduleHeartbeat(delayMillis: Long) {
        val task = object : Runnable {
            override fun run() {
                if (heartbeatTask !== this) return   // cancelled or replaced meanwhile
                val wait = millisUntilNextHeartbeat()
                if (wait > 0L) {
                    scheduleHeartbeat(wait)          // something proved presence since; look again then
                    return
                }
                heartbeat()
                scheduleHeartbeat(heartbeatIntervalMillis())
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
        stampHeartbeat(t)
        rememberActive(t)
        track(Signal.HEARTBEAT, null, t)
    }

    /**
     * The ping stamp is a wall-clock promise (at most one ping per interval, across relaunches),
     * so it is persisted the moment it moves.
     */
    private fun stampHeartbeat(t: Long) {
        lastHeartbeatAt = t
        prefs.putLong(lastHeartbeatKey, t)
    }

    /**
     * Remembers the newest ping in a batch the server accepted. Called from the send loop.
     */
    private fun noteDeliveredHeartbeats(batch: List<Event>) {
        val newest = batch.filter { it.signal == Signal.HEARTBEAT }.maxOfOrNull { it.clientTs } ?: return
        if (newest > (deliveredHeartbeatAt ?: Long.MIN_VALUE)) deliveredHeartbeatAt = newest
    }

    /**
     * Undoes the stamp of a ping that [requeue] has just dropped, putting it back to the newest
     * ping the server acknowledged.
     *
     * The stamp is written when a ping is *queued*, because it is what paces the timer and a ping
     * still on the wire must not earn a second one. But a dropped ping is one the server may never
     * have seen, and leaving its stamp in place spends a whole fresh interval before the install
     * proves presence again. At 60 s that costs a minute of resolution on a chart that is
     * approximate by design; at the four-minute cadence a free-plan account is asked for, two
     * intervals back to back are longer than the dashboard's five-minute presence window, so an
     * install that is in the foreground the whole time drops out of "active right now" for three
     * of them. Re-arming from the last acknowledged ping instead measures the silence that is
     * really outstanding, and the replacement never piles up: the next failure drops the ping this
     * one queues, so at most one unacknowledged ping is ever in the queue.
     *
     * The trade is one tick against another. If the dropped ping did land after all - a reply lost
     * on the way back - the replacement is a second tick inside one interval, exactly the size of
     * error a dropped tick is when it did not land. The answered failures that can reach a
     * presence-only batch (a 429, an edge 5xx, a connection dropped after connect) are
     * overwhelmingly ones the ingest never applied, and presence is the number the product is read
     * for, so proving it again is the right way round. [MIN_HEARTBEAT_RETRY_MILLIS] keeps the two
     * apart in the case where it was not.
     *
     * Called from the send loop, so the stamp and the timer are touched back on the command
     * thread that owns them, and only while the batch's own stamp is still the current one.
     */
    private fun rollBackHeartbeatStamp(newestDropped: Long) {
        val restored = deliveredHeartbeatAt
        scheduler.post {
            if (retired) return@post
            val current = lastHeartbeatAt ?: return@post
            if (current > newestDropped) return@post   // a later ping was stamped meanwhile; it paces us now
            lastHeartbeatAt = restored
            if (restored == null) prefs.remove(lastHeartbeatKey) else prefs.putLong(lastHeartbeatKey, restored)
            if (isActive) {
                stopHeartbeat()
                scheduleHeartbeat(maxOf(millisUntilNextHeartbeat(), MIN_HEARTBEAT_RETRY_MILLIS))
            }
        }
    }

    /**
     * The server's answer to a batch may carry `heartbeat_interval` (seconds): the sparsest
     * presence cadence the account's plan needs. It is a floor, never a ceiling - an app that
     * configured a longer interval keeps it - and it is remembered across launches. Out-of-range
     * values are ignored rather than obeyed: 15 s is the tightest cadence that has any meaning to
     * the dashboard's 5-minute presence window, and past an hour a "presence" ping is not one.
     * Called from the send loop; the timer picks the new value up the next time it looks.
     */
    private fun adoptHeartbeatFloor(seconds: Long?) {
        val millis = (seconds ?: return) * 1_000L
        if (!isSaneHeartbeatFloorMillis(millis) || millis == serverHeartbeatFloorMillis) return
        serverHeartbeatFloorMillis = millis
        prefs.putLong(heartbeatFloorKey, millis)
        log {
            "· presence pings at most every ${heartbeatIntervalMillis() / 1_000} s (the server asked for $seconds s)"
        }
    }

    private fun isSaneHeartbeatFloorMillis(millis: Long): Boolean = millis in 15_000L..3_600_000L

    // endregion

    // region Delivery

    /**
     * An automatic send trigger (the batch filling up, the flush timer firing): drains now unless
     * a retry backoff is in force, in which case the attempt is deferred to when the window closes
     * rather than dropped. [flush] is the unthrottled path for the developer's own call and for
     * the flush on backgrounding - the first is "send now" said explicitly, the second is the last
     * chance before the process may die, already paced by the user's own comings and goings.
     */
    private fun flushSoon() {
        val holdMillis = synchronized(queueLock) { nextAttemptAt - now() }
        if (holdMillis <= 0) flush() else scheduleFlush(holdMillis)
    }

    private fun scheduleFlush(delayMillis: Long = config.flushInterval.inWholeMilliseconds) {
        if (flushTask != null) return
        val task = object : Runnable {
            override fun run() {
                if (flushTask !== this) return   // an explicit flush cancelled this timer
                flushTask = null
                flushSoon()                      // re-checks the backoff; defers again if still inside it
            }
        }
        flushTask = task
        scheduler.postDelayed(task, delayMillis)
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
                        failureStreak = 0
                        nextAttemptAt = 0L
                        persistLocked()
                    }
                    log { "✓ sent $count" }
                    noteDeliveredHeartbeats(batch)
                    adoptHeartbeatFloor(transport.lastHeartbeatIntervalSeconds())
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
                    backOff(retryAfterSeconds = if (status == 429) transport.lastRetryAfterSeconds() else null)
                    return
                }
            }
        }
    }

    /**
     * Arms the automatic-retry throttle after a retryable failure. The next automatic drain waits
     * `min(60 s, 2^failures seconds)`, jittered into the upper half of that window so clients that
     * failed together do not all retry together, and never less than the server's own numeric
     * `Retry-After` on a 429. A successful send clears it; [flush] ignores it (see [flushSoon]).
     */
    private fun backOff(retryAfterSeconds: Long?) {
        synchronized(queueLock) {
            failureStreak++
            val base = minOf(MAX_BACKOFF_MILLIS, (1L shl minOf(failureStreak, 6)) * 1_000L)
            val jittered = base / 2 + (random() * (base / 2)).toLong()
            nextAttemptAt = now() + maxOf(jittered, (retryAfterSeconds ?: 0L) * 1_000L)
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
            // Dropped, so not proof of anything: the next ping is owed from the last one the
            // server acknowledged, not from these. See [rollBackHeartbeatStamp].
            rollBackHeartbeatStamp(batch.filter { it.signal == Signal.HEARTBEAT }.maxOf { it.clientTs })
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

        /** Ceiling on the automatic-retry backoff window. */
        const val MAX_BACKOFF_MILLIS: Long = 60_000L

        /** A background transition sends one last ping if the server has heard nothing for this long. */
        const val CLOSING_TICK_AFTER_MILLIS: Long = 60_000L

        /**
         * The soonest a ping may replace one that was dropped (see [rollBackHeartbeatStamp]). Far
         * enough out that a ping which did land after all is not followed by a second tick in the
         * same second; short enough that the sparsest cadence a plan asks for (240 s) plus one
         * dropped ping still keeps the install inside the dashboard's five-minute presence window.
         * It is also the finest presence resolution `heartbeatInterval` itself allows.
         */
        const val MIN_HEARTBEAT_RETRY_MILLIS: Long = 15_000L

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
