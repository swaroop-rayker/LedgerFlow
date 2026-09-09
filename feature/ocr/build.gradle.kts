plugins {
    id("ledgerflow.android.feature")
}

android {
    namespace = "com.ledgerflow.feature.ocr"
}

dependencies {
    // ReceiptCorpusTest reads testdata/receipts/manifest.json as a plain file,
    // so it needs a JSON parser on the test classpath -- the same reason
    // :feature:ingest's GoldenCorpusTest has one.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
