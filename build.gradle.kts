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
    dependsOn(":app:assembleSmsFullDebug", ":app:assemblePlaySafeDebug")
    dependsOn(":app:lintSmsFullDebug", ":app:lintPlaySafeDebug")
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
