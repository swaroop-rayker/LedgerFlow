plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.compose")
}

android {
    namespace = "com.ledgerflow.core.designsystem"
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
