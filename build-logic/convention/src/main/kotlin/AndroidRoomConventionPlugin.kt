import com.ledgerflow.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Room + exported schemas.
 *
 * `room.schemaLocation` is not optional. The exported JSONs are committed and
 * `scripts/guard-schema.sh` fails the build if they are missing or edited --
 * that guard is the BUG8 countermeasure, and it can only work if schemas are
 * actually written out.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")

        extensions.configure(com.google.devtools.ksp.gradle.KspExtension::class.java) {
            arg("room.schemaLocation", "${projectDir}/schemas")
            arg("room.generateKotlin", "true")
        }

        dependencies {
            add("implementation", libs.findLibrary("androidx-room-runtime").get())
            add("implementation", libs.findLibrary("androidx-room-ktx").get())
            add("ksp", libs.findLibrary("androidx-room-compiler").get())
            add("androidTestImplementation", libs.findLibrary("androidx-room-testing").get())
        }
    }
}
