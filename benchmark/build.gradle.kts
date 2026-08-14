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
    }

    // Benchmarks measure :app. Numbers from an emulator are noise, so this runs
    // on the self-hosted runner with a real device attached (SPEC.md §15.4).
    targetProjectPath = ":app"

    flavorDimensions += "ingest"
    productFlavors {
        create("smsFull") { dimension = "ingest" }
        create("playSafe") { dimension = "ingest" }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
