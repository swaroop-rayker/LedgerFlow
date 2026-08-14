package com.ledgerflow.core.crypto

import com.ledgerflow.core.crypto.keystore.KeystoreKek
import com.ledgerflow.core.crypto.keystore.KeystoreOpen
import java.security.SecureRandom

/**
 * In-memory KEK-A.
 *
 * Exists so the unlock state machine can be tested on the JVM. The decision
 * logic -- what to do when the Keystore dies -- is the part that must not be
 * device-dependent; the AndroidKeyStore binding itself is covered by the
 * instrumented test.
 */
internal class FakeKeystoreKek(
    private val random: SecureRandom = SecureRandom(),
) : KeystoreKek {

    private var key: ByteArray? = null

    /** Simulates KeyPermanentlyInvalidatedException without a device. */
    var invalidated: Boolean = false

    /** Simulates a device whose Keystore refuses to generate keys at all. */
    var creationFails: Boolean = false

    var createCount: Int = 0
        private set

    override fun exists(): Boolean = key != null && !invalidated

    override fun create(): Boolean {
        if (creationFails) return false
        key = ByteArray(Dek.LENGTH).also(random::nextBytes)
        invalidated = false
        createCount++
        return true
    }

    override fun seal(plaintext: ByteArray, aad: ByteArray): AesGcm.Sealed? {
        val current = key ?: return null
        return AesGcm.encrypt(current, plaintext, aad, random)
    }

    override fun open(sealed: AesGcm.Sealed, aad: ByteArray): KeystoreOpen {
        val current = key
        if (current == null || invalidated) return KeystoreOpen.Invalidated
        return AesGcm.decrypt(current, sealed, aad)
            ?.let(KeystoreOpen::Success)
            ?: KeystoreOpen.AuthenticationFailed
    }

    override fun delete() {
        key = null
    }
}

internal class InMemoryWrappedDekStore : WrappedDekStore {

    private val blobs = mutableMapOf<KekId, ByteArray>()
    var writeFails: Boolean = false

    override fun read(kekId: KekId): ByteArray? = blobs[kekId]?.copyOf()

    override fun write(kekId: KekId, bytes: ByteArray): Boolean {
        if (writeFails) return false
        blobs[kekId] = bytes.copyOf()
        return true
    }

    override fun exists(kekId: KekId): Boolean = blobs.containsKey(kekId)

    override fun delete(kekId: KekId) {
        blobs.remove(kekId)
    }

    fun corrupt(kekId: KekId) {
        val existing = blobs[kekId] ?: return
        // Flip a bit inside the ciphertext, well past the header.
        val index = existing.lastIndex
        existing[index] = (existing[index].toInt() xor 0x01).toByte()
    }
}
