package com.ledgerflow.feature.ingest.adapters

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * §5.2's privacy guarantee, enforced mechanically.
 *
 * > The notification package allowlist filter runs **before** any notification
 * > body is read. Never log or persist content from a non-allowlisted package —
 * > this is a stated privacy guarantee, not an implementation detail.
 * > — CLAUDE.md §7
 *
 * This is a promise made to the user in the permission explainer and in
 * Settings, and it has one line of code holding it up: the `isPackageAllowed`
 * check must come before anything touches `notification.extras`. Reversing those
 * two would compile, pass every behavioural test, capture exactly the same rows
 * for allowlisted packages, and quietly read every notification on the phone.
 * Nothing but source order distinguishes the correct version from the broken
 * one, so source order is what is asserted.
 *
 * Scanning rather than behaviour, for the reason `LedgerIsolationTest` records:
 * the interesting fact is *where* a call sits, which survives in source and not
 * in a signature. A test that fed the service a notification could only ever
 * show that allowlisted packages are captured — it could not show that
 * non-allowlisted ones were never read, because "never read" leaves no trace.
 */
class NotificationAllowlistOrderTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    private val serviceSource: String by lazy {
        val file = File(
            repositoryRoot,
            "feature/ingest/src/main/java/com/ledgerflow/feature/ingest/adapters/" +
                "NotificationIngestService.kt",
        )
        check(file.isFile) { "NotificationIngestService.kt not found at ${file.path}" }
        file.readText()
    }

    /**
     * The body of one function, by brace matching.
     *
     * **Ordering has to be asserted inside the function, not across the file.**
     * The first version of this test compared character offsets in the whole
     * source, which meant it was really asserting that the private extraction
     * helper is declared below `onNotificationPosted` — true regardless of when
     * it is *called*. It passed against a planted violation that read the
     * notification first and filtered afterwards. This is the same string-proxy
     * mistake ADR-0002's amendment records, and it is why the check now works on
     * the function body.
     */
    private fun bodyOf(signature: String): String {
        val start = serviceSource.indexOf(signature)
        check(start >= 0) { "Function not found: $signature" }
        val open = serviceSource.indexOf('{', start)
        var depth = 0
        var index = open
        while (index < serviceSource.length) {
            when (serviceSource[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return serviceSource.substring(open, index + 1)
                }
            }
            index++
        }
        error("Unbalanced braces after $signature")
    }

    /**
     * Code only, comments removed.
     *
     * These assertions look for identifiers by name, and the KDoc and inline
     * comments in the service discuss those same identifiers at length — a
     * `doesNotContain("extras")` run over raw text fails on the comment that
     * explains why `extras` is not read there. Prose is not a read.
     */
    private fun codeOf(text: String): String = text.lines()
        .filterNot {
            val trimmed = it.trimStart()
            trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }
        .joinToString(separator = LINE_BREAK)

    /** A silent zero would make every assertion below vacuously true. */
    @Test
    fun theServiceSourceIsDiscoverable() {
        assertThat(serviceSource).contains("class NotificationIngestService")
        assertThat(bodyOf("override fun onNotificationPosted")).isNotEmpty()
    }

    /**
     * **The assertion.** Inside `onNotificationPosted`, the allowlist gate comes
     * before the call that reads the notification.
     */
    @Test
    fun theAllowlistCheckPrecedesTheContentRead() {
        val body = codeOf(bodyOf("override fun onNotificationPosted"))

        val gate = body.indexOf("isPackageAllowed(packageName)")
        val read = body.indexOf("toIngestEvent(")

        assertThat(gate).isGreaterThan(-1)
        assertThat(read).isGreaterThan(-1)
        assertThat(gate).isLessThan(read)
    }

    /**
     * `onNotificationPosted` itself never touches the notification's contents.
     *
     * The extraction is funnelled through one private helper so that the
     * ordering assertion above has a single thing to be ordered against. A read
     * added directly to the entry point would slip past it, so the entry point
     * is asserted to contain none.
     */
    @Test
    fun theEntryPointReadsNothingButMetadata() {
        val body = codeOf(bodyOf("override fun onNotificationPosted"))

        listOf("extras", "EXTRA_", "notification.", "notification?.").forEach { access ->
            assertThat(body).doesNotContain(access)
        }
    }

    /**
     * And the helper is the only place in the class that reads them, so there is
     * exactly one path to guard.
     */
    @Test
    fun onlyTheExtractionHelperReadsTheNotification() {
        val helper = bodyOf("private fun StatusBarNotification.toIngestEvent")
        val elsewhere = codeOf(serviceSource.replace(helper, ""))

        listOf("extras", "EXTRA_TITLE", "EXTRA_TEXT", "EXTRA_BIG_TEXT", "EXTRA_SUB_TEXT")
            .forEach { access -> assertThat(elsewhere).doesNotContain(access) }
    }

    /**
     * The gate guards by returning, not by widening.
     *
     * `if (!isPackageAllowed(...)) return@launch` refuses; a positive `if` with
     * the capture inside would too, but the negated early return is what keeps
     * the reading code out of any branch a later edit could accidentally
     * broaden.
     */
    @Test
    fun theGateIsAnEarlyReturn() {
        assertThat(serviceSource).contains("if (!isPackageAllowed(packageName)) return@launch")
    }

    private companion object {
        /** Only used to rejoin lines for a `contains` check; the separator is irrelevant. */
        val LINE_BREAK: String = System.lineSeparator()
    }

    /**
     * Nothing derived from a notification's contents is logged, at any level.
     *
     * The service logs connection lifecycle and failures only. A `Log` call
     * carrying the body or the title would persist content to logcat, which is
     * still the user's device and still covered by the rule above.
     */
    @Test
    fun noLogStatementCarriesNotificationContent() {
        val logCalls = Regex("""Log\.[a-z]\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(serviceSource)
            .map { it.groupValues[1] }
            .toList()

        assertThat(logCalls).isNotEmpty()
        logCalls.map(::codeOf).forEach { call ->
            listOf("body", "title", "extras", "event.", "sbn.", "posted.").forEach { forbidden ->
                assertThat(call).doesNotContain(forbidden)
            }
        }
    }
}
