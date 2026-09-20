plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.settings"
}

dependencies {
    // The shared fakes -- a validator and a backup repository -- for the
    // "Back up now" ViewModel's tests.
    testImplementation(project(":core:testing"))

    // The nightly backup (ADR-0027) runs in a Worker, with no Activity alive
    // and no phrase anywhere; `hilt-work` is what lets it be constructed with
    // the repository rather than reaching for a static.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
