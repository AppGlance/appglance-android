package app.appglance

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours

/**
 * The public `AppGlance` facade end to end on a real (sandboxed) Android: calls are applied in
 * call order, `install` comes first, calls made before `configure` are held and replayed, the
 * real platform persists the id, and a flush reaches the transport from its own thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppGlanceFacadeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val transport = RecordingTransport()

    private fun config(debug: Boolean = false) = AppGlance.Configuration(
        apiKey = "glance_live_test",
        endpoint = "https://ingest.invalid/v1/events",
        flushInterval = 1.hours,
        maxBatchSize = 500,
        enabledEnvironments = AppEnvironment.values().toSet(),
        trackAppLifecycle = false,     // driven by hand below; ProcessLifecycleOwner is exercised separately
        debug = debug,
    )

    @Before
    fun fresh() {
        AppGlance.resetForTesting()
        AppGlance.transportFactory = { transport }
    }

    @After
    fun tearDown() {
        AppGlance.resetForTesting()
    }

    @Test
    fun `calls apply in order, install is first, and calls before configure are held and replayed`() {
        AppGlance.track("early")                          // nobody has configured anything yet
        AppGlance.identify(email = "ada@example.com")
        AppGlance.configure(app, config())                // a fresh install on this "device"
        AppGlance.track("late", mapOf("k" to "v"))
        AppGlance.trackScreen("paywall")
        AppGlance.drain()

        val client = assertNotNull(AppGlance.currentClientForTesting())
        val events = client.pendingEvents()
        val expected = listOf(Signal.INSTALL, "early", Signal.IDENTIFY, "late", "screen.paywall")
        assertEquals(expected, events.map { it.signal })
        // `install` is stamped with the configure moment; everything else with its own call time,
        // so the calls made before configure legitimately predate it - and stay in call order.
        val stamps = events.drop(1).map { it.clientTs }
        assertEquals("client timestamps are taken at call time, in order", stamps.sorted(), stamps)
        assertTrue(
            "early calls carry their own (earlier) time, not the replay time",
            events[1].clientTs <= events[0].clientTs,
        )
        assertEquals(mapOf("k" to "v"), events[3].metadata)
        assertEquals(app.packageName, client.appId)
        assertTrue(events.all { it.userId == events[0].userId })
    }

    @Test
    fun `a relaunch keeps the install id and does not report a second install`() {
        AppGlance.configure(app, config())
        AppGlance.track("first-run")
        AppGlance.drain()
        val first = assertNotNull(AppGlance.currentClientForTesting())
        val events = first.pendingEvents()
        assertEquals(listOf(Signal.INSTALL, "first-run"), events.map { it.signal })
        val userId = events[0].userId

        // "Kill and relaunch": the facade forgets everything in memory; the device keeps its files.
        AppGlance.resetForTesting()
        AppGlance.transportFactory = { transport }
        AppGlance.configure(app, config())
        AppGlance.track("second-run")
        AppGlance.drain()
        val second = assertNotNull(AppGlance.currentClientForTesting())
        val again = second.pendingEvents()
        assertEquals(
            "the queue was persisted, and there is no second install",
            listOf(Signal.INSTALL, "first-run", "second-run"),
            again.map { it.signal },
        )
        assertTrue("same install id across launches", again.all { it.userId == userId })
    }

    @Test
    fun `flush before configure is harmless and a later flush reaches the transport`() {
        AppGlance.flush()                                  // drops nothing, sends nothing
        AppGlance.configure(app, config())
        AppGlance.track("after")
        AppGlance.flush()
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        assertEquals(listOf(Signal.INSTALL, "after"), transport.signals())
        assertTrue(assertNotNull(AppGlance.currentClientForTesting()).pendingSignals().isEmpty())
    }

    @Test
    fun `rapid inactive-active ends active with one session, and stop flushes`() {
        transport.offline = true       // the mid-sequence flush must not empty the queue under us
        AppGlance.configure(app, config())
        AppGlance.setActive(true)      // first activity started
        AppGlance.setActive(true)      // second report of the same fact
        AppGlance.setActive(false)     // a moment away… (flushes: fails, everything is kept in order)
        AppGlance.setActive(true)      // …and back, all within a moment
        AppGlance.drain()
        val client = assertNotNull(AppGlance.currentClientForTesting())
        // The heartbeat's first tick is posted with no delay; let the command thread run it, and
        // let the failed send put its batch back.
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        val signals = client.pendingSignals()
        assertEquals("one visit, one session", 1, signals.count { it == Signal.SESSION_START })
        assertEquals("and exactly one heartbeat so far", 1, signals.count { it == Signal.HEARTBEAT })
        assertEquals(listOf(Signal.INSTALL, Signal.SESSION_START, Signal.HEARTBEAT), signals)

        transport.offline = false
        AppGlance.setActive(false)     // leaving: flush
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        assertEquals(listOf(Signal.INSTALL, Signal.SESSION_START, Signal.HEARTBEAT), transport.signals())
        assertTrue(client.pendingSignals().isEmpty())
    }

    @Test
    fun `the lifecycle bridge maps process start and stop onto setActive`() {
        AppGlance.configure(app, config())
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = LifecycleRegistry(this)
        }
        LifecycleBridge.onStart(owner)
        AppGlance.drain()
        AppGlance.drain()
        val client = assertNotNull(AppGlance.currentClientForTesting())
        assertEquals(listOf(Signal.INSTALL, Signal.SESSION_START, Signal.HEARTBEAT), client.pendingSignals())
        LifecycleBridge.onStop(owner)
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        assertEquals(listOf(Signal.INSTALL, Signal.SESSION_START, Signal.HEARTBEAT), transport.signals())
        // Installing the real bridge (ProcessLifecycleOwner) must be safe to call any number of times.
        LifecycleBridge.install()
        LifecycleBridge.install()
    }

    @Test
    fun `a second configure replaces the client without losing or doubling anything`() {
        AppGlance.configure(app, config())
        AppGlance.track("first")
        val first = assertNotNull(AppGlance.currentClientForTesting())

        AppGlance.configure(app, config())
        AppGlance.track("second")
        val second = assertNotNull(AppGlance.currentClientForTesting())
        assertTrue("a new client for the new configuration", first !== second)
        assertEquals(
            "the replacement inherits the persisted queue; the same install adds no second install",
            listOf(Signal.INSTALL, "first", "second"),
            second.pendingSignals(),
        )
        assertTrue("the retired client holds nothing", first.pendingSignals().isEmpty())
    }

    @Test
    fun `configure with the plain key overload and debug mode narrates to logcat`() {
        AppGlance.configure(app, "glance_live_test", debug = true)
        AppGlance.drain()
        val client = assertNotNull(AppGlance.currentClientForTesting())
        assertTrue("debug lifts the gate for whatever environment Robolectric looks like", client.collecting)
        AppGlance.resetForTesting()
        assertNull(AppGlance.currentClientForTesting())
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
