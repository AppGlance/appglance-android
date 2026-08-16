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
