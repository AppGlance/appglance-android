package app.appglance

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Build
import android.os.UserManager
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Locale

/** The production [Platform]: SharedPreferences, the app's private files, `Build`, `PackageManager`. */
internal class AndroidPlatform(context: Context) : Platform {
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
     */
    private inner class SharedPreferencesIdentityStore : IdentityStore {
        override fun lookup(): IdentityLookup {
            // Direct Boot: credential-encrypted storage (where SharedPreferences live) is
            // unreadable before the user's first unlock. Any answer invented now would be a
            // second user.
            if (!userUnlocked()) return IdentityLookup.Unavailable
            return try {
                val id = sharedPreferences.getString(KEY_INSTALL_ID, null)
                if (id.isNullOrEmpty()) IdentityLookup.Absent else IdentityLookup.Found(id)
            } catch (_: IllegalStateException) {
                IdentityLookup.Unavailable
            }
        }

        override fun save(id: String) {
            // `commit`, not `apply`: this is written once per install and must be on disk before
            // the `install` event that depends on it can possibly be sent.
            sharedPreferences.edit().putString(KEY_INSTALL_ID, id).commit()
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

        override fun remove(key: String) {
            sharedPreferences.edit().remove(key).apply()
        }
    }

    private class FileQueueStore(private val file: File) : QueueStore {
        private val atomic = AtomicFile(file)

        override fun load(): String? {
            if (!file.exists()) return null
            return try {
                String(atomic.readFully(), Charsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        }

        override fun save(json: String) {
            file.parentFile?.mkdirs()
            val out = try {
                atomic.startWrite()
            } catch (_: IOException) {
                return
            }
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (_: IOException) {
                atomic.failWrite(out)
            }
        }
    }

    companion object {
        const val PREFS_NAME = "app.appglance"
        const val KEY_INSTALL_ID = "installId"
    }
}

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
     * Precedence: emulator, then debuggable build, then the configured channel (default
     * production). An emulator run or a debuggable build is always reported as such, so a
     * developer's own runs never reach the numbers by default.
     */
    override fun environment(override: AppEnvironment?): AppEnvironment = when {
        isEmulator() -> AppEnvironment.EMULATOR
        isDebuggable() -> AppEnvironment.DEBUG
        else -> override ?: AppEnvironment.PRODUCTION
    }

    private fun isDebuggable(): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        /**
         * The usual fingerprint and hardware tells for the Android Emulator (goldfish/ranchu, the
         * `sdk_gphone*` products, Cuttlefish) and Genymotion. Best-effort: a false negative means
         * an emulator run is tagged debug (still gated by default) or, for a device lying about
         * itself, production.
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
            fingerprint.startsWith("unknown") ||
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
