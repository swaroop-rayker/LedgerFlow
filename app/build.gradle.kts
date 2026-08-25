plugins {
    // AGP 9+ has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is an error: https://kotl.in/gradle/agp-built-in-kotlin
    id("ledgerflow.android.application")
    id("ledgerflow.android.compose")
    id("ledgerflow.android.hilt")
    // Type-safe Navigation Compose routes are @Serializable (SPEC.md §9.3).
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow"

    defaultConfig {
        applicationId = "com.ledgerflow"
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:domain"))
    // :app is the only module allowed to see :core:data -- it is where the
    // domain ports get bound to their implementations (CLAUDE.md §3).
    implementation(project(":core:data"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:entry"))
    implementation(project(":feature:ledger"))
    implementation(project(":feature:analytics"))
    implementation(project(":feature:categories"))
    implementation(project(":feature:export"))
    // No UI of its own, and that is the point at S11: what :app takes from it
    // is the two capture components' manifest entries and the Hilt bindings
    // that put both TransactionIngestSource implementations in the graph
    // (SPEC.md §3.1). The Inbox that consumes them is P2.
    implementation(project(":feature:ingest"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // :core:testing carries the domain-port fakes. Test scope only -- nothing in
    // main source may see it.
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    // BUG6's regression test assembles the real graph -- SQLCipher, the
    // Keystore-wrapped DEK, the entry form -- tears all of it down, and rebuilds
    // it from disk. :app is the module that legitimately wires those together,
    // so it is where a test about the whole graph surviving a process belongs.
    // Test scope only; none of this reaches main source.
    androidTestImplementation(project(":core:common"))
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
