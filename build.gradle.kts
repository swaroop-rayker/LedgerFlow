import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    // kotlin.android is deliberately absent: AGP 9 provides Kotlin for Android
    // modules itself, and applying it alongside is an error.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
}

/**
 * Everything the CI PR gate runs, in one task (CLAUDE.md §4, §12).
 *
 * Builds BOTH flavours. `playSafe` is not a "later" deliverable -- it is the
 * Play-eligible build and it ships the same notification ingest path, so a
 * green check that only covered `smsFull` would be lying.
 *
 * Depends on each module's `test` lifecycle task rather than on a specific
 * variant: `:core:model` is a pure-Kotlin JVM module with no variants, so
 * `testSmsFullDebugUnitTest` does not exist there. `test` is the one name that
 * means the same thing in both module types.
 */
/**
 * Greppable bans for the Seven Laws (CLAUDE.md §2), mirroring the banned-API
 * step in .github/workflows/ci.yml so the same failures surface locally.
 *
 * This exists because detekt cannot currently enforce them here. Rules like
 * `UnsafeCallOnNullableType` require type resolution, and detekt 1.23.8 creates
 * its type-resolving Android variant tasks only when it detects the Kotlin
 * Android plugin -- which AGP 9 forbids us from applying, since it provides
 * Kotlin itself. The plain `detekt` task runs without type resolution and
 * silently skips those rules, so relying on it would mean a law that is
 * configured but not enforced.
 *
 * A regex is cruder than type resolution, but a crude gate that runs beats a
 * sophisticated one that does not. Revisit when detekt supports AGP 9.
 */
tasks.register("bannedApiCheck") {
    group = "verification"
    description = "Fails on `!!` and cacheDir outside tests (CLAUDE.md §2 Laws 5 and 7)."

    val sourceRoots = subprojects
        .filter { it.buildFile.exists() }
        .map { it.projectDir }
    outputs.upToDateWhen { false }

    doLast {
        // `x!!`, `f()!!`, `a[0]!!` -- anchored on the preceding token so that
        // `!!` inside a string or a doubled negation is not matched.
        val bangBang = Regex("""[\w)\]]!!""")
        val cacheDir = Regex("""\.cacheDir\b""")
        val violations = mutableListOf<String>()

        sourceRoots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file ->
                    val path = file.invariantSeparatorsPath
                    "/src/test/" in path || "/src/androidTest/" in path ||
                        file.name.endsWith("Test.kt") || "/build/" in path
                }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        val code = line.substringBefore("//")
                        if (bangBang.containsMatchIn(code)) {
                            violations += "${file.path}:${index + 1}: `!!` is banned outside tests " +
                                "(use requireNotNull(x) { \"why\" }) -- Law 7 / BUG7"
                        }
                        if (cacheDir.containsMatchIn(code)) {
                            violations += "${file.path}:${index + 1}: cacheDir is OS-clearable; " +
                                "persistent data belongs in filesDir -- Law 5 / BUG2"
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            violations.forEach { logger.error("::error::$it") }
            throw GradleException("bannedApiCheck found ${violations.size} violation(s).")
        }
        logger.lifecycle("bannedApiCheck: clean.")
    }
}

