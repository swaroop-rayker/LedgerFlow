package com.ledgerflow.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.util.Properties
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.int(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

/**
 * Product flavours (SPEC.md §3.1).
 *
 * Applied to EVERY Android module, not just `:app`. AGP requires a consumer's
 * flavour dimensions to be satisfiable by its dependencies, so declaring them
 * centrally is what lets `:feature:ingest` carry `smsFull`/`playSafe` source
 * sets without every other module needing a `missingDimensionStrategy`.
 *
 * The split lands at Phase 0, not P5 -- retrofitting it into a coupled codebase
 * is exactly the pain being avoided.
 */
internal enum class LedgerFlowFlavor(
    /**
     * Only meaningful on `:app`; library flavours have no application id.
     * Kept on the enum anyway so the two flavour lists cannot drift apart --
     * a flavour that exists for libraries but not for the application is a
     * configuration failure that surfaces as an unresolvable dependency.
     */
    val applicationIdSuffix: String?,
) {
    /** SMS + notification ingest. Sideload / internal testing only. */
    smsFull(applicationIdSuffix = null),

    /** Notification + OCR + manual. No Play-restricted permissions. */
    playSafe(applicationIdSuffix = ".playsafe"),
}

internal const val FLAVOR_DIMENSION = "ingest"

/**
 * Shared configuration for every Android library module.
 *
 * Anything set here is set once. A module build file that re-states compileSdk
 * or minSdk is drift waiting to happen.
 */
internal fun Project.configureAndroidLibrary(extension: LibraryExtension) = with(extension) {
    compileSdk = libs.int("compileSdk")

    defaultConfig {
        minSdk = libs.int("minSdk")
        // Set centrally so every module can carry instrumented tests. The
        // migration chain and the backup round-trip -- the two blocking gates
        // in SPEC.md §15.4 -- are instrumented, so this is not optional.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        val java = JavaVersion.toVersion(libs.int("jvmTarget"))
        sourceCompatibility = java
        targetCompatibility = java
        // SPEC.md §3. Not strictly required for java.time at minSdk 26, but it
        // also backports the newer collection/stream APIs, and turning it on
        // later is a bigger change than having it on from the start.
        isCoreLibraryDesugaringEnabled = true
    }

    flavorDimensions += FLAVOR_DIMENSION
    productFlavors {
        LedgerFlowFlavor.entries.forEach { flavor ->
            create(flavor.name) { dimension = FLAVOR_DIMENSION }
        }
    }

    configureJvmToolchain()

    // Explicit API mode on :core:* only (CLAUDE.md §5). Public API in a core
    // module is consumed by other modules, so implicit visibility and inferred
    // public return types are how a module's surface grows by accident.
    // Features are leaves -- the ceremony would not buy anything there.
    if (path.startsWith(":core")) {
        configureExplicitApi()
    }
}

/**
 * Shared configuration for `:app`.
 *
 * The mirror of [configureAndroidLibrary], and deliberately a separate function
 * rather than a `CommonExtension` generalisation of it: on AGP 9 `CommonExtension`
 * no longer exposes `flavorDimensions`, `productFlavors` or
 * `isCoreLibraryDesugaringEnabled`, so anything unifying the two would have to
 * give up exactly the settings that matter here. The concrete extension types
 * are bound instead, and the shared *values* live in one place (the version
 * catalog and [LedgerFlowFlavor]) rather than the shared code doing so.
 */
internal fun Project.configureAndroidApplication(extension: ApplicationExtension) =
    with(extension) {
        compileSdk = libs.int("compileSdk")

        val version = rootProject.file("version.properties").let { file ->
            Properties().apply { file.inputStream().use(::load) }
        }

        defaultConfig {
            minSdk = libs.int("minSdk")
            targetSdk = libs.int("targetSdk")
            // BUG3: a single monotonic counter, committed, guarded by
            // scripts/guard-version.sh against the last release tag.
            versionCode = version.getProperty("versionCode").toInt()
            versionName = version.getProperty("versionName")
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            val java = JavaVersion.toVersion(libs.int("jvmTarget"))
            sourceCompatibility = java
            targetCompatibility = java
            // Must match the library convention: AAR metadata records that every
            // :core/:feature module was built with desugaring, and a consumer
            // with it off fails the metadata check.
            isCoreLibraryDesugaringEnabled = true
        }

        buildTypes {
            getByName("debug") {
                // Debug and release coexist as separate apps with separate data.
                // This is BUG1's countermeasure -- do not remove it.
                applicationIdSuffix = ".debug"
            }
            getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
            }
        }

        flavorDimensions += FLAVOR_DIMENSION
        productFlavors {
            LedgerFlowFlavor.entries.forEach { flavor ->
                create(flavor.name) {
                    dimension = FLAVOR_DIMENSION
                    flavor.applicationIdSuffix?.let { applicationIdSuffix = it }
                }
            }
        }

        // ── ABI splits, so the artefact SPEC.md §11 names can exist ──────────
        //
        // §11's budget is "APK size (arm64 split) <= 15 MB", and until P4 this
        // build could not produce an arm64 split at all -- there was no splits
        // block, so `assembleSmsFullRelease` emitted a universal APK. CI's size
        // step then measured a *universal debug* APK (four ABIs, no R8) against
        // a budget written for an arm64 *release* split, and failed at 22.77 MB
        // on the first run this repository ever had. Measured properly, the
        // same commit's arm64 release split is 4.82 MB. §16 Q18.
        //
        // **Property-gated, not always on.** `splits` applies to every variant,
        // so enabling it unconditionally would build several APKs on every
        // `installSmsFullDebug` and tax the dev loop for a number only the size
        // gate reads. CI's `assemble` job passes -Pledgerflow.abiSplits.
        //
        // `isUniversalApk = true` keeps the all-ABI artefact that is sideloaded
        // today, so turning the flag on adds an output rather than replacing
        // one. Which of the two `smsFull` actually distributes is a
        // distribution decision and is deliberately not made here -- note that
        // a bundled OCR model ships one native library *per ABI*, so the gap
        // between the two widens sharply at P4.
        if (providers.gradleProperty("ledgerflow.abiSplits").isPresent) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86_64")
                    isUniversalApk = true
                }
            }
        }

        configureJvmToolchain()
    }

