package com.ledgerflow.core.crypto

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import org.junit.Test

/**
 * The phrase-derived key for attachments in the backup tree (ADR-0023).
 *
 * **If [theDerivationMatchesItsCommittedVector] fails, the code is wrong —
 * never re-record the fixture.** This is [KeyDerivationGoldenVectorTest]'s
 * rule, not [AttachmentKeyTest]'s: the local key protects files on one device
 * and its vector is re-recordable in principle, while this one protects files
 * that travel with a `.lfbk`. Re-recording it orphans every backed-up image
 * that has ever been written, exactly as re-recording `backupKey` would orphan
 * every backup.
 */
class AttachmentBackupKeyTest {

    /** Zero entropy: the canonical BIP-39 24-word phrase, 23 x abandon + art. */
    private val seed: ByteArray = Bip39.toSeed(Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES)))

    /** Fixed, non-random salt so the vector is reproducible. */
    private val salt: ByteArray = ByteArray(KeyDerivation.SALT_LENGTH) { it.toByte() }

    /**
     * **Computed outside this codebase**, by an HKDF written from RFC 5869 and
     * checked against the RFC's own A.1 vector — and, as the strongest
     * available cross-check, that same implementation reproduces all three
     * values [KeyDerivationGoldenVectorTest] already pins. So this number
     * agrees with the RFC, not merely with `Hkdf`.
     */
    @Test
    fun theDerivationMatchesItsCommittedVector() {
        assertThat(AttachmentBackupKey.forBackup(seed, salt).toHex())
            .isEqualTo("3bbaa17d5e7336083c1424d92aa3feeb5dc7c773f845c7f66c08a61d92195b84")
    }

    /**
     * Distinct from every other purpose derived from the same seed.
     *
     * These are all HKDF over one secret, so an `info` string copy-pasted from
     * one to another is a silent collision that makes two unrelated things
     * share a key — and here that would mean a stolen image file and the whole
     * backup opening with the same bytes.
     */
    @Test
    fun theKeyIsDistinctFromEveryOtherPurpose() {
        val backup = AttachmentBackupKey.forBackup(seed, salt)

        assertThat(backup).isNotEqualTo(KeyDerivation.backupKey(seed, salt))
        assertThat(backup).isNotEqualTo(KeyDerivation.kekB(seed, salt))
        assertThat(backup).isNotEqualTo(AttachmentKey.local(Dek(seed.copyOf(KeyDerivation.KEY_LENGTH))))
        assertThat(backup).isNotEqualTo(seed.copyOf(KeyDerivation.KEY_LENGTH))
    }

    /** A different salt is a different key: that is what per-file salting buys. */
    @Test
    fun aDifferentSalt_derivesADifferentKey() {
        val other = ByteArray(KeyDerivation.SALT_LENGTH) { (it + 1).toByte() }

        assertThat(AttachmentBackupKey.forBackup(seed, salt))
            .isNotEqualTo(AttachmentBackupKey.forBackup(seed, other))
    }

    @Test
    fun theKeyIsAes256Sized() {
        assertThat(AttachmentBackupKey.forBackup(seed, salt)).hasLength(KeyDerivation.KEY_LENGTH)
    }

    /**
     * A wrong-sized salt is refused rather than padded.
     *
     * The salt is written into the file's header at a fixed width, so a
     * shorter one would produce a header that cannot be parsed back — a
     * failure at restore time instead of at write time.
     */
    @Test
    fun aWrongSizedSalt_isRefused() {
        val error = runCatching { AttachmentBackupKey.forBackup(seed, ByteArray(8)) }
            .exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
