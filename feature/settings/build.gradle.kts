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

    // The nightly schedule's test (BUG31) runs the worker under a real,
    // in-memory WorkManager on Robolectric: whether a pass keeps its successor
    // aimed at 03:00 is a property of WorkManager's own bookkeeping, which a
    // fake would only restate.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // The nightly backup (ADR-0027) runs in a Worker, with no Activity alive
    // and no phrase anywhere; `hilt-work` is what lets it be constructed with
    // the repository rather than reaching for a static.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
