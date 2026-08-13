package com.ledgerflow.buildlogic

import com.android.build.api.dsl.LibraryExtension
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
internal enum class LedgerFlowFlavor {
    /** SMS + notification ingest. Sideload / internal testing only. */
    smsFull,

    /** Notification + OCR + manual. No Play-restricted permissions. */
    playSafe,
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
    }

    compileOptions {
        val java = JavaVersion.toVersion(libs.int("jvmTarget"))
        sourceCompatibility = java
        targetCompatibility = java
    }

    flavorDimensions += FLAVOR_DIMENSION
    productFlavors {
        LedgerFlowFlavor.entries.forEach { flavor ->
            create(flavor.name) { dimension = FLAVOR_DIMENSION }
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
    val kotlinExtension = (this as ExtensionAware).extensions.findByName("kotlin")
        ?: error("No `kotlin` extension on $path -- did the Android plugin apply?")
    kotlinExtension.javaClass
        .getMethod("jvmToolchain", Int::class.javaPrimitiveType)
        .invoke(kotlinExtension, target)
}
