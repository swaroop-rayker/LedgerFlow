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

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
