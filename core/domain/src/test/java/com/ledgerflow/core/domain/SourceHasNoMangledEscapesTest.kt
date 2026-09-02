package com.ledgerflow.core.domain

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * **Bug18: a mangled escape silently disarmed a Law 2 guard.**
 *
 * `LedgerIsolationTest.noQueryTouchesDailyRollupWithoutBindingALedger` was
 * written at v9 as `Regex("""\bdaily_rollup\b""")`. What reached the file was
 * byte `0x08` — an actual **backspace character** — where `\b` was meant, because
 * the editing tool that wrote it interpreted the escape (`KICKOFF-P3.md` §8
 * warns about exactly this and it happened anyway). The regex therefore matched
 * "backspace, daily_rollup, backspace", which no SQL string on earth contains.
 *
 * The consequences are the reason this test exists rather than a code comment:
 *
 * - **The guard passed.** It ran, reported green, and could not fail. A DAO
 *   query summing both books out of `daily_rollup` would have shipped.
 * - **Nothing else noticed.** It compiles, detekt is clean, ktlint is clean, and
 *   the diff reads correctly in every viewer, because a lone `0x08` renders as
 *   nothing at all. `od -c` even prints it as `\b`, which is how the first
 *   attempt to verify it concluded the bytes were fine.
 * - **It was found only by deliberate sabotage** — writing the offending query
 *   and observing that the guard stayed green. `CLAUDE.md` §11's "make it fail
 *   on purpose" is what caught this, and this test is what makes the next one
 *   cheaper to catch.
 *
 * So: no source file in the build may contain a C0 control character other than
 * tab and newline. That is a property no legitimate Kotlin source violates, and
 * a mangled `\b`, `\f`, `\v`, `\a` or `\e` violates it by construction. It does
 * not catch every mangled escape — `\n` written as a real newline inside a raw
 * string is still legal text — but it catches the whole family that is
 * invisible, and invisibility is what made this one dangerous.
 *
 * Scanning source rather than checking one regex, for the reason
 * `LedgerIsolationTest` records about itself: the interesting fact is a property
 * of the file, and a rule aimed at one known instance stops being a guard the
 * moment the next instance lands somewhere else.
 */
class SourceHasNoMangledEscapesTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    /** Every Kotlin source in the build — main, test and androidTest alike. */
    private fun kotlinSources(): List<File> =
        listOf("core", "feature", "app", "benchmark", "build-logic")
            .map { File(repositoryRoot, it) }
            .filter { it.isDirectory }
            .flatMap { area ->
                area.walkTopDown()
                    .onEnter { it.name != "build" }
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }

    @Test
    fun kotlinSources_areDiscoverable() {
        // A silent zero would make the assertion below vacuously true, which is
        // precisely the failure this whole test exists to describe.
        assertThat(kotlinSources().size).isGreaterThan(MINIMUM_EXPECTED_SOURCES)
    }

    /**
     * **Carriage return is deliberately not an offender, and the reason is one
     * `CLAUDE.md` §11 already paid for.**
     *
     * The first version of this test flagged `U+000D` at line 1 of dozens of
     * files and looked like it had found a repository-wide CRLF disaster. It had
     * not. The committed blobs are LF — `git cat-file -p` on any of them shows
     * `0a` — and CI, which checks out fresh, therefore sees LF. What has CRLF is
     * the *working tree* on this Windows box, and `.gitattributes`'
     * `* text=auto eol=lf` normalises it away on commit, which is why `git
     * status` is clean and nothing downstream ever notices.
     *
     * That is precisely §11's warning that a CRLF check against the working tree
     * reports a failure that does not exist. Line endings are owned by
     * `.gitattributes` and by the `guards` CI job; this test is about invisible
     * *escapes*, so it steps over `\r` rather than re-litigating them.
     */
    @Test
    fun noKotlinSourceContainsAnInvisibleControlCharacter() {
        val offenders = kotlinSources().mapNotNull { file ->
            val text = file.readText()
            val index = text.indexOfFirst {
                it.code < 0x20 && it != '\n' && it != '\t' && it != '\r'
            }
            if (index < 0) {
                null
            } else {
                val line = text.take(index).count { it == '\n' } + 1
                val code = text[index].code
                "${file.relativeTo(repositoryRoot).path}:$line: U+%04X".format(code)
            }
        }

        assertThat(offenders).isEmpty()
    }

    private companion object {
        /**
         * Well below the real count, which is in the hundreds. The number only
         * has to be large enough that an empty walk cannot pass.
         */
        const val MINIMUM_EXPECTED_SOURCES = 100
    }
}
