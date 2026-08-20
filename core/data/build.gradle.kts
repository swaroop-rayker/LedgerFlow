plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
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
