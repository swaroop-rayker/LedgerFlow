plugins {
    id("ledgerflow.jvm.library")
}

dependencies {
    // Test scope only. :core:model depends on nothing at runtime (CLAUDE.md §3)
    // and this does not change that -- a JVM module's test classpath is not its
    // API, and `preMergeCheck` runs `:core:model:test` like every other module's.
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
