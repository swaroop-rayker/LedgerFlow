import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.ledgerflow.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose support. Applied on top of `ledgerflow.android.library` or
 * `ledgerflow.android.application`.
 *
 * The Compose Compiler Gradle plugin is required from Kotlin 2.0 onward even
 * though AGP 9 otherwise owns Kotlin -- enabling `buildFeatures.compose`
 * without it fails configuration.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        // Which extension exists depends on whether this is the application or
        // a library. `CommonExtension` would cover both, but on AGP 9 it no
        // longer carries the settings the other convention plugins need, so the
        // codebase binds concrete extension types throughout; this matches.
        val library = extensions.findByType(LibraryExtension::class.java)
        if (library != null) {
            library.buildFeatures.compose = true
        } else {
            val application = requireNotNull(extensions.findByType(ApplicationExtension::class.java)) {
                "ledgerflow.android.compose needs the Android application or library " +
                    "plugin applied first; neither extension is present on $path"
            }
            application.buildFeatures.compose = true
        }

        val bom = libs.findLibrary("androidx-compose-bom").get()
        dependencies {
            add("implementation", platform(bom))
            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())

            add("androidTestImplementation", platform(bom))
            add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
        }
    }
}
