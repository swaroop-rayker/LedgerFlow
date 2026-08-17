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

tasks.matching { it.name.contains("AndroidTestAssets") }.configureEach {
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
