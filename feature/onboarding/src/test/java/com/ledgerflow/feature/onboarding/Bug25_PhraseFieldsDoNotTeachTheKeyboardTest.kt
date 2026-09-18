package com.ledgerflow.feature.onboarding

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * **Every text field that receives a recovery-phrase word uses
 * `LfKeyboards.RecoveryWord`.**
 *
 * A source guard rather than a UI test, deliberately. What the option changes
 * is the `EditorInfo` handed to the keyboard, which no screenshot and no
 * semantics node shows: a field that forgets it renders identically, accepts
 * the same input, and lets Gboard add the user's 24 words to a personal
 * dictionary that may sync off the device. So the property is asserted where
 * it lives — at the call site — for the two screens where the words are typed.
 *
 * A new screen that takes phrase words must be added to [PHRASE_SCREENS]; the
 * guard cannot know about a file it was never told to read, and says so.
 */
class Bug25_PhraseFieldsDoNotTeachTheKeyboardTest {

    private companion object {
        val PHRASE_SCREENS = listOf(
            "OnboardingScreen.kt",
            "recovery/RecoveryScreen.kt",
        )
        const val SOURCE_ROOT = "src/main/java/com/ledgerflow/feature/onboarding"
    }

    private fun source(relative: String): String {
        val moduleDir = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { dir -> if (File(dir, SOURCE_ROOT).isDirectory) dir else File(dir, "feature/onboarding") }
            .first { File(it, SOURCE_ROOT).isDirectory }
        return File(moduleDir, "$SOURCE_ROOT/$relative").readText()
    }

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
        PHRASE_SCREENS.forEach { screen ->
            val calls = textFieldCalls(source(screen))

            assertWithMessage("%s: no LfTextField found -- has the screen moved?", screen)
                .that(calls).isNotEmpty()
            calls.forEach { call ->
                assertWithMessage(
                    "%s: an LfTextField without keyboardOptions = LfKeyboards.RecoveryWord " +
                        "lets the keyboard learn the recovery phrase:\n%s",
                    screen,
                    call,
                ).that(call).contains("keyboardOptions = LfKeyboards.RecoveryWord")
            }
        }
    }
}
