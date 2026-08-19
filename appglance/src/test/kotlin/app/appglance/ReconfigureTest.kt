package app.appglance

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours

/**
 * `configure` called again mid-run - the documented way to apply a consent change - against the
 * real `ProcessLifecycleOwner`. The replacement client must pick up the app's CURRENT foreground
 * state at once: `addObserver` replays the lifecycle only to a newly added observer, and the
 * bridge has been attached since the first configure, so without the explicit hand-off the new
 * client would sit inactive until the app was backgrounded and reopened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconfigureTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val transport = RecordingTransport()
    private val registry: LifecycleRegistry
        get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry

    private fun config(trackAppLifecycle: Boolean = true, isEnabled: Boolean = true) = AppGlance.Configuration(
        apiKey = "glance_live_test",
        endpoint = "https://ingest.invalid/v1/events",
        flushInterval = 1.hours,
        maxBatchSize = 500,
        isEnabled = isEnabled,
        enabledEnvironments = AppEnvironment.values().toSet(),
        trackAppLifecycle = trackAppLifecycle,
    )

    @Before
    fun fresh() {
        AppGlance.resetForTesting()
        AppGlance.transportFactory = { transport }
    }

    @After
    fun tearDown() {
        // Lower the process lifecycle again so the next test (and the next class in this sandbox)
        // starts from the background, then forget the SDK state.
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        AppGlance.resetForTesting()
    }

    @Test
    fun `a second configure while the app is in the foreground resumes the session immediately`() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)   // the app is in front of the user
        AppGlance.configure(app, config())
        AppGlance.drain()
        AppGlance.drain()
        val first = requireNotNull(AppGlance.currentClientForTesting())
        assertEquals("the first configure went active", 1, first.pendingSignals().count { it == Signal.SESSION_START })
        val heartbeatsBefore = first.pendingSignals().count { it == Signal.HEARTBEAT }
        assertEquals("the start proved presence; no ping yet", 0, heartbeatsBefore)

        AppGlance.configure(app, config())                         // a consent change, say
        AppGlance.drain()
        AppGlance.drain()
        val second = requireNotNull(AppGlance.currentClientForTesting())
        assertTrue("a replacement client, not the same one", first !== second)
        val signals = second.pendingSignals()
        assertEquals(
            "the replacement resumed the running session rather than starting another",
            1,
            signals.count { it == Signal.SESSION_START },
        )
        assertEquals(
            "and it pings no sooner than the first client would have: the presence stamps are the install's, " +
                "not the client object's, so the session.start a moment ago still counts",
            heartbeatsBefore,
            signals.count { it == Signal.HEARTBEAT },
        )
    }

    /**
     * The same hand-over for an app that draws its own session boundaries. `trackAppLifecycle =
     * false` detaches the bridge, so nothing re-delivers the foreground state to a replacement
     * client, and the app's own `onStart` fired long before the consent switch was touched: the
     * screen it lives on is still on top, so no activity transition follows either.
     */
    @Test
    fun `a second configure keeps the foreground state an app reports itself`() {
        AppGlance.configure(app, config(trackAppLifecycle = false))
        AppGlance.setActive(true)                                  // the app's own onStart
        AppGlance.drain()
        AppGlance.drain()
        val first = requireNotNull(AppGlance.currentClientForTesting())
        assertEquals(
            "the app reported the foreground itself",
            1,
            first.pendingSignals().count { it == Signal.SESSION_START },
        )

        AppGlance.configure(app, config(trackAppLifecycle = false))   // consent granted, same screen
        AppGlance.drain()
        AppGlance.drain()
        val second = requireNotNull(AppGlance.currentClientForTesting())
        assertTrue("a replacement client, not the same one", first !== second)

        AppGlance.track("after.reconfigure")
        AppGlance.setActive(false)                                 // an active client flushes on the way out
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        assertEquals(
            "the replacement was in the foreground the app had already reported: it resumed that " +
                "session rather than starting another, and it flushed when the app left",
            listOf(Signal.INSTALL, Signal.SESSION_START, "after.reconfigure"),
            transport.signals(),
        )
    }

    @Test
    fun `configure in the background hands the client no start until the app comes forward`() {
        assertTrue(!registry.currentState.isAtLeast(Lifecycle.State.STARTED))
        AppGlance.configure(app, config())
        AppGlance.drain()
        AppGlance.drain()
        val client = requireNotNull(AppGlance.currentClientForTesting())
        assertEquals(
            "backgrounded: install only, no session, no heartbeat",
            listOf(Signal.INSTALL),
            client.pendingSignals(),
        )

        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)    // now the app comes forward
        AppGlance.drain()
        AppGlance.drain()
        assertEquals(
            listOf(Signal.INSTALL, Signal.SESSION_START),
            client.pendingSignals(),
        )
        assertNotNull(client.currentSessionId())
    }

    /**
     * The consent flow end to end, and the one shape where the hand-over is the only source of the
     * answer. An app waiting for consent configures with `isEnabled = false` while it sits in front
     * of the user, and reports the foreground itself; the gate drops the report, but the report is
     * still what happened. Consent is granted on that screen and the app configures again: with
     * `trackAppLifecycle = false` there is no bridge to re-deliver the state, and the app's own
     * `onStart` fired long before the switch was touched. A replacement left inactive records no
     * `session.start`, sends no presence ping and does not flush on the way out, so granting
     * consent would produce a completely silent session.
     */
    @Test
    fun `granting consent hands the replacement the foreground the gated client was told about`() {
        AppGlance.configure(app, config(trackAppLifecycle = false, isEnabled = false))
        AppGlance.setActive(true)                                  // the app's own onStart, dropped by the gate
        AppGlance.drain()
        AppGlance.drain()
        val gated = requireNotNull(AppGlance.currentClientForTesting())
        assertEquals("nothing is recorded while consent is withheld", emptyList<String>(), gated.pendingSignals())

        AppGlance.configure(app, config(trackAppLifecycle = false))   // consent granted, same screen
        AppGlance.drain()
        AppGlance.drain()
        val second = requireNotNull(AppGlance.currentClientForTesting())
        assertTrue("a replacement client, not the same one", gated !== second)

        AppGlance.track("after.consent")
        AppGlance.setActive(false)                                 // an active client flushes on the way out
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        assertEquals(
            "the replacement was told the app is in front of the user: it opened the session and flushed",
            listOf(Signal.INSTALL, Signal.SESSION_START, "after.consent"),
            transport.signals(),
        )
    }
}
