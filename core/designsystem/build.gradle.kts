plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.compose")
}

android {
    namespace = "com.ledgerflow.core.designsystem"
}

dependencies {
    // `api`: LfIcons exposes ImageVector values built from this artifact, so
    // consumers need it to name them. Core only -- material-icons-extended is a
    // very large artifact and §11's budget has ML Kit still to fit into it.
    api(libs.androidx.compose.material.icons.core)

    // The category palette lives in :core:model (it is data, not a Compose
    // type); the WCAG assertions over it live here, next to the contrast maths.
    implementation(project(":core:model"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
