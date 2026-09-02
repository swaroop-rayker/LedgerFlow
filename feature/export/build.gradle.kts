plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.export"
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
