plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.inbox"
}

dependencies {
    // The Inbox drives the approval use cases and renders extraction targets;
    // both live in :core:domain, which the feature convention already exposes.
    testImplementation(project(":core:testing"))
}
