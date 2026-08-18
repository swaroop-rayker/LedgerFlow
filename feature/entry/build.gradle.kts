plugins {
    id("ledgerflow.android.feature")
    // The draft payload is JSON (SPEC.md §6.1.2): a draft is partial and
    // invalid by definition, so typed columns would all have to be nullable,
    // and the multi-line editor would turn every 300 ms debounce tick into a
    // multi-row transaction instead of a single-row upsert.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.feature.entry"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}
