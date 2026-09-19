import java.util.Properties

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
    implementation(libs.mlkit.text.recognition)

    // Devanagari, on the owner's instruction. ML Kit's models are per SCRIPT,
    // not per language, so this single artifact is what "Hindi" means here --
    // and it covers Marathi, Nepali, Sanskrit and Konkani at the same time.
    //
    // ML Kit ships exactly five script models: Latin, Chinese, Devanagari,
    // Japanese and Korean. **Kannada and Malayalam do not exist**, probed
    // directly against dl.google.com rather than assumed. Neither does
    // `text-recognition-hindi`, for the reason above. See ADR-0021's amendment.
    implementation(libs.mlkit.text.recognition.devanagari)

    // ── Perspective correction (ADR-0024) ────────────────────────────────────
    // OpenCV's official Android AAR, core + imgproc used. Finds a photographed
    // page's four corners and warps it square before recognition -- the
    // four-point warp OCR-PIPELINE.md section B recorded as the open item.
    // Native, arm64 split measured in ADR-0024; budget raised there.
    implementation(libs.opencv)

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

/**
 * `ReceiptCorpusTest` reads the committed manifest and the **private receipt
 * store** as plain files at run time, and Gradle could see neither.
 *
 * Found on 2026-09-19: fixtures in the store were edited and the test task,
 * being up to date, reported the previous green — a check on the corpus that
 * does not run when the corpus changes. The same shape as `:feature:ingest`'s
 * golden corpus (its build file), and the fourth place it has appeared.
 *
 * The store is resolved exactly as the test resolves it — the
 * `LEDGERFLOW_RECEIPT_CORPUS` environment variable, then
 * `ledgerflow.receiptCorpusDir` in `local.properties`, then the sibling
 * `../LedgerFlow-receipts` — and its `.git` is excluded, so committing in the
 * store does not by itself re-run the tests. Absent, it simply is not an input,
 * and the test skips (locally) or fails (CI) as it always has.
 */
val receiptCorpusDir: File? = run {
    val fromEnv = providers.environmentVariable("LEDGERFLOW_RECEIPT_CORPUS").orNull?.takeIf { it.isNotBlank() }
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use { load(it) } }
            .getProperty("ledgerflow.receiptCorpusDir")?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    listOfNotNull(fromEnv?.let(::File), fromLocal?.let(::File), rootProject.file("../LedgerFlow-receipts"))
        .firstOrNull { it.isDirectory }
}

tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("testdata/receipts/manifest.json"))
        .withPropertyName("receiptManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    receiptCorpusDir?.let { dir ->
        inputs.files(fileTree(dir) { exclude(".git/**") })
            .withPropertyName("privateReceiptCorpus")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}
