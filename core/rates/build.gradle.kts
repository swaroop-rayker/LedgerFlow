plugins {
    id("ledgerflow.android.library")
    id("ledgerflow.android.hilt")
}

android {
    namespace = "com.ledgerflow.core.rates"
}

/**
 * The converter's public exchange-rate table (ADR-0036, Law 6): one fixed ECB
 * address, fetched only when the user taps for it, cached in
 * `filesDir/rates/`. The second of the two modules that may touch a network.
 *
 * It depends on `:core:model` and `:core:common` and nothing else, so it
 * cannot compile a reference to the vault, a key or the user's Drive;
 * `moduleDependencyRuleCheck` holds it to that, and lets only `:feature:tools`
 * (which holds the converter) and `:app` depend on it.
 */
dependencies {
    // `api`: a converted amount is a `Money` in this module's signatures.
    api(project(":core:model"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
