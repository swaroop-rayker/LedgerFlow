package com.ledgerflow.core.crypto

import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.bip39.MnemonicCheck
import com.ledgerflow.core.crypto.keystore.KeystoreKek
import com.ledgerflow.core.crypto.keystore.KeystoreOpen
import java.security.SecureRandom

/**
 * The DEK lifecycle and the self-healing unlock flow (SPEC.md §7.2, §7.3).
 *
 * One DEK, wrapped independently by each factor. KEK-A (Keystore) is the
 * frictionless daily path; KEK-B (24-word phrase) is mandatory and is what
 * survives a factory reset, a new device, or an invalidated Keystore. KEK-C is
 * deferred to P1 (ADR-0010) -- the slot exists, nothing writes it.
 *
 * **There is no wipe path in this class, and there must never be one.** Every
 * failure returns an [UnlockFailure] for the Recovery screen to act on. The
 * only destruction in LedgerFlow is behind the explicit type-DELETE dialog in
 * SPEC.md §7.3, which lives in the UI layer and is chosen by the user.
 */
public class DekManager(
    private val store: WrappedDekStore,
    private val keystoreKek: KeystoreKek,
    private val random: SecureRandom = SecureRandom(),
) {

    public fun isInitialized(): Boolean = store.exists(KekId.PHRASE)

    /**
     * First run: generate a DEK and wrap it under both factors.
     *
     * The phrase blob is written **first and verified** before the Keystore
     * blob. If the process dies midway, the recoverable factor is the one on
     * disk -- the reverse order would leave a DEK recoverable only by a
     * Keystore key that a factory reset destroys.
     */
    public fun initialize(mnemonic: List<String>): UnlockResult {
        val validation = Bip39.validate(mnemonic)
        if (validation is MnemonicCheck.Invalid) {
            return UnlockResult.Failure(UnlockFailure.InvalidMnemonic(validation.error))
        }

        val dekBytes = ByteArray(Dek.LENGTH).also(random::nextBytes)
        val dek = Dek(dekBytes)

        if (!wrapWithPhrase(dekBytes, mnemonic)) {
            return UnlockResult.Failure(UnlockFailure.MalformedBlob("phrase wrap failed to persist"))
        }
        // Best-effort: a device with a broken Keystore is still fully usable
        // through the phrase, so this must not fail initialisation.
        rewrapKeystore(dek)

        return UnlockResult.Success(dek)
    }

    /**
     * Step 1 of §7.3: the silent path.
     *
     * Any failure here is a routing decision, not an error to surface as a
     * crash. [UnlockFailure.KeystoreUnavailable] means "ask for the phrase".
     */
    public fun unlockWithKeystore(): UnlockResult {
        val blob = when (val decoded = readBlob(KekId.KEYSTORE)) {
            is WrappedDekBlob.DecodeResult.Failure -> return UnlockResult.Failure(decoded.reason)
            is WrappedDekBlob.DecodeResult.Success -> decoded.blob
        }

        return when (val opened = keystoreKek.open(blob.sealed, blob.aad)) {
            is KeystoreOpen.Success -> UnlockResult.Success(Dek(opened.plaintext))
            KeystoreOpen.Invalidated -> UnlockResult.Failure(UnlockFailure.KeystoreUnavailable)
            KeystoreOpen.AuthenticationFailed ->
                UnlockResult.Failure(UnlockFailure.AuthenticationFailed)
        }
    }

    /**
     * Step 2 of §7.3: recovery, and self-healing.
     *
     * On success the Keystore key is regenerated and the DEK re-wrapped, so the
     * next launch is silent again. "User loses nothing" is the requirement --
     * including not having to type 24 words twice.
     *
     * Validation runs before the KDF, so a mistyped word returns immediately
     * rather than after 2048 rounds of HMAC-SHA512 (CLAUDE.md §7).
     */
    public fun unlockWithPhrase(mnemonic: List<String>): UnlockResult {
        val validation = Bip39.validate(mnemonic)
        if (validation is MnemonicCheck.Invalid) {
            return UnlockResult.Failure(UnlockFailure.InvalidMnemonic(validation.error))
        }

        return when (val decoded = readBlob(KekId.PHRASE)) {
            is WrappedDekBlob.DecodeResult.Failure -> UnlockResult.Failure(decoded.reason)
            is WrappedDekBlob.DecodeResult.Success -> unwrapWithPhrase(decoded.blob, mnemonic)
        }
    }

    /** The expensive half of [unlockWithPhrase], reached only after validation. */
    private fun unwrapWithPhrase(
        blob: WrappedDekBlob.Decoded,
        mnemonic: List<String>,
    ): UnlockResult {
        val kekB = KeyDerivation.kekB(Bip39.toSeed(mnemonic), blob.salt)
        val dekBytes = AesGcm.decrypt(kekB, blob.sealed, blob.aad)
            ?: return UnlockResult.Failure(UnlockFailure.AuthenticationFailed)

        val dek = Dek(dekBytes)
        rewrapKeystore(dek)
        return UnlockResult.Success(dek)
    }

    /**
     * Regenerates KEK-A and re-wraps the DEK under it.
     *
     * Returns false rather than throwing: a device whose Keystore is broken
     * must still be usable via the phrase every launch. Degraded, not bricked.
     */
    public fun rewrapKeystore(dek: Dek): Boolean {
        if (!keystoreKek.create()) return false
        val aad = WrappedDekBlob.aad(KekId.KEYSTORE, EMPTY_SALT)
        val sealed = keystoreKek.seal(dek.bytes(), aad) ?: return false
        return store.write(KekId.KEYSTORE, WrappedDekBlob.encode(KekId.KEYSTORE, EMPTY_SALT, sealed))
    }

    private fun wrapWithPhrase(dekBytes: ByteArray, mnemonic: List<String>): Boolean {
        val salt = ByteArray(KeyDerivation.SALT_LENGTH).also(random::nextBytes)
        val kekB = KeyDerivation.kekB(Bip39.toSeed(mnemonic), salt)
        val aad = WrappedDekBlob.aad(KekId.PHRASE, salt)
        val sealed = AesGcm.encrypt(kekB, dekBytes, aad, random)

        val encoded = WrappedDekBlob.encode(KekId.PHRASE, salt, sealed)
        if (!store.write(KekId.PHRASE, encoded)) return false

        // Verify by reading back and unwrapping. A wrap that has not been
        // round-tripped is not a wrap -- the same rule the backup writer
        // follows (CLAUDE.md §7).
        val verified = store.read(KekId.PHRASE)
            ?.let { WrappedDekBlob.decode(it) as? WrappedDekBlob.DecodeResult.Success }
            ?.blob
            ?.let { AesGcm.decrypt(kekB, it.sealed, it.aad) }
        return verified != null && verified.contentEquals(dekBytes)
    }

    private fun readBlob(kekId: KekId): WrappedDekBlob.DecodeResult {
        val bytes = store.read(kekId)
            ?: return WrappedDekBlob.DecodeResult.Failure(UnlockFailure.NotInitialized)
        return WrappedDekBlob.decode(bytes)
    }

    private companion object {
        /** The Keystore holds its own key material; no derivation salt exists. */
        private val EMPTY_SALT = ByteArray(0)
    }
}
