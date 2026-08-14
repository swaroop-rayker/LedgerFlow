package com.ledgerflow.core.crypto

/**
 * Why an unlock attempt did not yield a DEK.
 *
 * Note what is absent: there is no `Wipe`, no `Reset`, no `Corrupt -- start
 * over`. CLAUDE.md §7 and SPEC.md §7.3 are categorical -- a decryption failure
 * routes to the Recovery screen and never destroys data. Making that
 * impossible to express in the type is stronger than documenting it.
 */
public sealed interface UnlockFailure {

    /** No wrapped blob for this factor. First run, or the file was removed. */
    public data object NotInitialized : UnlockFailure

    /**
     * The Keystore key is gone or permanently invalidated -- lock-screen
     * removal, biometric re-enrollment on some OEMs, or a device restore.
     *
     * **Not fatal.** The phrase re-derives the DEK and re-wraps a fresh
     * Keystore key. This is the case the whole multi-wrap design exists for.
     */
    public data object KeystoreUnavailable : UnlockFailure

    /** The blob is present but the tag did not verify: wrong key, or damage. */
    public data object AuthenticationFailed : UnlockFailure

    /** The blob is not a LedgerFlow wrapped-DEK file, or is truncated. */
    public data class MalformedBlob(val reason: String) : UnlockFailure

    /** A newer LedgerFlow wrote this blob. Never guess at a future format. */
    public data class UnsupportedFormat(val version: Int) : UnlockFailure

    /**
     * The phrase failed structural or checksum validation. Reported *before*
     * any KDF work, so a typo comes back instantly (CLAUDE.md §7).
     */
    public data class InvalidMnemonic(
        val error: com.ledgerflow.core.crypto.bip39.MnemonicError,
    ) : UnlockFailure
}

/** Outcome of an unlock attempt. */
public sealed interface UnlockResult {
    public data class Success(val dek: Dek) : UnlockResult
    public data class Failure(val reason: UnlockFailure) : UnlockResult
}

/**
 * The data encryption key: 32 bytes that decrypt everything.
 *
 * Wraps a raw array so it cannot be logged accidentally -- [toString] is
 * overridden, because a DEK in a crash report or a debug log is a full
 * compromise.
 */
public class Dek(bytes: ByteArray) {

    init {
        require(bytes.size == LENGTH) { "DEK must be $LENGTH bytes, was ${bytes.size}" }
    }

    private val material: ByteArray = bytes.copyOf()

    /** Defensive copy: callers must not be able to zero our copy. */
    public fun bytes(): ByteArray = material.copyOf()

    /** Best-effort scrub. The JVM may still have copies; this is hygiene. */
    public fun destroy() {
        material.fill(0)
    }

    override fun toString(): String = "Dek(REDACTED)"

    public companion object {
        public const val LENGTH: Int = 32
    }
}
