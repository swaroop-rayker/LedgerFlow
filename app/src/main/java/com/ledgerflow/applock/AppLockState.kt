package com.ledgerflow.applock

import com.ledgerflow.core.domain.security.SecurityPreferences
import com.ledgerflow.core.domain.security.shouldLock

/**
 * The app lock's memory for one process (SPEC §7.6, V2-PLAN §3.5).
 *
 * Pure, so the rules are tested without a device:
 *
 * - **A cold start needs an unlock**, if the lock is on.
 * - **Turning the lock on while using the app does not lock it there and
 *   then**: [preferencesChanged] with the lock off clears the need, so the
 *   lock first applies the next time the app is away long enough or starts
 *   cold. Locking the user out of the screen they just used would read as a
 *   fault.
 * - **Away and back**: [backgrounded] notes when, [foregrounded] asks
 *   `shouldLock` whether long enough has passed.
 * - **While the prompt is up, coming and going is not "away"**: on Android 9
 *   and older the PIN screen is another activity, and treating it as leaving
 *   the app would lock again behind the user's own unlock.
 *
 * Times are a monotonic clock (`elapsedRealtime`), so changing the phone's
 * clock moves nothing.
 */
internal class AppLockState {

    var needsUnlock: Boolean = true
        private set

    var authenticating: Boolean = false
        private set

    private var backgroundedAt: Long? = null

    fun preferencesChanged(prefs: SecurityPreferences) {
        if (!prefs.appLock) {
            needsUnlock = false
            backgroundedAt = null
        }
    }

    fun backgrounded(now: Long) {
        if (authenticating || needsUnlock) return
        backgroundedAt = now
    }

    fun foregrounded(prefs: SecurityPreferences, now: Long) {
        if (authenticating) return
        val away = backgroundedAt ?: return
        if (shouldLock(prefs, away, now)) needsUnlock = true
        backgroundedAt = null
    }

    fun promptShown() {
        authenticating = true
    }

    /** The phone's prompt said yes. */
    fun unlocked() {
        authenticating = false
        needsUnlock = false
        backgroundedAt = null
    }

    /** Cancelled or failed: still locked, and the next tap on Unlock asks again. */
    fun promptClosed() {
        authenticating = false
    }

    /** Whether to draw the lock: on, a screen lock to lift it with, and not yet unlocked. */
    fun isLocked(prefs: SecurityPreferences, phoneHasScreenLock: Boolean): Boolean =
        prefs.appLock && phoneHasScreenLock && needsUnlock
}
