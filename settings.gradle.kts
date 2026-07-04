pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LedgerFlow"
include(":app")
include(":core:common")
include(":core:security")
include(":core:ui")
include(":domain")
include(":data")
include(":services")
include(":presentation")
