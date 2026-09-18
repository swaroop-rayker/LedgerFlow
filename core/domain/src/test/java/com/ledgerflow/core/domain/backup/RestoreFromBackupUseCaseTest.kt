package com.ledgerflow.core.domain.backup

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * CLAUDE.md §7: **the checksum before the KDF.** A restore derives the seed
 * more than once — for the backup, for the wrap, for the images — and a typo
 * must be refused before the first of them.
 */
class RestoreFromBackupUseCaseTest {

    private var verdict: PhraseValidation = PhraseValidation.Valid

    private val validator = object : RecoveryPhraseValidator {
        override val wordCount: Int = 24
        override fun warmUp() = Unit
        override fun validate(words: List<String>): PhraseValidation = verdict
        override fun isKnownWord(word: String): Boolean = true
        override fun suggestions(prefix: String, limit: Int): List<String> = emptyList()
        override fun parse(input: String): List<String> = input.split(" ")
    }

    private val repository = object : RestoreRepository {
        var calls = 0
        override suspend fun listBackups(treeUri: String): List<String>? = emptyList()
        override suspend fun restore(source: RestoreSource, words: List<String>): RestoreOutcome {
            calls++
            return RestoreOutcome.WrongPhrase
        }
        override suspend fun finish() = Unit
    }

    private val useCase = RestoreFromBackupUseCase(validator, repository)
    private val source = RestoreSource.SingleFile("content://backup")
    private val words = List(24) { "abandon" }

    @Test
    fun aBadChecksum_neverReachesTheRepository() = runTest {
        verdict = PhraseValidation.ChecksumMismatch

        assertThat(useCase(source, words))
            .isEqualTo(RestoreOutcome.PhraseRejected(PhraseValidation.ChecksumMismatch))
        assertThat(repository.calls).isEqualTo(0)
    }

    @Test
    fun aValidPhrase_isHandedOn() = runTest {
        assertThat(useCase(source, words)).isEqualTo(RestoreOutcome.WrongPhrase)
        assertThat(repository.calls).isEqualTo(1)
    }
}
