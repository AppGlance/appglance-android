package app.appglance

import org.junit.Assert.assertEquals
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
}
