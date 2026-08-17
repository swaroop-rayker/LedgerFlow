plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
}

android {
    namespace = "com.ledgerflow.core.domain"
}

dependencies {
    // :core:model + :core:common only (CLAUDE.md §3). No :core:database, no
    // :core:crypto -- every Android and crypto type stops at :core:data and is
    // mapped to this module's vocabulary on the way through.
    api(project(":core:model"))
    api(project(":core:common"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