/**
 * **The app's entire permission set, pinned per source set** (SPEC.md §3.1
 * D-04, Law 6, CLAUDE.md §9).
 *
 * `RECEIVE_SMS` is Play-restricted. The whole reason the `smsFull`/`playSafe`
 * split exists is that it appears in exactly one flavour's manifest, and the
 * failure mode is a single misplaced line: merged into `src/main`, it reaches
 * `playSafe` too and the Play-eligible build stops being submittable. Nothing
 * about that shows up as a broken build, a failing test, or a visible symptom on
 * a device — it surfaces months later as a rejected release.
 *
 * Law 6 rides along for the same reason and with wider scope than CI's
 * `app/src/release` grep: no `INTERNET` in ANY source set of ANY module. All
 * parsing, OCR and analytics are on-device, and a library module declaring it
 * would merge into the release manifest just as effectively.
 *
 * ## Why this pins the whole set rather than blocklisting two names
 *
 * Until P2-7 this task knew about exactly `RECEIVE_SMS`, `READ_SMS` and
 * `INTERNET`, which meant it had **nothing to say about a permission nobody had
 * thought of yet**. Any other line — `READ_CONTACTS`, `ACCESS_FINE_LOCATION`, a
 * transitive `<uses-permission>` merged in from a future dependency's manifest —
 * would have shipped silently in both flavours. For an app whose pitch is that
 * it is offline and reads nothing it was not given, the interesting failure was
 * always going to be the permission that arrives without anyone deciding to add
 * it.
 *
 * So [EXPECTED_PERMISSIONS] is an allowlist, and anything not on it fails. A new
 * permission is then a deliberate two-part act: declare it, and say here that it
 * was meant. That is the point — the second half is where someone has to think
 * about which flavour it lands in.
 *
 * `POST_NOTIFICATIONS` (P2-7) is the first entry added under this rule, and
 * closes the deferred item that this task did not pin the full set.
 *
 * Mirrored by a step in .github/workflows/ci.yml so local and CI failures match.
 *
 * **This guard reads source manifests, which is not what ships.** A dependency
 * merges permissions no source set here declares -- measured at P4, `smsFull`
 * declares 2 and packages 7. `:app`'s `EXPECTED_MERGED_PERMISSIONS` pins the
 * merged manifest and is the half of this rule that can see a permission
 * arriving through a POM. Neither guard subsumes the other: this one says where
 * a permission may be written, that one says what may be packaged.
 */
val EXPECTED_PERMISSIONS: Map<String, Set<String>> = mapOf(
    // §5.1's inbox notification. Not restricted, and wanted by BOTH flavours:
    // playSafe produces pending_transaction rows from notification ingest
    // exactly as smsFull does from SMS.
    "app/src/main/AndroidManifest.xml" to setOf(
        "android.permission.POST_NOTIFICATIONS",
    ),
    // D-04. The one restricted permission LedgerFlow ever asks for, in the one
    // source set that may hold it.
    "feature/ingest/src/smsFull/AndroidManifest.xml" to setOf(
        "android.permission.RECEIVE_SMS",
    ),
    // The INSTRUMENTATION APK, which ships to nobody. InboxNotificationContentTest
    // posts real notifications and reads them back, and :feature:ingest is
    // self-instrumenting, so the posting process is the test APK.
    //
    // Pinned rather than exempted, and test manifests are walked rather than
    // skipped, because "it is only a test APK" is precisely the reasoning that
    // lets a permission drift into src/main later. The pin costs one line and
    // keeps the rule absolute.
    "feature/ingest/src/androidTest/AndroidManifest.xml" to setOf(
        "android.permission.POST_NOTIFICATIONS",
    ),
    // P4. CAMERA is the second permission this app asks for by choice, and
    // unlike RECEIVE_SMS it is NOT flavour-restricted: §5.3's capture path is
    // in both flavours, because OCR is one of the two sources playSafe ships.
    // So src/main, merging into both, deliberately.
    //
    // INTERNET is absent here and that is not an oversight: ML Kit merges it
    // transitively (ADR-0021), so it appears in the packaged manifest and is
    // pinned in :app's EXPECTED_MERGED_PERMISSIONS. Writing it into a source
    // manifest would read as this module asking for the network, which it does
    // not — the two guards say different things on purpose.
    "feature/ocr/src/main/AndroidManifest.xml" to setOf(
        "android.permission.CAMERA",
    ),
)

