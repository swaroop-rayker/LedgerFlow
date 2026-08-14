package com.ledgerflow.core.crypto.keystore

import com.ledgerflow.core.crypto.AesGcm

/** Result of asking the Keystore to unwrap something. */
public sealed interface KeystoreOpen {
    public data class Success(val plaintext: ByteArray) : KeystoreOpen

    /**
     * The key is gone or permanently invalidated -- lock-screen removal,
     * biometric re-enrollment on some OEMs, a device-to-device restore.
     *
     * Expected, survivable, and precisely why KEK-B is mandatory. The caller
     * falls back to the phrase and re-wraps. It is never a reason to wipe.
     */
    public data object Invalidated : KeystoreOpen

    /** Key exists but the tag failed: wrong AAD or a damaged blob. */
    public data object AuthenticationFailed : KeystoreOpen
}

/**
 * KEK-A (SPEC.md §7.2): a Keystore-held key that wraps the DEK.
 *
 * Behind an interface so the unlock state machine in
 * [com.ledgerflow.core.crypto.DekManager] is testable on the JVM. The real
 * implementation needs a device; the logic that decides *what to do* when the
 * Keystore dies must not.
 */
public interface KeystoreKek {

    /** True when a usable key exists. */
    public fun exists(): Boolean

    /** Creates the key, replacing any existing one. */
    public fun create(): Boolean

    public fun seal(plaintext: ByteArray, aad: ByteArray): AesGcm.Sealed?

    public fun open(sealed: AesGcm.Sealed, aad: ByteArray): KeystoreOpen

    /** Deletes the key. Used by rotation and by tests simulating invalidation. */
    public fun delete()
}
