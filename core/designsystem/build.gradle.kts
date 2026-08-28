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

    // OccurredAt -- the "a date-only message's time is its capture time" rule
    // (SPEC.md §16). It lives in :core:common rather than in TimeStamp because
    // the "Unsaved" section SORTS by it as well as displaying it, and the two
    // reading different values is what produced an out-of-order list on device.
    implementation(project(":core:common"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    // BUG9's regression test measures a real TextLayoutResult, which needs a
    // composition on a device rather than a JVM stub.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
