import com.ledgerflow.buildlogic.configureDetekt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure-Kotlin JVM module. Used by `:core:model`, which by the dependency rule
 * in CLAUDE.md §3 depends on nothing and must have no Android types in it --
 * enforced here by simply not applying the Android plugin.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }

        // Typed here, unlike the Android modules: this module applies the
        // Kotlin Gradle Plugin directly, so the extension really is KGP's type.
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
        }

        configureDetekt()
    }
}
