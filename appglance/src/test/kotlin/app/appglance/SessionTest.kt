package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the client sends for the lifecycle transitions Android actually produces - including the
 * doubled ones (two activities starting, `onStart` after a rotation) - and how it delivers the
 * queue.
 */
class SessionTest {

    private class Rig(sessionTimeout: Duration = 5.minutes) {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val transport = RecordingTransport()
        val platform = InMemoryPlatform()
        val config = testConfiguration(sessionTimeout = sessionTimeout)

        /** A new process against the same device state. */
        fun launch(executor: Executor = directExecutor, isNewInstall: Boolean = false): Client = makeClient(
            platform,
            clock,
            scheduler,
            transport,
            config,
            isNewInstall = isNewInstall,
            installAt = if (isNewInstall) clock.now else null,
            executor = executor,
        )

        /** The queue file for the app id under test. */
        val store: InMemoryQueueStore get() = platform.queues.getValue(config.appId!!)

        /** The signals persisted for the app id - what a relaunch would load. */
        fun onDisk(): List<String> = EventCoding.decode(store.json!!).map { it.signal }

        /** Writes the client really made. Skipping a write and writing the same bytes again look
         *  identical from the file, so a test has to be told which happened. */
        fun writes(): Int = store.writes
    }

    @Test
    fun `launch starts exactly one session and no ping until a minute of silence`() {
        val rig = Rig()
        val client = rig.launch()

        // Two activities starting, or onStart reported twice: two reports of the same fact.
        client.setActive(true)
        client.setActive(true)
        rig.scheduler.settle()

        assertEquals(
            "one session.start and nothing else: the start is presence enough",
            listOf(Signal.SESSION_START),
            client.pendingSignals(),
        )
        rig.scheduler.advance(60.seconds)
        assertEquals(
            "a minute of silence earns exactly one ping",
            listOf(Signal.SESSION_START, Signal.HEARTBEAT),
            client.pendingSignals(),
        )
    }

    /**
     * The heartbeat measures silence, not time: any real event resets it, so an install that is
     * sending events never pings, and one that goes quiet pings once per interval of quiet.
     */
    @Test
    fun `a ping is sent only after an interval of silence`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(40.seconds)
        client.track("tap", null)                    // presence proved at t40
        rig.scheduler.advance(50.seconds)            // t90: 90 s since the start, only 50 s since the tap
        assertEquals("the event reset the silence", 0, client.pendingSignals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(10.seconds)            // t100: a full minute since the tap
        assertEquals(listOf(Signal.SESSION_START, "tap", Signal.HEARTBEAT), client.pendingSignals())
        rig.scheduler.advance(59.seconds)
        client.track("tap", null)                    // t159, just before the next ping would be due
        rig.scheduler.advance(30.seconds)            // t189: 89 s since the ping, 30 s since the tap
        assertEquals("pushed out again", 1, client.pendingSignals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(30.seconds)            // t219: 60 s since the tap
        assertEquals(2, client.pendingSignals().count { it == Signal.HEARTBEAT })
    }

    /**
     * Leaving the foreground after more than a minute of silence sends one closing ping, so the
     * session's length ends where the visit ended; leaving sooner sends nothing extra.
     */
    @Test
    fun `leaving after a quiet minute sends a closing ping`() {
        val rig = Rig()
        val client = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(heartbeatInterval = 1.hours), // no periodic ping in this test
        )
        client.setActive(true)                       // session.start at t0
        rig.clock.advance(30.seconds)
        client.setActive(false)                      // 30 s of quiet: nothing to add
        assertEquals(listOf(Signal.SESSION_START), rig.transport.signals())

        rig.clock.advance(10.seconds)
        client.setActive(true)                       // resumed
        rig.clock.advance(90.seconds)                // a quiet minute and a half
        client.setActive(false)
        assertEquals(
            "quiet for over a minute: the goodbye is one ping, stamped at the moment of leaving",
            listOf(Signal.SESSION_START, Signal.HEARTBEAT),
            rig.transport.signals(),
        )

        rig.clock.advance(10.seconds)
        client.setActive(true)
        rig.clock.advance(20.seconds)
        client.track("tap", null)
        rig.clock.advance(20.seconds)
        client.setActive(false)                      // 20 s since the tap: the server already knows
        assertEquals(
            "a recent event is presence enough: no closing ping",
            listOf(Signal.SESSION_START, Signal.HEARTBEAT, "tap"),
            rig.transport.signals(),
        )
    }

    /**
     * The stamps that pace presence belong to the install, not to the client object. A relaunch
     * inside the session timeout resumes and records no `session.start` of its own, and a visit
     * shorter than one interval leaves no ping stamp behind either - so without the persisted
     * event stamp the fresh process would ping the moment it came up, seconds after the server
     * last heard from that install. Low-RAM devices kill and relaunch inside a visit routinely,
     * and a second `configure` (the documented way to apply a consent change) is the same shape.
     */
    @Test
    fun `a relaunch inside the timeout owes no ping for silence it did not have`() {
        val rig = Rig()
        val first = rig.launch()
        first.setActive(true)                        // session.start at t0: presence enough, no ping owed
        rig.scheduler.settle()
        rig.clock.advance(20.seconds)
        first.setActive(false)                       // 20 s of quiet: no closing ping either
        first.flush()
        assertEquals(0, rig.transport.signals().count { it == Signal.HEARTBEAT })

        rig.clock.advance(10.seconds)                // killed at t20, reopened at t30, inside the timeout
        val second = rig.launch()
        second.setActive(true)                       // a resume: no session.start to prove presence with
        rig.scheduler.settle()
        assertEquals(
            "the server heard from this install 30 s ago; a fresh process does not owe a ping for that",
            0,
            second.pendingSignals().count { it == Signal.HEARTBEAT },
        )
        rig.scheduler.advance(30.seconds)            // t60: a full minute since the last real event
        assertEquals(1, second.pendingSignals().count { it == Signal.HEARTBEAT })
    }

    /**
     * A ping that was dropped rather than retried is not proof of anything: the next one is owed
     * from the last ping the server acknowledged, not from the one it may never have seen. Left
     * alone, a single drop at the four-minute cadence a free-plan account is asked for opens an
     * eight-minute gap and the install falls out of the dashboard's five-minute presence window
     * while it is in the foreground the whole time.
     */
    @Test
    fun `a dropped ping does not spend its whole interval`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(60.seconds)            // a quiet minute: one ping, stamped t60
        assertEquals(1, client.pendingSignals().count { it == Signal.HEARTBEAT })

        rig.transport.script(503)
        client.flush()                               // the server answered, so the ping is dropped
        assertTrue(
            "dropped rather than risked twice",
            client.pendingSignals().none { it == Signal.HEARTBEAT },
        )

