plugins {
    id("ledgerflow.android.feature")
    // §12: this module's screens have goldens (P5 step 4).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.feature.dashboard"

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

    // DashboardBackupReminderTest drives the real ViewModel over the shared
    // FakeBackupRepository, as the other features' ViewModel tests do.
    testImplementation(project(":core:testing"))

    // DashboardBannerContentTest renders the real banner and reads what it says.
    // §5.2's two unhealthy states differ only in their sentence, and a state
    // enum can be correct while the screen renders the wrong words for it --
    // which is the half DashboardBannerTest (JVM, `showsCaptureBanner`) cannot
    // see, because it never composes anything.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
