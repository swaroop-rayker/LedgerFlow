import org.gradle.api.tasks.PathSensitivity

plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
    // The curated ingest allowlists ship as JSON assets (D-10), read here.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.core.data"
}

dependencies {
    // The layer where Android, crypto and Room types are allowed to meet the
    // domain ports -- and the only one. Everything above sees :core:domain.
    api(project(":core:domain"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))

    implementation(libs.androidx.lifecycle.process)
    // room-ktx supplies RoomDatabase.withTransaction, the suspend-safe
    // transaction wrapper. runInTransaction takes a blocking lambda and
    // suspend DAO calls cannot run inside it.
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // `asSnapshot()`. A PagingData is opaque by design -- there is no supported
    // way to read items out of one by hand, and hand-rolling it would test our
    // own reflection rather than the query (ADR-0014).
    androidTestImplementation(libs.androidx.paging.testing)
}

/**
 * `ExportCoversEveryTableTest` reads the committed Room schema JSON as a plain
 * file at run time, and Gradle cannot see that.
 *
 * Without this the task stays UP-TO-DATE when a schema version is added, and the
 * guard that exists to notice a new table stops running exactly when a new table
 * appears. That is not hypothetical here: three separate guards in this
 * repository have reported success while not executing, and the ingest tables
 * the test now checks for went missing from the backup for two schema versions
 * because its predecessor could only see itself.
 *
 * Same shape as `:feature:ingest`'s golden-corpus declaration. Copy one of them
 * for the next test that reads a repository file.
 */
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("core/database/schemas/**")
        },
    )
        .withPropertyName("committedRoomSchemas")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
