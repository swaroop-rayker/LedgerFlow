package com.ledgerflow

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * **BUG60: after V2-2, four messages still sent the user to a tab that no
 * longer existed.**
 *
 * V2-2 replaced the More tab with Settings (`docs/V2-PLAN.md` D7). The rows
 * moved and the bar changed, but the empty-picker messages in the entry form
 * and the Analytics filter ("Add some in More → Organise.") and the upgrade
 * screen's advice ("export your data from More → Export") were plain strings in
 * three other modules, and nothing tied them to the tab's name. Each still
 * rendered, read naturally, and pointed at nothing.
 *
 * A source scan rather than a test per string, because the defect is the
 * *kind* of sentence, and the next one will be written somewhere none of these
 * live. Only string literals in main sources count: KDoc and comments
 * legitimately say "it was More until V2-2".
 */
class Bug60_NoTextSendsUsersToTheMoreTabTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    /** Main Kotlin sources only: these are what a user can be shown. */
    private fun mainSources(): List<File> =
        listOf("core", "feature", "app")
            .map { File(repositoryRoot, it) }
            .filter { it.isDirectory }
            .flatMap { area ->
                area.walkTopDown()
                    .onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" }
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }

    @Test
    fun mainSources_areDiscoverable() {
        // A silent zero would make the scan below vacuously green.
        assertThat(mainSources().size).isGreaterThan(MINIMUM_EXPECTED_SOURCES)
    }

    @Test
    fun noStringLiteral_namesMoreAsAPlace() {
        val offenders = mainSources().flatMap { file ->
            file.readLines().withIndex()
                .filterNot { (_, line) -> line.trimStart().let { it.startsWith("*") || it.startsWith("/") } }
                .flatMap { (index, line) ->
                    STRING_LITERAL.findAll(line)
                        .map { it.value }
                        .filter { MORE_AS_A_PLACE.containsMatchIn(it) }
                        .map { "${file.relativeTo(repositoryRoot)}:${index + 1}: $it" }
                        .toList()
                }
        }
        assertWithMessage("strings that send the user to the More tab, which is Settings since V2-2")
            .that(offenders)
            .isEmpty()
    }

    @Test
    fun thePattern_catchesTheShapesBug60Shipped() {
        // The scan is only as good as its pattern, so pin it against the real
        // sentences rather than trusting it (CLAUDE.md §11: make it fail).
        listOf(
            "\"No categories yet. Add some in More → Organise.\"",
            "\"data from More → Export before updating.\"",
            "\"open More, then Diagnostics\"",
        ).forEach { assertWithMessage(it).that(MORE_AS_A_PLACE.containsMatchIn(it)).isTrue() }
        listOf(
            "\"Add some in Settings → Organise.\"",
            "\"Load more\"",
            "\"₹5,310.00 more than 1–16 Sep\"",
        ).forEach { assertWithMessage(it).that(MORE_AS_A_PLACE.containsMatchIn(it)).isFalse() }
    }

    private companion object {
        const val MINIMUM_EXPECTED_SOURCES = 100

        /** A Kotlin string literal on one line, escapes included. */
        val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")

        /** "More" as somewhere to go: capitalised, then an arrow or after a verb of going. */
        val MORE_AS_A_PLACE =
            Regex("""\bMore\s*(?:→|->|>|,\s*then)|\b(?:in|from|open|under|to)\s+More\b""")
    }
}
