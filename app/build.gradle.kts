import java.util.Properties

plugins {
    // AGP 9+ has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is an error: https://kotl.in/gradle/agp-built-in-kotlin
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
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

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get().toInt())
        // Must match the library convention plugin: AAR metadata records that
        // every :core/:feature module was built with desugaring, and a consumer
        // that has it off fails the metadata check.
        //
        // TODO(step7): this duplicates AndroidConventions.configureAndroidLibrary.
        //  :app deserves its own `ledgerflow.android.application` convention
        //  plugin rather than a hand-maintained copy that can drift.
        isCoreLibraryDesugaringEnabled = true
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

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":feature:onboarding"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
}
