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
