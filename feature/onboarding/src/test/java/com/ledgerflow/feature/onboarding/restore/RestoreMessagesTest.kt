package com.ledgerflow.feature.onboarding.restore

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.vault.PhraseValidation
import java.util.TimeZone
import org.junit.Test

/**
 * What the restore screen says. The rule these pin: **every refusal that wrote
 * nothing says "Nothing was restored"**, because a user who cannot tell whether
 * their phone now holds part of their data will not know what to do next.
 */
class RestoreMessagesTest {

    private val nothingWritten = listOf(
        RestoreOutcome.WrongPhrase,
        RestoreOutcome.Damaged,
        RestoreOutcome.NewerVersion(backupVersion = 12, supported = 11),
        RestoreOutcome.SourceUnreadable,
        RestoreOutcome.AlreadySetUp,
    )

    @Test
    fun everyRefusalThatWroteNothing_saysSo() {
        nothingWritten.forEach { outcome ->
            assertWithMessage(outcome.toString()).that(outcome.message()).contains("Nothing was restored")
        }
    }

    /** The interrupted case did write something, so it must not claim otherwise. */
    @Test
    fun theFailuresAfterWriting_doNotClaimNothingHappened() {
        listOf(RestoreOutcome.Failed, RestoreOutcome.NotTheInterruptedRestoresPhrase).forEach { outcome ->
            assertWithMessage(outcome.toString()).that(outcome.message()).doesNotContain("Nothing was restored")
            assertThat(outcome.message()).contains("same")
        }
    }

    @Test
    fun aRejectedPhrase_namesTheWord() {
        val message = RestoreOutcome.PhraseRejected(PhraseValidation.UnknownWord("abandom", 3)).message()

        assertThat(message).contains("Word 3")
        assertThat(message).contains("abandom")
    }

    /** ADR-0023's sentence, with a number in it. */
    @Test
    fun theReport_countsTheImagesThatDidNotCome() {
        val message = RestoreOutcome.Done(
            rows = 412,
            imagesRestored = 9,
            imagesNotFound = 3,
            imagesUnreadable = 1,
            imagesFailed = 0,
        ).message()

        assertThat(message).contains("412 records restored")
        assertThat(message).contains("9 receipt images restored")
        assertThat(message).contains("3 receipt images weren't found alongside this backup")
        assertThat(message).contains("1 receipt image couldn't be opened")
        assertThat(message).doesNotContain("couldn't be saved")
    }

    @Test
    fun theReport_isSingularForOne() {
        val message = RestoreOutcome.Done(
            rows = 1,
            imagesRestored = 0,
            imagesNotFound = 1,
            imagesUnreadable = 0,
            imagesFailed = 0,
        ).message()

        assertThat(message).contains("1 record restored")
        assertThat(message).contains("1 receipt image wasn't found")
    }

    /** "Back up now"'s UTC stamp, shown in the zone the user took it in. */
    @Test
    fun aBackupName_isShownAsItsLocalTime() {
        val label = backupLabel("ledgerflow-20260918-101500.lfbk", TimeZone.getTimeZone("Asia/Kolkata"))

        assertThat(label).contains("2026")
        assertThat(label).contains(":45")
    }

    @Test
    fun anyOtherName_isShownAsItIs() {
        assertThat(backupLabel("my-backup.lfbk")).isEqualTo("my-backup.lfbk")
    }
}
