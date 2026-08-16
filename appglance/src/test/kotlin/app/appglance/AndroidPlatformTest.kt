package app.appglance

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `prefs store round-trips longs and strings and forgets on remove`() {
        val prefs = AndroidPlatform(app).prefs
        assertNull(prefs.getLong("lastActive.a"))
        prefs.putLong("lastActive.a", 42L)
        prefs.putString("session.a", "s-1")
        assertEquals(42L, AndroidPlatform(app).prefs.getLong("lastActive.a"))
        assertEquals("s-1", prefs.getString("session.a"))
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
    fun `environment precedence is emulator, then debuggable, then the configured channel`() {
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

        // Emulator beats even that.
        ShadowBuild.setFingerprint("google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.036/11228894:userdebug/dev-keys")
        ShadowBuild.setHardware("ranchu")
        assertEquals(AppEnvironment.EMULATOR, device.environment(AppEnvironment.PRODUCTION))
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
