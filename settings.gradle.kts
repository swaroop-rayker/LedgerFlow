pluginManagement {
    // Convention plugins. An included build rather than buildSrc: buildSrc
    // invalidates the whole build's configuration cache on any change to it.
    includeBuild("build-logic")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Resolves and auto-provisions the JVM 17 toolchain (SPEC.md §3) so the
    // build does not depend on whatever JDK the IDE or the CI runner happens to
    // have. The version is a literal rather than a libs.versions.toml
    // reference because the version catalog is declared further down in this
    // same file and is not yet available to the settings plugins block -- the
    // one documented exception to "every version lives in the catalog".
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Modules must not declare their own repositories. A stray repository in a
    // module build file is how an unpinned or unexpected artifact gets in.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "LedgerFlow"

include(":app")

// :core:model depends on nothing and is pure Kotlin. :feature:* may depend on
// :core:* only -- never on another feature (CLAUDE.md §3).
include(
    ":core:model",
    ":core:common",
    ":core:crypto",
    ":core:database",
    ":core:datastore",
    ":core:domain",
    ":core:data",
    ":core:designsystem",
    ":core:ui",
    ":core:testing",
)

include(
    ":feature:onboarding",
    ":feature:dashboard",
    ":feature:inbox",
    ":feature:entry",
    ":feature:ledger",
    ":feature:analytics",
    ":feature:budget",
    ":feature:categories",
    ":feature:ingest",
    ":feature:ocr",
    ":feature:export",
    ":feature:settings",
)
