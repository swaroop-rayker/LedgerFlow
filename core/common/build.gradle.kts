plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
}

android {
    namespace = "com.ledgerflow.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
