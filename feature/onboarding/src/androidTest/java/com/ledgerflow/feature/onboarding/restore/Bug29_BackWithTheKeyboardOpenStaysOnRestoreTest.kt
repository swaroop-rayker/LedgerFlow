package com.ledgerflow.feature.onboarding.restore

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.vault.PhraseEntry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG29 — back with the keyboard open left the restore screen, and the words.
 *
 * Found on the owner's phone running `TESTING.md` D10 (2026-09-19) and confirmed
 * with one `adb` back key: after a refused restore, with the keyboard still up,
 * back went to the screen rather than the keyboard, so the screen closed and
 * took the typed words with it. The cause was ordering: the screen's back
 * handler switched off while the restore ran and back on after it, which
 * re-registered it *above* the keyboard's own callback.
 *
 * A system back **key** through the instrumentation, not a call on the
 * dispatcher: the defect lives in which window callback the platform picks, and
 * calling the app's dispatcher directly would skip exactly that choice. The
 * activity is edge-to-edge, as the app's is.
 *
 * Needs a soft keyboard. On a device where none appears (a hardware keyboard,
 * say), the keyboard cases are skipped rather than passed.
 */
@RunWith(AndroidJUnit4::class)
class Bug29_BackWithTheKeyboardOpenStaysOnRestoreTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0
    private val events = mutableListOf<RestoreEvent>()
    private var state by mutableStateOf(
        RestoreUiState(
            treeUri = "content://tree/backups",
            backups = listOf(BACKUP),
            selectedBackup = BACKUP,
            entry = PhraseEntry(requiredWordCount = WORDS),
            result = RestoreOutcome.WrongPhrase,
        ),
    )

    private fun setScreen() {
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent {
            LfTheme {
                RestoreScreen(state = state, resuming = false, onEvent = { events += it }, onBack = { backs++ })
            }
        }
    }

    private fun keyboardShown(): Boolean {
        var shown = false
        composeRule.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
            shown = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    /**
     * Focus, then an explicit request: on the owner's Samsung a test's tap
     * focuses the field and the keyboard never appears (measured,
     * `mImeWindowVis=0` throughout), so the request is made through the
     * window's insets controller, which is what the keyboard's own back
     * handling hangs off either way.
     */
    private fun openKeyboard() {
        composeRule.onNode(hasSetTextAction()).requestFocus()
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.ime())
        }
        val appeared = runCatching {
            composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { keyboardShown() }
        }.isSuccess
        assumeTrue("no soft keyboard appeared on this device", appeared)
        // The keyboard reports visible before its own back callback is in
        // place. On the unfixed code an attempt inside that window passed by
        // luck (measured: one pass, then a fail, on identical runs), because
        // the keyboard's callback then landed on top anyway. On the phone the
        // keyboard had been up through 24 words before Restore was tapped.
        Thread.sleep(KEYBOARD_SETTLE_MS)
    }

    /** What a restore attempt does to the screen: working, then an answer. */
    private fun runAnAttempt() {
        state = state.copy(isWorking = true, result = null)
        composeRule.waitForIdle()
        state = state.copy(isWorking = false, result = RestoreOutcome.WrongPhrase)
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    @Test
    fun bug29_backAfterAnAttempt_withTheKeyboardOpen_onlyClosesTheKeyboard() {
        setScreen()
        openKeyboard()
        runAnAttempt()

        pressBack()

        assertWithMessage("back left the restore screen while the keyboard was open")
            .that(backs).isEqualTo(0)
        assertWithMessage("back with the keyboard open forgot the words")
            .that(events).doesNotContain(RestoreEvent.Left)
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { !keyboardShown() }
    }

    /** The fix must not swallow back: with the keyboard closed it still leaves. */
    @Test
    fun backWithTheKeyboardClosed_leaves() {
        setScreen()
        runAnAttempt()

        pressBack()

        assertWithMessage("back with no keyboard open should return to onboarding")
            .that(backs).isEqualTo(1)
        // BUG30: the ViewModel outlives the screen, so leaving must say so.
        assertWithMessage("leaving did not tell the ViewModel to forget the words")
            .that(events).contains(RestoreEvent.Left)
    }

    /** The in-screen Back is the other way out, and forgets the same way (BUG30). */
    @Test
    fun theBackButton_leavesAndForgets() {
        setScreen()

        composeRule.onNodeWithText("Back").performClick()

        assertWithMessage("the Back button did not leave").that(backs).isEqualTo(1)
        assertWithMessage("the Back button did not tell the ViewModel to forget the words")
            .that(events).contains(RestoreEvent.Left)
    }

    /**
     * The result sits above the words, so a keyboard left open over it is how
     * the owner missed the message in the first place — and an open keyboard
     * is the precondition for BUG29.
     */
    @Test
    fun tappingRestore_closesTheKeyboard() {
        state = state.copy(entry = PhraseEntry(requiredWordCount = WORDS, words = List(WORDS) { "abandon" }))
        setScreen()
        openKeyboard()

        composeRule.onNodeWithText("Restore").performClick()

        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { !keyboardShown() }
    }

    /** Leaving mid-restore would strand the work; back waits for the answer. */
    @Test
    fun backWhileARestoreRuns_staysPut() {
        state = state.copy(isWorking = true, result = null)
        setScreen()

        pressBack()

        assertWithMessage("back left the screen while the restore was running")
            .that(backs).isEqualTo(0)
    }

    private companion object {
        const val BACKUP = "ledgerflow-20260919-120253.lfbk"
        const val WORDS = 24
        const val KEYBOARD_TIMEOUT_MS = 5_000L
        const val KEYBOARD_SETTLE_MS = 1_500L
    }
}
