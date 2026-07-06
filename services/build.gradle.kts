plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ledgerflow.services"
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
    implementation(project(":data"))
    
    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    
    // SQLCipher & SQLite
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)
    
    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)
    
    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    
    // ML Kit Text Recognition
    implementation(libs.google.mlkit.text.recognition)
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}

