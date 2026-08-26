import org.gradle.api.tasks.PathSensitivity

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

/**
 * The structural guards in this module read the **repository's** sources at run
 * time, not this module's compiled classes: `LedgerSingleWriterTest` and `TaxonomySingleWriterTest``
 * walk `core/`, `feature/` and `app/` looking for call sites.
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
