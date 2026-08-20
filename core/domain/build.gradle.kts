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

    // The one carve-out, and it is narrow on purpose (ADR-0014). paging-common
    // is a Kotlin/JVM artifact -- PagingData, Pager, PagingSource, nothing from
    // android.* -- so it does not cost this module the property §3 exists to
    // protect: it still compiles and unit-tests off-device.
    //
    // `api`, because LedgerRepository's list read has PagingData in its
    // signature and :feature:ledger has to see the type.
    //
    // **paging-runtime and paging-compose are NOT admissible here.** Those are
    // the Android halves; they belong to :feature:*. If a second AndroidX
    // coordinate is ever proposed for this module, that is the trigger to
    // reopen ADR-0014 rather than to widen this comment.
    api(libs.androidx.paging.common)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