        rig.scheduler.advance(Client.MIN_HEARTBEAT_RETRY_MILLIS - 1)
        assertEquals(
            "not instantly: the dropped ping may have landed after all",
            0,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
        rig.scheduler.advance(1)
        assertEquals(
            "but long before t120 - the server has had no proof of presence since t0",
            1,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
    }

    /**
     * A permanent 4xx drops the slice instead of putting it back, so the pings in it are gone the
     * same way - and they were never counted, because the ingest rejects a batch like that before
     * it reads a row. Left stamped, the next ping is not due for a full interval, and at the
     * four-minute cadence a free-plan account is asked for two of those back to back are longer
     * than the dashboard's five-minute presence window.
     */
    @Test
    fun `a ping dropped by a permanent rejection does not spend its whole interval either`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(60.seconds)            // a quiet minute: one ping, stamped t60
        assertEquals(1, client.pendingSignals().count { it == Signal.HEARTBEAT })

        rig.transport.script(400)
        client.flush()                               // never acceptable as sent: the slice is dropped whole
        assertTrue("dropped, not kept for a retry", client.pendingSignals().isEmpty())

        rig.scheduler.advance(Client.MIN_HEARTBEAT_RETRY_MILLIS - 1)
        assertEquals(
            "not instantly: the dropped ping may have landed after all",
            0,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
        rig.scheduler.advance(1)
        assertEquals(
            "but long before t120 - the server has had no proof of presence since t0",
            1,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
    }

    /**
     * A stamp the clock has not reached yet: written while the device was hours ahead, read back
     * after the correction. The silence it measures reads as negative, so the next ping is due
     * hours out, and every relaunch reads the same stamp and owes the same nothing.
     */
    @Test
    fun `a presence stamp from the future does not silence the loop`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val now = rig.clock.now
        // A session that is still resumable, so this launch records no session.start of its own:
        // a ping is then the only thing that can prove the install is in front of someone.
        rig.platform.prefs.putLong("lastActive.$appId", now - 30_000L)
        rig.platform.prefs.putString("session.$appId", "11111111-1111-1111-1111-111111111111")
        rig.platform.prefs.putLong("lastEvent.$appId", now - 600_000L)
        rig.platform.prefs.putLong("lastHeartbeat.$appId", now + 7_200_000L)

        val client = rig.launch()
        client.setActive(true)
        rig.scheduler.settle()

        assertEquals(
            "a stamp two hours ahead of the clock is not proof that anyone was here",
            listOf(Signal.HEARTBEAT),
            client.pendingSignals(),
        )
    }

    /**
     * The stamp a dropped ping rolls back to is the newest one the server acknowledged, and a
     * number the clock has not reached yet is not that: believing it would write the impossible
     * value back to disk for the next launch to read, which is where a stamp from a wrong clock
     * outlives the wrong clock.
     */
    @Test
    fun `a stamp from the future is not what a dropped ping rolls back to`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val now = rig.clock.now
        rig.platform.prefs.putLong("lastActive.$appId", now - 30_000L)
        rig.platform.prefs.putString("session.$appId", "55555555-5555-5555-5555-555555555555")
        rig.platform.prefs.putLong("lastHeartbeat.$appId", now + 7_200_000L)

        val client = rig.launch()
        client.setActive(true)
        rig.scheduler.settle()                  // the ping this install is owed, stamped now
        rig.transport.script(503)
        client.flush()                          // the server answered, so the ping is dropped

        val stamp = rig.platform.prefs.getLong("lastHeartbeat.$appId")
        assertTrue(
            "rolled back to nothing, not to a moment that has not happened yet (got $stamp)",
            stamp == null || stamp <= rig.clock.now,
        )
    }

