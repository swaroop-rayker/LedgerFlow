plugins {
    id("ledgerflow.android.feature")
    // CLAUDE.md §12: every screen here has goldens.
    alias(libs.plugins.roborazzi)
}

// V2-7's tools (V2-PLAN §4): savings goals first; recurring, debts and loans,
// and utilities join as their PRs land. One module, because the tools share
// pages and a split builds on the debt model, and features never depend on
// features (CLAUDE.md §3).
android {
    namespace = "com.ledgerflow.feature.tools"

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
    // The shared screenshot harness and its accessibility checks.
    testImplementation(project(":core:testing"))
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
