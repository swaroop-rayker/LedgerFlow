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
}
