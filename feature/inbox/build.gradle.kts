plugins {
    id("ledgerflow.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.feature.inbox"
}

dependencies {
    // The Inbox drives the approval use cases and renders extraction targets;
    // both live in :core:domain, which the feature convention already exposes.

    // ReviewDraftPayload (v8, BUG6). The review screen's in-progress typing is
    // this screen's own format -- the same split SPEC.md §6.1.2 draws for
    // `draft_entry.payload_json` -- so the encoder lives here rather than in
    // :core:data beside ExtractedTransactionJson. Already in the version
    // catalog and used by :app and :core:data; no new dependency.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
