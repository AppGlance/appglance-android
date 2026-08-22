package app.appglance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a genuinely new user from one who has had the app for years and only just met the SDK.
 * The evidence rides one SDK-owned event per install; the server decides what it means.
 */
class InstallOriginTest {

    private val year = 365L * 24 * 60 * 60 * 1000

    private fun origins(events: List<Event>): List<Map<String, String>> = events.mapNotNull { event ->
        event.metadata.orEmpty().filterKeys { it.startsWith("\$install") }.takeIf { it.isNotEmpty() }
    }

    @Test
    fun `the package manager's date rides this install's install event`() {
        val clock = FakeClock()
        val installed = clock.now - 3 * year
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = installed))
        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = true)

        client.recordInstallIfNeeded()

        val queued = client.pendingEvents()
        assertEquals(listOf(Signal.INSTALL), queued.map { it.signal })
        assertEquals(Iso8601.format(installed), queued[0].metadata?.get(InstallOrigin.KEY_INSTALLED_AT))
        assertEquals("package", queued[0].metadata?.get(InstallOrigin.KEY_EVIDENCE))
    }

    /**
     * The app's own record outranks the package manager's: it survives the handset, and it knows
     * about users who predate every device they now own.
     */
    @Test
    fun `an app-supplied date outranks the package manager`() {
        val clock = FakeClock()
        val signup = clock.now - 5 * year
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - 10_000))
        val client = makeClient(
            platform,
            clock,
            FakeScheduler(clock),
            RecordingTransport(),
            config = testConfiguration(firstInstalledAt = signup),
            isNewInstall = true,
        )

        client.recordInstallIfNeeded()

        val carried = origins(client.pendingEvents())
        assertEquals(1, carried.size)
        assertEquals("app", carried[0][InstallOrigin.KEY_EVIDENCE])
        assertEquals(Iso8601.format(signup), carried[0][InstallOrigin.KEY_INSTALLED_AT])
    }

    /** Sent once per install: a second answer for the same fact is one the server must reconcile. */
    @Test
    fun `the origin rides one event only`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - year))
        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = true)

        client.recordInstallIfNeeded()
        client.setActive(true)

        val queued = client.pendingEvents()
        assertTrue(
            "the session opened, so there was a second carrier",
            queued.any { it.signal == Signal.SESSION_START },
        )
        assertEquals(1, origins(queued).size)
    }

    /**
     * The whole point of the feature, for the installs that need it most: an app already running an
     * older SDK has a user base counted as new on adoption day. Those installs have nothing written
     * for the origin, so the first carrier after the upgrade backfills them - and it is never an
     * `install` event, because they had theirs long ago.
     */
    @Test
    fun `an existing install backfills on its next session`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - 2 * year))
        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = false)

        client.recordInstallIfNeeded()
        client.setActive(true)

        val queued = client.pendingEvents()
        assertFalse("an old install owes no install event", queued.any { it.signal == Signal.INSTALL })
        val carried = origins(queued)
        assertEquals(1, carried.size)
        assertEquals("package", carried[0][InstallOrigin.KEY_EVIDENCE])
    }

    /**
     * And once it has been sent, a relaunch on the same device records nothing further. The
     * relaunch inherits the queue the first run left on disk, so the stamped `install` is still
     * sitting there: what has to be empty is what the new run records, not what it inherited.
     */
    @Test
    fun `a relaunch does not send it again`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - year))
        makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = true)
            .also { it.recordInstallIfNeeded() }

        val relaunched = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport())
        relaunched.setActive(true)

        val queued = relaunched.pendingEvents()
        assertTrue("the relaunch opened a session", queued.any { it.signal == Signal.SESSION_START })
        assertEquals(0, origins(queued.filter { it.signal == Signal.SESSION_START }).size)
        assertEquals("only the inherited install still carries it", 1, origins(queued).size)
    }

    /**
     * A restored handset mints its own install id, and everything under that id in
     * SharedPreferences came off the old device - including the note saying the origin was already
     * sent. The new install has to send its own or it never gets one.
     */
    @Test
    fun `a new install re-earns its origin`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - year))
        platform.prefs.putBoolean("origin.sent.app.appglance.test", true)

        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = true)
        client.recordInstallIfNeeded()

        assertEquals(
            "the old device's note must not silence the new install",
            1,
            origins(client.pendingEvents()).size,
        )
    }

    /** A date nothing could have produced is not evidence. Dropped rather than clamped. */
    @Test
    fun `implausible dates are ignored`() {
        val clock = FakeClock()
        for (date in listOf(clock.now + 30L * 24 * 60 * 60 * 1000, 0L, 1L)) {
            val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = date))
            val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = true)
            client.recordInstallIfNeeded()
            assertEquals("$date should carry no origin", 0, origins(client.pendingEvents()).size)
        }
    }

    /**
     * The floor catches a clock that was never set, not a date that merely predates the platform:
     * a signup date an app passes from before Android existed is still evidence of a long-standing
     * user, and the server applies the same floor.
     */
    @Test
    fun `a signup date older than the platform is still evidence`() {
        val clock = FakeClock()
        val signup = 1_100_000_000_000L // 2004-11-09
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - year))
        val client = makeClient(
            platform,
            clock,
            FakeScheduler(clock),
            RecordingTransport(),
            config = testConfiguration(firstInstalledAt = signup),
            isNewInstall = true,
        )

        client.recordInstallIfNeeded()

        val carried = origins(client.pendingEvents())
        assertEquals(1, carried.size)
        assertEquals("app", carried[0][InstallOrigin.KEY_EVIDENCE])
        assertEquals(Iso8601.format(signup), carried[0][InstallOrigin.KEY_INSTALLED_AT])
    }

    /** Nothing to say is not something to send. */
    @Test
    fun `a device that cannot answer sends no origin`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = null))
        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport(), isNewInstall = true)

        client.recordInstallIfNeeded()
        client.setActive(true)

        assertEquals(0, origins(client.pendingEvents()).size)
        assertNull(platform.prefs.getString("origin.sent.app.appglance.test"))
    }

    /**
     * The origin never reaches a signal whose metadata belongs to somebody else: `user.identify`
     * carries the user's whole property set and the server stores it exactly as sent.
     */
    @Test
    fun `the origin never rides identify`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - year))
        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport())

        client.identify(mapOf("plan" to "pro"))

        val queued = client.pendingEvents()
        assertEquals(listOf(Signal.IDENTIFY), queued.map { it.signal })
        assertEquals(mapOf("plan" to "pro"), queued[0].metadata)
    }

    /** An app's own metadata key wins over one the SDK would attach, always. */
    @Test
    fun `app metadata is never overwritten`() {
        val clock = FakeClock()
        val platform = InMemoryPlatform(device = FakeDeviceInfo(firstInstalledAt = clock.now - year))
        val client = makeClient(platform, clock, FakeScheduler(clock), RecordingTransport())

        client.track(Signal.SESSION_START, mapOf(InstallOrigin.KEY_EVIDENCE to "mine"))

        val metadata = client.pendingEvents().single().metadata
        assertEquals("mine", metadata?.get(InstallOrigin.KEY_EVIDENCE))
        assertEquals(
            "the keys it does not already hold still arrive",
            Iso8601.format(clock.now - year),
            metadata?.get(InstallOrigin.KEY_INSTALLED_AT),
        )
    }
}
