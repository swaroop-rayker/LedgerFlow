plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.budget"
}

dependencies {
    // §5.7's threshold alerts evaluate in a Worker, for the same reason ingest's
    // parse step does: they must run with no Activity alive.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
