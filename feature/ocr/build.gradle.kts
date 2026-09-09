plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.ocr"
}

dependencies {
    // ── OCR (ADR-0021) ───────────────────────────────────────────────────────
    // The BUNDLED recognizer. The model ships inside the APK, so recognition
    // works with no network and on a device with no Play Services -- which is
    // the whole reason the unbundled variant was disqualified rather than
    // merely rated worse. Costs +12.35 MB on the arm64 release split, measured.
    //
    // It merges android.permission.INTERNET transitively, from
    // transport-backend-cct (Google's telemetry uploader) rather than from any
    // model download. Accepted by the owner; Law 6 amended in ADR-0021; pinned
    // in :app's EXPECTED_MERGED_PERMISSIONS. `OcrRunsWithoutNetworkTest` is
    // what keeps "recognition is local" a build-enforced property rather than a
    // claim about a dependency we do not control.
    //
    // Devanagari is deliberately NOT here yet. It is +0.61 MB, so the decision
    // is not a budget one -- §12's corpus diversity floor requires a
    // Devanagari-bearing receipt, and that fixture is what should trigger it.
    implementation(libs.mlkit.text.recognition)

    // ── Camera (SPEC.md §5.3) ────────────────────────────────────────────────
    // camera-core + camera2 is the capture stack; camera-lifecycle binds it to
    // the composable's lifecycle so a backgrounded app releases the sensor;
    // camera-compose supplies the viewfinder without an AndroidView wrapper.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    // ReceiptCorpusTest reads testdata/receipts/manifest.json as a plain file,
    // so it needs a JSON parser on the test classpath -- the same reason
    // :feature:ingest's GoldenCorpusTest has one. junit4 and truth come from
    // the feature convention plugin.
    testImplementation(libs.kotlinx.serialization.json)

    // OcrRunsWithoutNetworkTest drives the real recognizer on a real image, so
    // it cannot be a JVM test: ML Kit's pipeline is native.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
