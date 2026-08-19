package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `identify` / `setUserProperties` / `reset`: labels on the install id, sent only when they
 * change, remembered across launches, forgotten on sign-out.
 */
class UserPropertiesTest {

    private class Rig {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val transport = RecordingTransport()
        val platform = InMemoryPlatform()
        fun launch(): Client = makeClient(platform, clock, scheduler, transport)
    }

    @Test
    fun `identify sends once and merges later`() {
        val client = Rig().launch()

        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada"))
        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada")) // same again: free
        var queued = client.pendingEvents()
        assertEquals("identical values must not cost a second event", listOf(Signal.IDENTIFY), queued.map { it.signal })
        assertEquals(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada"), queued[0].metadata)

        client.identify(mapOf("plan" to "pro"))   // a new property merges with the old ones
        queued = client.pendingEvents()
        assertEquals(listOf(Signal.IDENTIFY, Signal.IDENTIFY), queued.map { it.signal })
        assertEquals(
            "each identify carries the WHOLE merged set, so the server can store it as-is",
            mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada", "plan" to "pro"),
            queued[1].metadata,
        )

        client.identify(mapOf("plan" to ""))      // empty string removes a key
        assertEquals(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada"), client.currentTraits())
    }

    @Test
    fun `traits survive a relaunch so the usual launch-time identify is free`() {
        val rig = Rig()
        val first = rig.launch()
        first.identify(mapOf(UserProperty.ID to "acct_42"))
        assertEquals(listOf(Signal.IDENTIFY), first.pendingSignals())

        val second = rig.launch()                 // the app relaunches, identify runs again
        second.identify(mapOf(UserProperty.ID to "acct_42"))
        // The queue is shared on disk (persisted by `first`), so it holds exactly the one event
        // from the first launch - the relaunch added nothing.
        assertEquals(
            "same values after a relaunch: nothing new to send",
            listOf(Signal.IDENTIFY),
            second.pendingSignals(),
        )
    }

    @Test
    fun `reset forgets and re-identify resends everything`() {
        val client = Rig().launch()
        client.reset()                            // nothing to forget yet: silent
        assertEquals(emptyList<String>(), client.pendingSignals())

        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        client.reset()
        assertEquals(listOf(Signal.IDENTIFY, Signal.RESET), client.pendingSignals())
        assertEquals(emptyMap<String, String>(), client.currentTraits())

        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))   // signs back in
        val queued = client.pendingEvents()
        assertEquals(listOf(Signal.IDENTIFY, Signal.RESET, Signal.IDENTIFY), queued.map { it.signal })
        assertEquals(
            "after a reset the same values are new again and must be re-sent",
            mapOf(UserProperty.EMAIL to "ada@example.com"),
            queued[2].metadata,
        )
    }

    @Test
    fun `at most twenty properties, reserved ones first`() {
        val client = Rig().launch()
        val many = LinkedHashMap<String, String>()
        many[UserProperty.EMAIL] = "ada@example.com"
        for (i in 0 until 30) many["k" + i.toString().padStart(2, '0')] = "v"
        client.identify(many)
        val traits = client.currentTraits()
        assertEquals("the ingest API keeps 20 metadata keys; the SDK must not pretend otherwise", 20, traits.size)
        assertEquals("reserved keys are kept ahead of custom ones", "ada@example.com", traits[UserProperty.EMAIL])
    }

    @Test
    fun `keys and values are trimmed and clamped to the server's limits`() {
        val client = Rig().launch()
        client.identify(mapOf("  plan  " to "  " + "x".repeat(300), "k".repeat(60) to "v", "   " to "ignored"))
        val traits = client.currentTraits()
        assertEquals(setOf("plan", "k".repeat(40)), traits.keys)
        assertEquals(200, traits["plan"]!!.length)
    }

