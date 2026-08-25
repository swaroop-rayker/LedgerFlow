plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.compose")
}

android {
    namespace = "com.ledgerflow.core.ui"
}

dependencies {
    // Shared composites are built out of the design system's atoms and use its
    // tokens for every colour, dimension and type size (CLAUDE.md §5).
    //
    // Nothing here depends on :core:model or :core:domain, and that is the
    // property worth keeping: a composite in this module renders values a host
    // has already resolved -- names, formatted amounts -- so it stays usable
    // from any feature without dragging a domain vocabulary along with it.
    implementation(project(":core:designsystem"))

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
