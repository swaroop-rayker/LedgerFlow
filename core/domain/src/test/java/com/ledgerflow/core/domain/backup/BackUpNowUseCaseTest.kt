package com.ledgerflow.core.domain.backup

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CLAUDE.md §7: **the checksum before the KDF.** The repository's check — do
 * these words open this vault — costs 2048 rounds of HMAC-SHA512; a typo must
 * be rejected before it, or it looks like a hang.
 */
class BackUpNowUseCaseTest {

    private var verdict: PhraseValidation = PhraseValidation.Valid

    private val validator = object : RecoveryPhraseValidator {
        override val wordCount: Int = 24
        override fun warmUp() = Unit
        override fun validate(words: List<String>): PhraseValidation = verdict
        override fun isKnownWord(word: String): Boolean = true
        override fun suggestions(prefix: String, limit: Int): List<String> = emptyList()
        override fun parse(input: String): List<String> = input.split(" ")
    }

    private val repository = object : BackupRepository {
        var calls = 0
        override suspend fun backUpNow(words: List<String>): BackupOutcome {
            calls++
            return BackupOutcome.NoBackupFolder
        }
        override fun lastBackupAt(): Flow<Long?> = flowOf(null)
        override suspend fun backupFolderName(): String? = null
        override suspend fun setBackupFolder(treeUri: String) = Unit
        override fun nightlyBackupsEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun backUpNightly(): NightlyBackupOutcome =
            NightlyBackupOutcome.Skipped(NightlyBackupOutcome.SkipReason.NotEnrolled)
    }

    private val useCase = BackUpNowUseCase(validator, repository)
    private val words = List(24) { "abandon" }

    @Test
    fun aBadChecksum_neverReachesTheRepository() = runTest {
        verdict = PhraseValidation.ChecksumMismatch

        val outcome = useCase(words)

        assertThat(outcome).isEqualTo(BackupOutcome.PhraseRejected(PhraseValidation.ChecksumMismatch))
        assertThat(repository.calls).isEqualTo(0)
    }

    @Test
    fun anUnknownWord_isReportedWithItsPosition() = runTest {
        verdict = PhraseValidation.UnknownWord("abandom", position = 3)

        assertThat(useCase(words))
            .isEqualTo(BackupOutcome.PhraseRejected(PhraseValidation.UnknownWord("abandom", 3)))
        assertThat(repository.calls).isEqualTo(0)
    }

    @Test
    fun aValidPhrase_isHandedOn() = runTest {
        assertThat(useCase(words)).isEqualTo(BackupOutcome.NoBackupFolder)
        assertThat(repository.calls).isEqualTo(1)
    }
}
