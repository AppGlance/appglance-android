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

    /**
     * The queue file as this client last left it; see [persistLocked]. Guarded by [queueLock],
     * like the queue itself.
     */
    private var lastWrittenQueueJson: String? = null

    /** Set by [shutdown] once a later `configure` has replaced this client. */
    @Volatile
    private var retired = false

    private var flushTask: Runnable? = null
    private var heartbeatTask: Runnable? = null
    private val drainPending = AtomicBoolean(false)
    /**
     * Set by [flush], read and cleared by the drain that acts on it: the developer's own "now"
     * travels with the request as far as [drain], which is where the backoff is read.
     */
    private val drainUnthrottled = AtomicBoolean(false)

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
    /**
     * The last foreground state anybody reported, whether or not this client acted on it; null
     * until something reports one. [isActive] cannot answer that question: it is what this client
     * did, and a client built by a second `configure` starts inactive with the app already in
     * front of the user. See [reportedForegroundState].
     */
    private var reportedActive: Boolean? = null
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

    // User properties. [traits] is the snapshot the server has ACKNOWLEDGED: it moves only when a
    // batch carrying the `user.identify` (or `user.reset`) that changed it comes back accepted, so
    // `identify` with the same values on every launch stays free while what it recorded is really
    // stored, and starts sending again the moment a carrying event is lost. What an event still
    // owed will leave the server with is read from the queue instead of being kept beside this,
    // because the queue is the one place that knows whether that event still exists: a trim, a
    // permanent 4xx, a deleted queue file and a relaunch all move it, and a second copy of the
    // answer would disagree with the first. See [pendingTraits].
    private var traits: Map<String, String>
    private val traitsKey = "traits.$appId"

    /**
     * Set while this install still owes its `install` event, and cleared the moment the event is
     * queued. The launch that mints the install id is not always one that can record: the
     * environment gate excludes debuggable builds and emulators by default, and an app waiting for
     * consent configures with `isEnabled = false`, so [track] is a no-op on both. [isNewInstall] is
     * true on that one launch only, so without a note on disk the event is owed by a launch that is
     * already over and is lost for the life of the install. Nothing written means nothing owed,
     * which is how an install whose storage carries no marker reads: it was counted on the launch
     * that minted its id.
     */
    private val installPendingKey = "install.pending.$appId"
    private var installRecorded = false

    init {
        // A minted id means this device is not the one the state below was written on. The install
        // id is device-bound (it is honoured only where the marker for the device that minted it
        // still matches), but everything else lives in the same SharedPreferences, which Auto
        // Backup and a device-to-device transfer both carry - so a restored handset mints its own
        // id and then reads the old device's session, presence stamps and user properties as its
        // own. The traits are the lasting half: `identify` only sends a change, so an install
        // whose cache says the server already has these properties never sends them, and its page
        // in the dashboard stays empty however often the app calls `identify`. A genuinely new
        // install has nothing here to clear, so this costs it nothing.
        if (isNewInstall) forgetPersistedState()
        val bootTime = now()
        restoredStamp(prefs.getLong(lastActiveKey), bootTime)?.let {
            lastActiveAt = it
            sessionId = prefs.getString(sessionKey)
        }
        // The ping stamp survives a relaunch for the same reason the session does: the interval is
        // a wall-clock promise, at most one presence ping per interval, and the server folds pings
        // into additive rollups that a doubled tick would inflate.
        lastHeartbeatAt = restoredStamp(prefs.getLong(lastHeartbeatKey), bootTime)
        // A ping an earlier process stamped is the best proof of delivery this one can have: the
        // queue file never holds a ping that was in flight, so there is nothing left to re-send
        // either way. A roll-back therefore undoes only this process's own unconfirmed pings.
        deliveredHeartbeatAt = lastHeartbeatAt
        // The event stamp comes back with it - it is the other half of the silence the interval
        // measures, and a resumed session records no event of its own to replace it.
        lastEventAt = restoredStamp(prefs.getLong(lastEventKey), bootTime)
        serverHeartbeatFloorMillis = prefs.getLong(heartbeatFloorKey)?.takeIf { isSaneHeartbeatFloorMillis(it) }
        premintSessionIdIfNeeded()
        traits = prefs.getString(traitsKey)?.let(::decodeTraits) ?: emptyMap()
        // Consent withdrawal applies to what is already on disk, not only to what happens next.
        // The way an app honours it is to `configure` again with `isEnabled = false`, and the
        // events recorded before that are exactly the ones consent was withdrawn for: a later
        // `flush()` must not ship them, and turning the switch back on must not resurrect them.
        //
        // The delete is keyed on `isEnabled` alone, NOT on `collecting`. `collecting` also folds
        // in the environment gate, and that gate closing is not a withdrawal of consent: a
        // debuggable build run over an installed release copy closes it for that run, and
        // deleting the file there would throw away a real queue the release build saved during an
        // outage. A closed environment gate stops the queue being loaded; only a closed consent
        // switch destroys it.
        // Trimmed on the way in, not left to the next `track`. The file holds what is OWED,
        // which is the queue plus the non-ping half of the slice that was on the wire, so it can
        // carry MAX_EVENTS_PER_REQUEST more than the queue's own cap. Restoring it whole started
        // the launch over that cap and kept it there until something else was tracked, which is
        // how a documented 500-event ceiling became 600 on the launch after a crash.
        if (collecting) {
            queueStore.load()?.let {
                queue.addAll(EventCoding.decode(it))
                trimLocked()
            }
        }
        if (!config.isEnabled) {
            queueStore.delete()
            // The person goes with the events. `$email`, `$name` and `$id` are the only personal
            // data the SDK puts on disk, they live in the same SharedPreferences, inside Auto
            // Backup, and the one API that clears them, `reset()`, records nothing on a client
            // that is not collecting - so an app that turns the switch off first can never reach
            // them again. Keyed on `isEnabled` for the same reason the delete above is: a closed
            // environment gate is not a withdrawal, and clearing on one would throw away the
            // release build's record of what the server holds, from a build sharing its storage.
            // The install id itself stays: turning collection back on has to be the same install,
            // not a new one.
            prefs.remove(traitsKey)
            traits = emptyMap()
        }
        announce()
    }

    /**
     * Drops every persisted trace of an install that is not this one: the session and its pending
     * id, the presence stamps, the server's cadence floor, an `install` another install still owed
     * and the user properties. The queue file is not among them - it lives outside the backup set
     * and so never arrives on a second device in the first place.
     */
    private fun forgetPersistedState() {
        val keys = listOf(
            lastActiveKey,
            lastHeartbeatKey,
            lastEventKey,
            heartbeatFloorKey,
            sessionKey,
            pendingSessionKey,
            installPendingKey,
            traitsKey,
        )
        for (key in keys) prefs.remove(key)
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

    /**
     * Records `install`, exactly once per install, ahead of everything else.
     *
     * A launch that mints the install id and cannot record writes the debt down instead, and the
     * first launch that is collecting pays it, stamped with its own `configure`: the moment this
     * install can first be counted, and the moment the rest of that first batch belongs to. An
     * older stamp would date the install to a run the app asked to be left out of its numbers.
     * Only a launch that mints the id ever writes the marker, so a debuggable build run over an
     * installed release copy leaves nothing behind and no install is ever recorded twice.
     */
    fun recordInstallIfNeeded() {
        if (installRecorded || retired) return
        val owed = prefs.getBoolean(installPendingKey)
        if (!isNewInstall && !owed) return
        if (!collecting) {
            // The facade asks again on every command; write only what is not already there.
            if (!owed) prefs.putBoolean(installPendingKey, true)
            return
        }
        installRecorded = true
        // Cleared before the event is queued, as the pre-minted session id's marker is: a death in
        // between costs this install its `install`, where clearing it afterwards would record a
        // second one on the next launch. One uncounted install beats one counted twice.
        prefs.remove(installPendingKey)
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
     *
     * "A change" is measured against what the server will hold once everything already owed has
     * landed, not against what it holds now: an identify made while an earlier one is still queued
     * or on the wire merges on top of that one and never re-sends it.
     */
    fun identify(patch: Map<String, String>, at: Long? = null) {
        if (!collecting || retired) return
        val known = pendingTraits() ?: traits
        val merged = LinkedHashMap(known)
        for ((key, value) in patch) {
            val k = clamped(key, MAX_TRAIT_KEY_LENGTH)
            if (k.isEmpty()) continue
            val v = clamped(value, MAX_TRAIT_VALUE_LENGTH)
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
        // The acknowledged snapshot is deliberately not touched here: it names what the server
        // has, and this call's event has not been sent yet, let alone accepted. It moves in
        // [noteDeliveredTraits] and nowhere else.
        if (capped == known) return
        track(Signal.IDENTIFY, capped, at)
    }

    /**
     * Forgets every property attached to this install (sign-out) and sends `user.reset` if there
     * was anything to forget. The install id itself is untouched.
     *
     * The stored snapshot is cleared here rather than when the `user.reset` lands, and this is the
     * one place where clearing ahead of delivery is right. An empty snapshot suppresses nothing:
     * every later `identify` sends its whole set whatever became of the reset, and each one
     * replaces the stored set wholesale server-side. Holding the person's email and name on disk
     * until the server acknowledged the sign-out would keep them exactly where the sign-out asked
     * for them to be gone.
     */
    fun reset(at: Long? = null) {
        if (!collecting || retired || (pendingTraits() ?: traits).isEmpty()) return
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
        // Recorded ahead of every guard below, including the ones that drop the report: what the
        // app said is worth keeping even when this client does nothing with it, because it is the
        // only thing a replacement client can be told the foreground state with.
        reportedActive = active
        if (!collecting || retired || active == isActive) return
        isActive = active
        val t = at ?: now()
        if (active) {
            val resumes = sessionId != null && !sessionIdPreminted &&
                millisSince(lastActiveAt, t) <= config.sessionTimeout.inWholeMilliseconds
            if (!resumes) {
                if (sessionIdPreminted) {
                    // A pre-minted id (already on every event recorded since init) becomes the
                    // session's id, exactly once. The marker clears before the event is queued, so
                    // a death in between mints a fresh id rather than re-adopting one whose
                    // `session.start` is already in the persisted queue.
                    sessionIdPreminted = false
                    prefs.remove(pendingSessionKey)
                } else {
                    // An in-process new session: the app backgrounded past the timeout and came
                    // back. The id is written down before the event that carries it, as the
                    // pre-minted one is, because `track` persists the queue: a death in between
                    // would otherwise leave a `session.start` for an id nothing on disk names, and
                    // the next launch inside the timeout would resume the id before it and file
                    // the whole visit under a session it never opened.
                    val minted = UUID.randomUUID().toString()
                    sessionId = minted
                    prefs.putString(sessionKey, minted)
                }
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
            if (millisSince(lastPresenceAt(), t) > CLOSING_TICK_AFTER_MILLIS) {
                stampHeartbeat(t)
                track(Signal.HEARTBEAT, null, t)
                log { "· closing presence ping (quiet for over a minute)" }
            }
            rememberActive(t)
            flush()
        }
    }

    /**
     * The foreground state last reported to this client, for the client that replaces it; null
     * when nothing was ever reported. A second `configure` is the documented way to apply a
     * consent change, and it can happen with the app in front of the user - on the settings screen
     * the switch lives on. Nothing re-reports the state to the replacement: `ProcessLifecycleOwner`
     * replays the lifecycle only to a newly added observer, and an app that turns
     * `trackAppLifecycle` off drives [setActive] from its own `onStart`, which has already been and
     * gone. Left inactive for the rest of the visit, that client records no `session.start`, sends
     * no presence ping and does not flush on the way to the background.
     */
    fun reportedForegroundState(): Boolean? = reportedActive

    /**
     * Sends the queue now, whatever the retry backoff says. Cancels the flush timer and asks the
     * send loop to drain, joining a send already in progress rather than racing it: the slice is
     * claimed out of the queue before the network call, so an overlapping flush never re-sends the
     * same events.
     */
    fun flush() = requestDrain(unthrottled = true)

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
        // The unadopted id is read before the gap is measured, and wins over it: an id sits under
        // that key only because a process minted it, recorded events under it and died before any
        // foreground, so those events are on disk carrying it and the session it belongs to has
        // still never been opened. A stamp saying the last session is resumable cannot be true at
        // the same moment, and when the two disagree - a clock corrected backwards under a stamp
        // this install wrote - believing the stamp strands the id and every event under it in a
        // session the server is never told about.
        val unadopted = prefs.getString(pendingSessionKey)
        if (unadopted == null) {
            val resumes = sessionId != null &&
                millisSince(lastActiveAt, now()) <= config.sessionTimeout.inWholeMilliseconds
            if (resumes) return
        }
        sessionId = unadopted ?: UUID.randomUUID().toString().also { prefs.putString(pendingSessionKey, it) }
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
     * a resumed session that started before this process. Never more than one interval, whatever
     * the stamps say: this number becomes the delay the whole presence loop is paced by, and a
     * delay that outlives the visit is the same thing as no presence at all. Exposed for tests.
     */
    internal fun millisUntilNextHeartbeat(): Long {
        val silence = millisSince(lastPresenceAt(), now())
        val interval = heartbeatIntervalMillis()
        return if (silence >= interval) 0L else interval - silence
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
     * ping the server acknowledged - floored so the replacement stays [MIN_HEARTBEAT_RETRY_MILLIS]
     * away from the ping it replaces.
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
        val acknowledged = deliveredHeartbeatAt
        scheduler.post {
            if (retired) return@post
            val current = lastHeartbeatAt ?: return@post
            if (current > newestDropped) return@post   // a later ping was stamped meanwhile; it paces us now
            // The floor lives in the stamp, not only in the timer the re-arm below restarts. The
            // re-arm happens only while the app is in front, and the visit can end, or the
            // process die, between the drop and the replacement - a background bounce, or a kill
            // and a relaunch - and either would otherwise tick the moment the app came back,
            // seconds after the ping it replaces. The stamp is the one record that survives both,
            // so it is rolled back to the acknowledged ping but never so far that the next tick
            // lands inside the floor: the next ping is due at
            // `max(acknowledged + interval, dropped + MIN_HEARTBEAT_RETRY_MILLIS)`.
            val floor = newestDropped + MIN_HEARTBEAT_RETRY_MILLIS - heartbeatIntervalMillis()
            val restored = maxOf(acknowledged ?: Long.MIN_VALUE, floor)
            lastHeartbeatAt = restored
            prefs.putLong(lastHeartbeatKey, restored)
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
     *
     * A retired client adopts nothing, like every other answer that can land after [shutdown]:
     * a request already on the wire cannot be recalled, and the floor key is the install's,
     * read by the replacement client at its own init.
     */
    private fun adoptHeartbeatFloor(seconds: Long?) {
        if (retired) return
        val millis = (seconds ?: return) * 1_000L
        if (!isSaneHeartbeatFloorMillis(millis) || millis == serverHeartbeatFloorMillis) return
        serverHeartbeatFloorMillis = millis
        prefs.putLong(heartbeatFloorKey, millis)
        log {
            "· presence pings at most every ${heartbeatIntervalMillis() / 1_000} s (the server asked for $seconds s)"
        }
    }

    private fun isSaneHeartbeatFloorMillis(millis: Long): Boolean = millis in 15_000L..3_600_000L

    /**
     * A persisted stamp, or null when the number on disk cannot be one: not after the epoch, or
     * ahead of [t]. The presence stamps are this install's only record of when it was last heard
     * from, and a device whose clock was hours ahead when they were written leaves every one of
     * them in the future, where the silence they measure reads as negative and no ping is ever due
     * again. They get the same treatment as the server's cadence floor above for the same reason:
     * a value that cannot be true is worth less than no value at all. Discarding one costs this
     * launch a ping it did not owe and a session boundary it did not need; keeping one costs every
     * ping until the clock catches up, on this launch and on all the ones after it.
     */
    private fun restoredStamp(millis: Long?, t: Long): Long? =
        if (millis == null || millis <= 0L || millis > t + CLOCK_SKEW_TOLERANCE_MILLIS) null else millis

    /**
     * How long ago [stamp] was, as of [t], when that is a duration worth acting on: [Long.MAX_VALUE]
     * when there is no stamp at all, and when the stamp is ahead of [t] by more than
     * [CLOCK_SKEW_TOLERANCE_MILLIS] - a clock corrected backwards under a stamp this process itself
     * wrote, which no check at startup can see coming.
     *
     * Every question the SDK asks of a stamp - resume this session or start a new one, is a ping
     * owed, does leaving deserve a closing tick - is safe when the answer is "a long time" and stuck
     * when it is "not yet", so a gap that cannot be measured reads as unbounded.
     */
    private fun millisSince(stamp: Long?, t: Long): Long {
        if (stamp == null) return Long.MAX_VALUE
        val elapsed = t - stamp
        if (elapsed < -CLOCK_SKEW_TOLERANCE_MILLIS) return Long.MAX_VALUE
        return elapsed.coerceAtLeast(0L)
    }

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
        if (holdMillis <= 0) requestDrain(unthrottled = false) else scheduleFlush(holdMillis)
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
     * Cancels the flush timer and asks the send loop to drain. At most one drain runs at a time
     * (the executor is serial) and at most one more is queued behind it: a flush that arrives
     * mid-send starts after the running one and finds whatever it left.
     */
    private fun requestDrain(unthrottled: Boolean) {
        flushTask?.let(scheduler::cancel)
        flushTask = null
        if (!collecting || retired) return
        if (unthrottled) drainUnthrottled.set(true)
        if (drainPending.compareAndSet(false, true)) sendExecutor.execute(::drain)
    }

    /**
     * Sends the queue in request-sized slices, oldest first, until what it set out to send has
     * gone or the network says "later". Each slice is claimed out of the queue before the request,
     * so events tracked meanwhile line up behind it.
     *
     * What it sets out to send is what was owed when the delivery began, and that bound is what
     * keeps a burst from leaving as roughly one request per event. The loop is fed by the same
     * queue the app is writing to, so an app recording as fast as the network answers - a
     * screenful of items, a replayed queue of user actions - would otherwise have every iteration
     * after the first find exactly the one event tracked during the last round trip, and send it
     * on its own: a full set of request headers and a round trip each. Anything tracked after the
     * delivery began goes with the next delivery instead, the one [armNextDelivery] asks for.
     */
    private fun drain() {
        drainPending.set(false)
        // The backoff is read where the send starts, not where it was asked for. A drain requested
        // while a request is on the wire waits behind that request on the serial executor, and the
        // request it is waiting for is what arms the backoff as it fails: an attempt decided in
        // front of that answer is decided against a window nobody had asked for yet, so one outage
        // counts as two consecutive failures and a server that has just asked for room is hit
        // again with no delay. The flag is only cleared by a drain that really attempts, so an
        // explicit flush arriving while this one defers is still honoured by the drain queued
        // behind it.
        if (!drainUnthrottled.get()) {
            val holdMillis = synchronized(queueLock) { nextAttemptAt - now() }
            if (holdMillis > 0) {
                scheduler.post { if (!retired) scheduleFlush(holdMillis) }
                return
            }
        }
        drainUnthrottled.set(false)
        var slice = MAX_EVENTS_PER_REQUEST
        var owed = synchronized(queueLock) { queue.size }
        while (owed > 0) {
            val batch: List<Event> = synchronized(queueLock) {
                if (!collecting || retired || queue.isEmpty()) return@synchronized emptyList()
                val n = minOf(slice, minOf(owed, queue.size))
                List(n) { queue.removeFirst() }.also {
                    inFlightBatch = it
                    persistLocked()
                }
            }
            if (batch.isEmpty()) break
            val count = "${batch.size} event${if (batch.size == 1) "" else "s"}"
            log { "↑ sending $count…" }
            val status = transport.send(EventCoding.encodeBytes(batch))
            when {
                status in 200..299 -> {
                    owed -= batch.size
                    synchronized(queueLock) {
                        inFlightBatch = emptyList()
                        failureStreak = 0
                        nextAttemptAt = 0L
                        persistLocked()
                    }
                    log { "✓ sent $count" }
                    noteDeliveredHeartbeats(batch)
                    // After the slice has left [inFlightBatch], so "still owed" means what it says.
                    noteDeliveredTraits(batch, transport.lastAcceptedCount())
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
                    owed -= batch.size
                    synchronized(queueLock) {
                        inFlightBatch = emptyList()
                        persistLocked()
                    }
                    // The pings in it go too, and they were never counted: the ingest rejects a
                    // batch like this before it reads a row. Leaving their stamp in place spends a
                    // whole fresh interval before the install proves presence again, which is the
                    // silence [rollBackHeartbeatStamp] exists to prevent; this branch simply never
                    // asked for it, because it drops the slice instead of putting it back.
                    batch.filter { it.signal == Signal.HEARTBEAT }
                        .maxOfOrNull { it.clientTs }
                        ?.let { rollBackHeartbeatStamp(it) }
                }

                else -> {
                    // Offline, 5xx, 429: keep everything, in order, for a later attempt.
                    val why = if (status < 0) "no response" else "HTTP $status"
                    log { "⟳ couldn't send ($why) - keeping $count for the next try" }
                    requeue(batch, keepingHeartbeats = status == Transport.NEVER_CONNECTED)
                    // Whatever the status stated it. A maintenance window or a load shed answers
                    // 503 and says how long to stay away, which is the case the header exists for;
                    // 429 is the narrower one where this install alone is being throttled. The
                    // transport reports the header only for an answer it actually read, so a
                    // request that never reached a server states nothing. [flush] and the flush on
                    // the way to the background ignore the window, so a header cannot silence an
                    // install however it is set.
                    val holdMillis = backOff(retryAfterSeconds = transport.lastRetryAfterSeconds())
                    // The retry is armed here rather than left to whatever the app does next:
                    // [requestDrain] cancelled the flush timer on the way in, and the batch-size
                    // trigger asks for one delivery at a time, so an app that goes quiet after a
                    // burst would otherwise sit on a full queue until something else happened
                    // to it.
                    armNextDelivery(holdMillis)
                    return
                }
            }
        }
        armNextDelivery()
    }

    /**
     * Arms the flush timer for whatever a delivery left behind: the slices its bound did not
     * reach, and the batch a retryable failure handed back. [requestDrain] cancels the timer on
     * the way in and the batch-size trigger asks for one delivery at a time, so what is left has
     * no trigger of its own until the app records something else, which an app that has just gone
     * quiet does not.
     *
     * The hop onto the scheduler is what makes this safe to call from the send loop: [flushTask]
     * belongs to the command thread. A timer is already armed here as often as not, and
     * [scheduleFlush] makes that a no-op.
     */
    private fun armNextDelivery(delayMillis: Long = config.flushInterval.inWholeMilliseconds) {
        if (synchronized(queueLock) { queue.isEmpty() }) return
        scheduler.post { if (!retired) scheduleFlush(delayMillis) }
    }

    /**
     * Arms the automatic-retry throttle after a retryable failure and answers the window it armed.
     * The next automatic drain waits `min(ceiling, 2^failures seconds)`, jittered into the upper
     * half of that window so clients that failed together do not all retry together, and never
     * less than the server's own numeric `Retry-After`, which is itself clamped (see
     * [obeyableRetryAfterMillis]) and is read from any answered status, not from a `429` alone.
     * A successful send clears it; [flush] ignores it (see [flushSoon]).
     *
     * The ceiling is [MAX_BACKOFF_MILLIS] for the first [SUSTAINED_OUTAGE_AFTER] consecutive
     * failures and [SUSTAINED_OUTAGE_BACKOFF_MILLIS] past that. A minute is the right cadence for
     * a server that is briefly unwell; something that has already survived ten attempts is an
     * outage, and retrying every 30 to 60 s for the length of it re-uploads the same head slice
     * over and over at an ingest that can least absorb the herd. Waiting longer loses nothing:
     * the queue is on disk, [flush] and the flush on the way to the background both ignore the
     * backoff, and a track or a presence ping re-arms the timer, so the install is still looked at
     * once a cadence. The Swift SDK widens its ceiling at the same streak.
     */
    private fun backOff(retryAfterSeconds: Long?): Long = synchronized(queueLock) {
        failureStreak++
        val ceiling =
            if (failureStreak > SUSTAINED_OUTAGE_AFTER) SUSTAINED_OUTAGE_BACKOFF_MILLIS else MAX_BACKOFF_MILLIS
        // Capped at 2^9 s so the shift cannot run away; 512 s is past the widest ceiling, so the
        // cap is never what decides the window.
        val base = minOf(ceiling, (1L shl minOf(failureStreak, 9)) * 1_000L)
        val jittered = base / 2 + (random() * (base / 2)).toLong()
        val holdMillis = maxOf(jittered, obeyableRetryAfterMillis(retryAfterSeconds))
        nextAttemptAt = now() + holdMillis
        holdMillis
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
     *
     * A write whose bytes match the last one this client landed is skipped. Several of the calls
     * here cannot change what is owed at all: claiming a slice with no presence ping in it moves
     * events from [queue] into [inFlightBatch], and the union of the two is what gets written, so
     * the file comes out exactly what it already was; putting that same slice back after a
     * transient failure moves them again, for the same answer. Each of those pays a full atomic
     * rewrite, which costs the same for four hundred bytes as for two hundred kilobytes, because
     * what it buys is filesystem metadata rather than throughput. Claiming a slice that DOES carry
     * a ping is a change, and that write is the one that takes the ping off disk, so it always
     * happens. Only this client writes the file while it lives - init reads it, [shutdown] retires
     * it, and a replacement is built only after that - so the record cannot go stale underneath it.
     */
    private fun persistLocked() {
        if (retired) return
        val owed = inFlightBatch.filter { it.signal != Signal.HEARTBEAT } + queue
        val json = EventCoding.encode(owed)
        // The file is confirmed to still be there before anything is skipped, so a skip can never
        // be measured against a file that has gone.
        if (json == lastWrittenQueueJson && queueStore.exists()) return
        // The record is the bytes that LANDED, not the bytes that were offered: a store that
        // reports failure, or throws, leaves it cleared, so the next identical write repairs the
        // file instead of being skipped against one that never received it.
        lastWrittenQueueJson = try {
            if (queueStore.save(json)) json else null
        } catch (_: Exception) {
            // Best effort: a full disk must not take the app down with it.
            null
        }
    }

    // endregion

    // region User properties persistence

    /**
     * The set the newest `user.identify` or `user.reset` still owed - queued or on the wire - will
     * leave the server with once it lands; null when nothing is owed, in which case [traits] is
     * the whole truth. A `user.reset` owes the empty map, which is not the same answer as null.
     */
    private fun pendingTraits(): Map<String, String>? =
        synchronized(queueLock) { outstandingTraits(inFlightBatch + queue) }

    /**
     * Commits the snapshot a batch the server accepted has just left it holding. The only place
     * [traits] moves forward: what `identify` recorded is not what the server has until a batch
     * carrying it comes back accepted, and every way that event can die - the queue cap trimming
     * the oldest, a permanent 4xx dropping the slice, the file being deleted - then leaves the
     * snapshot where it was, so the next `identify` sends again instead of matching a cache the
     * server never received.
     *
     * `accepted` is the ingest's own count of the rows it took. A 2xx alone is not proof that they
     * were stored: past the plan's grace ceiling, and under the per-install rate limiter, the
     * answer is still 202 and the identify is discarded. A batch counted short of what was sent
     * commits nothing. Null is "the answer said nothing about it", not "none".
     *
     * Nothing is committed while a NEWER identify or reset is still owed either: what the server
     * holds now is already superseded, and re-persisting it would put properties a sign-out has
     * just cleared back on disk. That one commits when its own batch lands.
     *
     * Called from the send loop, so the commit hops back to the thread that owns [traits].
     */
    private fun noteDeliveredTraits(batch: List<Event>, accepted: Int?) {
        if (accepted != null && accepted < batch.size) return
        val delivered = outstandingTraits(batch) ?: return
        scheduler.post {
            if (retired || pendingTraits() != null || delivered == traits) return@post
            traits = delivered
            persistTraits()
        }
    }

    private fun persistTraits() {
        if (traits.isEmpty()) prefs.remove(traitsKey) else prefs.putString(traitsKey, encodeTraits(traits))
    }

    // endregion

    // region Test hooks

    /** Signals waiting for the next flush, in order. */
    fun pendingSignals(): List<String> = synchronized(queueLock) { queue.map { it.signal } }

    /** The queued events themselves, in order. */
    fun pendingEvents(): List<Event> = synchronized(queueLock) { queue.toList() }

    /**
     * The user properties as they stand: what an identify or reset still owed will leave the
     * server with, or the acknowledged snapshot when nothing is owed.
     */
    fun currentTraits(): Map<String, String> = pendingTraits() ?: traits

    /** The snapshot the server has acknowledged. */
    fun deliveredTraits(): Map<String, String> = traits

    /**
     * The session id every recorded event is stamped with: pre-minted at init when a new session
     * is coming, the resumed one otherwise. Null only for a gated client that never had one.
     */
    fun currentSessionId(): String? = sessionId

    // endregion

    companion object {
        /** Hard cap on the queue, so repeated failures cannot grow it without bound. */
        const val MAX_QUEUED_EVENTS: Int = 500

        /** Ceiling on the automatic-retry backoff window while a failure still looks like a blip. */
        const val MAX_BACKOFF_MILLIS: Long = 60_000L

        /** The wider ceiling a sustained outage earns. See [backOff]. */
        const val SUSTAINED_OUTAGE_BACKOFF_MILLIS: Long = 300_000L

        /** Consecutive failures past which [SUSTAINED_OUTAGE_BACKOFF_MILLIS] is the ceiling. */
        const val SUSTAINED_OUTAGE_AFTER: Int = 10

        /**
         * The longest wait the SDK obeys from a `Retry-After`. Past this it is not rate limiting
         * any more, it is an outage, and the on-disk queue plus the flush on the way to the
         * background answer that better than a foreground stretch that never sends. A header alone
         * must not be able to stop an install sending for a day.
         */
        const val MAX_RETRY_AFTER_SECONDS: Long = 900L

        /** A server's `Retry-After` as a delay: obeyed only as a positive number of seconds, clamped. */
        fun obeyableRetryAfterMillis(seconds: Long?): Long =
            if (seconds == null || seconds <= 0L) 0L else minOf(seconds, MAX_RETRY_AFTER_SECONDS) * 1_000L

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
         * How far ahead of the clock a stamp may be and still be read as "now": the finest presence
         * resolution anything here has (`heartbeatInterval`'s floor, and [MIN_HEARTBEAT_RETRY_MILLIS]).
         * Below it a skew is the distance between two clock reads - the facade stamps a call, the
         * client applies it, a time server nudges the clock a fraction of a second - and changes
         * nothing anybody sees. Above it, a stamp in the future is a clock that was wrong when it
         * was written.
         */
        const val CLOCK_SKEW_TOLERANCE_MILLIS: Long = 15_000L

        /**
         * Events per request. The ingest API accepts up to 500 events / 256 KB per batch; a
         * long-offline queue drains in slices this size rather than as one oversized POST.
         */
        const val MAX_EVENTS_PER_REQUEST: Int = 100

        const val MAX_TRAITS: Int = 20
        const val MAX_TRAIT_KEY_LENGTH: Int = 40
        const val MAX_TRAIT_VALUE_LENGTH: Int = 200

        /**
         * Trimmed, then cut to [max] UTF-16 code units - the unit the ingest counts in, and the
         * unit `String.length` and `take` already work in, so the two agree on where the cut
         * falls. A cut that lands between the two halves of a surrogate pair is the one place they
         * do not: what is left ends in a lone high surrogate, which the ingest strips and which
         * UTF-8 encoding turns into `?` on the way out of here anyway, so the server can only
         * store something the SDK does not have. The snapshot would then never match, and since
         * only a change is sent, no later `identify` with the same values could correct it. The
         * orphaned half goes instead.
         */
        fun clamped(value: String, max: Int): String {
            val trimmed = value.trim()
            if (trimmed.length <= max) return trimmed
            val kept = trimmed.substring(0, max)
            return if (kept.last().isHighSurrogate()) kept.dropLast(1) else kept
        }

        /**
         * Client errors that mean "this batch will never be accepted as is". 408 (timeout), 425
         * (too early) and 429 (rate limited) are the 4xx that do deserve a retry.
         */
        fun isPermanent(status: Int): Boolean = status in 400..499 && status !in setOf(408, 425, 429)

        /**
         * What the server ends up holding once every identify and reset in [owed] has landed: the
         * last one wins, exactly as it does server-side, where each `user.identify` replaces the
         * stored set wholesale and `user.reset` deletes it. Null when [owed] carries neither.
         */
        fun outstandingTraits(owed: List<Event>): Map<String, String>? {
            val last = owed.lastOrNull { it.signal == Signal.IDENTIFY || it.signal == Signal.RESET } ?: return null
            return if (last.signal == Signal.RESET) emptyMap() else (last.metadata ?: emptyMap())
        }

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
