package com.ledgerflow.core.domain.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The reminder rule: no backup, or none in more than seven days (owner, 2026-09-19). */
class BackupReminderTest {

    private val now = 1_790_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun noBackupAtAll_isReminded() {
        assertThat(BackupReminder.of(lastBackupAt = null, now = now)).isEqualTo(BackupReminder.NeverBackedUp)
    }

    @Test
    fun exactlySevenDays_isNotYetStale_aMomentMoreIs() {
        assertThat(BackupReminder.of(now - 7 * day, now)).isNull()
        assertThat(BackupReminder.of(now - 7 * day - 1, now)).isEqualTo(BackupReminder.Stale(daysAgo = 7))
    }

    @Test
    fun theDaysAreWholeDays() {
        assertThat(BackupReminder.of(now - 12 * day - 5 * 60 * 60 * 1000L, now)).isEqualTo(BackupReminder.Stale(12))
    }

    @Test
    fun aRecentBackup_isNotReminded() {
        assertThat(BackupReminder.of(now - day, now)).isNull()
    }

    /** A clock set back since the backup: recent by any honest reading, never stale. */
    @Test
    fun aBackupDatedInTheFuture_isNotStale() {
        assertThat(BackupReminder.of(now + day, now)).isNull()
    }
}
