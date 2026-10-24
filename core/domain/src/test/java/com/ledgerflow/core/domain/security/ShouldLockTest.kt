package com.ledgerflow.core.domain.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The app lock's one decision (SPEC §7.6, V2-PLAN §3.5). */
class ShouldLockTest {

    private val on = SecurityPreferences(appLock = true, lockAfter = LockTimeout.FIVE_MINUTES)

    @Test
    fun off_neverLocks() {
        val off = SecurityPreferences(appLock = false)
        assertThat(shouldLock(off, backgroundedAtMillis = null, nowMillis = 0)).isFalse()
        assertThat(shouldLock(off, 0, 10_000_000)).isFalse()
    }

    @Test
    fun aColdStart_locks() {
        assertThat(shouldLock(on, backgroundedAtMillis = null, nowMillis = 0)).isTrue()
    }

    @Test
    fun awayLessThanTheTimeout_doesNotLock_andAtItDoes() {
        assertThat(shouldLock(on, 1_000, 1_000 + 299_999)).isFalse()
        assertThat(shouldLock(on, 1_000, 1_000 + 300_000)).isTrue()
    }

    @Test
    fun immediately_locksOnAnyAbsence() {
        val now = on.copy(lockAfter = LockTimeout.IMMEDIATELY)
        assertThat(shouldLock(now, 5_000, 5_000)).isTrue()
    }

    @Test
    fun theDefaults_areOffAndFiveMinutes() {
        assertThat(SecurityPreferences()).isEqualTo(
            SecurityPreferences(appLock = false, lockAfter = LockTimeout.FIVE_MINUTES, blockScreenshots = false),
        )
        assertThat(LockTimeout.entries.map { it.millis }).containsExactly(0L, 60_000L, 300_000L, 900_000L).inOrder()
    }
}
