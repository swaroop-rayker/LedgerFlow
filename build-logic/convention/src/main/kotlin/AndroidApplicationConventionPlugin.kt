import com.android.build.api.dsl.ApplicationExtension
import com.ledgerflow.buildlogic.configureAndroidApplication
import com.ledgerflow.buildlogic.configureDetekt
import com.ledgerflow.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * The `:app` module.
 *
 * Its counterpart, `ledgerflow.android.library`, has existed since Phase 0;
 * `:app` hand-maintained a copy of the same settings, which is how compileSdk,
 * desugaring and the flavour list drift apart between the application and the
 * 23 libraries it consumes. The AAR metadata check turns that drift into a
 * build failure rather than a runtime surprise, but only after the fact.
 *
 * `applicationId` stays in the module build file: it is the one genuinely
 * app-specific value here, and a convention plugin is not the place to hide
 * which package the thing installs as.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")

        extensions.configure<ApplicationExtension> {
            configureAndroidApplication(this)
        }

        configureDetekt()

        dependencies {
            add("coreLibraryDesugaring", libs.findLibrary("android-desugar-jdk-libs").get())
        }
    }
}
