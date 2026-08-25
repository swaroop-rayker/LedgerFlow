plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.ingest"
}

dependencies {
    // NotificationManagerCompat (is the listener grant live?) and
    // ContextCompat.checkSelfPermission. Both have platform equivalents at
    // minSdk 26, and both of those are the versions with the API-level
    // caveats -- the compat wrappers are the reason the two adapters can state
    // their status in one line each rather than in a version fork.
    implementation(libs.androidx.core.ktx)
}
