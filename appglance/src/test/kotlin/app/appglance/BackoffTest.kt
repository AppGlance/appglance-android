package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun `an explicit flush ignores the backoff window`() {
        val rig = Rig()
        rig.transport.script(503)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // refused: a window is now in force
        rig.client.flush()                           // the developer said now
        assertEquals(listOf(2, 2), rig.transport.requestSizes())
        assertTrue(rig.client.pendingSignals().isEmpty())
    }

    @Test
    fun `the window never exceeds sixty seconds`() {
        val rig = Rig(random = { 1.0 })              // jitter pinned to the top of the window
        rig.transport.script(503, 503, 503, 503, 503, 503, 503)
        rig.client.track("a", null)
        rig.client.track("b", null)                  // failure 1
        for (i in 2..7) {                            // failures 2 through 7: past where the cap bites
            rig.scheduler.advance(61.seconds)        // past any window, so the next trigger attempts
            rig.client.track("e$i", null)
        }
        val attempts = rig.transport.requestSizes().size
        assertEquals(7, attempts)

        rig.client.track("late", null)               // deferred by the seventh failure's window
        rig.scheduler.advance(59.seconds)
        assertEquals("the cap holds the wait at sixty seconds", attempts, rig.transport.requestSizes().size)
        rig.scheduler.advance(1.seconds)             // exactly sixty: delivered
        assertEquals(attempts + 1, rig.transport.requestSizes().size)
        assertTrue(rig.client.pendingSignals().isEmpty())
    }
}
