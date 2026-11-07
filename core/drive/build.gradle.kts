plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
}

android {
    namespace = "com.ledgerflow.core.drive"
}

/**
 * The only module that touches a network (ADR-0034, Law 6): Play services'
 * authorization for a Drive access token, and Drive's REST API over
 * HttpsURLConnection. It is handed bytes and names, never the vault, the
 * database or a key; `DriveModuleBoundaryTest` holds it to that, and
 * `bannedApiCheck` fails on a network API anywhere else.
 */
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:common"))

    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
