plugins {
    id("ledgerflow.android.feature")
    // §12's screenshot gate, for the diagnostics screen (P5): the first feature
    // screen with goldens. `verifyRoborazziSmsFullDebug` in `preMergeCheck`
    // and CI's `screenshot` job pick the module up by task name.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.ledgerflow.feature.settings"

    testOptions.unitTests {
        // Robolectric needs the merged resources and the manifest to compose
        // anything; see the identical block in :core:ui.
        isIncludeAndroidResources = true

        // Forwarded, not hardcoded -- see :core:designsystem for why.
        all { test ->
            listOf(
                "javax.net.ssl.trustStore",
                "javax.net.ssl.trustStorePassword",
            ).forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

/**
 * Settings > About > Privacy policy shows `docs/play/privacy-policy.md` itself
 * (V2-3): copied into the assets at build time, so the policy on the phone and
 * the one the stores link to are one file and cannot drift apart.
 * `PrivacyPolicyIsTheDocsFileTest` holds it.
 */
abstract class BundleAboutDocuments : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val documents: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val about = outputDir.get().asFile.resolve("about")
        about.deleteRecursively()
        about.mkdirs()
        documents.forEach { it.copyTo(about.resolve(it.name), overwrite = true) }
    }
}

val bundleAboutDocuments = tasks.register<BundleAboutDocuments>("bundleAboutDocuments") {
    documents.from(rootProject.layout.projectDirectory.file("docs/play/privacy-policy.md"))
    outputDir.set(layout.buildDirectory.dir("generated/aboutDocuments"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleAboutDocuments, BundleAboutDocuments::outputDir)
    }
}

dependencies {
    // The shared fakes -- a validator and a backup repository -- for the
    // "Back up now" ViewModel's tests.
    testImplementation(project(":core:testing"))

    // The nightly schedule's test (BUG31) runs the worker under a real,
    // in-memory WorkManager on Robolectric: whether a pass keeps its successor
    // aimed at 03:00 is a property of WorkManager's own bookkeeping, which a
    // fake would only restate.
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // The diagnostics screen's goldens and its BUG17 title check.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // The nightly backup (ADR-0027) runs in a Worker, with no Activity alive
    // and no phrase anywhere; `hilt-work` is what lets it be constructed with
    // the repository rather than reaching for a static.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
