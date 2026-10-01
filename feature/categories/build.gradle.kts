plugins {
    id("ledgerflow.android.feature")
    // §12: this module's screens have goldens (P5 step 4).
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.feature.categories"

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

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

dependencies {
    // BUG17's header shape is still used on this screen. The guard is expected
    // to be green -- see the test's KDoc for why a green guard over an unbroken
    // screen is the point: the title's width is a function of the button beside
    // it, so this screen is one label change away from the same defect.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
