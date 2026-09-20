package com.ledgerflow.core.crypto.kem

import com.ledgerflow.core.crypto.kdf.Hkdf
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Sealing a backup to a key only the recovery phrase can reproduce (ADR-0027).
 *
 * This is **DHKEM(P-256, HKDF-SHA256)** from RFC 9180 (HPKE), which is why every
 * label below is the RFC's rather than one of ours: its published vectors then
 * apply to the derivation and to encapsulation, and this build is checked
 * against something it did not produce — `SPEC.md` §7.2's rule for the phrase
 * derivation, applied to its neighbour. `BackupSealKemVectorTest` is where that
 * is enforced, and a failure there means this file is wrong.
 *
 * P-256 and not X25519 because the platform has had `ECPrivateKeySpec`,
 * `ECPublicKeySpec` and `KeyAgreement` since API 1, while X25519's key specs
 * arrive at API 33 and `minSdk` is 26 — the measurement in ADR-0027, which also
 * records why no crypto dependency was added.
 *
 * The seed is the BIP-39 seed of `SPEC.md` §7.2, unchanged. Nothing here keeps
 * a seed beyond the call it was passed to, and nothing here logs.
 */
public object BackupSealKem {

    /** Uncompressed point: `0x04 || x || y`, the RFC's serialisation for P-256. */
    public const val PUBLIC_KEY_BYTES: Int = P256.PUBLIC_KEY_BYTES

    /** `Nsecret` for this KEM. */
    public const val SHARED_SECRET_BYTES: Int = 32

    /** What a sealing side produces: the file's header carries [enc]; [sharedSecret] never leaves memory. */
    public class Encapsulation(public val enc: ByteArray, public val sharedSecret: ByteArray)

    /**
     * The public key for [seed], as 65 uncompressed bytes.
     *
     * Deterministic: the same phrase always produces the same key, which is what
     * makes a backup openable with the words alone.
     */
    public fun publicKey(seed: ByteArray): ByteArray = P256.serialize(P256.pointOf(deriveScalar(seed)))

    /**
     * Seals to [publicKeyBytes] — the nightly path, which never sees a seed.
     *
     * The ephemeral private key exists only inside this call; once it returns,
     * nothing on the device can reach [Encapsulation.sharedSecret] again.
     */
    public fun seal(publicKeyBytes: ByteArray, random: SecureRandom = SecureRandom()): Encapsulation =
        encapsulate(publicKeyBytes, P256.randomScalar(random))

    /**
     * Reproduces [seal]'s shared secret from the phrase — the restore path.
     *
     * [enc] comes from the file's authenticated header, so it is attacker-shaped
     * input until proven otherwise: `P256.deserialize` refuses anything that is
     * not a point on this curve, which is what stops an invalid-curve attack
     * from asking questions about the private key.
     */
    public fun open(seed: ByteArray, enc: ByteArray): ByteArray = decapsulate(deriveScalar(seed), enc)

    /** [seal] with the ephemeral key chosen by the caller: the seam the RFC's vectors are pinned through. */
    internal fun encapsulate(publicKeyBytes: ByteArray, ephemeralScalar: BigInteger): Encapsulation {
        val recipient = P256.deserialize(publicKeyBytes)
        val enc = P256.serialize(P256.pointOf(ephemeralScalar))
        val dh = P256.agree(P256.privateKeyOf(ephemeralScalar), P256.publicKeyOf(recipient))
        return Encapsulation(enc, extractAndExpand(dh, enc, publicKeyBytes))
    }

    /** [open]'s half, with the private scalar given rather than derived. */
    internal fun decapsulate(scalar: BigInteger, enc: ByteArray): ByteArray {
        val dh = P256.agree(P256.privateKeyOf(scalar), P256.publicKeyOf(P256.deserialize(enc)))
        return extractAndExpand(dh, enc, P256.serialize(P256.pointOf(scalar)))
    }

    /** `DeriveKeyPair` (RFC 9180 §7.1.3): rejection sampling, because a P-256 scalar has a range a hash does not. */
    internal fun deriveScalar(seed: ByteArray): BigInteger {
        require(seed.isNotEmpty()) { "seed must not be empty" }
        val dkpPrk = labeledExtract(ByteArray(0), "dkp_prk", seed)
        var counter = 0
        while (counter <= MAX_CANDIDATES) {
            val candidate = labeledExpand(dkpPrk, "candidate", byteArrayOf(counter.toByte()), P256.SCALAR_BYTES)
            candidate[0] = (candidate[0].toInt() and BITMASK).toByte()
            val scalar = BigInteger(1, candidate)
            if (scalar.signum() != 0 && scalar < P256.order) return scalar
            counter++
        }
        error("DeriveKeyPair exhausted its candidates")
    }

    private fun extractAndExpand(dh: ByteArray, enc: ByteArray, recipientPublicKey: ByteArray): ByteArray {
        val eaePrk = labeledExtract(ByteArray(0), "eae_prk", dh)
        return labeledExpand(eaePrk, "shared_secret", enc + recipientPublicKey, SHARED_SECRET_BYTES)
    }

    private fun labeledExtract(salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        Hkdf.extract(salt, HPKE_V1 + SUITE_ID + label.toByteArray(Charsets.US_ASCII) + ikm)

    private fun labeledExpand(prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val labeledInfo = byteArrayOf((length ushr BYTE_BITS).toByte(), length.toByte()) +
            HPKE_V1 + SUITE_ID + label.toByteArray(Charsets.US_ASCII) + info
        return Hkdf.expand(prk, labeledInfo, length)
    }

    private const val MAX_CANDIDATES = 255
    private const val BITMASK = 0xFF
    private const val BYTE_BITS = 8
    private val HPKE_V1 = "HPKE-v1".toByteArray(Charsets.US_ASCII)

    /** `"KEM" || I2OSP(0x0010, 2)`, the suite id for DHKEM(P-256, HKDF-SHA256). */
    private val SUITE_ID = "KEM".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x10)
}
