import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    // Publishes to Maven Central through the Central Portal, with the sources and javadoc jars
    // and the GPG signatures Central requires.
    alias(libs.plugins.maven.publish)
}

// The one place the SDK version lives: the POM, the AAR and `BuildConfig.VERSION` (the
// User-Agent the ingest sees) all read it from here.
group = "app.appglance"
version = "1.2.4"

android {
    namespace = "app.appglance"
    // What the SDK compiles against. Not a floor for apps: the AAR declares no minCompileSdk, and
    // its only dependency (lifecycle-process 2.8) asks for 34.
    compileSdk = 36

    defaultConfig {
        // Android 5.0: `noBackupFilesDir` (the queue's home) is API 21, and everything newer the
        // SDK touches is behind a version check.
        minSdk = 21
        buildConfigField("String", "VERSION", "\"$version\"")
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Explicit-API mode for the shipped code: every non-internal declaration must say `public` and
// carry a return type, so nothing leaks out by accident. Tests are not API.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
}

mavenPublishing {
    // Central rejects a bundle without a javadoc jar. For Kotlin the plugin builds it from Dokka
    // when present and ships an empty-but-valid jar otherwise.
    configure(AndroidSingleVariantLibrary("release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral()
    coordinates(group.toString(), "appglance", version.toString())

    // Sign only when a key is configured, so `publishToMavenLocal` stays a one-liner on a machine
    // without one. Central itself requires the signatures.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    ) {
        signAllPublications()
    }

    pom {
        name.set("AppGlance")
        description.set("Privacy-first, live analytics for apps - the Android SDK.")
        url.set("https://appglance.app")
        licenses {
            license {
                name.set("MIT")
                url.set("https://github.com/AppGlance/appglance-android/blob/main/LICENSE")
            }
        }
        developers {
            developer {
                id.set("ludyem")
                name.set("Ludyem")
                url.set("https://ludyem.dev")
            }
        }
        scm {
            url.set("https://github.com/AppGlance/appglance-android")
            connection.set("scm:git:https://github.com/AppGlance/appglance-android.git")
            // Central validates that developerConnection is present as well as connection.
            developerConnection.set("scm:git:ssh://git@github.com/AppGlance/appglance-android.git")
        }
    }
}
