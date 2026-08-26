plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.onboarding"
}

dependencies {
    // Onboarding generates the recovery phrase and wraps the DEK, so it needs
    // the crypto core directly rather than through a repository.
    implementation(project(":core:crypto"))

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // OnboardingCtaReachabilityTest measures a real composition: whether a
    // control is inside the visible viewport at font scale 2.0 is a layout
    // fact, and a JVM stub would assert nothing about it. The Compose test
    // rule and the debug test manifest already come from the compose
    // convention plugin.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
