import org.gradle.api.tasks.PathSensitivity

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

    // :core:testing carries FakeRawIngestRepository, the domain-port fake the
    // pipeline tests drive. Test scope only -- nothing in main may see it.
    testImplementation(project(":core:testing"))

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

/**
 * `GoldenCorpusTest` reads `testdata/` and the shipped ruleset asset as plain
 * files at run time, and Gradle cannot see either.
 *
 * Without this the corpus -- which is the parser's actual specification -- does
 * not re-run when a fixture is added or a rule is edited. It went unnoticed for
 * exactly one commit: adding a real bank SMS that the ruleset could not parse
 * produced a green build, because the task was up to date and never executed.
 * A spec that does not run when you change the thing it specifies is worse than
 * no spec, and this is the third place in this repository where that same shape
 * has bitten.
 */
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("testdata/**")
            include("core/data/src/main/assets/parser_rules/**")
        },
    )
        .withPropertyName("goldenCorpusAndShippedRuleset")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
