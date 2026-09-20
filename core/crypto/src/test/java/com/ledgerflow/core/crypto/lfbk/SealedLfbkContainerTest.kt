package com.ledgerflow.core.crypto.lfbk

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.kem.BackupSealKem
import java.security.SecureRandom
import org.junit.Test

/**
 * Container v2 — a backup written without the phrase (ADR-0027).
 *
 * The seeds here are arbitrary test bytes, never a real phrase.
 */
class SealedLfbkContainerTest {

    private val seed = ByteArray(64) { (it + 1).toByte() }
    private val otherSeed = ByteArray(64) { (it + 99).toByte() }
    private val publicKey = BackupSealKem.publicKey(seed)
    private val payload = "{\"entries\":[1,2,3]}".toByteArray()
    private val schemaVersion = 11

    private fun sealed(): ByteArray =
        LfbkContainer.writeSealed(payload, publicKey, schemaVersion, SecureRandom())

    @Test
    fun aSealedBackup_opensWithThePhraseItWasSealedTo() {
        val result = LfbkContainer.read(sealed(), seed)

        assertThat(result).isInstanceOf(LfbkResult.Success::class.java)
        val success = result as LfbkResult.Success
        assertThat(success.payload).isEqualTo(payload)
        assertThat(success.schemaVersion).isEqualTo(schemaVersion)
    }

    /** The point of the whole design: the writer cannot read back what it wrote. */
    @Test
    fun anotherPhraseIsToldItIsTheWrongOne_notThatTheFileIsDamaged() {
        assertThat(LfbkContainer.read(sealed(), otherSeed))
            .isEqualTo(LfbkResult.Failure(LfbkFailure.WrongPhrase))
    }

    @Test
    fun aDamagedSealedBackup_readsAsCorrupt_notAsTheWrongPhrase() {
        val bytes = sealed()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        assertThat(LfbkContainer.read(bytes, seed)).isEqualTo(LfbkResult.Failure(LfbkFailure.Corrupt))
    }

    /** The header is the AAD: editing it must fail the tag, not steer the restore. */
    @Test
    fun editingTheSchemaVersionInTheHeader_failsTheTag() {
        val bytes = sealed()
        val schemaVersionOffset = 6 // magic(4) + formatVersion(2)
        bytes[schemaVersionOffset + 3] = (bytes[schemaVersionOffset + 3] + 1).toByte()

        assertThat(LfbkContainer.read(bytes, seed)).isEqualTo(LfbkResult.Failure(LfbkFailure.Corrupt))
    }

    @Test
    fun everySealedBackupOfTheSamePayload_differs() {
        assertThat(sealed()).isNotEqualTo(sealed())
    }

    /** Version 1 keeps working, unchanged, forever (ADR-0027). */
    @Test
    fun aVersionOneBackup_stillOpens() {
        val v1 = LfbkContainer.write(payload, seed, schemaVersion, SecureRandom())

        val result = LfbkContainer.read(v1, seed)

        assertThat((result as LfbkResult.Success).payload).isEqualTo(payload)
    }

    @Test
    fun aVersionOneBackupWithTheWrongPhrase_stillSaysWrongPhrase() {
        val v1 = LfbkContainer.write(payload, seed, schemaVersion, SecureRandom())

        assertThat(LfbkContainer.read(v1, otherSeed)).isEqualTo(LfbkResult.Failure(LfbkFailure.WrongPhrase))
    }

    /** A header claiming one format and keying by the other is refused rather than half-read. */
    @Test
    fun aMixedFormatAndKdfId_isMalformed() {
        val bytes = sealed()
        bytes[5] = 1 // formatVersion -> 1, leaving kdfId at 2

        val result = LfbkContainer.read(bytes, seed)

        assertThat(result).isInstanceOf(LfbkResult.Failure::class.java)
        assertThat((result as LfbkResult.Failure).reason).isInstanceOf(LfbkFailure.Malformed::class.java)
    }

    /** `enc` is attacker-shaped input; a point off the curve is refused, not agreed with. */
    @Test
    fun aSealingKeyThatIsNotOnTheCurve_isRefused() {
        val bytes = sealed()
        val encOffset = 11 // magic(4) + formatVersion(2) + schemaVersion(4) + kdfId(1)
        bytes[encOffset + 2] = (bytes[encOffset + 2] + 1).toByte()

        val result = LfbkContainer.read(bytes, seed)

        assertThat(result).isInstanceOf(LfbkResult.Failure::class.java)
        assertThat((result as LfbkResult.Failure).reason).isInstanceOf(LfbkFailure.Malformed::class.java)
    }
}
