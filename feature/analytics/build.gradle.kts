plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.analytics"
}

dependencies {
    // The nightly rollup reconciliation (ADR-0006) runs in a Worker, for the
    // same reason ingest's parse step does: it must survive with no Activity
    // alive. `hilt-work` is what lets it be constructed with its use case
    // rather than reaching for a static.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
