package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/**
 * The process the system starts in the background: a push, a scheduled job, a broadcast. The
 * first run of a build that adopts the SDK is often one of those, and nobody sees it.
 */
class BackgroundLaunchTest {
    private val appId = "app.appglance.test"
    private val platform = InMemoryPlatform()
    private val clock = FakeClock()
    private val scheduler = FakeScheduler(clock)

    private fun launch(isNewInstall: Boolean) = makeClient(
        platform,
        clock,
        scheduler,
        RecordingTransport(),
        config = testConfiguration(heartbeatInterval = 1.hours),
        userId = "u-background",
        isNewInstall = isNewInstall,
    )

    private val owed: Boolean get() = platform.prefs.getBoolean("install.pending.$appId")

    @Test
    fun `an install minted in the background is recorded at the first foreground`() {
        val client = launch(isNewInstall = true)
        client.setActive(false)
        assertEquals("nothing to count yet, so nothing recorded", emptyList<String>(), client.pendingSignals())
        assertTrue("the debt is on disk, so a process that ends here leaves it to the next one", owed)

        clock.advance(3.hours)
        client.setActive(true)
        val events = client.pendingEvents()
        assertEquals(listOf(Signal.INSTALL, Signal.SESSION_START), events.map { it.signal })
        assertEquals("one session, as on any first visit", events[1].sessionId, events[0].sessionId)
        assertEquals("dated by the visit, not by the launch nobody saw", clock.now, events[0].clientTs)
        assertFalse(owed)
    }

    @Test
    fun `an event recorded in the background takes the install with it`() {
        val client = launch(isNewInstall = true)
        client.setActive(false)
        client.track("refresh.done", null)
        clock.advance(1.hours)
        client.setActive(true)

        val events = client.pendingEvents()
        assertEquals(listOf(Signal.INSTALL, "refresh.done", Signal.SESSION_START), events.map { it.signal })
        assertEquals("stamped with the event that made it due", events[1].clientTs, events[0].clientTs)
    }

    @Test
    fun `an install owed by a background launch is paid by the next visit`() {
        val woken = launch(isNewInstall = true)
        woken.setActive(false)
        assertEquals(emptyList<String>(), woken.pendingSignals())
        woken.shutdown()                                           // the process ends

        val next = launch(isNewInstall = false)                    // the id is stored now
        next.setActive(true)
        assertEquals("counted once, on the visit", listOf(Signal.INSTALL, Signal.SESSION_START), next.pendingSignals())
    }

    @Test
    fun `a visit that resumes a session still brings the owed install`() {
        val first = launch(isNewInstall = false)
        first.setActive(true)
        first.setActive(false)
        first.shutdown()
        platform.prefs.putBoolean("install.pending.$appId", true)  // a gated launch left the debt

        clock.advance(1_000)                                       // well inside the session timeout
        val resumed = launch(isNewInstall = false)
        resumed.setActive(true)
        assertEquals(
            "a resume records no session.start, only the install it owes",
            listOf(Signal.INSTALL),
            resumed.pendingSignals(),
        )
    }
}