tasks.register("restrictedPermissionCheck") {
    group = "verification"
    description = "Pins the exact permission set per source set (SPEC.md §3.1 D-04, Law 6)."

    val rootDir = project.rootDir
    val sourceRoots = subprojects
        .filter { it.buildFile.exists() }
        .map { it.projectDir }
    val expected = EXPECTED_PERMISSIONS
    outputs.upToDateWhen { false }

    doLast {
        /**
         * The reason a particular stray permission is a problem, where we know one.
         *
         * A local function rather than a script-level one: a top-level `fun` in
         * a build script is a method on the script object, and capturing it in a
         * task action makes the task unserialisable for the configuration cache
         * ("cannot serialize Gradle script object references"). The guard still
         * ran and still passed — it was the *build* that then failed, which is
         * the kind of green-then-red that teaches people to distrust a check.
         */
        fun why(permission: String, path: String): String = when {
            permission.endsWith("RECEIVE_SMS") || permission.endsWith("READ_SMS") ->
                "RECEIVE_SMS/READ_SMS are Play-restricted and belong in " +
                    "feature/ingest/src/smsFull/ only -- D-04 / SPEC.md §3.1. Found in $path."

            permission.endsWith("INTERNET") ->
                "INTERNET is banned in every flavour; all parsing, OCR and analytics are " +
                    "on-device -- Law 6. If you think you need the network, raise an ADR."

            else ->
                "add it to EXPECTED_PERMISSIONS if it is intended, and decide there whether " +
                    "it belongs in src/main (both flavours) or in one flavour's source set."
        }

        // Anchored on the element AND the attribute, never on a bare permission
        // name: these manifests are heavily commented and several of them have
        // to NAME a permission in order to explain where it may not go. A prose
        // mention is not a declaration, and a guard that cannot tell the
        // difference is one people mute.
        val declaration = Regex("""<uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
        val violations = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        sourceRoots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .filterNot { "/build/" in it.invariantSeparatorsPath }
                .forEach { manifest ->
                    val relative = manifest.relativeTo(rootDir).invariantSeparatorsPath
                    seen += relative
                    val allowed = expected[relative].orEmpty()
                    val text = manifest.readText()

                    declaration.findAll(text).forEach { match ->
                        val permission = match.groupValues[1]
                        if (permission in allowed) return@forEach

                        val line = text.take(match.range.first).count { it == '\n' } + 1
                        violations += "$relative:$line: $permission is declared here and is " +
                            "not in EXPECTED_PERMISSIONS -- " + why(permission, relative)
                    }

                    (allowed - declaration.findAll(text).map { it.groupValues[1] }.toSet())
                        .forEach { missing ->
                            violations += "$relative: $missing is pinned in " +
                                "EXPECTED_PERMISSIONS but is NOT declared here. If it moved, " +
                                "move the pin with it; a stale pin guards nothing."
                        }
                }
        }

        // A pin naming a manifest that no longer exists is a guard pointing at
        // thin air -- the fourth instance in this repository of a check that
        // could not see the thing it checked (see ExportCoversEveryTableTest).
        (expected.keys - seen).forEach { orphan ->
            violations += "$orphan is pinned in EXPECTED_PERMISSIONS but no such manifest " +
                "exists. Delete the pin or restore the file."
        }

        if (violations.isNotEmpty()) {
            violations.forEach { logger.error("::error::$it") }
            throw GradleException("restrictedPermissionCheck found ${violations.size} violation(s).")
        }
        logger.lifecycle("restrictedPermissionCheck: clean (${expected.values.sumOf { it.size }} pinned).")
    }
}

/** Does this module carry instrumented tests worth compiling in the gate? */
fun Project.hasAndroidTests(): Boolean =
    file("src/androidTest").isDirectory ||
        file("src/androidTestSmsFull").isDirectory ||
        file("src/androidTestPlaySafe").isDirectory

tasks.register("preMergeCheck") {
    group = "verification"
    description = "Full PR gate: both flavours assemble, unit tests, detekt, Android Lint."

    // :benchmark is excluded deliberately. It is a com.android.test module that
    // measures :app on a real device; it has no unit tests to run, and its
    // instrumented benchmarks are a self-hosted-runner concern, not a PR gate.
    val verifiableModules = subprojects
        .filter { it.buildFile.exists() && it.path != ":benchmark" }
        .map { it.path }

    dependsOn("bannedApiCheck")
    dependsOn("restrictedPermissionCheck")
    // The merged-manifest half of the permission rule (see EXPECTED_PERMISSIONS'
    // docs). All four variants: `release` is what ships, and `debug` is what is
    // on the developer's phone. Merging a manifest costs no R8 run, so pinning
    // the release variants here does not make the gate meaningfully slower.
    dependsOn(
        ":app:mergedPermissionCheckSmsFullDebug",
        ":app:mergedPermissionCheckSmsFullRelease",
        ":app:mergedPermissionCheckPlaySafeDebug",
        ":app:mergedPermissionCheckPlaySafeRelease",
    )
    dependsOn(":app:assembleSmsFullDebug", ":app:assemblePlaySafeDebug")

    // **Android Lint on EVERY module, both flavours** -- S13 §6.
    //
    // This read `dependsOn(":app:lintSmsFullDebug", ":app:lintPlaySafeDebug")`,
    // which meant a lint *error* anywhere in a library module could never fail
    // the gate. It was not hypothetical: running lint across every module found
    // eight error sites in five of them, including `NewApi` in `:core:crypto`
    // and `MissingPermission` on three notification posts, two of which were
    // genuinely unchecked. §5.7's budget alerts could not post below API 33 at
    // all, and nothing in CI had any way to say so. `:app`'s own lint sees
    // almost nothing, because `:app` contains no business logic by design
    // (CLAUDE.md §3) -- so the one module in the gate was the one with the
    // least to check. This is the sixth gate in this repository found to be
    // silently doing nothing; `ReceiptCorpusTest`'s KDoc lists the other five.
    //
    // Filtered by task existence rather than listed, for the reason the
    // Roborazzi block below gives: `:core:model` is a pure-Kotlin JVM module
    // with no Android variants and therefore no `lint<Flavour>Debug`, and
    // `dependsOn` on a task that does not exist fails configuration rather than
    // being skipped. Resolved inside this task's configuration block, which
    // runs when the task is realised -- by which point every subproject has
    // been evaluated.
    dependsOn(
        verifiableModules.flatMap { path ->
            val module = subprojects.single { it.path == path }
            listOf("lintSmsFullDebug", "lintPlaySafeDebug")
                .filter { module.tasks.findByName(it) != null }
                .map { "$path:$it" }
        },
    )

    // Instrumented tests are COMPILED here even though they cannot be run --
    // they need a device, and that is the CI `instrumented` job's business.
    //
    // Compiling them is not busywork. `Bug6_DraftSurvivesProcessDeathTest` --
    // one of the Seven Laws' named regression tests -- stopped compiling when
    // ADR-0018 reshaped the entry form's events, and nothing said so for two
    // sessions, because every task in this gate ignores androidTest sources.
    // A regression test that does not build is a regression test that is not
    // running, and the build reported success throughout.
    dependsOn(
        verifiableModules
            .filter { path -> subprojects.single { it.path == path }.hasAndroidTests() }
            .flatMap {
                listOf("$it:assembleSmsFullDebugAndroidTest", "$it:assemblePlaySafeDebugAndroidTest")
            },
    )
    dependsOn(verifiableModules.map { "$it:detekt" })
    dependsOn(verifiableModules.map { "$it:test" })

    // §12's screenshot gate, and the whole point of this task is CI parity --
    // CI's `screenshot` job runs exactly `verifyRoborazziSmsFullDebug`, and
    // `pr-gate` requires it, so a PR that is green here and red there would make
    // this task a lie. Wired at P2-9, when Roborazzi was finally set up.
    //
    // Named by task rather than added to `verifiableModules`: only modules that
    // apply the Roborazzi plugin have it, and `dependsOn` on a task that does
    // not exist fails configuration rather than being skipped.
    dependsOn(
        verifiableModules
            .filter { path ->
                subprojects.single { it.path == path }
                    .tasks.findByName("verifyRoborazziSmsFullDebug") != null
            }
            .map { "$it:verifyRoborazziSmsFullDebug" },
    )
}

/**
 * Bumps the monotonic versionCode in version.properties (SPEC.md §15.6, BUG3).
 *
 * Rewrites only the one line rather than using Properties.store(), which would
 * strip the file's comments -- those comments are the explanation of why the
 * value must never go backwards.
 *
 * scripts/guard-version.sh enforces monotonicity against the last release tag.
 */
/**
 * Rewrites `testdata/receipts/manifest.json` from the private receipt store.
 *
 * The receipt corpus is split: images and expected-output JSON live outside
 * this repository (a real receipt carries a card tail and often a phone number,
 * and the ground truth is literally the owner's shopping list), while the
 * public record is a manifest of names, hashes, provenance and counts.
 *
 * **Derived, never hand-written.** Every field here is read out of the fixtures
 * themselves, so the manifest cannot claim a corpus that does not exist and
 * cannot drift from one that does. `ReceiptCorpusTest` then checks it back
 * against the store in both directions whenever the store is reachable.
 *
 * See `testdata/receipts/README.md` for the store's three resolution paths.
 */
tasks.register("regenerateReceiptManifest") {
    group = "verification"
    description = "Rebuilds testdata/receipts/manifest.json from the private receipt store."

    val root = project.rootDir
    val fromEnv = providers.environmentVariable("LEDGERFLOW_RECEIPT_CORPUS").orNull
    val fromLocal = rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { f -> Properties().apply { f.inputStream().use(::load) }.getProperty("ledgerflow.receiptCorpusDir") }
    outputs.upToDateWhen { false }

    doLast {
        val dir = listOfNotNull(
            fromEnv?.takeIf { it.isNotBlank() }?.let(::File),
            fromLocal?.takeIf { it.isNotBlank() }?.let(::File),
            File(root.parentFile, "LedgerFlow-receipts"),
        ).firstOrNull { it.isDirectory }
            ?: throw GradleException(
                "No private receipt store found. Set LEDGERFLOW_RECEIPT_CORPUS, or " +
                    "ledgerflow.receiptCorpusDir in local.properties, or place the store at " +
                    "../LedgerFlow-receipts. See testdata/receipts/README.md.",
            )

        val slurper = groovy.json.JsonSlurper()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun sha(f: File): String {
            digest.reset()
            return digest.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        }

        val entries = dir.listFiles { f: File -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .map { fixtureFile ->
                val name = fixtureFile.nameWithoutExtension

                @Suppress("UNCHECKED_CAST")
                val fixture = slurper.parse(fixtureFile) as Map<String, Any?>
                val imageName = fixture["image"] as? String
                    ?: throw GradleException("fixture '$name' has no \"image\"")
                val image = File(dir, imageName)
                if (!image.isFile) {
                    throw GradleException("fixture '$name' names an image that is not in the store: $imageName")
                }

                @Suppress("UNCHECKED_CAST")
                val capture = fixture["capture"] as? Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                val lines = fixture["lines"] as? List<Map<String, Any?>> ?: emptyList()

                linkedMapOf<String, Any?>(
                    "name" to name,
                    "image" to imageName,
                    "imageSha256" to sha(image),
                    "expectedSha256" to sha(fixtureFile),
                    "provenance" to (fixture["provenance"] ?: "unstated"),
                    "safeBecause" to (capture?.get("safeBecause") ?: ""),
                    "surface" to (capture?.get("surface") ?: "unstated"),
                    // Only ITEM lines count toward recall -- a missed tax line is a
                    // different defect from a missed purchase (SPEC.md 12).
                    "itemLineCount" to lines.count { it["kind"] == "ITEM" },
                )
            }

        val manifest = linkedMapOf<String, Any?>(
            "_comment" to listOf(
                "Generated by `./gradlew regenerateReceiptManifest`, never hand-edited. It is the",
                "public, auditable record of a corpus whose content is private -- names, hashes,",
                "provenance and counts, and nothing about what was bought. See",
                "testdata/receipts/README.md.",
                "",
                "A guard whose only reference is itself is a restatement, not a guard, so",
                "ReceiptCorpusTest checks this file AGAINST the private store whenever the",
                "store is reachable, in both directions.",
            ),
            "version" to 1,
            "gradedReceiptsFloor" to 25,
            "gradedItemLinesFloor" to 300,
            "receipts" to entries,
        )

        val out = File(root, "testdata/receipts/manifest.json")
        // LF explicitly: System.lineSeparator() writes CRLF on Windows, which
        // .gitattributes then normalises on commit -- so the file shows as
        // modified after every run for no real change.
        out.writeText(
            groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest))
                .replace("\r\n", "\n") + "\n",
        )
        logger.lifecycle(
            "regenerateReceiptManifest: ${entries.size} receipt(s), " +
                "${entries.sumOf { it["itemLineCount"] as Int }} ITEM line(s) -> ${out.name}",
        )
    }
}

tasks.register("incrementVersionCode") {
    group = "versioning"
    description = "Increments versionCode in version.properties by 1."

    val versionFile = layout.projectDirectory.file("version.properties").asFile
    outputs.upToDateWhen { false }

    doLast {
        val lines = versionFile.readLines()
        val regex = Regex("""^versionCode=(\d+)\s*$""")

        val old = requireNotNull(
            lines.firstNotNullOfOrNull { regex.find(it)?.groupValues?.get(1)?.toInt() },
        ) { "No versionCode= line in ${versionFile.name}" }

        val updated = lines.map { line ->
            if (regex.matches(line)) "versionCode=${old + 1}" else line
        }
        // LF explicitly, not System.lineSeparator(). On Windows the latter
        // writes CRLF, which .gitattributes then normalises on commit -- so the
        // file shows as modified after every run for no real change.
        versionFile.writeText(updated.joinToString("\n") + "\n")
        logger.lifecycle("versionCode: $old -> ${old + 1}")
    }
}
