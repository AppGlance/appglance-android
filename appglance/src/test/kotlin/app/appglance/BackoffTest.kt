package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.seconds

/**
 * Automatic delivery retries back off exponentially after a transient failure - jittered, capped
 * at 60 seconds, floored by a numeric Retry-After on a 429 - and the first success resets it.
 * Only automatic triggers wait; an explicit [Client.flush] is the developer saying "now" and
 * always attempts. With the jitter pinned to 0 (see `makeClient`), the window after the n-th
 * consecutive failure is exactly `min(60 s, 2^n s) / 2`: 1 s, 2 s, 4 s, and so on.
 */
class BackoffTest {

    private class Rig(maxBatchSize: Int = 2, random: () -> Double = { 0.0 }) {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val transport = RecordingTransport()
        val platform = InMemoryPlatform()
        val client = makeClient(
            platform,
            clock,
            scheduler,
            transport,
            testConfiguration(maxBatchSize = maxBatchSize),
            random = random,
        )
    }

    @Test
    fun `a size-triggered flush after a transient failure waits out the backoff`() {
        val rig = Rig()
        rig.transport.script(503)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // the batch is full: sent, refused, kept
        assertEquals(listOf(2), rig.transport.requestSizes())

        rig.client.track("c", null)                  // full again, but inside the 1 s window
        assertEquals("the failed batch is not hammered", listOf(2), rig.transport.requestSizes())

        rig.scheduler.advance(1.seconds)             // the deferred attempt runs when the window closes
        assertEquals(listOf(2, 3), rig.transport.requestSizes())
        assertEquals(listOf("a", "b", "c"), rig.transport.signals())
        assertTrue(rig.client.pendingSignals().isEmpty())
    }

    @Test
    fun `the wait doubles with consecutive failures and the first success resets it`() {
        val rig = Rig()
        rig.transport.script(503, 503)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // failure 1: the next window is 1 s
        rig.client.track("c", null)                  // deferred
        rig.scheduler.advance(1.seconds)             // failure 2: the next window is 2 s
        assertEquals(listOf(2, 3), rig.transport.requestSizes())

        rig.client.track("d", null)                  // deferred again
        rig.scheduler.advance(1.seconds)
        assertEquals("one second is no longer enough", listOf(2, 3), rig.transport.requestSizes())
        rig.scheduler.advance(1.seconds)             // two are: delivered, streak reset
        assertEquals(listOf(2, 3, 4), rig.transport.requestSizes())

        rig.transport.script(503)
        rig.client.track("e", null)
        rig.client.track("f", null)                  // attempts at once: the reset really happened
        assertEquals(listOf(2, 3, 4, 2), rig.transport.requestSizes())
        rig.client.track("g", null)
        rig.scheduler.advance(1.seconds)             // and the streak starts over at a 1 s window
        assertEquals(listOf(2, 3, 4, 2, 3), rig.transport.requestSizes())
    }

    @Test
    fun `a numeric Retry-After on 429 floors the wait`() {
        val rig = Rig()
        rig.transport.retryAfterSeconds = 90
        rig.transport.script(429)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // refused with Retry-After: 90
        rig.client.track("c", null)                  // deferred to the server's floor, not the 1 s jitter
        rig.scheduler.advance(89.seconds)
        assertEquals("the server said ninety seconds", listOf(2), rig.transport.requestSizes())
        rig.scheduler.advance(1.seconds)
        assertEquals(listOf(2, 3), rig.transport.requestSizes())
        assertTrue(rig.client.pendingSignals().isEmpty())
    }

    @Test
    fun `an absurd Retry-After is clamped to fifteen minutes`() {
        val rig = Rig()
        rig.transport.retryAfterSeconds = 86_400     // a day: an outage, not rate limiting
        rig.transport.script(429)
        rig.client.track("a", null)
        rig.client.track("b", null)
        rig.client.track("c", null)
        rig.scheduler.advance(899.seconds)
        assertEquals("the header is obeyed up to the ceiling", listOf(2), rig.transport.requestSizes())
        rig.scheduler.advance(1.seconds)
        assertEquals("and never past it", listOf(2, 3), rig.transport.requestSizes())
        assertTrue(rig.client.pendingSignals().isEmpty())
    }

    @Test
    fun `an explicit flush ignores the backoff window`() {
        val rig = Rig()
        rig.transport.script(503)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // refused: a window is now in force
        rig.client.flush()                           // the developer said now
        assertEquals(listOf(2, 2), rig.transport.requestSizes())
        assertTrue(rig.client.pendingSignals().isEmpty())
    }

