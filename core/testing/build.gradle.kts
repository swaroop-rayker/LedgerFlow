plugins {
    id("ledgerflow.android.library")
    // The shared screenshot harness (P5 step 4) takes `@Composable` content.
    id("ledgerflow.android.compose")
}

android {
    namespace = "com.ledgerflow.core.testing"

    testOptions.unitTests {
        // LfScreenshotOptionsTest renders nothing, but Robolectric-backed
        // Roborazzi classes are on its classpath; see :core:ui for the block.
        isIncludeAndroidResources = true
    }
}

dependencies {
    // Fakes implement the domain ports, so they need the ports. This module is
    // consumed as testImplementation only -- nothing in main source depends on
    // it, and the dependency-rule check treats it as a leaf.
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)

    // The screenshot harness: one comparison setting and one capture helper
    // for every module with goldens (§12), plus the structural accessibility
    // checks every capture runs (§9.6). `api`, because a module that uses the
    // helper renders with Robolectric and compares with Roborazzi anyway, and
    // this way it declares the harness once instead of six coordinates.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.robolectric)
    api(libs.androidx.test.ext.junit)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    // AccessibilityChecksTest composes under Robolectric.
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
