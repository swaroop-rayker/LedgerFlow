plugins {
    id("ledgerflow.android.feature")
    // §12: this module's screens have goldens (P5 step 4).
    alias(libs.plugins.roborazzi)
    // The draft payload is JSON (SPEC.md §6.1.2): a draft is partial and
    // invalid by definition, so typed columns would all have to be nullable,
    // and the multi-line editor would turn every 300 ms debounce tick into a
    // multi-row transaction instead of a single-row upsert.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.feature.entry"

    testOptions.unitTests {
        // Robolectric needs the merged resources and the manifest to compose
        // anything; see :core:ui for the reasoning.
        isIncludeAndroidResources = true

        // Forwarded, not hardcoded -- see :core:designsystem for why.
        all { test ->
            listOf(
                "javax.net.ssl.trustStore",
                "javax.net.ssl.trustStorePassword",
            ).forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

dependencies {
    // The shared screenshot harness and its accessibility checks (P5 step 4).
    testImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
