package com.ledgerflow.feature.onboarding.notifications

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * §5.2's privacy hard rule, and the screen that quotes it, kept identical.
 *
 * > **Privacy hard rule:** LedgerFlow reads notifications **only** from the
 * > package allowlist. Notification content from non-allowlisted packages is
 * > never read, logged, or persisted — the filter runs before any body access.
 * > **This is stated verbatim in the permission explainer and in Settings.**
 *
 * The spec asks for "verbatim", so this reads the spec rather than a copy of it.
 * A test asserting the screen's string against a second hardcoded string would
 * be the closed loop §16 Q13 and §16 Q15 both record: internally consistent,
 * blind to the thing it is supposed to be checking, and green through any drift
 * because both halves get edited together.
 *
 * **The drift this is really guarding against runs one way.** Someone tightens
 * the implementation, updates §5.2 to match, and leaves the screen making the
 * older and weaker promise — at which point the app is telling the user
 * something less true than what it does, which is the only direction of this
 * error that matters. A check for "the screen mentions the allowlist" would pass
 * through that; only equality catches it.
 */
class PrivacyRuleIsVerbatimTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    private val specLine: String by lazy {
        File(repositoryRoot, "SPEC.md").readLines()
            .firstOrNull { it.contains(PRIVACY_RULE_MARKER) }
            ?: error("SPEC.md no longer contains a line marked \"$PRIVACY_RULE_MARKER\"")
    }

    /**
     * Guards the guard.
     *
     * If §5.2's marker is ever reworded, [specLine] throws and this suite fails
     * loudly rather than silently comparing against nothing. That failure is
     * correct and the fix is to update the marker — not to relax it, which would
     * turn the whole file back into a comparison of two copies of the same
     * string.
     */
    @Test
    fun specMarker_isStillPresent() {
        assertThat(specLine).contains(PRIVACY_RULE_MARKER)
    }

    @Test
    fun screenRule_matchesTheSpecWordForWord() {
        assertThat(ruleFromSpec()).isEqualTo(NOTIFICATION_PRIVACY_RULE)
    }

    /**
     * The spec's sentence, with the markdown taken off and the instruction
     * dropped.
     *
     * Three transformations, and each one is a deliberate narrowing rather than
     * a convenience:
     *
     * - the `**Privacy hard rule:**` label is the section's heading, not part of
     *   the promise;
     * - `**` is emphasis the screen expresses with layout instead;
     * - the final sentence — "This is stated verbatim in the permission
     *   explainer and in Settings" — is the instruction *to* this screen, and
     *   reproducing it would have the screen quoting its own requirement back at
     *   the user.
     *
     * Nothing else is touched. In particular the em-dash is left exactly as the
     * spec writes it, because normalising punctuation is how "verbatim" quietly
     * becomes "close enough".
     */
    private fun ruleFromSpec(): String = specLine
        .substringAfter(PRIVACY_RULE_MARKER)
        .replace("**", "")
        .trim()
        .substringBefore("This is stated verbatim")
        .trim()

    private companion object {
        private const val PRIVACY_RULE_MARKER = "**Privacy hard rule:**"
    }
}
