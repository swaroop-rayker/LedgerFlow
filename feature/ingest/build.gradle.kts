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

    // ParseIngestWorker: the receiver has ~10 seconds (CLAUDE.md §7), so every
    // lookup the pipeline needs happens in a Worker instead. hilt-work is what
    // lets that worker be constructed with its use cases rather than reaching
    // for a service locator.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.androidx.work.runtime.ktx)

    // GoldenCorpusTest reads the shipped ruleset asset and testdata/ as plain
    // files, so it needs a JSON parser on the test classpath.
    testImplementation(libs.kotlinx.serialization.json)

    // SmsCaptureFromPduTest drives Android's own PDU parser. `SmsMessage` has no
    // public constructor, and adb cannot deliver an SMS either (BROADCAST_SMS is
    // signature-level), so the platform unwrap is reachable only on a device.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