    /**
     * The cut is made in UTF-16 code units, which is what the ingest counts in, so the two agree
     * on where it falls - except when it falls between the two halves of a surrogate pair. What is
     * left then ends in a lone high surrogate, which UTF-8 encoding turns into `?` on the way out,
     * so the server can only store something this snapshot does not have; and since only a change
     * is sent, no later `identify` with the same values could ever correct it.
     */
    @Test
    fun `a clamp never leaves half of a surrogate pair behind`() {
        val client = Rig().launch()
        val grin = "\uD83D\uDE00"                  // U+1F600: two code units, and the cut lands inside it
        client.identify(mapOf("k".repeat(39) + grin to "v".repeat(199) + grin))
        val traits = client.currentTraits()
        val key = traits.keys.single()
        val value = traits.getValue(key)

        assertEquals("the orphaned half goes, not the pair's first unit alone", 39, key.length)
        assertEquals(199, value.length)
        for (kept in listOf(key, value)) {
            assertEquals(
                "what is remembered has to survive the encoding it is sent in",
                kept,
                String(kept.toByteArray(Charsets.UTF_8), Charsets.UTF_8),
            )
        }
    }

    /**
     * The snapshot names what the server acknowledged, not what was queued. A `user.identify` the
     * ingest never stored has to leave the next identical call something to send, or the install's
     * page in the dashboard stays blank for as long as the app keeps passing the same values -
     * which is what the documentation tells it to do at every launch.
     */
    @Test
    fun `an identify that never lands is sent again by the next identical call`() {
        val rig = Rig()
        val client = rig.launch()
        rig.transport.script(400)                 // a permanent 4xx: the slice is dropped, never stored
        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        client.flush()
        assertEquals("the batch was dropped, not kept for a retry", emptyList<String>(), client.pendingSignals())
        assertEquals(
            "nothing was acknowledged, so nothing is remembered",
            emptyMap<String, String>(),
            client.deliveredTraits(),
        )

        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        assertEquals(
            "the same values are new again: the event that carried them never reached the server",
            listOf(Signal.IDENTIFY),
            client.pendingSignals(),
        )
    }

    /**
     * A 202 is not by itself proof that the rows were stored. Past the plan's grace ceiling the
     * ingest answers 202 and drops `user.identify`, saying so in `accepted`; a snapshot committed
     * on the status code alone would freeze this install's properties for the rest of the month.
     */
    @Test
    fun `an identify dropped over quota is sent again`() {
        val rig = Rig()
        val client = rig.launch()
        rig.transport.acceptedCount = 0
        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        client.flush()
        assertEquals(
            "a batch the server counted short is not an acknowledgement",
            emptyMap<String, String>(),
            client.deliveredTraits(),
        )

        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        assertEquals(
            "so the app's next identify has something to send",
            listOf(Signal.IDENTIFY),
            client.pendingSignals(),
        )
    }

    /**
     * The other direction, which is what stops the fix costing the customer an event a launch: a
     * delivered identify IS remembered, and the launch-time repeat the docs encourage is free.
     */
    @Test
    fun `a delivered identify is remembered so the next identical call is free`() {
        val rig = Rig()
        val client = rig.launch()
        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        client.flush()
        assertEquals(mapOf(UserProperty.EMAIL to "ada@example.com"), client.deliveredTraits())

        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com"))
        assertEquals(
            "an acknowledged snapshot still makes a repeat cost nothing",
            emptyList<String>(),
            client.pendingSignals(),
        )
    }

    /**
     * Withdrawing consent has to reach the person's own data. `$email` and `$name` sit in the same
     * SharedPreferences, inside Auto Backup, and `reset()` cannot clear them once collection is
     * off - which is the order an app is most likely to use.
     */
    @Test
    fun `withdrawing consent clears the stored properties and a closed gate does not`() {
        val rig = Rig()
        val key = "traits.app.appglance.test"
        val client = rig.launch()
        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada"))
        client.flush()
        assertNotNull("acknowledged, so it is on disk", rig.platform.prefs.map[key])
        client.shutdown()

        // A debuggable build run over an installed release copy shares this storage. Its closed
        // environment gate is not a withdrawal of consent and must leave the snapshot alone.
        val gated = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(enabledEnvironments = emptySet()),
        )
        assertNotNull("a closed environment gate is not a withdrawal", rig.platform.prefs.map[key])
        gated.shutdown()

