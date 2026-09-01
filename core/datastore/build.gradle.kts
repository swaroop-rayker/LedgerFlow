import org.gradle.api.tasks.PathSensitivity

plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
}

android {
    namespace = "com.ledgerflow.core.datastore"
}

dependencies {
    // The ports this module implements. ADR-0020 draws the line this module
    // lives on: operational metadata about the app's own machinery, never
    // financial data or message content. Nothing here reaches :core:database or
    // :core:crypto, and that absence is the module's entire point.
    implementation(project(":core:domain"))
    implementation(project(":core:common"))

    // Already pinned in libs.versions.toml and unused until now (ADR-0020).
    // Preferences rather than Proto: four scalar keys do not justify a
    // serialization plugin and a .proto file, and the guard that keeps this
    // module honest reads key *names*, which Preferences has and Proto does not.
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // NotificationListenerHealthDataStoreTest writes a real DataStore file and
    // reads it back through a second store instance -- the cross-process
    // durability property Option A in ADR-0020 could not offer. A JVM test
    // would exercise a temp directory and prove nothing about filesDir.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * `DatastoreKeySurfaceTest` reads this module's *sources* at run time to check
 * that no preference key has appeared without amending ADR-0020's permitted
 * list. Gradle cannot see a file a test opens by path.
 *
 * Without this the guard would still pass its own module's compilation and then
 * quietly stop running whenever the task was up to date -- which is the fourth
 * recorded instance of that shape in this repository (§16 Q13) and the reason
 * it is declared here rather than assumed.
 */
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(projectDir) {
            include("src/main/**/*.kt")
        },
    )
        .withPropertyName("datastoreProductionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
