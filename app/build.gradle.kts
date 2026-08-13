import java.util.Properties

plugins {
    // AGP 9+ has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is an error: https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application)
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.ledgerflow"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ledgerflow"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    buildTypes {
        debug {
            // Debug and release coexist as separate apps with separate data.
            // This is BUG1's countermeasure -- do not remove it.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }

    flavorDimensions += "ingest"
    productFlavors {
        create("smsFull") {
            dimension = "ingest"
            // SMS + notification ingest. Sideload / internal testing only:
            // RECEIVE_SMS is Play-restricted (SPEC.md §3.1).
        }
        create("playSafe") {
            dimension = "ingest"
            applicationIdSuffix = ".playsafe"
            // Notification ingest + OCR + manual. No restricted permissions.
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}
