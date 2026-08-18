package app.appglance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/**
 * The environment gate and debug mode: emulator/debug builds send nothing by default; `debug`
 * lifts that (and only that); the tag on the event stays honest; `isEnabled = false` wins.
 */
class EnvironmentGateTest {

    private val lines = ArrayList<String>()
    private lateinit var previousSink: (String) -> Unit

    @Before fun captureLog() {
        previousSink = Log.sink
        Log.sink = { lines += it }
    }

    @After fun restoreLog() {
        Log.sink = previousSink
    }

    private fun client(env: AppEnvironment, config: AppGlance.Configuration): Client {
        val clock = FakeClock()
        return makeClient(
            InMemoryPlatform(device = FakeDeviceInfo(env = env)),
            clock,
            FakeScheduler(clock),
            RecordingTransport(),
            config,
        )
    }

    @Test
    fun `the default gate keeps emulator and debug builds out, and lets production and beta through`() {
        val default = AppGlance.Configuration(apiKey = "glance_live_test")
        assertEquals(setOf(AppEnvironment.PRODUCTION, AppEnvironment.BETA), default.enabledEnvironments)
        assertEquals(false, default.debug)

        for (env in AppEnvironment.values()) {
            val c = client(env, testConfiguration(enabledEnvironments = default.enabledEnvironments))
            c.track("paywall.viewed", null)
            c.setActive(true)
            val expected = env == AppEnvironment.PRODUCTION || env == AppEnvironment.BETA
            assertEquals("$env sends? ", expected, c.pendingSignals().isNotEmpty())
        }
    }

    @Test
    fun `debug mode lifts the environment gate but not the off-switch`() {
        val gated = client(AppEnvironment.DEBUG, testConfiguration(enabledEnvironments = emptySet()))
        gated.track("paywall.viewed", null)
        assertEquals(
            "environment excluded, debug off: nothing is recorded",
            emptyList<String>(),
            gated.pendingSignals(),
        )
        assertTrue("…and the SDK says so, once", lines.any { it.startsWith("not sending: this is a debuggable build") })

        val debugging = client(AppEnvironment.DEBUG, testConfiguration(enabledEnvironments = emptySet(), debug = true))
        debugging.track("paywall.viewed", mapOf("source" to "test"))
        debugging.identify(mapOf("plan" to "pro"))
        val recorded = debugging.pendingEvents()
        assertEquals(
            "debug on: the same build records events and properties",
            listOf("paywall.viewed", Signal.IDENTIFY),
            recorded.map { it.signal },
        )
        assertEquals(
            "debug mode never lies about the environment - the tag stays real, so Live stays clean",
            AppEnvironment.DEBUG.wireValue,
            recorded.first().environment,
        )
        assertTrue(lines.any { it.startsWith("debug mode on · environment: debug · sending to ingest.invalid") })
        assertTrue(lines.any { it.startsWith("▸ paywall.viewed") })

        val offConfig = testConfiguration(enabledEnvironments = emptySet(), debug = true, isEnabled = false)
        val off = client(AppEnvironment.DEBUG, offConfig)
        off.track("paywall.viewed", null)
        assertEquals("isEnabled = false wins over debug mode", emptyList<String>(), off.pendingSignals())
    }

    @Test
    fun `a beta channel is opt-in via the configuration and emulator or debug detection still wins`() {
        val clock = FakeClock()
        fun env(detected: AppEnvironment, override: AppEnvironment?) =
            FakeDeviceInfo(env = detected).environment(override)
        assertEquals(AppEnvironment.PRODUCTION, env(AppEnvironment.PRODUCTION, null))
        assertEquals(AppEnvironment.BETA, env(AppEnvironment.PRODUCTION, AppEnvironment.BETA))
        assertEquals(AppEnvironment.EMULATOR, env(AppEnvironment.EMULATOR, AppEnvironment.BETA))
        assertEquals(AppEnvironment.DEBUG, env(AppEnvironment.DEBUG, AppEnvironment.PRODUCTION))

        val beta = makeClient(
            InMemoryPlatform(),
            clock,
            FakeScheduler(clock),
            RecordingTransport(),
            AppGlance.Configuration(
                apiKey = "glance_live_test",
                appId = "a",
                environment = AppEnvironment.BETA,
                flushInterval = 1.hours,
                maxBatchSize = 500,
            ),
        )
        beta.track("x", null)
        assertEquals("beta", beta.pendingEvents().single().environment)
    }

    /**
     * `isEnabled = false` is how an app honours a consent withdrawal, and it has to apply to what
     * is already on disk: the events recorded before the switch was flipped are exactly the ones
     * consent was withdrawn for. The Swift SDK holds the same line.
     */
    @Test
    fun `turning collection off discards what was already queued`() {
        val platform = InMemoryPlatform()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val transport = RecordingTransport()

        val collecting = makeClient(platform, clock, scheduler, transport, testConfiguration())
        collecting.track("before.the.switch", null)
        collecting.identify(mapOf("\$email" to "ada@example.com"))
        val onDisk = EventCoding.decode(requireNotNull(platform.queues.values.single().json))
        assertEquals("both are on disk", 2, onDisk.size)
        collecting.shutdown()

        // The app calls configure again with the switch off; the replacement client owns the file.
        val off = makeClient(platform, clock, scheduler, transport, testConfiguration(isEnabled = false))
        assertEquals("the backlog is not inherited", emptyList<String>(), off.pendingSignals())
        assertNull(
            "nor left on disk to be resurrected by turning the switch back on",
            platform.queues.values.single().json,
        )

        off.flush()
        assertEquals("an explicit flush after withdrawal sends nothing", emptyList<String>(), transport.signals())

        val backOn = makeClient(platform, clock, scheduler, transport, testConfiguration())
        backOn.flush()
        assertEquals("and turning it back on resurrects nothing", emptyList<String>(), transport.signals())
    }

    /**
     * A closed environment gate is not a withdrawal of consent: a debuggable build run over an
     * installed release copy closes it for that run, and destroying the file there would throw
     * away a real queue the release build saved during an outage. It is not loaded, and it is
     * still there for the build that owns it.
     */
    @Test
    fun `a closed environment gate leaves the queue on disk`() {
        val platform = InMemoryPlatform()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)

        val sending = makeClient(platform, clock, scheduler, RecordingTransport(), testConfiguration())
        sending.track("from.the.release.build", null)
        sending.shutdown()

        val gatedConfig = testConfiguration(enabledEnvironments = emptySet())
        val gated = makeClient(platform, clock, scheduler, RecordingTransport(), gatedConfig)
        assertEquals("a gated client loads nothing", emptyList<String>(), gated.pendingSignals())
        assertNotNull(
            "but the queue is still owed to the build that recorded it",
            platform.queues.values.single().json,
        )
    }

    @Test
    fun `nothing is logged for a normal production launch`() {
        val c = client(AppEnvironment.PRODUCTION, testConfiguration())
        c.track("x", null)
        c.flush()
        assertEquals(emptyList<String>(), lines)
    }
}
