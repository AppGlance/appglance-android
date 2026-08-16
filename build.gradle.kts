// Plugins are declared here (versions from gradle/libs.versions.toml) and applied by the module.
// Kotlin is built into AGP 9; there is no separate Kotlin Android plugin.
plugins {
    alias(libs.plugins.android.library) apply false
}
