plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.data"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:security"))
    implementation(project(":domain"))
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    
    // SQLCipher Room Integration
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)
    
    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.mockito:mockito-core:5.8.0")
}
