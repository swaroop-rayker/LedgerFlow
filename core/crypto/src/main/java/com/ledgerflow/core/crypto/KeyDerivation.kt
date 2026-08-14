package com.ledgerflow.core.crypto

import com.ledgerflow.core.crypto.kdf.Hkdf

/**
 * The phrase -> key derivation, pinned byte-for-byte (SPEC.md §7.2, ADR-0010).
 *
 * **Nothing in this file may change.** If v1 ships one derivation and a later
 * version assumes another, every `.lfbk` a user has ever written becomes
 * permanently undecryptable -- the exact P4 catastrophe the security section
 * exists to prevent. The `info` strings are versioned; changing one is a
 * breaking format change requiring a `formatVersion` bump in SPEC.md §5.9.
 *
 * [KeyDerivationGoldenVectorTest] locks the output. **If that test fails, this
 * code is wrong -- never re-record the fixture** (CLAUDE.md §7).
 */
public object KeyDerivation {

    /** AES-256. */
    public const val KEY_LENGTH: Int = 32

    /** HKDF salt length, and the `.lfbk` container's salt field width. */
    public const val SALT_LENGTH: Int = 16

    /**
     * Bytes of `keyCheck` in the `.lfbk` header (SPEC.md §5.9). Four bytes let
     * the Recovery screen distinguish "wrong phrase" from "corrupt file"
     * instead of showing one opaque GCM tag failure for both. It leaks nothing
     * exploitable against 256-bit phrase entropy.
     */
    public const val KEY_CHECK_LENGTH: Int = 4

    private const val INFO_KEK_B = "ledgerflow-kek-b-v1"
    private const val INFO_BACKUP = "lfbk-backup-v1"
    private const val INFO_KEY_CHECK = "lfbk-keycheck-v1"

    /**
     * KEK-B: the phrase-derived key that wraps the DEK.
     *
     * Mandatory and primary. It is what survives a factory reset, a lost
     * device, or an invalidated Keystore.
     */
    public fun kekB(seed: ByteArray, salt: ByteArray): ByteArray =
        derive(seed, salt, INFO_KEK_B, KEY_LENGTH)

    /**
     * The `.lfbk` content-encryption key.
     *
     * Phrase-derived and **never** passphrase-derived (CLAUDE.md §0, §7). A
     * backup can leave the device; its protection must be the 256-bit phrase.
     */
    public fun backupKey(seed: ByteArray, salt: ByteArray): ByteArray =
        derive(seed, salt, INFO_BACKUP, KEY_LENGTH)

    /** The 4-byte `keyCheck` written into the `.lfbk` header. */
    public fun keyCheck(seed: ByteArray, salt: ByteArray): ByteArray =
        derive(seed, salt, INFO_KEY_CHECK, KEY_CHECK_LENGTH)

    private fun derive(seed: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        require(salt.size == SALT_LENGTH) {
            "Salt must be $SALT_LENGTH bytes, was ${salt.size}"
        }
        return Hkdf.derive(
            ikm = seed,
            salt = salt,
            info = info.toByteArray(Charsets.UTF_8),
            length = length,
        )
    }
}