        val disabled = makeClient(
            rig.platform,
            rig.clock,
            rig.scheduler,
            rig.transport,
            config = testConfiguration(isEnabled = false),
        )
        assertNull(
            "an email and a name must not outlive the consent they were collected under",
            rig.platform.prefs.map[key],
        )
        assertEquals(emptyMap<String, String>(), disabled.currentTraits())
        // And `reset()` after the fact is still the no-op it always was, which is the point: the
        // withdrawal itself has to do the clearing.
        disabled.reset()
        assertEquals(emptyList<String>(), disabled.pendingSignals())
    }

    /**
     * Sign-out clears the person from disk at the moment it is asked for, not when the
     * `user.reset` is acknowledged. It is the one place clearing ahead of delivery is right: an
     * empty snapshot suppresses nothing, because every later `identify` sends its whole set
     * anyway. Waiting for the acknowledgement leaves `$email` and `$name` in SharedPreferences -
     * and inside Auto Backup - for as long as the reset is undelivered, which is forever if the
     * queue cap trims it, a permanent 4xx drops it, or the app is uninstalled first. `currentTraits`
     * reads the pending reset and so cannot see this: only the disk can.
     */
    @Test
    fun `an offline sign-out clears the person from disk without waiting for the reset to land`() {
        val rig = Rig()
        val key = "traits.app.appglance.test"
        val client = rig.launch()
        client.identify(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada"))
        client.flush()
        assertNotNull("acknowledged, so the person is on disk", rig.platform.prefs.map[key])

        rig.transport.offline = true
        client.reset()                            // signed out on a plane
        assertNull(
            "the sign-out asked for this to be gone, and nothing about a queued event says it is",
            rig.platform.prefs.map[key],
        )

        client.flush()                            // still no network
        assertEquals("the reset is still owed", listOf(Signal.RESET), client.pendingSignals())
        assertNull("and the person is still gone from disk", rig.platform.prefs.map[key])

        // A relaunch after the reset was lost for good must not find them either.
        rig.platform.queues.getValue("app.appglance.test").delete()
        val relaunched = rig.launch()
        assertEquals(emptyMap<String, String>(), relaunched.deliveredTraits())
    }

    /**
     * Sign out while an earlier `identify` is still on the wire. The reset clears the person from
     * disk immediately; the identify's 2xx then lands carrying what the server held a moment ago,
     * and committing that would write `$email` and `$name` straight back behind the sign-out - and
     * back into Auto Backup. Nothing commits while a newer identify or reset is still owed; that
     * one commits when its own batch lands.
     */
    @Test
    fun `a late acknowledgement does not put a signed-out person back on disk`() {
        val rig = Rig()
        val key = "traits.app.appglance.test"
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val client = makeClient(rig.platform, rig.clock, rig.scheduler, rig.transport, executor = executor)
            client.identify(mapOf(UserProperty.EMAIL to "ada@example.com", UserProperty.NAME to "Ada"))

            val arrived = java.util.concurrent.CountDownLatch(1)
            val gate = java.util.concurrent.CountDownLatch(1)
            rig.transport.arrived = arrived
            rig.transport.gate = gate
            // The identify is accepted; the reset queued behind it is not, which is what leaves
            // the window open long enough to see. Every way the reset can be lost - a trim, a
            // permanent 4xx, an uninstall - leaves it open for good.
            rig.transport.script(202, 503)
            client.flush()                        // the identify is on the wire
            assertTrue(arrived.await(5, java.util.concurrent.TimeUnit.SECONDS))

            client.reset()                        // the user signs out while it is still out there
            assertNull("cleared the moment the sign-out was made", rig.platform.prefs.map[key])

            gate.countDown()                      // now the identify comes back accepted
            executor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)

            assertEquals("and the reset never landed", listOf(Signal.RESET), client.pendingSignals())

            assertNull(
                "the data the user asked to be forgotten must not be written back behind them",
                rig.platform.prefs.map[key],
            )
            assertEquals(emptyMap<String, String>(), client.deliveredTraits())
        } finally {
            executor.shutdownNow()
        }
    }
}
