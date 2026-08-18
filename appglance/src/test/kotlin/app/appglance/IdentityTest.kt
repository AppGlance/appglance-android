package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
}
