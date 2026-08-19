package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test
    fun `the anonymous id is stable across calls and new exactly once`() {
        val store = InMemoryIdentityStore()
        val first = requireNotNull(AnonymousIdentity.current(store))
        val second = requireNotNull(AnonymousIdentity.current(store))
        assertEquals("Anonymous id must be stable for an install", first.id, second.id)
        assertTrue(first.id.isNotEmpty())
        assertTrue("The minting call is the install moment", first.isNew)
        assertFalse("Only the minting call may report a new install", second.isNew)
    }

    @Test
    fun `different stores mint different ids`() {
        assertNotEquals(
            AnonymousIdentity.current(InMemoryIdentityStore())?.id,
            AnonymousIdentity.current(InMemoryIdentityStore())?.id,
        )
    }

    @Test
    fun `a persisted id is reused, and a reinstall is not a new install`() {
        val store = InMemoryIdentityStore("EXISTING-ID")
        val identity = requireNotNull(AnonymousIdentity.current(store))
        assertEquals("EXISTING-ID", identity.id)
        assertFalse("A reinstall must not look like a new install", identity.isNew)
    }

    /**
     * The id is device-bound - it is honoured only where the marker for the device that minted it
     * still matches - but the session, the presence stamps and the user properties beside it are
     * in the same SharedPreferences, which Auto Backup and a device-to-device transfer both carry.
     * The restored handset correctly mints its own id; it must not then read the old device's
     * state as its own, or it opens no session of its own and never sends the properties its
     * install page is waiting for.
     */
    @Test
    fun `a new install id does not inherit the state a transfer carried over`() {
        val platform = InMemoryPlatform()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)

        val a = makeClient(platform, clock, scheduler, RecordingTransport(), userId = "install-A")
        a.setActive(true)
        a.identify(mapOf("\$email" to "ada@example.com"))
        a.flush()
        val sessionA = a.currentSessionId()
        a.shutdown()

        // The queue file is not part of this: it lives in noBackupFilesDir, so it never arrives on
        // the second device. The preferences beside it do. The transfer lands and the app is
        // opened a minute later - inside the session timeout, so the old device's session would
        // still look resumable.
        clock.advance(60_000)
        val transport = RecordingTransport()
        val b = makeClient(platform, clock, scheduler, transport, userId = "install-B", isNewInstall = true)
        b.recordInstallIfNeeded()
        assertEquals(
            "the new install starts with no properties of its own",
            emptyMap<String, String>(),
            b.currentTraits(),
        )
        assertNotEquals("and does not continue the old device's session", sessionA, b.currentSessionId())

        b.setActive(true)
        b.identify(mapOf("\$email" to "ada@example.com"))
        b.flush()
        assertEquals(
            "so its first visit is a session of its own, and its properties reach the server",
            listOf(Signal.INSTALL, Signal.SESSION_START, Signal.IDENTIFY),
            transport.signals(),
        )
    }

    /**
     * `isNew` is what makes the SDK record an `install`, so it is claimed only for an id the store
     * really kept. A device that cannot write - a full data partition - otherwise mints a fresh id
     * on every launch and records an install for each, and nothing on the server can collapse
     * those: every one of them arrives under a different user id.
     */
    @Test
    fun `an id the store did not keep is not a new install`() {
        val store = InMemoryIdentityStore(dropsWrites = true)
        val first = requireNotNull(AnonymousIdentity.current(store))
        assertTrue("the run still has an id to send this launch's events under", first.id.isNotEmpty())
        assertFalse("but it belongs to no install, so no install is recorded", first.isNew)

        val second = requireNotNull(AnonymousIdentity.current(store))
        assertNotEquals("the next launch finds nothing and mints again", first.id, second.id)
        assertFalse("and claims nothing by that one either", second.isNew)
    }

    /**
     * Credential-encrypted storage is unreadable before the user's first unlock (Direct Boot) -
     * exactly when a background launch can happen. Minting there would create a phantom second
     * user; the SDK must wait instead.
     */
    @Test
    fun `a locked store does not mint an identity`() {
        val locked = InMemoryIdentityStore("REAL-ID", locked = true)
        assertNull("no answer beats a wrong one", AnonymousIdentity.current(locked))
        val unlocked = InMemoryIdentityStore("REAL-ID")
        assertEquals("REAL-ID", AnonymousIdentity.current(unlocked)?.id)
        assertEquals(false, AnonymousIdentity.current(unlocked)?.isNew)
    }

    /**
     * The environment gate excludes debuggable builds and emulators by default, and an app waiting
     * for consent configures with `isEnabled = false`, so the launch that mints the install id is
     * very often one that can record nothing. `isNewInstall` is true on that launch only, so the
     * debt has to outlive it: otherwise every later launch finds the stored id, records nothing,
     * and the install never appears in the dashboard at all.
     */
    @Test
    fun `a gated first launch still records the install on the first launch that collects`() {
        val platform = InMemoryPlatform()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)

        val gated = makeClient(
            platform,
            clock,
            scheduler,
            RecordingTransport(),
            config = testConfiguration(enabledEnvironments = emptySet()),
            userId = "install-1",
            isNewInstall = true,
            installAt = clock.now,
        )
        gated.recordInstallIfNeeded()
        assertEquals("a gated client records nothing", emptyList<String>(), gated.pendingSignals())
        gated.shutdown()

        // The next launch is the release build: the id is already stored, so `isNewInstall` is
        // false, and only the note left on disk can say that an install is still owed.
        val transport = RecordingTransport()
        val sending = makeClient(platform, clock, scheduler, transport, userId = "install-1", installAt = clock.now)
        sending.recordInstallIfNeeded()
        assertEquals(
            "the install the gated launch could not record",
            listOf(Signal.INSTALL),
            sending.pendingSignals(),
        )
        sending.flush()
        sending.shutdown()

        // And exactly once: the debt is paid, so a third launch owes nothing.
        val later = makeClient(platform, clock, scheduler, RecordingTransport(), userId = "install-1")
        later.recordInstallIfNeeded()
        assertEquals("and never a second time", emptyList<String>(), later.pendingSignals())
    }

    /**
     * The other half of the same doubled check. A store can report a write it did not keep AND a
     * store can keep a write it will not hand back, and the two are different faults: the Direct
     * Boot window between an unchecked `save` and an unlock-checked `lookup` is the concrete
     * Android case for the second. Reading the id back is what catches it. Claiming that id as new
     * records an `install` for an id the next launch will not find, and the device then arrives as
     * a stream of one-event users nothing server-side can collapse.
     */
    @Test
    fun `an id the store will not hand back is not a new install either`() {
        val forgets = requireNotNull(AnonymousIdentity.current(UnkeptIdentityStore()))
        assertTrue("the run still has an id to send this launch's events under", forgets.id.isNotEmpty())
        assertFalse("the write said it landed, but the read back says otherwise", forgets.isNew)

        // And a store that hands back something else: whatever is on disk, it is not this id, so
        // this id belongs to no install.
        val swaps = requireNotNull(AnonymousIdentity.current(UnkeptIdentityStore(readsBackAfterSave = "SOMEONE-ELSE")))
        assertNotEquals("SOMEONE-ELSE", swaps.id)
        assertFalse("an id the store does not hold is not this install's id", swaps.isNew)
    }

    /**
     * The debt marker clears BEFORE the event is queued, because `track` persists the queue as it
     * records: the event is durable the instant it is queued. Killed in the window, the two orders
     * cost different things - clearing first loses one uncounted install, clearing afterwards
     * leaves the marker set and the next launch pays the debt again, so the server receives two
     * `install` events for one device. The pre-minted session id's marker follows the same rule
     * for the same reason.
     */
    @Test
    fun `the install marker is already gone when the install event reaches the queue file`() {
        val platform = InMemoryPlatform()
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val appId = "app.appglance.test"
        platform.prefs.putBoolean("install.pending.$appId", true)   // a gated launch left the debt

        val client = makeClient(platform, clock, scheduler, RecordingTransport(), userId = "install-1")
        var markerAtWrite: Boolean? = null
        var prefsAtWrite: Map<String, Any> = emptyMap()
        var queueAtWrite: String? = null
        platform.queues.getValue(appId).onSave = { json ->
            if (queueAtWrite == null && EventCoding.decode(json).any { it.signal == Signal.INSTALL }) {
                markerAtWrite = platform.prefs.getBoolean("install.pending.$appId")
                prefsAtWrite = HashMap(platform.prefs.map)
                queueAtWrite = json
            }
        }
        client.recordInstallIfNeeded()

        assertEquals(listOf(Signal.INSTALL), client.pendingSignals())
        assertNotNull("the install reached the queue file", queueAtWrite)
        assertEquals("the debt is cleared before the event it pays is durable", false, markerAtWrite)

        // The kill itself: a second process reading exactly what was on disk at that instant.
        val crashed = InMemoryPlatform()
        crashed.prefs.map.putAll(prefsAtWrite)
        crashed.queues[appId] = InMemoryQueueStore().apply { json = queueAtWrite }
        val relaunched = makeClient(crashed, clock, scheduler, RecordingTransport(), userId = "install-1")
        relaunched.recordInstallIfNeeded()
        assertEquals(
            "the install the dead process queued, and no second one",
            listOf(Signal.INSTALL),
            relaunched.pendingSignals(),
        )
    }
}
