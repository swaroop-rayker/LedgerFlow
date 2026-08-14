plugins {
    id("ledgerflow.android.library")
}

android {
    namespace = "com.ledgerflow.core.crypto"
}

dependencies {
    implementation(project(":core:common"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    // Parses the official BIP-39 vectors.json. Test-only, and used purely as a
    // JsonElement reader, so the serialization compiler plugin is not needed.
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
