plugins {
    id("ledgerflow.android.feature")
    // §12's screenshot gate, for the diagnostics screen (P5): the first feature
    // screen with goldens. `verifyRoborazziSmsFullDebug` in `preMergeCheck`
    // and CI's `screenshot` job pick the module up by task name.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.feature.settings"

    testOptions.unitTests {
        // Robolectric needs the merged resources and the manifest to compose
        // anything; see the identical block in :core:ui.
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
    // The shared fakes -- a validator and a backup repository -- for the
    // "Back up now" ViewModel's tests.
    testImplementation(project(":core:testing"))

    // The nightly schedule's test (BUG31) runs the worker under a real,
    // in-memory WorkManager on Robolectric: whether a pass keeps its successor
    // aimed at 03:00 is a property of WorkManager's own bookkeeping, which a
    // fake would only restate.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // The diagnostics screen's goldens and its BUG17 title check.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // The nightly backup (ADR-0027) runs in a Worker, with no Activity alive
    // and no phrase anywhere; `hilt-work` is what lets it be constructed with
    // the repository rather than reaching for a static.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
