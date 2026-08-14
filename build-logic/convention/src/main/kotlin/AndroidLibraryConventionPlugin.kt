import com.android.build.api.dsl.LibraryExtension
import com.ledgerflow.buildlogic.configureAndroidLibrary
import com.ledgerflow.buildlogic.configureDetekt
import com.ledgerflow.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Base for every Android library module. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")

        extensions.configure<LibraryExtension> {
            configureAndroidLibrary(this)
        }

        configureDetekt()

        dependencies {
            add("coreLibraryDesugaring", libs.findLibrary("android-desugar-jdk-libs").get())
        }
    }
}
