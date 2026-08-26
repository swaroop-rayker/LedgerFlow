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
 * D-04, enforced instead of remembered (SPEC.md §3.1, CLAUDE.md §9).
 *
 * `RECEIVE_SMS` is Play-restricted. The entire reason the `smsFull`/`playSafe`
 * split exists is that it appears in exactly one flavour's manifest, and the
 * failure mode is a single misplaced line: merged into `src/main`, it reaches
 * `playSafe` too and the Play-eligible build stops being submittable. Nothing
 * about that shows up as a broken build, a failing test, or a visible symptom on
 * a device — it surfaces months later as a rejected release.
 *
 * Law 6 is checked here for the same reason and with wider scope than CI's
 * `app/src/release` grep: no `INTERNET` permission in ANY source set of ANY
 * module. All parsing, OCR and analytics are on-device, and a library module
 * declaring it would merge into the release manifest just as effectively.
 *
 * Mirrored by a step in .github/workflows/ci.yml so local and CI failures match.
 */
tasks.register("restrictedPermissionCheck") {
    group = "verification"
    description = "RECEIVE_SMS only in smsFull; INTERNET nowhere (SPEC.md §3.1 D-04, Law 6)."

    val sourceRoots = subprojects
        .filter { it.buildFile.exists() }
        .map { it.projectDir }
    outputs.upToDateWhen { false }

    doLast {
        // Anchored on the element, not on the bare permission name: these files
        // are heavily commented and several of them have to NAME the permission
        // in order to explain where it may not go. A prose mention is not a
        // declaration, and a guard that cannot tell the difference gets muted.
        // Same expression as the CI step, deliberately.
        val restrictedSms = Regex("""<uses-permission[^>]*android\.permission\.(RECEIVE|READ)_SMS""")
        val internet = Regex("""<uses-permission[^>]*android\.permission\.INTERNET""")
        val violations = mutableListOf<String>()

        sourceRoots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .filterNot { "/build/" in it.invariantSeparatorsPath }
                .forEach { manifest ->
                    val path = manifest.invariantSeparatorsPath
                    val inSmsFullSourceSet = "/src/smsFull/" in path
                    manifest.readLines().forEachIndexed { index, line ->
                        if (restrictedSms.containsMatchIn(line) && !inSmsFullSourceSet) {
                            violations += "$path:${index + 1}: RECEIVE_SMS/READ_SMS are " +
                                "Play-restricted and belong in src/smsFull/ only -- " +
                                "D-04 / SPEC.md §3.1"
                        }
                        if (internet.containsMatchIn(line)) {
                            violations += "$path:${index + 1}: INTERNET is banned in every flavour; " +
                                "all parsing and analytics are on-device -- Law 6"
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            violations.forEach { logger.error("::error::$it") }
            throw GradleException("restrictedPermissionCheck found ${violations.size} violation(s).")
        }
        logger.lifecycle("restrictedPermissionCheck: clean.")
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
    dependsOn(":app:assembleSmsFullDebug", ":app:assemblePlaySafeDebug")
    dependsOn(":app:lintSmsFullDebug", ":app:lintPlaySafeDebug")

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
