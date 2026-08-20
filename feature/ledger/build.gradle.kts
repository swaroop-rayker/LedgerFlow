plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.ledger"
}

dependencies {
    // The Android half of Paging 3 (ADR-0014). `collectAsLazyPagingItems()` and
    // the `LazyPagingItems` load-state surface live here; :core:domain sees only
    // paging-common, which is the JVM half. This is the layer where the split is
    // supposed to be crossed.
    implementation(libs.androidx.paging.compose)

    testImplementation(project(":core:testing"))
}
