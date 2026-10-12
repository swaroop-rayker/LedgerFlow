package com.ledgerflow.core.domain.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * BUG7(c)'s privacy rule: a crash report carries code facts, never the
 * exception's message (owner's design 2026-10-12, "scrubbed, no encryption").
 *
 * The messages here are the shapes that really turn up in this app's
 * exceptions: an amount and a merchant from a parse, a SQL statement with
 * values, a word that could be from the recovery phrase.
 */
class CrashReportCarriesNoMessageTest {

    private fun report(throwable: Throwable, thread: String = "main") = CrashReportFormat.format(
        throwable = throwable,
        thread = thread,
        appVersion = "0.1.0 (1)",
        androidVersion = "16 (API 36)",
        atUtc = "2026-10-12 14:03:00",
    )

    @Test
    fun noMessageAnywhereInTheCauseChain() {
        val root = IllegalStateException("debited Rs 1,240.50 at CORNER GROCERS a/c XX6402")
        val middle = RuntimeException("INSERT INTO ledger_entry VALUES (1240, 'abandon')", root)
        val top = IllegalArgumentException("phrase word abandon at index 3", middle)

        val text = report(top)

        listOf("1,240.50", "CORNER GROCERS", "XX6402", "INSERT INTO", "abandon", "phrase word")
            .forEach { secret -> assertThat(text).doesNotContain(secret) }
    }

    @Test
    fun keepsWhatADeveloperNeeds_classesFramesAndVersions() {
        val text = report(IllegalStateException("x", RuntimeException("y")))

        assertThat(text).contains("java.lang.IllegalStateException")
        assertThat(text).contains("caused by: java.lang.RuntimeException")
        assertThat(text).contains("CrashReportCarriesNoMessageTest.keepsWhatADeveloperNeeds")
        assertThat(text).contains("app: 0.1.0 (1)")
        assertThat(text).contains("android: 16 (API 36)")
        assertThat(text).contains("time (UTC): 2026-10-12 14:03:00")
    }

    @Test
    fun aThreadNameIsNotCopied_becauseAnyLibraryCanPutAnythingThere() {
        val text = report(RuntimeException(), thread = "upload Rs 500 to CORNER GROCERS")
        assertThat(text).contains("thread: background")
        assertThat(text).doesNotContain("CORNER GROCERS")
    }

    @Test
    fun aCyclicCauseChain_ends() {
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)

        val text = report(b)

        assertThat(text.lines().count { it.startsWith("caused by:") }).isAtMost(CrashReportFormat.MAX_CAUSES)
    }
}
