package app.appglance

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** The production [Platform]: SharedPreferences, the app's private files, `Build`, `PackageManager`. */
internal class AndroidPlatform(
    context: Context,
    /** Which device this is, for [SharedPreferencesIdentityStore]; a seam for tests. */
    private val deviceMarker: (Context) -> String? = ::androidDeviceMarker,
) : Platform {
    private val context: Context = context.applicationContext ?: context

    private val sharedPreferences: SharedPreferences by lazy {
        this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override val identity: IdentityStore = SharedPreferencesIdentityStore()
    override val prefs: KeyValueStore = SharedPreferencesStore()
    override val device: DeviceInfo = AndroidDeviceInfo(this.context)

    override fun queueStore(appId: String): QueueStore {
        // `noBackupFilesDir`: survives reboots and updates (unlike the cache dir, which the system
        // may clear), but is NOT restored onto another device - a restored queue would replay
        // stale events there. The install id, by contrast, lives in SharedPreferences precisely
        // so that Auto Backup DOES carry it across a reinstall.
        val safe = appId.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return FileQueueStore(File(File(context.noBackupFilesDir, "appglance"), "queue-$safe.json"))
    }

    /**
     * The install id lives in SharedPreferences - inside Android's Auto Backup set, so a
     * delete-and-reinstall on the same account usually restores it and the person is counted once.
     * A fresh id after a reinstall without a backup counts as a new user, which is acceptable.
     *
     * Auto Backup and a device-to-device transfer also carry those preferences onto a NEW handset,
     * where the same id would then be live on two devices at once and report them as one install.
     * The id is therefore stored with a marker for the device that minted it (see
     * [androidDeviceMarker]) and is honoured only where that marker still matches: elsewhere it
     * reads as absent, and a fresh id is minted for the second device. An id stored without a
     * marker adopts the current one instead of being renumbered, so an install set up by an
     * earlier version of the SDK stays the same install.
     */
    private inner class SharedPreferencesIdentityStore : IdentityStore {
        override fun lookup(): IdentityLookup {
            // Direct Boot: credential-encrypted storage (where SharedPreferences live) is
            // unreadable before the user's first unlock. Any answer invented now would be a
            // second user.
            if (!userUnlocked()) return IdentityLookup.Unavailable
            return try {
                val id = sharedPreferences.getString(KEY_INSTALL_ID, null)
                if (id.isNullOrEmpty()) return IdentityLookup.Absent
                val marker = deviceMarker(context)
                val minted = sharedPreferences.getString(KEY_INSTALL_DEVICE, null)
                when {
                    // Nothing to compare with: the platform will not say which device this is, so
                    // the id stands rather than every launch minting a new one.
                    marker == null -> IdentityLookup.Found(id)

                    minted == null -> {
                        sharedPreferences.edit().putString(KEY_INSTALL_DEVICE, marker).apply()
                        IdentityLookup.Found(id)
                    }

                    minted == marker -> IdentityLookup.Found(id)

                    // These preferences were minted on another device and travelled here in a
                    // backup or a transfer. Both handsets are in use; each needs its own id.
                    else -> IdentityLookup.Absent
                }
            } catch (_: IllegalStateException) {
                IdentityLookup.Unavailable
            }
        }

        override fun save(id: String): Boolean {
            // `commit`, not `apply`: this is written once per install and must be on disk before
            // the `install` event that depends on it can possibly be sent. Its answer is the one
            // thing that can tell a caller the id will not be there next launch - a full data
            // partition is the case - because the value is served back out of the in-memory map
            // whether or not it ever reached the file.
            val editor = sharedPreferences.edit().putString(KEY_INSTALL_ID, id)
            val marker = deviceMarker(context)
            if (marker == null) editor.remove(KEY_INSTALL_DEVICE) else editor.putString(KEY_INSTALL_DEVICE, marker)
            return editor.commit()
        }

        private fun userUnlocked(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
            val manager = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
            return manager.isUserUnlocked
        }
    }

    private inner class SharedPreferencesStore : KeyValueStore {
        override fun getLong(key: String): Long? =
            if (sharedPreferences.contains(key)) sharedPreferences.getLong(key, 0L) else null

        override fun putLong(key: String, value: Long) {
            sharedPreferences.edit().putLong(key, value).apply()
        }

        override fun getString(key: String): String? = sharedPreferences.getString(key, null)

        override fun putString(key: String, value: String) {
            sharedPreferences.edit().putString(key, value).apply()
        }

        override fun getBoolean(key: String): Boolean = sharedPreferences.getBoolean(key, false)

        override fun putBoolean(key: String, value: Boolean) {
            sharedPreferences.edit().putBoolean(key, value).apply()
        }

        override fun remove(key: String) {
            sharedPreferences.edit().remove(key).apply()
        }
    }

    private class FileQueueStore(private val file: File) : QueueStore {
        private val atomic = AtomicFile(file)

        override fun load(): String? {
            // No short circuit on the base file's absence. AtomicFile moves the previous copy to a
            // `.bak` name for the length of a write and restores it on the next read, so a process
            // killed between startWrite and finishWrite leaves the only good queue under that name
            // and nothing under this one. Answering from `file.exists()` alone reported an empty
            // queue in exactly the case the backup exists to survive, and every event in it was
            // dropped. readFully performs that recovery, and still raises for a queue that really
            // is not there.
            return try {
                String(atomic.readFully(), Charsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        }

        override fun exists(): Boolean = file.exists()

        override fun save(json: String): Boolean {
            file.parentFile?.mkdirs()
            val out = try {
                atomic.startWrite()
            } catch (_: IOException) {
                return false
            }
            return try {
                out.write(json.toByteArray(Charsets.UTF_8))
                // Flushed and synced here rather than left to finishWrite, which logs a failure to
                // close and swallows it. The boolean this returns is what the client records as the
                // bytes that landed, and it skips an identical write against that record, so a
                // `true` for a write the device never completed is not one lost queue but every
                // repair of it declined until the bytes change.
                out.flush()
                out.fd.sync()
                atomic.finishWrite(out)
                true
            } catch (_: IOException) {
                atomic.failWrite(out)
                false
            }
        }

        override fun delete() {
            atomic.delete()
        }
    }

    companion object {
        const val PREFS_NAME = "app.appglance"
        const val KEY_INSTALL_ID = "installId"
        const val KEY_INSTALL_DEVICE = "installDevice"
    }
}

/**
 * Which device an install id was minted on: `ANDROID_ID`, hashed, kept in the app's own
 * preferences and never sent anywhere. It is scoped to the app's signing key, the user and the
 * device, so it survives a reinstall - which is what keeps a returning user one user - and differs
 * on a new handset, which is what stops one id being live on two of them. Null when the platform
 * will not answer; the caller then trusts the id it has.
 */
internal fun androidDeviceMarker(context: Context): String? = try {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (id.isNullOrBlank()) null else shortHash(id)
} catch (_: Exception) {
    null
}

/** Enough of a SHA-256 to tell two devices apart, so the raw value is never stored or backed up. */
private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Minimal, non-identifying device context. */
internal class AndroidDeviceInfo(private val context: Context) : DeviceInfo {
    override val osName: String = "Android"

    /** `Build.VERSION.RELEASE` (`14`, `16`); the dashboard groups by `os_name` and the major version. */
    override val osVersion: String = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown"

    override val appVersion: String by lazy {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName?.takeIf { it.isNotBlank() }
                ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    override val defaultAppId: String = context.packageName

    /**
     * The device's region setting as an ISO country code (e.g. "US") - not GPS, no permission, no
     * IP. The system locale rather than the app's, so an in-app language switch does not move a
     * user on the map. Only real two-letter codes are sent (numeric regions mean nothing on a map).
     */
    override fun country(): String? {
        val locale: Locale = try {
            val configuration = Resources.getSystem().configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val locales = configuration.locales
                if (locales.isEmpty) Locale.getDefault() else locales[0]
            } else {
                @Suppress("DEPRECATION")
                configuration.locale ?: Locale.getDefault()
            }
        } catch (_: Exception) {
            Locale.getDefault()
        }
        val code = locale.country
        return if (code.length == 2 && code.all { it.isLetter() }) code.uppercase(Locale.ROOT) else null
    }

    /**
     * Precedence: debuggable build, then emulator, then the configured channel (default
     * production) - the order the platform porting contract lists. A debuggable build or an
     * emulator run is always reported as such, so a developer's own runs never reach the numbers
     * by default.
     */
    override fun environment(override: AppEnvironment?): AppEnvironment = when {
        isDebuggable() -> AppEnvironment.DEBUG
        isEmulator() -> AppEnvironment.EMULATOR
        else -> override ?: AppEnvironment.PRODUCTION
    }

    private fun isDebuggable(): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        /**
         * The usual fingerprint and hardware tells for the Android Emulator (goldfish/ranchu, the
         * `sdk_gphone*` products, Cuttlefish) and Genymotion. A fingerprint of just "unknown" is
         * NOT a tell on its own: some OEM and custom-ROM builds ship it on real hardware, and the
         * hardware and product checks below still catch any emulator that reports it. Best-effort:
         * a false negative means an emulator run is tagged debug (still gated by default) or, for
         * a device lying about itself, production.
         */
        fun isEmulator(
            fingerprint: String = Build.FINGERPRINT ?: "",
            model: String = Build.MODEL ?: "",
            manufacturer: String = Build.MANUFACTURER ?: "",
            brand: String = Build.BRAND ?: "",
            device: String = Build.DEVICE ?: "",
            product: String = Build.PRODUCT ?: "",
            hardware: String = Build.HARDWARE ?: "",
        ): Boolean = fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            fingerprint.contains("sdk_gphone") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            model.contains("sdk_gphone") ||
            manufacturer.contains("Genymotion") ||
            hardware == "goldfish" ||
            hardware == "ranchu" ||
            hardware.contains("cutf") ||        // Cuttlefish
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product == "google_sdk" ||
            product == "sdk" ||
            product.contains("sdk_gphone") ||
            product.contains("emulator") ||
            product.contains("simulator") ||
            product.contains("vbox86p")
    }
}
