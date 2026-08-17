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

    private fun config() = AppGlance.Configuration(
        apiKey = "glance_live_test",
        endpoint = "https://ingest.invalid/v1/events",
        flushInterval = 1.hours,
        maxBatchSize = 500,
        enabledEnvironments = AppEnvironment.values().toSet(),
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
}
