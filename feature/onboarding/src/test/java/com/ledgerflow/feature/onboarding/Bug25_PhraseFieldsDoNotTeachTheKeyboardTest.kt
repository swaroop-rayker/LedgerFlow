package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * **Every text field that receives a recovery-phrase word uses
 * `LfKeyboards.RecoveryWord`** (BUG25).
 *
 * A source guard rather than a UI test, deliberately. What the option changes
 * is the `EditorInfo` handed to the keyboard, which no screenshot and no
 * semantics node shows: a field that forgets it renders identically, accepts
 * the same input, and lets Gboard add the user's 24 words to a personal
 * dictionary that may sync off the device. So the property is asserted where
 * it lives — at the call site.
 *
 * Two shapes are covered. A file that **builds** a phrase field must pass the
 * option on every one. A screen that takes the phrase through the shared
 * `LfPhraseEntry` gets the option for free, and must not build a bare field
 * beside it that would not.
 *
 * A new place that takes phrase words must be added to [FIELD_BUILDERS] or
 * [SHARED_ENTRY_USERS]; the guard cannot know about a file it was never told
 * to read.
 */
class Bug25_PhraseFieldsDoNotTeachTheKeyboardTest {

    private companion object {
        /** Files that build a phrase field themselves. Each field must pass the option. */
        val FIELD_BUILDERS = listOf(
            "feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/OnboardingScreen.kt",
            "core/ui/src/main/java/com/ledgerflow/core/ui/phrase/LfPhraseEntry.kt",
        )

        /** Screens that take the phrase through the shared `LfPhraseEntry`. */
        val SHARED_ENTRY_USERS = listOf(
            "feature/onboarding/src/main/java/com/ledgerflow/feature/onboarding/recovery/RecoveryScreen.kt",
        )
    }

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private fun source(relative: String): String = File(repositoryRoot, relative).readText()

    /**
     * Each `LfTextField(` call, as the text up to its closing parenthesis at
     * the same depth. Counting parentheses rather than matching a regex keeps a
     * multi-line call with nested lambdas in one piece.
     */
    private fun textFieldCalls(code: String): List<String> {
        val calls = mutableListOf<String>()
        var from = code.indexOf("LfTextField(")
        while (from >= 0) {
            val end = closingParenthesis(code, from)
            calls += code.substring(from, end + 1)
            from = code.indexOf("LfTextField(", end)
        }
        return calls
    }

    /** Index of the `)` that closes the first `(` at or after [from]. */
    private fun closingParenthesis(code: String, from: Int): Int {
        var depth = 0
        for (i in from until code.length) {
            if (code[i] == '(') depth++
            if (code[i] == ')' && --depth == 0) return i
        }
        return code.length - 1
    }

    @Test
    fun everyPhraseField_usesTheRecoveryWordKeyboard() {
        FIELD_BUILDERS.forEach { file ->
            val calls = textFieldCalls(source(file))

            assertWithMessage("%s: no LfTextField found -- has the field moved?", file)
                .that(calls).isNotEmpty()
            calls.forEach { call ->
                assertWithMessage(
                    "%s: an LfTextField without keyboardOptions = LfKeyboards.RecoveryWord " +
                        "lets the keyboard learn the recovery phrase:\n%s",
                    file,
                    call,
                ).that(call).contains("keyboardOptions = LfKeyboards.RecoveryWord")
            }
        }
    }

    @Test
    fun screensOnTheSharedEntry_buildNoFieldOfTheirOwn() {
        SHARED_ENTRY_USERS.forEach { file ->
            val code = source(file)
            assertWithMessage("%s: expected to take the phrase through LfPhraseEntry", file)
                .that(code).contains("LfPhraseEntry(")
            assertWithMessage("%s: builds its own LfTextField beside the shared entry", file)
                .that(textFieldCalls(code)).isEmpty()
        }
    }
}
