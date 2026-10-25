package com.ledgerflow.applock

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.security.LockTimeout
import com.ledgerflow.core.domain.security.SecurityPreferences
import java.io.File
import org.junit.Test

/**
 * The app lock's rules for one process (SPEC §7.6, V2-PLAN §3.5), and the
 * property that makes it safe: it gates the screen and reaches nothing else.
 */
class AppLockStateTest {

    private val on = SecurityPreferences(appLock = true, lockAfter = LockTimeout.FIVE_MINUTES)
    private val off = SecurityPreferences(appLock = false)

    @Test
    fun aColdStartWithTheLockOn_isLocked_untilUnlocked() {
        val state = AppLockState()
        state.preferencesChanged(on)
        assertThat(state.isLocked(on, phoneHasScreenLock = true)).isTrue()
        state.promptShown()
        state.unlocked()
        assertThat(state.isLocked(on, phoneHasScreenLock = true)).isFalse()
    }

    @Test
    fun turningTheLockOnInTheApp_doesNotLockItThere() {
        val state = AppLockState()
        state.preferencesChanged(off)
        state.preferencesChanged(on)
        assertThat(state.isLocked(on, phoneHasScreenLock = true)).isFalse()
    }

    @Test
    fun awayPastTheTimeout_locks_andAShortAbsenceDoesNot() {
        val state = unlockedWith(on)
        state.backgrounded(now = 0)
        state.foregrounded(on, now = 60_000)
        assertThat(state.isLocked(on, true)).isFalse()

        state.backgrounded(now = 100_000)
        state.foregrounded(on, now = 100_000 + 300_000)
        assertThat(state.isLocked(on, true)).isTrue()
    }

    @Test
    fun thePromptsOwnScreen_isNotBeingAway() {
        // Android 8-9: the PIN screen is another activity, so the process
        // stops and starts around it. That must not lock again.
        val state = AppLockState()
        state.preferencesChanged(on.copy(lockAfter = LockTimeout.IMMEDIATELY))
        state.promptShown()
        state.backgrounded(now = 0)
        state.foregrounded(on.copy(lockAfter = LockTimeout.IMMEDIATELY), now = 10_000)
        state.unlocked()
        assertThat(state.isLocked(on, true)).isFalse()
    }

    @Test
    fun aCancelledPrompt_staysLocked() {
        val state = AppLockState()
        state.preferencesChanged(on)
        state.promptShown()
        state.promptClosed()
        assertThat(state.authenticating).isFalse()
        assertThat(state.isLocked(on, true)).isTrue()
    }

    @Test
    fun withNoScreenLockOnThePhone_itNeverLocks() {
        // Nothing could lift it; a UI gate must never strand the user.
        val state = AppLockState()
        state.preferencesChanged(on)
        assertThat(state.isLocked(on, phoneHasScreenLock = false)).isFalse()
    }

    @Test
    fun theLock_reachesNothingButTheScreen() {
        // SPEC §7.6 / CLAUDE.md §7: it gates UI access only, never the DEK.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = File(root, "app/src/main/java/com/ledgerflow/applock").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertThat(sources.map { it.name }).contains("AppLockController.kt")
        val forbidden = listOf(
            "com.ledgerflow.core.crypto",
            "com.ledgerflow.core.database",
            "com.ledgerflow.core.data.vault",
            "java.security.KeyStore",
            "setUserAuthenticationRequired",
        )
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { term ->
                assertWithMessage("${file.name} mentions $term").that(text).doesNotContain(term)
            }
        }
    }

    private fun unlockedWith(prefs: SecurityPreferences) = AppLockState().apply {
        preferencesChanged(prefs)
        promptShown()
        unlocked()
    }
}
