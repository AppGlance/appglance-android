package app.appglance

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.File

/** The Android glue against a real (sandboxed) framework: SharedPreferences, files, Build, PackageManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidPlatformTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the install id is minted once, kept in SharedPreferences, and found by the next launch`() {
        val first = AndroidPlatform(app)
        assertTrue(first.identity.lookup() is IdentityLookup.Absent)
        val minted = requireNotNull(AnonymousIdentity.current(first.identity))
        assertTrue(minted.isNew)

        val prefs = app.getSharedPreferences(AndroidPlatform.PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(
            "written synchronously, in the default (auto-backed-up) prefs file",
            minted.id,
            prefs.getString(AndroidPlatform.KEY_INSTALL_ID, null),
        )

        val relaunch = AndroidPlatform(app)                       // a new process, same device
        val again = requireNotNull(AnonymousIdentity.current(relaunch.identity))
        assertEquals(minted.id, again.id)
        assertFalse("a relaunch is not an install", again.isNew)
    }

    /**
     * Auto Backup and a device-to-device transfer carry the preferences onto a new handset. Both
     * are then in use, so each needs its own install id: the id is honoured only where the device
     * marker stored with it still matches.
     */
    @Test
    fun `an install id that arrived from another device is not reused`() {
        val firstDevice = AndroidPlatform(app) { "device-a" }
        val minted = requireNotNull(AnonymousIdentity.current(firstDevice.identity))
        assertTrue(minted.isNew)

        val secondDevice = AndroidPlatform(app) { "device-b" }   // same prefs, restored elsewhere
        val restored = requireNotNull(AnonymousIdentity.current(secondDevice.identity))
        assertNotEquals("two handsets in use are two installs", minted.id, restored.id)
        assertTrue("and the second one is an install", restored.isNew)

        val relaunch = requireNotNull(AnonymousIdentity.current(AndroidPlatform(app) { "device-b" }.identity))
        assertEquals("which the second device then keeps", restored.id, relaunch.id)
        assertFalse(relaunch.isNew)
    }

    @Test
    fun `an id stored without a marker adopts this device rather than being renumbered`() {
        val prefs = app.getSharedPreferences(AndroidPlatform.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(AndroidPlatform.KEY_INSTALL_ID, "SET-UP-BY-AN-EARLIER-VERSION").commit()

        val identity = requireNotNull(AnonymousIdentity.current(AndroidPlatform(app) { "device-a" }.identity))
        assertEquals("SET-UP-BY-AN-EARLIER-VERSION", identity.id)
        assertFalse("an existing install is not a new one", identity.isNew)
        assertEquals("device-a", prefs.getString(AndroidPlatform.KEY_INSTALL_DEVICE, null))
    }

    @Test
    fun `a device that will not identify itself keeps the id it has`() {
        val minted = requireNotNull(AnonymousIdentity.current(AndroidPlatform(app) { null }.identity))
        val again = requireNotNull(AnonymousIdentity.current(AndroidPlatform(app) { null }.identity))
        assertEquals(minted.id, again.id)
        assertFalse(again.isNew)
    }

    @Test
    fun `the device marker is a hash, never the raw value, and is stable`() {
        val marker = androidDeviceMarker(app)
        if (marker != null) {
            assertEquals(marker, androidDeviceMarker(app))
            assertTrue("a short hex digest", marker.matches(Regex("^[0-9a-f]{16}$")))
        }
    }

    @Test
    fun `the queue lives in noBackupFilesDir and round-trips atomically`() {
        val platform = AndroidPlatform(app)
        val store = platform.queueStore("com.example.app/../weird id")
        assertNull("nothing yet", store.load())
        store.save("[{\"signal\":\"x\"}]")
        assertEquals("[{\"signal\":\"x\"}]", store.load())
        val dir = File(app.noBackupFilesDir, "appglance")
        val files = dir.listFiles()!!.map { it.name }
        assertEquals(listOf("queue-com.example.app..weirdid.json"), files)
        // A second store for the same app id sees the same file (a relaunch loads what was persisted).
        assertEquals("[{\"signal\":\"x\"}]", AndroidPlatform(app).queueStore("com.example.app/../weird id").load())
    }

    /**
     * The client skips a write whose bytes match the one it last landed, and asks the store whether
     * the file is still there before it does. The production store is the one whose answer matters,
     * so it is asked here rather than only the in-memory fake.
     */
    @Test
    fun `the queue store reports whether the file is there`() {
        val store = AndroidPlatform(app).queueStore("com.example.presence")
        assertFalse("nothing written yet", store.exists())
        store.save("[]")
        assertTrue("written", store.exists())
        store.delete()
        assertFalse("withdrawn", store.exists())
    }

    @Test
    fun `prefs store round-trips longs, strings and markers and forgets on remove`() {
        val prefs = AndroidPlatform(app).prefs
        assertNull(prefs.getLong("lastActive.a"))
        prefs.putLong("lastActive.a", 42L)
        prefs.putString("session.a", "s-1")
        assertEquals(42L, AndroidPlatform(app).prefs.getLong("lastActive.a"))
        assertEquals("s-1", prefs.getString("session.a"))
        // A marker whose presence is the whole value: absent reads as false, which is what lets
        // an install that owes nothing say so by writing nothing.
        assertFalse("never written", prefs.getBoolean("install.pending.a"))
        prefs.putBoolean("install.pending.a", true)
        assertTrue(AndroidPlatform(app).prefs.getBoolean("install.pending.a"))
        prefs.remove("install.pending.a")
        assertFalse(prefs.getBoolean("install.pending.a"))
        prefs.remove("session.a")
        assertNull(prefs.getString("session.a"))
    }

    @Test
    fun `device info is non-identifying and the country is a two-letter code or nothing`() {
        val device = AndroidPlatform(app).device
        assertEquals("Android", device.osName)
        assertTrue(device.osVersion.isNotBlank())
        assertTrue(device.appVersion.isNotBlank())
        assertEquals(app.packageName, device.defaultAppId)
        val country = device.country()
        assertTrue(
            "got $country",
            country == null || (country.length == 2 && country == country.uppercase() && country.all { it.isLetter() }),
        )
    }

    @Test
    fun `emulator detection knows the usual fingerprints`() {
        assertTrue(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:userdebug/dev-keys",
                model = "sdk_gphone64_arm64",
                manufacturer = "Google",
                brand = "google",
                device = "emu64a",
                product = "sdk_gphone64_arm64",
                hardware = "ranchu",
            ),
        )
        assertTrue(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "generic/sdk/generic:4.4/KRT16L/eng:userdebug/test-keys",
                model = "sdk",
                manufacturer = "unknown",
                brand = "generic",
                device = "generic",
                product = "sdk",
                hardware = "goldfish",
            ),
        )
        assertTrue(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "x",
                model = "x",
                manufacturer = "Genymotion",
                brand = "x",
                device = "x",
                product = "x",
                hardware = "x",
            ),
        )
        assertTrue(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "x",
                model = "x",
                manufacturer = "x",
                brand = "x",
                device = "x",
                product = "x",
                hardware = "cutf_cvm",
            ),
        )
        assertFalse(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "google/oriole/oriole:14/AP1A.240305.019.A1/11445699:user/release-keys",
                model = "Pixel 6",
                manufacturer = "Google",
                brand = "google",
                device = "oriole",
                product = "oriole",
                hardware = "oriole",
            ),
        )
        assertFalse(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "samsung/a54xnaeea/a54x:14/UP1A.231005.007/A546BXXS7CXF3:user/release-keys",
                model = "SM-A546B",
                manufacturer = "samsung",
                brand = "samsung",
                device = "a54x",
                product = "a54xnaeea",
                hardware = "s5e8835",
            ),
        )
        // Real hardware on a custom ROM often reports a bare "unknown" fingerprint. That alone is
        // not an emulator tell; only another marker alongside it is.
        assertFalse(
            AndroidDeviceInfo.isEmulator(
                fingerprint = "unknown",
                model = "POCO F1",
                manufacturer = "Xiaomi",
                brand = "xiaomi",
                device = "beryllium",
                product = "lineage_beryllium",
                hardware = "qcom",
            ),
        )
        assertTrue(
            "an unknown fingerprint plus emulator hardware is still an emulator",
            AndroidDeviceInfo.isEmulator(
                fingerprint = "unknown",
                model = "x",
                manufacturer = "x",
                brand = "x",
                device = "x",
                product = "x",
                hardware = "ranchu",
            ),
        )
    }

    @Test
    fun `environment precedence is debuggable, then emulator, then the configured channel`() {
        // A real device by every tell, not debuggable: production unless told otherwise.
        ShadowBuild.setFingerprint("google/oriole/oriole:14/AP1A.240305.019.A1/11445699:user/release-keys")
        ShadowBuild.setModel("Pixel 6")
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("google")
        ShadowBuild.setDevice("oriole")
        ShadowBuild.setProduct("oriole")
        ShadowBuild.setHardware("oriole")
        val info = app.applicationInfo
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val device = AndroidDeviceInfo(app)
        assertEquals(AppEnvironment.PRODUCTION, device.environment(null))
        assertEquals(AppEnvironment.BETA, device.environment(AppEnvironment.BETA))

        // Debuggable: always debug, whatever the channel says.
        info.flags = info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertEquals(AppEnvironment.DEBUG, device.environment(null))
        assertEquals(AppEnvironment.DEBUG, device.environment(AppEnvironment.BETA))

        // An emulator run of a release build: emulator, whatever the channel says.
        ShadowBuild.setFingerprint("google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:userdebug/dev-keys")
        ShadowBuild.setHardware("ranchu")
        info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        assertEquals(AppEnvironment.EMULATOR, device.environment(AppEnvironment.PRODUCTION))

        // A debuggable build on that emulator: debuggable is checked first - the porting
        // contract's order. Both are kept out of the numbers by default either way.
        info.flags = info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        assertEquals(AppEnvironment.DEBUG, device.environment(AppEnvironment.PRODUCTION))
    }

    @Test
    fun `AtomicFile leaves no temp file behind after a write`() {
        val platform = AndroidPlatform(app)
        platform.queueStore("a.b").save("[]")
        val dir = File(app.noBackupFilesDir, "appglance")
        assertNotNull(dir.listFiles())
        assertTrue(
            dir.listFiles()!!.none {
                it.name.endsWith(".new") || it.name.endsWith(".bak") ||
                    it.name.endsWith(".tmp")
            },
        )
    }
}