    /**
     * The window has to be read where the send starts. A flush timer that fires while a request is
     * on the wire sees no window yet, because the request it would queue behind is the one that is
     * about to arm it: the deferred drain then ran the instant that request came back, so one
     * outage counted as two consecutive failures and a server that had just asked for room was hit
     * again with no delay at all.
     */
    @Test
    fun `a flush timer that fires mid-send does not retry inside the window that send arms`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val transport = RecordingTransport()
        // Drains are held here instead of run, which is what the serial send executor does to a
        // drain requested while another one is still inside `transport.send`.
        val waitingDrains = ArrayDeque<Runnable>()
        val client = makeClient(
            InMemoryPlatform(),
            clock,
            scheduler,
            transport,
            testConfiguration(flushInterval = 10.seconds),
            executor = Executor { waitingDrains.addLast(it) },
        )
        transport.script(503)

        client.track("a", null)
        scheduler.advance(10.seconds)                // the flush timer fires: one drain is queued
        assertEquals(1, waitingDrains.size)

        // The next timer fires while the first request is still out, so the drain it asks for
        // lines up behind the attempt that is about to fail.
        transport.onSend = {
            client.track("b", null)
            scheduler.advance(10.seconds)
        }
        waitingDrains.removeFirst().run()
        transport.onSend = {}
        assertEquals(listOf(1), transport.requestSizes())
        assertEquals("the second drain was queued behind the failing one", 1, waitingDrains.size)

        waitingDrains.removeFirst().run()
        assertEquals(
            "the window the failure armed is obeyed by the drain decided before it",
            listOf(1),
            transport.requestSizes(),
        )

        scheduler.advance(1.seconds)                 // and the attempt runs when the window closes
        assertEquals(1, waitingDrains.size)
        waitingDrains.removeFirst().run()
        assertEquals(listOf(1, 2), transport.requestSizes())
        assertEquals(listOf("a", "b"), transport.signals())
        assertTrue(client.pendingSignals().isEmpty())
    }

    @Test
    fun `the window never exceeds sixty seconds`() {
        val rig = Rig(random = { 1.0 })              // jitter pinned to the top of the window
        rig.transport.script(503, 503, 503, 503, 503, 503, 503)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // failure 1: the window it arms is 2 s
        // A retryable failure arms its own retry, so the windows are walked by advancing exactly
        // each one rather than by tracking again: 2, 4, 8, 16 and 32 seconds, and then the cap.
        // Advancing the doubling sequence is only enough to reach the seventh refusal because
        // each window is the one the failure before it armed, and the sixth is 60 s and not 64.
        for (window in listOf(2, 4, 8, 16, 32, 60)) {
            rig.scheduler.advance(window.seconds)    // failures 2 through 7: past where the cap bites
        }
        val attempts = rig.transport.requestSizes().size
        assertEquals("seven refusals, one per window", 7, attempts)

        rig.client.track("late", null)               // deferred by the seventh failure's window
        rig.scheduler.advance(59.seconds)
        assertEquals("the cap holds the wait at sixty seconds", attempts, rig.transport.requestSizes().size)
        rig.scheduler.advance(1.seconds)             // exactly sixty: delivered
        assertEquals(attempts + 1, rig.transport.requestSizes().size)
        assertTrue(rig.client.pendingSignals().isEmpty())
    }

    /**
     * An automatic trigger inside the retry window defers rather than sending, and defers without
     * waking the send loop at all. The drain reads the window again where the send starts, so
     * nothing reaches the wire early either way - but a trigger that submits regardless pays an
     * executor hop for every full batch and every timer tick for the length of an outage, on a
     * queue it already knows it may not send.
     */
    @Test
    fun `an automatic trigger inside the retry window never reaches the send loop`() {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val transport = RecordingTransport()
        val counted = CountingExecutor(directExecutor)
        val client = makeClient(
            InMemoryPlatform(),
            clock,
            scheduler,
            transport,
            testConfiguration(maxBatchSize = 2),
            executor = counted,
            random = { 0.0 },
        )
        transport.script(503)

        client.track("a", null)
        client.track("b", null)                      // the batch is full: sent, refused, kept
        assertEquals(listOf(2), transport.requestSizes())
        assertEquals("one drain, the one that attempted", 1, counted.submitted)

        client.track("c", null)                      // full again, but inside the 1 s window
        assertEquals("the failed batch is not hammered", listOf(2), transport.requestSizes())
        assertEquals("and the send loop is not woken to be told so", 1, counted.submitted)

        scheduler.advance(1.seconds)                 // the deferred attempt runs when the window closes
        assertEquals(listOf(2, 3), transport.requestSizes())
        assertEquals(2, counted.submitted)
        assertEquals(listOf("a", "b", "c"), transport.signals())
        assertTrue(client.pendingSignals().isEmpty())
    }
}
