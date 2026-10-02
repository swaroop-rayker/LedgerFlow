import com.android.apksig.ApkVerifier
import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import java.util.jar.JarFile

plugins {
    // AGP 9+ has built-in Kotlin support. Applying org.jetbrains.kotlin.android
    // alongside it is an error: https://kotl.in/gradle/agp-built-in-kotlin
    id("ledgerflow.android.application")
    id("ledgerflow.android.compose")
    id("ledgerflow.android.hilt")
    // Type-safe Navigation Compose routes are @Serializable (SPEC.md §9.3).
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing (BUG46).
 *
 * `release.yml` has always written `keystore.properties` from the repository's
 * secrets, and nothing read it: the release build type had no signing config,
 * so every release APK came out `-unsigned` and the workflow's own signature
 * step could never have passed. It never ran, so nobody found out.
 *
 * The file is gitignored (the `guards` job fails if one is tracked) and holds
 * `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Absent, release
 * stays unsigned, which is right for a dev box that only builds release to
 * check R8. `-Pledgerflow.requireReleaseSigning`, which `release.yml` passes,
 * turns that absence into a failure, so a tag can never publish an unsigned
 * build. One key, owner's decision (2026-10-02): it signs sideloaded `smsFull`
 * APKs and is `playSafe`'s Play upload key; Google holds the Play signing key.
 */
val releaseSigning: Properties? = providers
    .fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) } }

if (releaseSigning == null && providers.gradleProperty("ledgerflow.requireReleaseSigning").isPresent) {
    throw GradleException(
        "ledgerflow.requireReleaseSigning is set but keystore.properties does not exist. " +
            "A release must be signed (BUG46).",
    )
}

android {
    namespace = "com.ledgerflow"

    defaultConfig {
        applicationId = "com.ledgerflow"
    }

    signingConfigs {
        releaseSigning?.let { props ->
            fun required(key: String): String = requireNotNull(props.getProperty(key)?.takeIf { it.isNotBlank() }) {
                "keystore.properties has no '$key' (BUG46)."
            }
            create("release") {
                storeFile = file(required("storeFile"))
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
    }

    buildTypes {
        // Before `benchmark`, which copies release with initWith (BUG38).
        getByName("release") {
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }

        // What :benchmark measures (P5, SPEC §11). Release's code -- R8 on, not
        // debuggable, because a debuggable build's timings are not the app's --
        // signed with the debug key so it installs from a dev box, and its own
        // application id so it never touches a real install's vault. Its
        // src/benchmark source set adds the one thing release must never have:
        // a seed entry point that makes a throwaway vault with synthetic data.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            applicationIdSuffix = ".bench"
            isDebuggable = false
            proguardFiles("benchmark-rules.pro")
        }
    }
}

// Benchmarked on smsFull only (owner, 2026-09-29): the flavours share every
// screen, so a second set of numbers would cost device time and say nothing new.
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "benchmark" && variant.flavorName != "smsFull") {
            variant.enable = false
        }
    }
}

/**
 * The permissions that actually reach a built APK, per variant.
 *
 * ## Why this exists alongside the root project's `EXPECTED_PERMISSIONS`
 *
 * That guard reads **source** manifests. This one reads the **merged** manifest
 * -- the thing that is actually packaged -- and they do not agree, because a
 * dependency's manifest merges permissions that no source set in this
 * repository declares. Measured at P4: `smsFull` source manifests declare 2
 * permissions and the shipped APK declares 7. The other five arrive from
 * WorkManager (`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`,
 * `FOREGROUND_SERVICE`) and androidx.core's dynamic-receiver permission.
 *
 * So the source guard's promise -- "the interesting failure was always going to
 * be the permission that arrives without anyone deciding to add it" -- was true
 * and the guard could not keep it, because the permission that arrives that way
 * arrives through a POM, not through a file anyone edits.
 *
 * This was found by measurement, not by review: adding bundled ML Kit merges
 * `android.permission.INTERNET` (from `transport-backend-cct`, Google's
 * telemetry uploader) and **nothing in the build said so**.
 *
 * ## Read through the artifact API, not a path
 *
 * `SingleArtifact.MERGED_MANIFEST` is AGP's supported handle on this file.
 * Hardcoding `build/intermediates/merged_manifest/<variant>/...` would work
 * today and break on an AGP upgrade -- and it would break by finding *nothing*,
 * which for a guard means passing. That is the failure this repository has
 * recorded four times: a check that could not see the thing it checked.
 *
 * ## The pin is per variant because the dynamic-receiver permission is
 *
 * `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` embeds the
 * application id, so it differs across all four variants. Writing it out four
 * times is deliberate: a pattern here would also match a permission we did not
 * mean to allow.
 */
val EXPECTED_MERGED_PERMISSIONS: Map<String, Set<String>> = mapOf(
    "smsFullDebug" to setOf(
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.RECEIVE_SMS",
        "android.permission.WAKE_LOCK",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "com.ledgerflow.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ),
    "smsFullRelease" to setOf(
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.RECEIVE_SMS",
        "android.permission.WAKE_LOCK",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "com.ledgerflow.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ),
    // No RECEIVE_SMS, in either build type. That absence is D-04's whole point
    // and it is the one line here worth checking by eye.
    "playSafeDebug" to setOf(
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "com.ledgerflow.playsafe.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ),
    "playSafeRelease" to setOf(
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.FOREGROUND_SERVICE",
        "com.ledgerflow.playsafe.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ),
)

androidComponents {
    onVariants { variant ->
        val expected = EXPECTED_MERGED_PERMISSIONS[variant.name] ?: return@onVariants
        val merged = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val variantName = variant.name

        tasks.register("mergedPermissionCheck${variantName.replaceFirstChar(Char::uppercaseChar)}") {
            group = "verification"
            description = "Pins the permissions in $variantName's MERGED manifest (Law 6, D-04)."
            inputs.file(merged)
            outputs.upToDateWhen { false }

            doLast {
                val text = merged.get().asFile.readText()
                val found = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .toSet()

                val violations = mutableListOf<String>()

                (found - expected).forEach { extra ->
                    val why = if (extra.endsWith("INTERNET")) {
                        "INTERNET reaches the packaged APK from a source that is NOT pinned. " +
                            "One INTERNET is expected and already recorded -- bundled ML Kit " +
                            "merges it from transport-backend-cct, and ADR-0021 amended Law 6 " +
                            "for exactly that one. Seeing it here means it arrived somewhere " +
                            "the pin does not cover, so find who merged it in " +
                            "app/build/outputs/logs/manifest-merger-*-report.txt before adding " +
                            "a pin. Law 6's substantive half is unchanged: nothing this app " +
                            "computes touches a network."
                    } else {
                        "It is in the merged manifest but not pinned. Find who merged it in " +
                            "app/build/outputs/logs/manifest-merger-*-report.txt, then either " +
                            "remove it or record here that it was meant."
                    }
                    violations += "$variantName: $extra is packaged and NOT pinned -- $why"
                }

                (expected - found).forEach { gone ->
                    violations += "$variantName: $gone is pinned but is NOT in the merged " +
                        "manifest. If it was deliberately removed, drop the pin; a stale pin " +
                        "guards nothing."
                }

                if (violations.isNotEmpty()) {
                    violations.forEach { logger.error("::error::$it") }
                    throw GradleException(
                        "mergedPermissionCheck($variantName) found ${violations.size} violation(s).",
                    )
                }
                logger.lifecycle(
                    "mergedPermissionCheck($variantName): clean (${expected.size} pinned).",
                )
            }
        }
    }
}

/**
 * BUG46's check: what a release produces is signed, and not with the debug key.
 *
 * `verifyReleaseSigning<Variant>` reads the variant's APKs with AGP's own
 * `apksig` (the verifier `apksigner` wraps) and, for `playSafe`, the AAB that
 * goes to Play, whose JAR signature the JDK can check. `verifyReleaseSigning`
 * runs both; `release.yml` runs it, and CI dry-runs it on every PR so the task
 * names `release.yml` uses cannot rot unseen again.
 */
val releaseSigningChecks = mutableListOf<String>()

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val variantName = variant.name
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        // The AAB is what Play receives, and only playSafe goes to Play.
        val bundle = if (variant.flavorName == "playSafe") variant.artifacts.get(SingleArtifact.BUNDLE) else null
        val taskName = "verifyReleaseSigning${variantName.replaceFirstChar(Char::uppercaseChar)}"
        releaseSigningChecks += taskName

        tasks.register(taskName) {
            group = "verification"
            description = "Fails unless $variantName's release artifacts are signed with a non-debug key (BUG46)."
            inputs.files(apkDir)
            bundle?.let { inputs.file(it) }
            outputs.upToDateWhen { false }

            doLast {
                val problems = mutableListOf<String>()
                fun checkSubject(what: String, subjects: List<String>) {
                    when {
                        subjects.isEmpty() -> problems += "$what has no signer"
                        subjects.any { "CN=Android Debug" in it } -> problems += "$what is signed with the DEBUG key"
                        else -> logger.lifecycle("verifyReleaseSigning($variantName): $what signed by ${subjects.joinToString()}")
                    }
                }

                val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
                if (apks.isEmpty()) problems += "no APK was produced"
                apks.forEach { apk ->
                    if (apk.name.contains("unsigned")) problems += "${apk.name} is unsigned"
                    val result = ApkVerifier.Builder(apk).build().verify()
                    if (!result.isVerified) {
                        problems += "${apk.name} does not verify: ${result.errors.joinToString { it.toString() }}"
                    } else {
                        checkSubject(apk.name, result.signerCertificates.map { it.subjectX500Principal.name })
                    }
                }

                bundle?.get()?.asFile?.let { aab ->
                    val subjects = JarFile(aab, true).use { jar ->
                        // A JAR's signers are only known once each entry has been read in full.
                        jar.entries().toList().filterNot { it.isDirectory || it.name.startsWith("META-INF/") }
                            .flatMap { entry ->
                                jar.getInputStream(entry).use { it.readBytes() }
                                entry.codeSigners.orEmpty().map { signer ->
                                    (signer.signerCertPath.certificates.first() as java.security.cert.X509Certificate)
                                        .subjectX500Principal.name
                                }.ifEmpty { listOf("") }
                            }
                    }
                    if ("" in subjects) problems += "${aab.name} has an unsigned entry"
                    checkSubject(aab.name, subjects.filter { it.isNotEmpty() }.distinct())
                }

                if (problems.isNotEmpty()) {
                    problems.forEach { logger.error("::error::verifyReleaseSigning($variantName): $it") }
                    throw GradleException("verifyReleaseSigning($variantName): ${problems.size} problem(s) (BUG46).")
                }
            }
        }
    }
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "BUG46: every release artifact is signed, and not with the debug key."
    dependsOn(releaseSigningChecks)
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:domain"))
    // :app is the only module allowed to see :core:data -- it is where the
    // domain ports get bound to their implementations (CLAUDE.md §3).
    implementation(project(":core:data"))
    // The second store, and the only one that is not the vault (ADR-0020).
    // Here for the same reason :core:data is: this is where a port meets its
    // implementation. Nothing above :app knows either exists.
    implementation(project(":core:datastore"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:entry"))
    implementation(project(":feature:ledger"))
    implementation(project(":feature:analytics"))
    implementation(project(":feature:budget"))
    implementation(project(":feature:categories"))
    implementation(project(":feature:export"))
    // No UI of its own, and that is the point at S11: what :app takes from it
    // is the two capture components' manifest entries and the Hilt bindings
    // that put both TransactionIngestSource implementations in the graph
    // (SPEC.md §3.1). The Inbox that consumes them is P2.
    implementation(project(":feature:inbox"))
    implementation(project(":feature:ingest"))
    // P4. Brings CAMERA and, transitively through bundled ML Kit, INTERNET --
    // both pinned in EXPECTED_MERGED_PERMISSIONS above, which is the point of
    // wiring it here rather than later: an unwired module's permissions reach
    // no merged manifest, so the pin would guard nothing until the day the
    // dependency landed.
    implementation(project(":feature:ocr"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // LedgerFlowApplication is WorkManager's Configuration.Provider, so :app
    // holds the factory even though the only worker lives in :feature:ingest.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // :core:testing carries the domain-port fakes. Test scope only -- nothing in
    // main source may see it.
    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    // BUG6's regression test assembles the real graph -- SQLCipher, the
    // Keystore-wrapped DEK, the entry form -- tears all of it down, and rebuilds
    // it from disk. :app is the module that legitimately wires those together,
    // so it is where a test about the whole graph surviving a process belongs.
    // Test scope only; none of this reaches main source.
    androidTestImplementation(project(":core:common"))
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
