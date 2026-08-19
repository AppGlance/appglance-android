package app.appglance

import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Where foreground state comes from, and when it stops coming.
 *
 * `ProcessLifecycleOwner` only reports once androidx.startup's `InitializationProvider` has run,
 * and a host app that trims that provider out of its manifest leaves the registry at INITIALIZED
 * for the life of the process - no `session.start`, no presence ping, no flush on background,
 * while `install` and every `track` call still ship. Robolectric leaves the registry in exactly
 * that state, which is what these tests run against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifecycleSourceTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val transport = RecordingTransport()
    private val registry: LifecycleRegistry
        get() = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val lines = ArrayList<String>()
    private lateinit var previousSink: (String) -> Unit

    /** The facade's clock, so "six minutes later" is a number rather than a wait. */
    private var clock = 1_700_000_000_000L

    private fun config(trackAppLifecycle: Boolean = true) = AppGlance.Configuration(
        apiKey = "glance_live_test",
        endpoint = "https://ingest.invalid/v1/events",
        flushInterval = 1.hours,
        heartbeatInterval = 1.hours,     // presence pings are not what these tests are about
        maxBatchSize = 500,
        sessionTimeout = 5.minutes,
        enabledEnvironments = AppEnvironment.values().toSet(),
        trackAppLifecycle = trackAppLifecycle,
    )

    @Before
    fun fresh() {
        AppGlance.resetForTesting()
        // `ProcessLifecycleOwner` is a process singleton and Robolectric gives this module one
        // sandbox, so every test class here shares the registry - and nothing can put it back:
        // the lowest an ON_STOP reaches is CREATED, so once any class has driven it, INITIALIZED
        // is gone for the rest of the run. That state is this class's whole premise, and class
        // order is not fixed, so it is restored here rather than assumed. Safe with no observers
        // attached, which the reset above guarantees by detaching the bridge.
        registry.currentState = Lifecycle.State.INITIALIZED
        AppGlance.transportFactory = { transport }
        AppGlance.now = { clock }
        previousSink = Log.sink
        Log.sink = { lines += it }
    }

    @After
    fun tearDown() {
        Log.sink = previousSink
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        AppGlance.resetForTesting()
    }

    @Test
    fun `an inert ProcessLifecycleOwner falls back to watching the activities`() {
        assertEquals(
            "nothing ran the process lifecycle initializer, so the registry never left INITIALIZED",
            Lifecycle.State.INITIALIZED,
            registry.currentState,
        )
        AppGlance.configure(app, config())
        AppGlance.drain()
        val client = requireNotNull(AppGlance.currentClientForTesting())
        assertEquals(listOf(Signal.INSTALL), client.pendingSignals())
        assertTrue(
            "and the SDK says which piece is missing, rather than reporting nothing in silence",
            lines.any { it.startsWith("androidx.startup's InitializationProvider isn't in this app's manifest") },
        )

        val activity = Robolectric.buildActivity(Activity::class.java).create().start()
        AppGlance.drain()
        AppGlance.drain()
        assertEquals(
            "an activity in front of the user is the app in front of the user",
            listOf(Signal.INSTALL, Signal.SESSION_START),
            client.pendingSignals(),
        )

        activity.stop()
        AppGlance.drain()
        AppGlance.awaitSenderIdle()
        assertEquals(
            "and leaving flushes what the visit recorded",
            listOf(Signal.INSTALL, Signal.SESSION_START),
            transport.signals(),
        )
        activity.destroy()
    }

    /**
     * `trackAppLifecycle = false` has to mean the same thing on the second `configure` as on the
     * first: an app that turns it off to draw its own session boundaries (the end of a match, say)
     * must not keep getting the platform's as well.
     */
    @Test
    fun `trackAppLifecycle false on a later configure stops the platform's transitions`() {
        AppGlance.configure(app, config())                       // the default: automatic sessions
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        AppGlance.drain()
        AppGlance.drain()
        val first = requireNotNull(AppGlance.currentClientForTesting())
        assertEquals(1, first.pendingSignals().count { it == Signal.SESSION_START })
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        AppGlance.drain()

        clock += 6.minutes.inWholeMilliseconds                   // past the session timeout
        AppGlance.configure(app, config(trackAppLifecycle = false))
        AppGlance.drain()
        AppGlance.drain()
        val second = requireNotNull(AppGlance.currentClientForTesting())
        // The replacement inherits the queue the first client persisted, session.start included.
        val inherited = second.pendingSignals().count { it == Signal.SESSION_START }

        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)  // the platform's boundary, not the app's
        AppGlance.drain()
        AppGlance.drain()
        assertEquals(
            "the developer drives setActive now; a session they did not ask for is one they cannot explain",
            inherited,
            second.pendingSignals().count { it == Signal.SESSION_START },
        )

        AppGlance.setActive(true)                                // …and their own call still works
        AppGlance.drain()
        assertEquals(inherited + 1, second.pendingSignals().count { it == Signal.SESSION_START })
    }
}
