package com.ledgerflow.core.crypto

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.SecureRandom
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AesGcmTest {

    private val key = ByteArray(Dek.LENGTH) { it.toByte() }
    private val plaintext = "ledger entry".toByteArray()

    @Test
    fun encryptThenDecrypt_roundTrips() {
        val sealed = AesGcm.encrypt(key, plaintext)

        assertThat(AesGcm.decrypt(key, sealed)).isEqualTo(plaintext)
    }

    @Test
    fun decrypt_withWrongKey_returnsNullRatherThanThrowing() {
        val sealed = AesGcm.encrypt(key, plaintext)
        val wrong = ByteArray(Dek.LENGTH) { (it + 1).toByte() }

        assertThat(AesGcm.decrypt(wrong, sealed)).isNull()
    }

    @Test
    fun decrypt_withTamperedAad_fails() {
        val sealed = AesGcm.encrypt(key, plaintext, aad = "header-v1".toByteArray())

        assertThat(AesGcm.decrypt(key, sealed, aad = "header-v2".toByteArray())).isNull()
    }

    @Test
    fun decrypt_withTamperedCiphertext_fails() {
        val sealed = AesGcm.encrypt(key, plaintext)
        val damaged = sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertThat(AesGcm.decrypt(key, AesGcm.Sealed(sealed.nonce, damaged))).isNull()
    }

    /**
     * Nonce reuse is the one catastrophic misuse of GCM. The API gives callers
     * no way to supply a nonce, so this asserts the generator actually varies.
     */
    @Test
    fun encrypt_neverReusesANonce() {
        val random = SecureRandom()
        val nonces = (1..500).map { AesGcm.encrypt(key, plaintext, random = random).nonce.toHex() }

        assertThat(nonces.toSet()).hasSize(nonces.size)
    }
}

class WrappedDekBlobTest {

    private val salt = ByteArray(KeyDerivation.SALT_LENGTH) { it.toByte() }
    private val key = ByteArray(Dek.LENGTH) { 3 }

    private fun sealedBlob(): AesGcm.Sealed =
        AesGcm.encrypt(key, ByteArray(Dek.LENGTH) { 5 }, WrappedDekBlob.aad(KekId.PHRASE, salt))

    @Test
    fun encodeThenDecode_roundTrips() {
        val sealed = sealedBlob()

        val decoded = WrappedDekBlob.decode(WrappedDekBlob.encode(KekId.PHRASE, salt, sealed))

        val blob = (decoded as WrappedDekBlob.DecodeResult.Success).blob
        assertThat(blob.kekId).isEqualTo(KekId.PHRASE)
        assertThat(blob.salt).isEqualTo(salt)
        assertThat(AesGcm.decrypt(key, blob.sealed, blob.aad)).isEqualTo(ByteArray(Dek.LENGTH) { 5 })
    }

    /** The salt is covered by the AAD, so rewriting it must break the tag. */
    @Test
    fun tamperedSalt_breaksAuthentication() {
        val encoded = WrappedDekBlob.encode(KekId.PHRASE, salt, sealedBlob())
        // Salt begins after magic(4) + version(1) + kekId(1) + saltLen(1).
        encoded[7] = (encoded[7].toInt() xor 0xFF).toByte()

        val blob = (WrappedDekBlob.decode(encoded) as WrappedDekBlob.DecodeResult.Success).blob
        assertThat(AesGcm.decrypt(key, blob.sealed, blob.aad)).isNull()
    }

    @Test
    fun decode_rejectsBadMagic() {
        val encoded = WrappedDekBlob.encode(KekId.PHRASE, salt, sealedBlob()).also { it[0] = 'X'.code.toByte() }

        assertThat(WrappedDekBlob.decode(encoded))
            .isEqualTo(WrappedDekBlob.DecodeResult.Failure(UnlockFailure.MalformedBlob("bad magic")))
    }

    @Test
    fun decode_rejectsFutureFormatVersionExplicitly() {
        val encoded = WrappedDekBlob.encode(KekId.PHRASE, salt, sealedBlob()).also { it[4] = 99 }

        assertThat(WrappedDekBlob.decode(encoded))
            .isEqualTo(WrappedDekBlob.DecodeResult.Failure(UnlockFailure.UnsupportedFormat(99)))
    }

    @Test
    fun decode_rejectsBlobTruncatedInsideTheHeader() {
        val encoded = WrappedDekBlob.encode(KekId.PHRASE, salt, sealedBlob())

        // Cut inside the salt, so the reader runs off the end of the buffer.
        val result = WrappedDekBlob.decode(encoded.copyOf(10))

        assertThat(result)
            .isEqualTo(WrappedDekBlob.DecodeResult.Failure(UnlockFailure.MalformedBlob("truncated")))
    }

    /**
     * A blob truncated inside the ciphertext still parses -- the ciphertext is
     * "the remainder", so there is no declared length to contradict. That is
     * correct: GCM is what detects the damage, and it does so at the tag. This
     * asserts the division of labour rather than demanding the parser guess.
     */
    @Test
    fun blobTruncatedInsideCiphertext_parsesButFailsAuthentication() {
        val encoded = WrappedDekBlob.encode(KekId.PHRASE, salt, sealedBlob())

        val decoded = WrappedDekBlob.decode(encoded.copyOf(encoded.size - 4))

        val blob = (decoded as WrappedDekBlob.DecodeResult.Success).blob
        assertThat(AesGcm.decrypt(key, blob.sealed, blob.aad)).isNull()
    }
}

class FileWrappedDekStoreTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun write_thenRead_roundTrips() {
        val store = FileWrappedDekStore(folder.root)
        val payload = ByteArray(64) { it.toByte() }

        assertThat(store.write(KekId.PHRASE, payload)).isTrue()

        assertThat(store.read(KekId.PHRASE)).isEqualTo(payload)
        assertThat(store.exists(KekId.PHRASE)).isTrue()
    }

    @Test
    fun write_leavesNoTempFileBehind() {
        val store = FileWrappedDekStore(folder.root)

        store.write(KekId.PHRASE, ByteArray(32))

        assertThat(folder.root.list()?.toList()).containsExactly(KekId.PHRASE.fileName)
    }

    @Test
    fun read_missingBlob_returnsNull() {
        assertThat(FileWrappedDekStore(folder.root).read(KekId.KEYSTORE)).isNull()
    }

    @Test
    fun write_createsDirectoryIfAbsent() {
        val nested = File(folder.root, "keys")

        assertThat(FileWrappedDekStore(nested).write(KekId.PHRASE, ByteArray(16))).isTrue()
    }
}
