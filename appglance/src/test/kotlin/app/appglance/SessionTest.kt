package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
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
        fun launch(): Client = makeClient(platform, clock, scheduler, transport, config)

        /** The signals persisted for the app id - what a relaunch would load. */
        fun onDisk(): List<String> {
            val json = platform.queues.getValue(config.appId!!).json!!
            return EventCoding.decode(json).map { it.signal }
        }
    }

    @Test
    fun `launch starts exactly one session and one heartbeat`() {
        val rig = Rig()
        val client = rig.launch()

        // Two activities starting, or onStart reported twice: two reports of the same fact.
        client.setActive(true)
        client.setActive(true)
        rig.scheduler.settle()

        assertEquals(
            "one session.start then one heartbeat - never two of either",
            listOf(Signal.SESSION_START, Signal.HEARTBEAT),
            client.pendingSignals(),
        )
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
            "resuming inside the heartbeat interval must not tick again immediately",
            1,
            sent.count { it == Signal.HEARTBEAT },
        )
        assertTrue("a successful flush clears the queue - nothing to replay later", client.pendingSignals().isEmpty())
    }

    @Test
    fun `the heartbeat keeps ticking every interval while active and stops when inactive`() {
        val rig = Rig()
        val client = rig.launch()
        client.setActive(true)
        rig.scheduler.settle()
        rig.scheduler.advance(3.minutes)             // three more ticks at 60 s spacing
        assertEquals(1 + 3, client.pendingSignals().count { it == Signal.HEARTBEAT })

        client.setActive(false)                      // flushes: everything so far is on the server
        assertEquals(4, rig.transport.signals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(30.seconds)            // nothing ticks while backgrounded
        assertTrue(client.pendingSignals().isEmpty())

        // Back after 30 s: the next beat is due 30 s later, not immediately.
        client.setActive(true)
        rig.scheduler.settle()
        assertEquals("no fresh beat right away", 0, client.pendingSignals().count { it == Signal.HEARTBEAT })
        rig.scheduler.advance(30.seconds)
        assertEquals("one when the interval completes", 1, client.pendingSignals().count { it == Signal.HEARTBEAT })

        // A long absence is a new session, and its first beat is immediate.
        client.setActive(false)
        rig.scheduler.advance(10.minutes)
        client.setActive(true)
        rig.scheduler.settle()
        assertEquals(listOf(Signal.SESSION_START, Signal.HEARTBEAT), client.pendingSignals())
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
            "each return after the gap is a new session with its own first heartbeat",
            listOf(Signal.SESSION_START, Signal.HEARTBEAT, Signal.SESSION_START, Signal.HEARTBEAT),
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
        val first = rig.launch()
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
     */
    @Test
    fun `the queue on disk never contains an in-flight heartbeat`() {
        val rig = Rig()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val client = makeClient(rig.platform, rig.clock, rig.scheduler, rig.transport, executor = executor)

            client.setActive(true)              // session.start + heartbeat
            rig.scheduler.settle()
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
        rig.scheduler.settle()
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
        rig.scheduler.settle()

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
}
