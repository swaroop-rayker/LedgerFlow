package com.ledgerflow.feature.settings.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.feature.settings.MoreUiState
import com.ledgerflow.feature.settings.backupSubtitle
import java.util.Locale
import org.junit.Test

/**
 * What the backup screen and its Settings row say.
 *
 * The property that matters most is on the failures: **each one says that
 * nothing was backed up.** A backup screen that leaves the user unsure whether
 * a backup exists is the failure this feature most needs to avoid.
 */
class BackupMessagesTest {

    private val failures = listOf(
        BackupOutcome.NotThisVaultsPhrase,
        BackupOutcome.PhraseCheckUnavailable,
        BackupOutcome.NoBackupFolder,
        BackupOutcome.VaultClosed,
        BackupOutcome.WriteFailed,
    )

    @Test
    fun everyFailure_saysNothingWasBackedUp() {
        failures.forEach { outcome ->
            assertWithMessage(outcome.toString()).that(outcome.message())
                .containsMatch("[Nn]othing was (backed up|saved)")
        }
    }

    /** And a failed write says the earlier backups survived it — rotation waits for success. */
    @Test
    fun aFailedWrite_saysEarlierBackupsAreUntouched() {
        assertThat(BackupOutcome.WriteFailed.message()).contains("earlier backups are untouched")
    }

    @Test
    fun aRejectedPhrase_pointsAtTheWord() {
        assertThat(BackupOutcome.PhraseRejected(PhraseValidation.UnknownWord("abandom", 7)).message())
            .contains("Word 7")
    }

    /** The ledger being safe and every receipt being safe are different claims. */
    @Test
    fun aDoneWithMissedImages_saysHowMany() {
        val message = BackupOutcome.Done(
            fileName = "ledgerflow-20260918-101500.lfbk",
            rows = 12,
            imagesWritten = 2,
            imagesAlreadyThere = 5,
            imagesUnreadable = 1,
            imagesFailed = 0,
            olderBackupsRemoved = 1,
        ).message()

        assertThat(message).contains("ledgerflow-20260918-101500.lfbk")
        assertThat(message).contains("2 new, 5 already there")
        assertThat(message).contains("1 couldn't be copied")
        assertThat(message).contains("1 older backup removed")
    }

    // ─── The Settings row ───────────────────────────────────────────────────

    @Test
    fun neverBackedUp_saysWhatThatMeans() {
        assertThat(backupSubtitle(MoreUiState(lastBackupAt = null)))
            .isEqualTo("No backup yet. Your data exists only on this phone.")
    }

    @Test
    fun afterABackup_namesTheDate() {
        val subtitle = backupSubtitle(MoreUiState(lastBackupAt = 1_758_189_300_000L), Locale.UK)

        assertThat(subtitle).startsWith("Last backup ")
        assertThat(subtitle).contains("2025")
        assertThat(subtitle).contains("Asks for your 24 words")
    }

    // ─── Nightly backups (ADR-0027) ─────────────────────────────────────────

    /** With nightly backups on, "asks for your 24 words" is the wrong expectation. */
    @Test
    fun withNightlyBackupsOn_theRowSaysThePhoneDoesItItself() {
        val subtitle = backupSubtitle(
            MoreUiState(lastBackupAt = 1_758_189_300_000L, nightlyBackupsEnabled = true),
            Locale.UK,
        )

        assertThat(subtitle).contains("Backs up nightly on its own")
        assertThat(subtitle).doesNotContain("Asks for your 24 words")
    }

    /** Said once, on the backup that enrolled — not on every backup after it. */
    @Test
    fun theBackupThatEnrols_saysNightlyBackupsAreOn() {
        val enrolling = done(nightlyBackupsJustEnabled = true).message()
        val ordinary = done(nightlyBackupsJustEnabled = false).message()

        assertThat(enrolling).contains("Nightly backups are on from now on")
        assertThat(ordinary).doesNotContain("Nightly backups")
    }

    private fun done(nightlyBackupsJustEnabled: Boolean) = BackupOutcome.Done(
        fileName = "ledgerflow-20260920-101500.lfbk",
        rows = 12,
        imagesWritten = 0,
        imagesAlreadyThere = 0,
        imagesUnreadable = 0,
        imagesFailed = 0,
        olderBackupsRemoved = 0,
        nightlyBackupsJustEnabled = nightlyBackupsJustEnabled,
    )
}
