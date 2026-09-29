plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.ledgerflow.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Macrobenchmark drives a separate process via shell commands and needs
        // a higher floor than the app itself (SPEC.md §11).
        minSdk = 28
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // One module, two jobs, chosen by which task was asked for:
        // `generateBaselineProfile` runs only BaselineProfileRule, everything
        // else runs only the measurements. Without the split a profile run
        // would also sit through every timing loop, on a laptop that overheats.
        val profileRun = gradle.startParameter.taskNames.any { it.contains("generateBaselineProfile") }
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] =
            if (profileRun) "BaselineProfile" else "Macrobenchmark"
    }

    buildTypes {
        // Matches :app's `benchmark` build type, which is release code signed
        // with the debug key. This module itself must be debuggable to
        // instrument; the app it measures is not.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // Benchmarks measure :app. Numbers from an emulator are noise, so this runs
    // on the self-hosted runner with a real device attached (SPEC.md §15.4).
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    flavorDimensions += "ingest"
    productFlavors {
        create("smsFull") { dimension = "ingest" }
        create("playSafe") { dimension = "ingest" }
    }
}

// smsFull + benchmark only (owner, 2026-09-29; see :app's build type).
androidComponents {
    beforeVariants { variant ->
        variant.enable = variant.buildType == "benchmark" && variant.flavorName == "smsFull"
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}

/**
 * Runs the profile generator on the attached device and copies its output to
 * where AGP compiles it into the APK (`app/src/main/baseline-prof.txt`).
 * Regenerate when startup or the nav graph changes materially (CLAUDE.md §8).
 */
tasks.register<Copy>("generateBaselineProfile") {
    group = "benchmark"
    description = "Generates the shipped baseline profile on the attached device."
    dependsOn("connectedSmsFullBenchmarkAndroidTest")
    from(layout.buildDirectory.dir("outputs/connected_android_test_additional_output")) {
        include("**/*-baseline-prof.txt")
    }
    eachFile { path = "baseline-prof.txt" }
    includeEmptyDirs = false
    into(rootProject.layout.projectDirectory.dir("app/src/main"))
    // The seed activity is recorded too, and exists only in the benchmark
    // build type; its rules name classes no shipped APK contains.
    doLast {
        val profile = rootProject.file("app/src/main/baseline-prof.txt")
        val kept = profile.readLines().filterNot { "Lcom/ledgerflow/bench/" in it }
        profile.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
    }
}
