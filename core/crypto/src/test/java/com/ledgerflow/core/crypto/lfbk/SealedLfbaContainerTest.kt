package com.ledgerflow.core.crypto.lfbk

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.kem.BackupSealKem
import java.security.SecureRandom
import org.junit.Test

/**
 * The image sidecar, sealed without the phrase (ADR-0027, container v2).
 *
 * The seeds are arbitrary test bytes, never a real phrase.
 */
class SealedLfbaContainerTest {

    private val seed = ByteArray(64) { (it + 3).toByte() }
    private val otherSeed = ByteArray(64) { (it + 41).toByte() }
    private val publicKey = BackupSealKem.publicKey(seed)
    private val image = ByteArray(512) { (it % 251).toByte() }
    private val attachmentId = "01890c1e-0000-7000-8000-000000000001"

    private fun sealed(id: String = attachmentId) =
        LfbaContainer.writeSealed(image, publicKey, id, SecureRandom())

    @Test
    fun aSealedImage_opensWithThePhraseItWasSealedTo() {
        val result = LfbaContainer.read(sealed(), seed, attachmentId)

        assertThat((result as LfbaResult.Success).image).isEqualTo(image)
    }

    @Test
    fun anotherPhrase_isToldItIsTheWrongOne() {
        assertThat(LfbaContainer.read(sealed(), otherSeed, attachmentId))
            .isEqualTo(LfbaResult.Failure(LfbaFailure.WrongPhrase))
    }

    /** ADR-0023's swap protection has to survive the new format unchanged. */
    @Test
    fun aFileRestoredOntoTheWrongRow_saysWhichRowItHolds() {
        val other = "01890c1e-0000-7000-8000-000000000002"

        val result = LfbaContainer.read(sealed(), seed, other)

        assertThat(result).isEqualTo(
            LfbaResult.Failure(LfbaFailure.WrongAttachment(expected = other, found = attachmentId)),
        )
    }

    @Test
    fun aDamagedSealedImage_readsAsCorrupt() {
        val bytes = sealed()
        bytes[bytes.size - 2] = (bytes[bytes.size - 2] + 1).toByte()

        assertThat(LfbaContainer.read(bytes, seed, attachmentId))
            .isEqualTo(LfbaResult.Failure(LfbaFailure.Corrupt))
    }

    /**
     * The incremental-write check: a folder is only skipped for files the
     * current phrase can still open, which is what stops a rotation leaving
     * images nobody can restore (ADR-0023).
     */
    @Test
    fun sealedWith_recognisesTheCurrentPhrase_andRefusesAnother() {
        val bytes = sealed()

        assertThat(LfbaContainer.sealedWith(bytes, seed)).isTrue()
        assertThat(LfbaContainer.sealedWith(bytes, otherSeed)).isFalse()
        // The nightly writer's version of the same question, with no seed.
        assertThat(LfbaContainer.sealedTo(bytes, publicKey)).isTrue()
        assertThat(LfbaContainer.sealedTo(bytes, BackupSealKem.publicKey(otherSeed))).isFalse()
    }

    @Test
    fun aVersionOneImage_stillOpens_andIsStillRecognised() {
        val v1 = LfbaContainer.write(image, seed, attachmentId, SecureRandom())

        assertThat((LfbaContainer.read(v1, seed, attachmentId) as LfbaResult.Success).image).isEqualTo(image)
        assertThat(LfbaContainer.sealedWith(v1, seed)).isTrue()
        assertThat(LfbaContainer.sealedWith(v1, otherSeed)).isFalse()
        // A phrase-written copy is not current for the nightly writer, so the
        // first pass after enrolment re-seals it rather than skipping it.
        assertThat(LfbaContainer.sealedTo(v1, publicKey)).isFalse()
    }

    @Test
    fun twoSealedCopiesOfTheSameImage_shareNoKey() {
        assertThat(sealed()).isNotEqualTo(sealed())
    }
}
