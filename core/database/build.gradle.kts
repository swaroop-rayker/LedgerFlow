import org.gradle.api.tasks.PathSensitivity

plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.room")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.core.database"
}

// MigrationTestHelper reads the exported schema JSONs from the test APK's
// assets. Without them the migration harness -- the BUG8 gate -- fails with
// "Cannot find the schema file in the assets folder".
//
// Getting them there took three attempts, recorded so nobody repeats them:
//   1. `android.sourceSets.getByName("androidTest")` throws a ClassCastException
//      in AGP 9 (DefaultAndroidLibrarySourceSet_Decorated).
//   2. `variant.androidTest` no longer exists in AGP 9; it is null.
//   3. `variant.deviceTests[..].sources.assets.addStaticSourceDirectory(...)`
//      is reachable and does get called -- confirmed by logging -- but produces
//      no merged assets at all.
//
// So the schemas are synced into the standard `src/androidTest/assets`
// directory, which AGP picks up unconditionally with no API involved. That copy
// is generated and gitignored; `schemas/` stays the committed source of truth
// that scripts/guard-schema.sh checks. Testing migrations against the
// *committed* schemas is the correct thing to do regardless.
val syncRoomSchemasToTestAssets = tasks.register<Sync>("syncRoomSchemasToTestAssets") {
    description = "Copies committed Room schemas into androidTest assets for MigrationTestHelper."
    from(layout.projectDirectory.dir("schemas"))
    into(layout.projectDirectory.dir("src/androidTest/assets"))
}

// Every androidTest-flavoured task, not just the asset merger.
//
// Narrowing this to "AndroidTestAssets" was enough while only the asset merge
// read the directory. It is not: Android Lint's androidTest analysis reads
// `src/androidTest/assets` too, and Gradle rejects the build with an implicit
// dependency error the moment both run in one invocation -- which is exactly
// what happened when `preMergeCheck` started compiling instrumented sources.
tasks.matching { it.name.contains("AndroidTest", ignoreCase = true) }.configureEach {
    dependsOn(syncRoomSchemasToTestAssets)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))

    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

/**
 * The structural guards in this module read the **repository's** sources at run
 * time, not this module's compiled classes: `LedgerIsolationTest`
 * walks `core/`, `feature/` and `app/` looking for call sites.
 *
 * Gradle cannot see that. Without this, the test task's inputs are just this
 * module's own code, so a change anywhere else leaves the task UP-TO-DATE and
 * **the guard silently does not run** — which is strictly worse than not having
 * it, because the green build says it did. That is not hypothetical: a taxonomy
 * DAO rewrite broke one of these assertions and it went unnoticed for three
 * sessions, only surfacing when an unrelated change to `:core:model` happened to
 * invalidate the task.
 *
 * Declaring the sources as an input is the fix: touch any production Kotlin file
 * in the repository and these tests run again.
 */
tasks.withType<Test>().configureEach {
    // The repository-wide input below contains `core/database/src/androidTest/assets`,
    // which `syncRoomSchemasToTestAssets` generates (it has to live under `src/`
    // -- see that task for the three AGP 9 APIs that do not work). Gradle
    // rightly refuses an input location that overlaps another task's output
    // without a declared dependency, so declare it.
    dependsOn(":core:database:syncRoomSchemasToTestAssets")

    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("core/**/src/main/**/*.kt")
            include("feature/**/src/main/**/*.kt")
            include("app/**/src/main/**/*.kt")
            exclude("**/build/**")
        },
    )
        .withPropertyName("repositoryProductionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