/**
 * Sets the Kotlin JVM toolchain.
 *
 * Reached reflectively on purpose. AGP 9 provides Kotlin support *itself*
 * (applying `org.jetbrains.kotlin.android` alongside it is an error), so the
 * `kotlin` extension object at runtime is not guaranteed to be the
 * `KotlinAndroidProjectExtension` type the Kotlin Gradle Plugin publishes at
 * compile time. Binding to that type would compile here and fail with
 * NoClassDefFoundError in the consuming build. `jvmToolchain(Int)` is stable
 * across both.
 */
private fun Project.configureJvmToolchain() {
    val target = libs.int("jvmTarget")
    val kotlinExtension = kotlinExtension()
    kotlinExtension.javaClass
        .getMethod("jvmToolchain", Int::class.javaPrimitiveType)
        .invoke(kotlinExtension, target)
}

/** Strict explicit API mode. Reflective for the same reason as the toolchain. */
private fun Project.configureExplicitApi() {
    val kotlinExtension = kotlinExtension()
    kotlinExtension.javaClass
        .getMethod("explicitApi")
        .invoke(kotlinExtension)
}

private fun Project.kotlinExtension(): Any =
    (this as ExtensionAware).extensions.findByName("kotlin")
        ?: error("No `kotlin` extension on $path -- did the Android plugin apply?")

/**
 * Detekt, configured from the single root ruleset.
 *
 * Typed access is safe here, unlike the Kotlin extension above: DetektExtension
 * comes from the detekt plugin this build declares, so the compile-time and
 * runtime types are the same artifact.
 */
internal fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure(DetektExtension::class.java) {
        buildUponDefaultConfig = true
        parallel = true
        val ruleset = rootProject.file("config/detekt/detekt.yml")
        if (ruleset.exists()) {
            config.setFrom(ruleset)
        }
    }
}
