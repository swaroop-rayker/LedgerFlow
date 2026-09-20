package com.ledgerflow.core.crypto.kem

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * The curve work [BackupSealKem] needs, kept apart from the RFC 9180 layer.
 *
 * Everything here is the platform's: key construction, ECDH and ECDSA come from
 * the provider, so no curve arithmetic lives in this repository. The two
 * exceptions are pure field arithmetic on **public** values — the square root
 * that recovers `y`, and the curve-equation check that refuses a point someone
 * else wrote into a file.
 */
internal object P256 {

    /** Uncompressed point: `0x04 || x || y`. */
    const val PUBLIC_KEY_BYTES: Int = 65
    const val SCALAR_BYTES: Int = 32

    val params: ECParameterSpec = AlgorithmParameters.getInstance(EC).run {
        init(ECGenParameterSpec(CURVE))
        getParameterSpec(ECParameterSpec::class.java)
    }
    val field: BigInteger = (params.curve.field as ECFieldFp).p
    val order: BigInteger = params.order

    fun privateKeyOf(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance(EC).generatePrivate(ECPrivateKeySpec(scalar, params))

    fun publicKeyOf(point: ECPoint): PublicKey =
        KeyFactory.getInstance(EC).generatePublic(ECPublicKeySpec(point, params))

    fun agree(privateKey: PrivateKey, peer: PublicKey): ByteArray =
        KeyAgreement.getInstance(ECDH).run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }

    /** Uniform in `[1, n-1]` by rejection — a modulo would bias the top of the range. */
    fun randomScalar(random: SecureRandom): BigInteger {
        val bytes = ByteArray(SCALAR_BYTES)
        while (true) {
            random.nextBytes(bytes)
            val candidate = BigInteger(1, bytes)
            if (candidate.signum() != 0 && candidate < order) return candidate
        }
    }

    /**
     * `scalar × G`, as the ECDH the platform will do plus the `y` it does not return.
     *
     * **The sign is load-bearing**, unlike inside a plain Diffie-Hellman: RFC
     * 9180 feeds the *serialised* public key into the shared secret, so the
     * mirrored point gives a self-consistent scheme no other implementation
     * agrees with — which the RFC's own vectors caught while this was written.
     * The root is chosen by asking the platform: only the true public key
     * verifies a signature made with the private key. One signature and at most
     * two verifications, once per derivation.
     */
    fun pointOf(scalar: BigInteger): ECPoint {
        val x = BigInteger(1, agree(privateKeyOf(scalar), publicKeyOf(params.generator)))
        val y = sqrtOfRightHandSide(x)
        val signature = Signature.getInstance(ECDSA).run {
            initSign(privateKeyOf(scalar))
            update(SIGN_PROBE)
            sign()
        }
        val candidate = ECPoint(x, y)
        return if (verifies(candidate, signature)) candidate else ECPoint(x, field - y)
    }

    fun serialize(point: ECPoint): ByteArray =
        byteArrayOf(UNCOMPRESSED) + fixedWidth(point.affineX) + fixedWidth(point.affineY)

    /**
     * A point from bytes that may have come from a file someone else wrote.
     *
     * Every check is a refusal a real attacker needs: the tag, the length, the
     * field bounds, the point at infinity, and finally the curve equation —
     * without which a crafted "public key" off the curve turns each ECDH into a
     * question about the private key.
     */
    fun deserialize(bytes: ByteArray): ECPoint {
        require(bytes.size == PUBLIC_KEY_BYTES) { "public key must be $PUBLIC_KEY_BYTES bytes, was ${bytes.size}" }
        require(bytes[0] == UNCOMPRESSED) { "public key must be an uncompressed point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + SCALAR_BYTES))
        val y = BigInteger(1, bytes.copyOfRange(1 + SCALAR_BYTES, PUBLIC_KEY_BYTES))
        require(x < field && y < field) { "public key coordinates are outside the field" }
        require(x.signum() != 0 || y.signum() != 0) { "public key is the point at infinity" }
        require(y.modPow(TWO, field) == rightHandSide(x)) { "public key is not on the curve" }
        return ECPoint(x, y)
    }

    private fun verifies(point: ECPoint, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance(ECDSA).run {
            initVerify(publicKeyOf(point))
            update(SIGN_PROBE)
            verify(signature)
        }
    }.getOrDefault(false)

    /** `y = sqrt(x^3 - 3x + b) mod p`, one `modPow` because `p = 3 (mod 4)` for P-256. */
    private fun sqrtOfRightHandSide(x: BigInteger): BigInteger =
        rightHandSide(x).modPow((field + BigInteger.ONE).shiftRight(2), field)

    private fun rightHandSide(x: BigInteger): BigInteger =
        (x.modPow(THREE, field) + params.curve.a * x + params.curve.b).mod(field)

    private fun fixedWidth(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == SCALAR_BYTES -> bytes
            bytes.size > SCALAR_BYTES -> bytes.copyOfRange(bytes.size - SCALAR_BYTES, bytes.size)
            else -> ByteArray(SCALAR_BYTES - bytes.size) + bytes
        }
    }

    private const val EC = "EC"
    private const val ECDH = "ECDH"
    private const val ECDSA = "SHA256withECDSA"
    private const val CURVE = "secp256r1"
    private const val UNCOMPRESSED: Byte = 0x04
    /**
     * `BigInteger.TWO` and friends are API 33 — caught by lint, and it would
     * have been a crash on exactly the Android versions this curve was chosen
     * to keep (ADR-0027: `minSdk` 26).
     */
    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    /** Fixed, meaningless, never stored: it exists only to tell two candidate points apart. */
    private val SIGN_PROBE = "ledgerflow-kem-point-parity".toByteArray(Charsets.US_ASCII)
}
