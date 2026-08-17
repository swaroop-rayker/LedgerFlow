plugins {
    // AGP 9+ has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is an error: https://kotl.in/gradle/agp-built-in-kotlin
    id("ledgerflow.android.application")
    id("ledgerflow.android.compose")
    id("ledgerflow.android.hilt")
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

    implementation(libs.androidx.activity.compose)
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
}
