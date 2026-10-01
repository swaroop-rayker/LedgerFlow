plugins {
    id("ledgerflow.android.feature")
    // §12: this module's screens have goldens (P5 step 4).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.feature.ledger"

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

    // The Android half of Paging 3 (ADR-0014). `collectAsLazyPagingItems()` and
    // the `LazyPagingItems` load-state surface live here; :core:domain sees only
    // paging-common, which is the JVM half. This is the layer where the split is
    // supposed to be crossed.
    implementation(libs.androidx.paging.compose)

    testImplementation(project(":core:testing"))
}