    /**
     * The same fault from inside one visit: a clock corrected backwards leaves the stamps this
     * process wrote ahead of it. The wait is what paces the whole presence loop, so it is bounded
     * by one interval however the arithmetic comes out.
     */
    @Test
    fun `a clock that steps backwards cannot stretch the wait`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)                  // session.start proves presence at t0
        rig.clock.advance(-7_200_000L)          // two hours backwards, mid-visit

        assertEquals(
            "the gap cannot be measured, so it reads as unbounded and a ping is owed now. One whole " +
                "interval is what dropping the guard returns, and it is inside any range this could be " +
                "asserted against",
            0L,
            client.millisUntilNextHeartbeat(),
        )
    }

    /**
     * A visit that records nothing at all - a resumed session, no event tracked, no ping due yet -
     * still closes with one, so the session's length on the dashboard ends where the visit ended.
     * A missing stamp is an unbounded silence, not a number to subtract; the Swift SDK closes the
     * same visit the same way.
     */
    @Test
    fun `a visit with no presence stamp at all still closes with a ping`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        // Resumable, and with neither presence stamp on disk: the last visit was shorter than one
        // interval, so it left no ping behind, and its events have long since been sent.
        rig.platform.prefs.putLong("lastActive.$appId", rig.clock.now - 30_000L)
        rig.platform.prefs.putString("session.$appId", "22222222-2222-2222-2222-222222222222")

        val client = rig.launch()
        client.setActive(true)                  // a resume: nothing recorded
        assertEquals(emptyList<String>(), client.pendingSignals())

        client.setActive(false)                 // which flushes, so the ping is on the wire at once
        assertEquals(
            "leaving still ends the session where the visit ended",
            listOf(Signal.HEARTBEAT),
            rig.transport.signals(),
        )
    }

    /**
     * A process that pre-mints a session id and dies before any foreground hands it to the next
     * launch. The hand-over must not turn on the gap still looking wide: the events already queued
     * carry that id, so a launch that measures a resumable gap instead - the one way the two can
     * disagree is a clock corrected backwards under a stamp this install wrote - leaves them in a
     * session the server is never told about.
     */
    @Test
    fun `an unadopted session id is picked up whatever the gap looks like`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        rig.platform.prefs.putString("session.pending.$appId", "33333333-3333-3333-3333-333333333333")
        rig.platform.prefs.putString("session.$appId", "44444444-4444-4444-4444-444444444444")
        rig.platform.prefs.putLong("lastActive.$appId", rig.clock.now - 30_000L)   // says: still inside the session

        val client = rig.launch()
        assertEquals(
            "the id the dead process queued events under, not the one the stamp points at",
            "33333333-3333-3333-3333-333333333333",
            client.currentSessionId(),
        )
        client.setActive(true)
        rig.scheduler.settle()
        assertEquals(listOf(Signal.SESSION_START), client.pendingSignals())
        assertNull(
            "and it is adopted exactly once",
            rig.platform.prefs.getString("session.pending.$appId"),
        )
    }

    /**
     * The server may raise the cadence for the account's plan through the ingest response; the
     * SDK obeys it as a floor, remembers it across launches, and ignores nonsense.
     */
    @Test
    fun `the server heartbeat floor is honoured remembered and bounded`() {
        val rig = Rig()
        val first = rig.launch()
        assertEquals(60_000L, first.heartbeatIntervalMillis())

        rig.transport.heartbeatIntervalSeconds = 240
        first.track("a", null)
        first.flush()
        assertEquals("the plan asks for a ping every four minutes at most", 240_000L, first.heartbeatIntervalMillis())

        // And the timer follows: quiet from here, the next ping is four minutes out, not one.
        first.setActive(true)                        // session.start now
        rig.scheduler.advance(3.minutes)
        assertEquals(0, first.pendingSignals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(1.minutes)
        assertEquals(1, first.pendingSignals().count { it == Signal.HEARTBEAT })

        // Remembered: the next launch paces itself before its first response arrives.
        val second = rig.launch()
        assertEquals(240_000L, second.heartbeatIntervalMillis())

        // A floor, not a ceiling: an app that configured 5 minutes keeps them.
        val wide = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(heartbeatInterval = 5.minutes),
        )
        assertEquals(300_000L, wide.heartbeatIntervalMillis())

        // Nonsense is ignored: too tight to mean anything, or not a presence cadence at all.
        rig.transport.heartbeatIntervalSeconds = 5
        second.track("b", null)
        second.flush()
        assertEquals("below the sane floor: the last good value stands", 240_000L, second.heartbeatIntervalMillis())
        rig.transport.heartbeatIntervalSeconds = 86_400
        second.track("c", null)
        second.flush()
        assertEquals("a day is not a presence cadence: kept 240 s", 240_000L, second.heartbeatIntervalMillis())
        // Back down when the plan changes; a response with no hint changes nothing.
        rig.transport.heartbeatIntervalSeconds = 60
        second.track("d", null)
        second.flush()
        assertEquals(60_000L, second.heartbeatIntervalMillis())
        rig.transport.heartbeatIntervalSeconds = null
        second.track("e", null)
        second.flush()
        assertEquals(60_000L, second.heartbeatIntervalMillis())
    }

    @Test
    fun `a brief interruption resumes the same session`() {
        val rig = Rig()
        val client = rig.launch()

        client.setActive(true)
        rig.scheduler.settle()
        rig.clock.advance(20.seconds)
        client.setActive(false)     // a notification shade pulled down, say
        client.setActive(false)     // reported again - must not flush/act twice
        rig.clock.advance(30.seconds)
        client.setActive(true)      // back within the timeout
        rig.scheduler.settle()
        client.setActive(false)
        client.flush()

        val sent = rig.transport.signals()
        assertEquals(
            "50 seconds away is an interruption, not a new session",
            1,
            sent.count { it == Signal.SESSION_START },
        )
        assertEquals(
            "resuming inside the interval since session.start must not tick, and 20 s of quiet earns no closing ping",
            0,
            sent.count { it == Signal.HEARTBEAT },
        )
        assertTrue("a successful flush clears the queue - nothing to replay later", client.pendingSignals().isEmpty())
    }

    @Test
    fun `the heartbeat keeps ticking every quiet interval while active and stops when inactive`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(3.minutes)             // three ticks at 60 s spacing: t60, t120, t180
        assertEquals(3, client.pendingSignals().count { it == Signal.HEARTBEAT })

        client.setActive(false)                      // flushes: everything so far is on the server
        assertEquals(3, rig.transport.signals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(30.seconds)            // nothing ticks while backgrounded
        assertTrue(client.pendingSignals().isEmpty())

        // Back after 30 s: the next beat is due 30 s later, not immediately.
        client.setActive(true)
        rig.scheduler.settle()
        assertEquals("no fresh beat right away", 0, client.pendingSignals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(30.seconds)
        assertEquals("one when the interval completes", 1, client.pendingSignals().count { it == Signal.HEARTBEAT })

        // A long absence is a new session; its start is its first proof of presence.
        client.setActive(false)
        rig.scheduler.advance(10.minutes)
        client.setActive(true)
        rig.scheduler.settle()
        assertEquals(listOf(Signal.SESSION_START), client.pendingSignals())
    }

    @Test
    fun `returning after the timeout starts a new session`() {
        val rig = Rig()
        val client = rig.launch()

        client.setActive(true)
        rig.scheduler.settle()
        client.setActive(false)
        rig.clock.advance(6.minutes)                 // gone longer than the 5-minute session timeout
        client.setActive(true)
        rig.scheduler.settle()
        client.setActive(false)
        client.flush()

        assertEquals(
            "each return after the gap is a new session; its start is its first proof of presence",
            listOf(Signal.SESSION_START, Signal.SESSION_START),
            rig.transport.signals(),
        )
    }

    @Test
    fun `a relaunch within the timeout is the same session across processes`() {
        val rig = Rig()

        val first = rig.launch()
        first.setActive(true)
        rig.scheduler.settle()
        first.setActive(false)
        first.flush()

        rig.clock.advance(60.seconds)                // killed and reopened a minute later: same session
        val second = rig.launch()
        second.setActive(true)
        rig.scheduler.settle()
        second.setActive(false)
        second.flush()

        rig.clock.advance(20.minutes)                // reopened much later: new session
        val third = rig.launch()
        third.setActive(true)
        rig.scheduler.settle()
        third.flush()

        assertEquals(
            "the dashboard splits sessions on a 5-minute gap; the SDK must agree",
            2,
            rig.transport.signals().count { it == Signal.SESSION_START },
        )
    }

    @Test
    fun `every session event carries the session id and it survives a relaunch inside the timeout`() {
        val rig = Rig()
        val first = rig.launch()
        first.setActive(true)
        rig.scheduler.settle()
        first.track("paywall.viewed", null)
        val sid = first.currentSessionId()
        assertNotNull(sid)
        first.setActive(false)
        first.flush()

        rig.clock.advance(90.seconds)                // relaunch inside the timeout
        val second = rig.launch()
        second.setActive(true)
        rig.scheduler.settle()
        assertEquals("same session across a kill-and-relaunch inside the timeout", sid, second.currentSessionId())
        second.setActive(false)
        second.flush()

        rig.clock.advance(10.minutes)                // and a genuinely new one after it
        val third = rig.launch()
        third.setActive(true)
        rig.scheduler.settle()
        assertNotEquals(sid, third.currentSessionId())
        third.flush()

        val ids = rig.transport.sessionIds()
        assertTrue(ids.isNotEmpty())
        assertTrue("hosted mode stamps every event with its session", ids.all { it != null })
        assertEquals("two sessions were lived", 2, ids.filterNotNull().toSet().size)
    }

    @Test
    fun `a long queue drains in slices and keeps order`() {
        val rig = Rig()
        val client = rig.launch()
        for (i in 0 until 230) client.track("e$i", null)
        client.flush()
        assertEquals(listOf(100, 100, 30), rig.transport.requestSizes())
        assertEquals((0 until 230).map { "e$it" }, rig.transport.signals())
        assertTrue(client.pendingSignals().isEmpty())
    }

    @Test
    fun `a permanent rejection drops only that slice and a transient one keeps it`() {
        val rig = Rig()
        val client = rig.launch()
        client.track("a", null)
        // 429: keep and stop - nothing is lost, nothing else is attempted this round.
        rig.transport.script(429)
        client.flush()
        assertEquals("rate-limited: the batch waits for the next attempt", listOf("a"), client.pendingSignals())
        // No response at all (offline): same.
        rig.transport.script(-1)
        client.flush()
        assertEquals(listOf("a"), client.pendingSignals())
        // 401 (an unknown key): dropping beats a queue that can never drain again.
        rig.transport.script(401)
        client.flush()
        assertTrue("a permanent 4xx must not be retried forever", client.pendingSignals().isEmpty())
        assertEquals("and nothing was recorded as delivered", emptyList<String>(), rig.transport.signals())
    }

    @Test
    fun `an oversized batch is split until it fits`() {
        val rig = Rig()
        val client = rig.launch()
        for (i in 0 until 8) client.track("e$i", null)
        // The first two attempts are "too big"; halves get through.
        rig.transport.script(413, 413)
        client.flush()
        assertEquals(8, rig.transport.requestSizes().first())
        assertEquals("everything arrives, in order", (0 until 8).map { "e$it" }, rig.transport.signals())
        assertTrue(rig.transport.requestSizes().drop(2).all { it <= 2 })
    }

    @Test
    fun `events are persisted as they are tracked`() {
        val rig = Rig()
        val client = rig.launch()
        client.track("x", null)
        assertEquals("on disk before any flush - a crash can't lose it", listOf("x"), rig.onDisk())
        client.flush()
        assertEquals("and gone from disk once acknowledged", emptyList<String>(), rig.onDisk())
    }

    @Test
    fun `a persisted queue is picked up by the next launch and the cap drops the oldest`() {
        val rig = Rig()
        // The first process is killed before its sender ever gets to run: the full-batch flush it
        // requests at 500 queued goes nowhere, and everything stays owed on disk.
        val first = rig.launch(executor = Executor { })
        for (i in 0 until 520) first.track("e$i", null)   // 20 over the cap
        assertEquals(500, first.pendingSignals().size)
        assertEquals("e20", first.pendingSignals().first())

        val second = rig.launch()                       // relaunch: the queue is loaded from disk
        assertEquals(500, second.pendingSignals().size)
        second.flush()
        assertEquals(listOf(100, 100, 100, 100, 100), rig.transport.requestSizes())
        assertEquals("e20", rig.transport.signals().first())
        assertEquals("e519", rig.transport.signals().last())
    }

    @Test
    fun `the flush timer sends a partial batch and a full batch sends at once`() {
        val rig = Rig()
        val client = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            testConfiguration(maxBatchSize = 3, flushInterval = 10.seconds),
        )
        client.track("a", null)
        assertEquals(emptyList<String>(), rig.transport.signals())
        rig.scheduler.advance(9.seconds)
        assertEquals("not yet", emptyList<String>(), rig.transport.signals())
        rig.scheduler.advance(1.seconds)
        assertEquals("the flush interval after the first queued event", listOf("a"), rig.transport.signals())

        client.track("b", null)
        client.track("c", null)
        client.track("d", null)
        assertEquals("maxBatchSize reached: sent immediately", listOf("a", "b", "c", "d"), rig.transport.signals())
    }

    /** Two overlapping flushes - the shape of a stop + an explicit flush - send one batch. */
    @Test
    fun `overlapping flushes join the send in progress and never re-send`() {
        val rig = Rig()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = makeClient(rig.platform, rig.clock, rig.scheduler, rig.transport, executor = executor)
            client.track("a", null)
            client.track("b", null)
            val gate = CountDownLatch(1)
            rig.transport.gate = gate
            client.flush()                       // claims the batch, blocks in send
            client.flush()                       // arrives mid-send: joins, does not race
            client.flush()
            gate.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)   // wait for the drains to finish
            assertEquals(listOf("a", "b"), rig.transport.signals())
            assertEquals("one request, not three", listOf(2), rig.transport.requestSizes())
            assertTrue(client.pendingSignals().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    // Presence pings are never risked twice. Every other signal carries an event id the server
    // dedupes on, so retrying it is free. Heartbeats are folded into rollups on arrival and a
    // re-sent one counts twice, so they are retried only when the server definitely did not
    // process the batch.

    /**
     * The on-disk queue is what a relaunch sends. While a slice is on the wire, the disk shows its
     * real events as still owed and its heartbeats as gone - so a process killed before the
     * response arrives replays the events (deduplicated by id) and never the pings.
     *
     * An event tracked while that slice is on the wire is past the bound the delivery set out
     * with, so it stays owed until the next delivery rather than extending this one.
     */
    @Test
    fun `the queue on disk never contains an in-flight heartbeat`() {
        val rig = Rig()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = makeClient(rig.platform, rig.clock, rig.scheduler, rig.transport, executor = executor)

            client.setActive(true)              // session.start, then a quiet minute earns a heartbeat
            rig.scheduler.advance(60.seconds)
            client.track("purchase", null)
            assertEquals(
                "queued, not yet sent: everything is owed",
                listOf(Signal.SESSION_START, Signal.HEARTBEAT, "purchase"),
                rig.onDisk(),
            )

            val arrived = CountDownLatch(1)
            val gate = CountDownLatch(1)
            rig.transport.arrived = arrived
            rig.transport.gate = gate
            client.flush()                       // claims the slice, blocks in send
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            client.track("later", null)          // tracked while the slice is in flight
            assertEquals(
                "in flight: real events stay owed, the ping is not risked, the new event queues behind",
                listOf(Signal.SESSION_START, "purchase", "later"),
                rig.onDisk(),
            )

            gate.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(
                "the delivery carries what it set out to send, and no more",
                listOf(Signal.SESSION_START, Signal.HEARTBEAT, "purchase"),
                rig.transport.signals(),
            )
            assertEquals(
                "what was tracked mid-flight is still owed, and the disk says so",
                listOf("later"),
                rig.onDisk(),
            )

            client.flush()                       // the next delivery is what takes it
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(Signal.SESSION_START, Signal.HEARTBEAT, "purchase", "later"), rig.transport.signals())
            assertEquals("acknowledged: nothing is owed", emptyList<String>(), rig.onDisk())
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * A later `configure` replaces the client. The old one must stop recording and, above all,
     * stop writing the queue file the replacement now owns.
     */
    @Test
    fun `a retired client records nothing and leaves the queue file alone`() {
        val rig = Rig()
        val old = rig.launch()
        old.track("before", null)
        old.shutdown()

        val replacement = rig.launch()
        assertEquals("the replacement picks up what was persisted", listOf("before"), replacement.pendingSignals())

        old.track("stray", null)
        old.setActive(true)
        old.flush()
        assertTrue("retired: nothing is recorded", old.pendingSignals().isEmpty())
        assertEquals("retired: nothing is sent", emptyList<String>(), rig.transport.signals())
        assertEquals("the file is untouched", listOf("before"), rig.onDisk())
    }

    /**
     * The queue file holds what is OWED, which is the queue plus the non-ping half of the slice
     * that was on the wire, so it can legitimately carry a whole request more than the queue's own
     * cap. Restoring it whole started the next launch over that cap and left it there until
     * something else was tracked, so the documented 500-event ceiling was really 600 on exactly
     * the launch that follows a crash mid-delivery.
     */
    @Test
    fun `a queue file holding more than the cap is trimmed on the way in`() {
        val rig = Rig()
        val first = rig.launch()
        repeat(300) { first.track("e$it", null) }
        val stored = EventCoding.decode(rig.store.json!!)
        assertEquals("nothing is trimmed under the cap", 300, stored.size)

        // A file carrying 600 owed events: the shape a kill mid-delivery leaves behind.
        rig.store.json = EventCoding.encode(stored + stored)
        assertEquals(600, EventCoding.decode(rig.store.json!!).size)

        assertEquals(
            "the launch starts at the cap, not over it",
            Client.MAX_QUEUED_EVENTS,
            rig.launch().pendingSignals().size,
        )
    }

    /**
     * The elision remembers what LANDED, not what was offered. A store that refuses a write and
     * says so must not leave the client believing the file holds those bytes, because the write
     * that follows is very often the identical one: claiming a slice and putting it back both
     * write the union of the queue and the in-flight batch, which is the same set. Skipping that
     * one against a file that never received it leaves the disk a whole event behind the client,
     * and a kill in between loses it - the one way this optimisation could cost events.
     */
    @Test
    fun `a queue write that did not land is not remembered as though it had`() {
        val rig = Rig()
        val client = rig.launch()
        client.track("a", null)
        assertEquals("the first write is on disk", listOf("a"), rig.onDisk())

        rig.store.failWrites = true
        client.track("b", null)
        assertEquals("the refused write left the file as it was", listOf("a"), rig.onDisk())

        // The disk frees up, and the next write carries exactly the bytes the refused one did:
        // claiming a slice with no ping in it and putting it back is the commonest write there is.
        rig.store.failWrites = false
        rig.transport.script(503)
        client.flush()

        assertEquals(
            "the write the elision would have skipped is the one that repairs the file",
            listOf("a", "b"),
            rig.onDisk(),
        )
    }

    /**
     * Claiming a slice moves events out of the queue and into the in-flight batch, and the file
     * holds the union of the two - so a slice with no presence ping in it leaves the file saying
     * exactly what it already said, and putting that slice back after a transient failure says it
     * again. Neither write carries any information, and an atomic rewrite costs the same whatever
     * it carries. The on-disk assertions are the point: eliding the write must not change a byte
     * of what a relaunch would find.
     */
    @Test
    fun `a slice with no presence ping is claimed and returned without rewriting the file`() {
        val rig = Rig()
        val client = rig.launch()
        client.track("a", null)
        client.track("b", null)
        assertEquals("one write per tracked event - that is the durability guarantee", 2, rig.writes())

        rig.transport.script(503)
        client.flush()
        assertEquals("claimed and put back for the same answer: nothing to write", 2, rig.writes())
        assertEquals("and both are still owed", listOf("a", "b"), rig.onDisk())

        client.flush()                      // 202 this time
        assertEquals("an acknowledgement does change what is owed", 3, rig.writes())
        assertEquals(emptyList<String>(), rig.onDisk())
    }

    /**
     * The write that can never be elided. Claiming a slice that carries a presence ping takes that
     * ping off disk, and it has to be off disk before the request leaves: the server folds pings
     * additively and never dedupes them, so a kill mid-send that left one on the file would have
     * the next launch send it again and the count would be wrong for good.
     */
    @Test
    fun `claiming a slice that carries a ping still rewrites the file`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)
        rig.scheduler.advance(60.seconds)   // a quiet minute: session.start, then one ping
        client.track("purchase", null)
        val before = rig.writes()

        var inFlight: List<String>? = null
        rig.transport.onSend = { inFlight = rig.onDisk() }
        client.flush()

        assertEquals(
            "the ping is off disk before the request leaves; the real events stay owed",
            listOf(Signal.SESSION_START, "purchase"),
            inFlight,
        )
        assertTrue("taking the ping off disk is a change, so it is a write", rig.writes() > before)
    }

    /**
     * A write is elided against what the file already holds, so the file has to be there. An
     * interrupted atomic write can leave only the backup, and the Apple SDK keeps the same file in
     * a directory the system may reclaim at any moment. A queue that is nowhere at all is the one
     * outcome this must never produce, so presence is confirmed before anything is skipped.
     */
    @Test
    fun `an identical write is not skipped when the file has gone`() {
        val rig = Rig()
        val client = rig.launch()
        client.track("a", null)
        rig.store.delete()

        rig.transport.script(503)
        client.flush()                      // claims the slice and puts it back: the same bytes

        assertEquals("written again, not skipped against a file that is gone", listOf("a"), rig.onDisk())
    }

    @Test
    fun `a transient failure retries real events but drops presence pings`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)              // session.start + heartbeat
        rig.scheduler.settle()
        client.track("purchase", null)

        rig.transport.script(503)
        client.flush()

        val pending = client.pendingSignals()
        assertTrue(
            "the presence ping must not be queued for a retry - it may already have been applied",
            !pending.contains(Signal.HEARTBEAT),
        )
        assertTrue(
            "a real event is safe to retry: it dedupes on (app_id, event_id)",
            pending.contains("purchase"),
        )
        assertTrue("session.start is a real event too", pending.contains(Signal.SESSION_START))

        client.flush()                      // 202 this time
        val resent = rig.transport.batches().last().map { it.signal }
        assertTrue("no heartbeat on the wire the second time", !resent.contains(Signal.HEARTBEAT))
        assertTrue(resent.contains("purchase"))
    }

    @Test
    fun `a 413 keeps presence pings, because the server answered without applying them`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)
        rig.scheduler.advance(60.seconds)            // a quiet minute: one ping in the queue
        client.track("purchase", null)

        // 413 is a definite answer: the body was rejected before anything was processed, so the
        // ticks in it were never counted and re-sending them in smaller slices is correct. The
        // retry happens inside the same drain, so the queue is empty by the time flush returns -
        // what proves the ping survived is that it reached the wire, not that it is still queued.
        rig.transport.script(413)
        client.flush()

        assertTrue(
            "the ping is re-sent after a 413 - nothing was applied, so it is safe to retry",
            rig.transport.signals().contains(Signal.HEARTBEAT),
        )
        assertTrue("and it was not lost from the queue either", client.pendingSignals().isEmpty())
    }

    @Test
    fun `being offline keeps presence pings, because nothing left the device`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)
        rig.scheduler.advance(60.seconds)            // a quiet minute: one ping in the queue

        // No connection was ever established, so nothing on the other end could have counted the
        // ping. Offline is the ordinary case the on-disk queue exists for - discarding presence
        // for the length of a flight or a tunnel would be loss bought for no safety at all.
        rig.transport.offline = true
        client.flush()

        assertTrue(
            "an unreachable network is not an ambiguous outcome - the ping is kept",
            client.pendingSignals().contains(Signal.HEARTBEAT),
        )

        rig.transport.offline = false
        client.flush()
        assertTrue(
            "and it lands once connectivity comes back",
            rig.transport.signals().contains(Signal.HEARTBEAT),
        )
    }

    // The session id is pre-minted at init whenever the next foreground will start a new session
    // (a fresh install, or a cold start past the timeout). `install` and every event recorded
    // before the first foreground already carry the id `session.start` then adopts, so the server
    // sees one session per launch - never a second, id-less one for the early events.

    @Test
    fun `install and pre-foreground events carry the id session start adopts`() {
        val rig = Rig()
        val client = rig.launch(isNewInstall = true)
        client.recordInstallIfNeeded()
        client.track("early", null)                  // tracked from Application.onCreate, say
        client.setActive(true)
        rig.scheduler.settle()
        client.flush()

        val events = rig.transport.batches().flatten()
        assertEquals(
            listOf(Signal.INSTALL, "early", Signal.SESSION_START),
            events.map { it.signal },
        )
        val ids = events.map { it.sessionId }.toSet()
        assertEquals("one launch, one session id, install included", 1, ids.size)
        assertNotNull("and it is a real id, not null", ids.single())
    }

    @Test
    fun `the pre-minted id is adopted exactly once`() {
        val rig = Rig()
        val client = rig.launch()
        val preminted = client.currentSessionId()
        assertNotNull("minted at init, before any foreground", preminted)

        client.setActive(true)                       // session.start adopts it
        rig.scheduler.settle()
        assertEquals(preminted, client.currentSessionId())

        client.setActive(false)
        rig.clock.advance(30.seconds)
        client.setActive(true)                       // within the timeout: a resume, same id
        rig.scheduler.settle()
        assertEquals(preminted, client.currentSessionId())

        client.setActive(false)
        rig.clock.advance(6.minutes)
        client.setActive(true)                       // past the timeout: a fresh id, never the pre-minted one again
        rig.scheduler.settle()
        assertNotEquals(preminted, client.currentSessionId())
        client.flush()
        assertEquals(2, rig.transport.signals().count { it == Signal.SESSION_START })
    }

    @Test
    fun `a process that dies before the first foreground does not strand the pre-minted id`() {
        val rig = Rig()
        val first = rig.launch(isNewInstall = true)
        first.recordInstallIfNeeded()
        val preminted = first.currentSessionId()
        // The process is killed here: the app never reached the foreground, nothing was sent.

        rig.clock.advance(10.minutes)
        val second = rig.launch()
        assertEquals("the unadopted id is found and reused, not replaced", preminted, second.currentSessionId())
        second.setActive(true)
        rig.scheduler.settle()
        second.flush()

        val events = rig.transport.batches().flatten()
        assertEquals(
            "the install queued by the dead process still opens the batch",
            listOf(Signal.INSTALL, Signal.SESSION_START),
            events.map { it.signal },
        )
        assertEquals("and every event shares the one id", setOf(preminted), events.map { it.sessionId }.toSet())
    }

    @Test
    fun `a cold start past the timeout pre-mints the new session id for early events`() {
        val rig = Rig()
        val first = rig.launch()
        first.setActive(true)
        rig.scheduler.settle()
        val firstSession = first.currentSessionId()
        first.setActive(false)
        first.flush()

        rig.clock.advance(10.minutes)                // relaunched well past the 5-minute timeout
        val second = rig.launch()
        val preminted = second.currentSessionId()
        assertNotEquals("the coming session's id, not the dead session's", firstSession, preminted)
        second.track("from.push", null)              // recorded before any foreground
        second.setActive(true)
        rig.scheduler.settle()
        second.flush()

        val late = rig.transport.batches().last()
        assertEquals(listOf("from.push", Signal.SESSION_START), late.map { it.signal })
        assertEquals(setOf(preminted), late.map { it.sessionId }.toSet())
    }

    @Test
    fun `a relaunch inside the timeout pre-mints nothing and resumes untouched`() {
        val rig = Rig()
        val first = rig.launch()
        first.setActive(true)
        rig.scheduler.settle()
        val sid = first.currentSessionId()
        first.setActive(false)
        first.flush()

        rig.clock.advance(60.seconds)
        val second = rig.launch()
        assertEquals("still inside the session: its id, nothing new", sid, second.currentSessionId())
        second.track("early", null)                  // before the foreground report: still that session's
        second.setActive(true)
        rig.scheduler.settle()
        assertEquals(0, second.pendingSignals().count { it == Signal.SESSION_START })
        assertNull(
            "no pre-minted id was persisted for a session that never needed one",
            rig.platform.prefs.getString("session.pending." + rig.config.appId),
        )
        second.flush()
        assertEquals(setOf(sid), rig.transport.batches().flatten().map { it.sessionId }.toSet())
    }

    /**
     * The id a `session.start` carries has to be on disk before the event is, because `track`
     * persists the queue as it records. A process killed in that window - a force-quit as the app
     * is coming back - otherwise leaves a start for a session nothing on disk names, and the next
     * launch inside the timeout resumes the id before it and files the whole visit under a session
     * whose start was never sent. The pre-minted id has always been written in this order; the
     * in-process mint is the one that was not.
     */
    @Test
    fun `a second session in one process writes its id down before the event that carries it`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val client = rig.launch()
        client.setActive(true)                       // the pre-minted session opens
        rig.scheduler.settle()
        client.setActive(false)                      // and flushes, so the queue file is empty
        rig.clock.advance(6.minutes)                 // past the timeout: the next foreground mints in-process

        var carried: String? = null
        var onDisk: String? = null
        rig.platform.queues.getValue(appId).onSave = { json ->
            val start = EventCoding.decode(json).firstOrNull { it.signal == Signal.SESSION_START }
            if (start != null && carried == null) {
                carried = start.sessionId
                onDisk = rig.platform.prefs.getString("session.$appId")
            }
        }
        client.setActive(true)

        assertNotNull("the session.start reached the queue file", carried)
        assertEquals(
            "the id on disk the moment the start was written is the id the start carries",
            carried,
            onDisk,
        )
    }

    /**
     * The other half of the same fault, read for what it costs. Every question the stamps are
     * asked is safe when the answer is "a long time" and stuck when it is "not yet": a negative gap
     * read as zero means the closing tick never fires, so a visit that ends under a corrected clock
     * is never given an end at all.
     */
    @Test
    fun `a clock that steps backwards still closes the visit`() {
        val rig = Rig()
        val client = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(heartbeatInterval = 1.hours),   // no periodic ping in this test
        )
        client.setActive(true)                  // session.start proves presence at t0
        rig.scheduler.settle()
        rig.clock.advance(-7_200_000L)          // two hours backwards, mid-visit

        client.setActive(false)
        assertEquals(
            "the visit ends where it ended, with one closing ping",
            listOf(Signal.SESSION_START, Signal.HEARTBEAT),
            rig.transport.signals(),
        )
    }

    /**
     * The session boundary itself. The rule is "longer than `sessionTimeout`", so the boundary
     * resumes and only a gap past it starts a new visit. Everything else here drives either a few
     * seconds away or one well clear of the timeout, which leaves the whole of the upper half of
     * the window unpinned: an SDK that halved the configured timeout would stay green while a
     * 20-minute app switch split one visit into two sessions - the count doubled, the average
     * length halved, and neither agreeing with the server's own gap rule any more.
     */
    @Test
    fun `a gap up to the session timeout resumes and one past it does not`() {
        val rig = Rig()
        val client = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(sessionTimeout = 30.minutes, heartbeatInterval = 1.hours),
        )
        client.setActive(true)                       // session.start at t0
        val visit = client.currentSessionId()
        client.setActive(false)

        rig.clock.advance(20.minutes)                // two thirds of the way out: an app switch
        client.setActive(true)
        assertEquals("twenty minutes away is an interruption, not a new visit", visit, client.currentSessionId())
        client.setActive(false)

        rig.clock.advance(30.minutes)                // exactly the timeout
        client.setActive(true)
        assertEquals("the boundary itself resumes: the rule is longer than", visit, client.currentSessionId())
        client.setActive(false)

        rig.clock.advance(30.minutes)
        rig.clock.advance(1)                         // and one millisecond past it does not
        client.setActive(true)
        assertNotEquals(visit, client.currentSessionId())
        client.flush()
        assertEquals(
            "two visits over the four returns",
            2,
            rig.transport.signals().count { it == Signal.SESSION_START },
        )
    }

    /**
     * A rollback is posted from the send loop and applied on the command thread, so a ping stamped
     * in that window is newer than the one being undone. Rolling back past it re-arms the presence
     * loop against a moment the queue has already moved beyond: a second ping goes out while the
     * newer one is still queued, both are delivered, and both are folded into the additive presence
     * and session-length rollups. That is the permanent double count the whole
     * drop-rather-than-retry design exists to prevent.
     */
    @Test
    fun `a rollback never undoes a ping stamped while it was in flight`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(60.seconds)            // a quiet minute: one ping, stamped t60
        val newer = rig.clock.now + 60_000L          // where the ping after it will land

        var tickedMidFlight = false
        rig.transport.onSend = {
            if (!tickedMidFlight) {
                tickedMidFlight = true
                rig.scheduler.advance(60.seconds)    // the presence loop ticks while the batch is out
            }
        }
        rig.transport.script(503)
        client.flush()                               // the server answered, so the ping in the batch is dropped

        assertTrue("the loop really did tick mid-send", tickedMidFlight)
        assertEquals(
            "the ping stamped meanwhile paces us now, and the rollback must leave it alone",
            newer,
            requireNotNull(rig.platform.prefs.getLong("lastHeartbeat.$appId")),
        )
        assertEquals(
            "which is the one ping still owed",
            1,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
        rig.scheduler.advance(59.seconds)
        assertEquals(
            "and no second tick inside the interval it opened",
            1,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
    }

    /**
     * At most one drain waits behind the one running. The send executor is serial and each slice is
     * claimed out of the queue under the lock before its request, so the extra drains a burst of
     * triggers would submit find nothing to send - but the hop is paid per trigger, and the
     * triggers are the flush timer and every full batch.
     */
    @Test
    fun `overlapping flush requests collapse onto a single queued drain`() {
        val rig = Rig()
        val executor = Executors.newSingleThreadExecutor()
        val counted = CountingExecutor(executor)
        try {
            val client = makeClient(rig.platform, rig.clock, rig.scheduler, rig.transport, executor = counted)
            client.track("a", null)
            val arrived = CountDownLatch(1)
            val gate = CountDownLatch(1)
            rig.transport.arrived = arrived
            rig.transport.gate = gate
            client.flush()                           // claims the batch, blocks in send
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            client.flush()                           // one drain lines up behind it
            client.flush()                           // and these two join that one
            client.flush()
            gate.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)

            assertEquals("four flushes, two drains", 2, counted.submitted)
            assertEquals(listOf("a"), rig.transport.signals())
            assertEquals("one request, not four", listOf(1), rig.transport.requestSizes())
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * The retired client's last write is the dangerous one. [Client.shutdown] clears its queue
     * under the lock, but a send already on the wire keeps running, and its answer leads back into
     * the persist path - where what it would write is an empty array over the file the replacement
     * has just saved its own queue into. A `configure` landing mid-send would erase everything the
     * replacement had queued, and a kill after that loses all of it.
     */
    @Test
    fun `a send that lands after the client is retired does not clobber the replacement's queue`() {
        val rig = Rig()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = makeClient(rig.platform, rig.clock, rig.scheduler, rig.transport, executor = executor)
            old.track("before", null)
            val arrived = CountDownLatch(1)
            val gate = CountDownLatch(1)
            rig.transport.arrived = arrived
            rig.transport.gate = gate
            old.flush()                              // claims the batch, blocks in send
            assertTrue(arrived.await(5, TimeUnit.SECONDS))

            old.shutdown()                           // a second configure replaces it mid-send
            val replacement = rig.launch()
            replacement.track("after", null)
            assertEquals(listOf("before", "after"), rig.onDisk())

            gate.countDown()
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(
                "the replacement owns that file now",
                listOf("before", "after"),
                rig.onDisk(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * The 15 s replacement floor lives in the stamp, so it survives the visit ending. The re-arm
     * that enforces it runs only while the app is in front, and a ping dropped by the flush on
     * the way to the background used to leave nothing behind but the rolled-back stamp: coming
     * back seconds later ticked at once, a second ping within seconds of one the server may well
     * have counted, and a relaunch inside the interval did the same with no live state at all.
     */
    @Test
    fun `a dropped ping's replacement floor survives a background bounce`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val start = rig.clock.now
        val client = rig.launch()
        client.setActive(true)                       // session.start proves presence at t0
        client.flush()                               // and is acknowledged, so only the ping is ever owed
        rig.scheduler.advance(60.seconds)            // quiet for an interval: the loop ticks at t60
        assertEquals(listOf(Signal.SESSION_START), rig.transport.signals())
        assertEquals(listOf(Signal.HEARTBEAT), client.pendingSignals())

        rig.transport.script(500)
        rig.clock.advance(1.seconds)
        client.setActive(false)                      // quiet for 1 s: no closing tick, and the flush fails
        rig.scheduler.settle()                       // the roll-back hops home to the command thread
        assertEquals(
            "rolled back to fifteen seconds after the dropped ping, not erased",
            start + 15_000L,
            rig.platform.prefs.getLong("lastHeartbeat.$appId"),
        )

        rig.clock.advance(4.seconds)
        client.setActive(true)                       // back five seconds after the dropped ping
        assertEquals(
            "the replacement is due 15 s after the ping it replaces, not the moment the app is back",
            10_000L,
            client.millisUntilNextHeartbeat(),
        )
        rig.scheduler.advance(9.seconds)
        assertEquals(0, client.pendingSignals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(1.seconds)
        assertEquals(1, client.pendingSignals().count { it == Signal.HEARTBEAT })
    }

    /**
     * The same late answer can also carry the server's cadence floor, and the floor key is the
     * install's: the replacement client read it at its own init and owns it from then on. A
     * retired client adopting the floor would write preferences on behalf of a client that was
     * told to record nothing further.
     */
    @Test
    fun `a cadence floor that lands after the client is retired is not adopted`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = rig.launch(executor = executor)
            old.track("before", null)
            val arrived = CountDownLatch(1)
            val gate = CountDownLatch(1)
            rig.transport.arrived = arrived
            rig.transport.gate = gate
            old.flush()                              // claims the batch, blocks in send
            assertTrue(arrived.await(5, TimeUnit.SECONDS))

            old.shutdown()                           // a second configure replaces it mid-send
            rig.transport.heartbeatIntervalSeconds = 240
            gate.countDown()                         // the 2xx lands, floor and all
            executor.submit {}.get(5, TimeUnit.SECONDS)

            assertNull(
                "the floor key is the replacement's to move",
                rig.platform.prefs.getLong("heartbeatFloor.$appId"),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * The closing ping is a ping like any other, so the stamp moves with it. Left unstamped it is
     * invisible to everything that paces the loop: the user comes back a few seconds later,
     * `startHeartbeat` reads a stamp from before the visit ended and fires again at once - two
     * ticks within seconds, both delivered, both folded into the additive rollups. A relaunch
     * inside the timeout has the same problem, because nothing on disk records the closing ping
     * either.
     */
    @Test
    fun `the closing ping moves the presence stamp, so coming back does not tick again`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val client = rig.launch()
        client.setActive(true)                       // session.start proves presence at t0
        rig.scheduler.settle()
        rig.clock.advance(90.seconds)                // quiet, and the loop is never given the chance to tick
        client.setActive(false)                      // so this is the visit's only ping, stamped t90

        assertEquals(listOf(Signal.SESSION_START, Signal.HEARTBEAT), rig.transport.signals())
        assertEquals(
            "queued and stamped together, like every other ping",
            rig.clock.now,
            requireNotNull(rig.platform.prefs.getLong("lastHeartbeat.$appId")),
        )

        rig.clock.advance(10.seconds)
        client.setActive(true)                       // back ten seconds later
        rig.scheduler.settle()
        assertEquals(
            "the server heard from this install ten seconds ago; it owes nothing yet",
            0,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
        rig.scheduler.advance(50.seconds)
        assertEquals(
            "and the next tick lands a full interval after the closing one",
            1,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
    }

    /**
     * The acknowledged-ping stamp is what a dropped ping rolls back TO, so it may only move
     * forward. A clock corrected backwards mid-visit is how an older ping comes to be acknowledged
     * after a newer one: the closing tick is stamped with the corrected clock, and taking that as
     * the newest thing the server knows would send the next rollback further into the past than the
     * server's real knowledge - so the install pings again inside an interval it had already
     * proved, and presence is counted twice.
     */
    @Test
    fun `the acknowledged-ping stamp never moves backwards`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val start = rig.clock.now
        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(60.seconds)            // a ping at t60
        client.flush()                               // acknowledged: the server knows about t60

        rig.clock.advance(-30_000L)                  // a time server nudges the clock back to t30
        client.setActive(false)                      // the closing tick is stamped t30, and acknowledged too
        assertEquals(2, rig.transport.signals().count { it == Signal.HEARTBEAT })

        rig.clock.advance(10_000L)                   // reopened at t40, inside the timeout
        client.setActive(true)
        rig.scheduler.advance(50.seconds)            // a ping at t90
        rig.transport.script(503)
        client.flush()                               // dropped, so the stamp rolls back

        assertEquals(
            "to t60, the newest ping the server acknowledged, never to the older one behind it",
            start + 60_000L,
            requireNotNull(rig.platform.prefs.getLong("lastHeartbeat.$appId")),
        )
        rig.scheduler.advance(29.seconds)
        assertEquals(
            "so the replacement ping waits out the interval t60 already proved",
            0,
            client.pendingSignals().count { it == Signal.HEARTBEAT },
        )
        rig.scheduler.advance(1.seconds)
        assertEquals(1, client.pendingSignals().count { it == Signal.HEARTBEAT })
    }

    /**
     * A stamp at or before the epoch is not one a running device wrote; it is what a handset whose
     * RTC was never set leaves behind. In the arithmetic it is harmless - an enormous elapsed time
     * answers every question the way an absent stamp does - but it also seeds the acknowledged-ping
     * stamp with a number instead of nothing, and a rollback would then write that nonsense back to
     * disk for every launch after this one. Discarded at init, the rollback has no acknowledged
     * ping at all, so it writes the replacement floor paced from the dropped ping itself: a real
     * moment this device's running clock produced, never the number from the unset one.
     */
    @Test
    fun `a stamp at the epoch is discarded rather than carried forward`() {
        val rig = Rig()
        val appId = rig.config.appId!!
        val start = rig.clock.now
        rig.platform.prefs.putLong("lastHeartbeat.$appId", 0L)

        val client = rig.launch()
        client.setActive(true)                       // session.start at t0
        rig.scheduler.advance(60.seconds)            // a ping at t60
        rig.transport.script(503)
        client.flush()                               // the server answered, so the ping is dropped

        assertEquals(
            "no acknowledged ping to go back to, so the floor stands: fifteen seconds after the " +
                "dropped ping, never the number from the unset clock",
            start + 15_000L,
            rig.platform.prefs.getLong("lastHeartbeat.$appId"),
        )
    }

    /**
     * Every ping refreshes the last-active stamp, so a process killed while still in the foreground
     * leaves a recent one behind. Without it the stamp is the last transition's, and an app left
     * open and quiet for longer than `sessionTimeout` - a recipe on the counter, a long read, a
     * paused video - then killed by the system reads as an absence on relaunch: one continuous
     * visit filed as two sessions, inflating the session count and collapsing the average length on
     * exactly the apps whose users stay longest.
     */
    @Test
    fun `a ping keeps the session alive across a kill in the foreground`() {
        val rig = Rig()
        val first = rig.launch()
        first.setActive(true)                        // session.start at t0
        val visit = first.currentSessionId()
        rig.scheduler.advance(6.minutes)             // open, quiet and pinging, past the 5-minute timeout
        assertEquals("six quiet minutes, six pings", 6, first.pendingSignals().count { it == Signal.HEARTBEAT })
        // Killed here, with no background transition: the last one was at t0.

        rig.clock.advance(10.seconds)
        val second = rig.launch()
        assertEquals(
            "the pings say someone was here ten seconds ago, so this is the same visit",
            visit,
            second.currentSessionId(),
        )
        val inherited = second.pendingSignals().count { it == Signal.SESSION_START }
        second.setActive(true)
        rig.scheduler.settle()
        assertEquals(
            "and the relaunch opens no second session",
            inherited,
            second.pendingSignals().count { it == Signal.SESSION_START },
        )
    }

    /**
     * What the flush cadence costs a low-rate app, which is the shape most apps have. Below roughly
     * two events a second `maxBatchSize` never fills, so the timer alone decides the request count:
     * one request per interval for the whole visit, each carrying about as much HTTP head as
     * payload, and each promoting the cellular radio in an SDK the end user did not choose to
     * install. At a ten-second interval that is one request per event for an app doing six a
     * minute. Leaving the foreground still sends everything at once, whatever the interval says.
     */
    @Test
    fun `a low-rate visit costs one request per flush interval, not one per event`() {
        assertEquals(
            "the shipped default: three events to a request over a ten-minute visit",
            21,
            requestsForATenMinuteVisit(30.seconds),
        )
        assertEquals(
            "at ten seconds the timer fires faster than the events arrive, so each one is its own request",
            61,
            requestsForATenMinuteVisit(10.seconds),
        )
    }

    /** Ten minutes in the foreground at six events a minute; the number of requests it takes. */
    private fun requestsForATenMinuteVisit(flushInterval: Duration): Int {
        val rig = Rig()
        val client = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(
                maxBatchSize = 20,                       // the shipped default, which never fills here
                flushInterval = flushInterval,
                heartbeatInterval = 1.hours,             // presence pings are not what this measures
            ),
        )
        client.setActive(true)
        for (i in 0 until 60) {
            rig.scheduler.advance(10.seconds)
            client.track("e$i", null)
        }
        client.setActive(false)                          // leaving sends what is left, at once
        assertTrue("everything reached the wire", client.pendingSignals().isEmpty())
        return rig.transport.requestSizes().size
    }
}
