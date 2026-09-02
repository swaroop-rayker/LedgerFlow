plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.dashboard"
}

dependencies {
    // DashboardBannerContentTest renders the real banner and reads what it says.
    // §5.2's two unhealthy states differ only in their sentence, and a state
    // enum can be correct while the screen renders the wrong words for it --
    // which is the half DashboardBannerTest (JVM, `showsCaptureBanner`) cannot
    // see, because it never composes anything.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
