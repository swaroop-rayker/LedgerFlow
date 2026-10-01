plugins {
    id("ledgerflow.android.feature")
    // §12: this module's screens have goldens (P5 step 4).
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.feature.inbox"

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

    // The Inbox drives the approval use cases and renders extraction targets;
    // both live in :core:domain, which the feature convention already exposes.

    // ReviewDraftPayload (v8, BUG6). The review screen's in-progress typing is
    // this screen's own format -- the same split SPEC.md §6.1.2 draws for
    // `draft_entry.payload_json` -- so the encoder lives here rather than in
    // :core:data beside ExtractedTransactionJson. Already in the version
    // catalog and used by :app and :core:data; no new dependency.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
