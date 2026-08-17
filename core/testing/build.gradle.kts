plugins {
    id("ledgerflow.android.library")
}

android {
    namespace = "com.ledgerflow.core.testing"
}

dependencies {
    // Fakes implement the domain ports, so they need the ports. This module is
    // consumed as testImplementation only -- nothing in main source depends on
    // it, and the dependency-rule check treats it as a leaf.
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
}
