package com.ledgerflow.core.crypto

import com.ledgerflow.core.crypto.kdf.Hkdf

/**
 * The key that seals an attachment **in the backup tree** (ADR-0023).
 *
 * ## Two seals, and why they are in different files
 *
 * The same image is sealed twice, under two keys with two different change
 * rules, and keeping them apart is the point:
 *
 * | | derived from | if the derivation changed |
 * |---|---|---|
 * | [AttachmentKey] `local` | the **DEK** | local files stop opening — recoverable from the backup copy |
 * | this | the **BIP-39 seed** | every backed-up image ever written is orphaned, permanently |
 *
 * So this one's rule is [KeyDerivation]'s rule: **its committed vector may
 * never be re-recorded.** It does not live *in* `KeyDerivation` only because
 * that file is pinned as the phrase→*vault* derivation and its KDoc says
 * nothing in it may change; adding a function there would make that sentence
 * false the first time someone read it. ADR-0023's own words settle that this
 * is permitted: `info` strings are versioned, and "adding one is not changing
 * one".
 *
 * ## It is not a third wrap
 *
 * ADR-0011 forbids adding a wrap on the DEK path. This adds none: there is
 * still exactly one DEK, wrapped by exactly two factors. This derives a new
 * *purpose* from the seed, exactly as `backupKey` and `keyCheck` already do,
 * and it protects files rather than keys.
 *
 * ## The salt is per file, not per install
 *
 * Unlike [AttachmentKey.local] — which must be deterministic, because the file
 * it opens was sealed by a previous run — a backup copy is sealed once and
 * carries its own salt in its header, exactly as a `.lfbk` does. A fresh salt
 * per file means two images of the same receipt in the same folder share no
 * key material.
 */
public object AttachmentBackupKey {

    /**
     * ADR-0023's string, unchanged and unchangeable.
     *
     * Deliberately *not* `ledgerflow-attachment-local-v1` — the two seals are
     * named apart so nobody later assumes one file opens with the other's key.
     */
    private const val INFO_ATTACHMENT_BACKUP = "lfbk-attachment-v1"

    /** Named apart from v1 for the same reason v1 is named apart from the local seal. */
    private const val INFO_SEALED_ATTACHMENT_BACKUP = "lfbk-attachment-v2"

    /**
     * The AES-256 key for one sealed image beside the `.lfbk`.
     *
     * @param seed the BIP-39 seed. **Never a passphrase** (CLAUDE.md §0): this
     *   file leaves the device with the backup folder, so only the 256-bit
     *   phrase may protect it.
     * @param salt fresh per file, stored in that file's header.
     */
    public fun forBackup(seed: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == KeyDerivation.SALT_LENGTH) {
            "Salt must be ${KeyDerivation.SALT_LENGTH} bytes, was ${salt.size}"
        }
        return Hkdf.derive(
            ikm = seed,
            salt = salt,
            info = INFO_ATTACHMENT_BACKUP.toByteArray(Charsets.UTF_8),
            length = KeyDerivation.KEY_LENGTH,
        )
    }

    /**
     * The key for an image sealed without the phrase (container v2, ADR-0027).
     *
     * @param sharedSecret from `BackupSealKem`, one encapsulation per file.
     * @param salt fresh per file, stored in that file's header.
     */
    public fun forSealedBackup(sharedSecret: ByteArray, salt: ByteArray): ByteArray {
        require(salt.size == KeyDerivation.SALT_LENGTH) {
            "Salt must be ${KeyDerivation.SALT_LENGTH} bytes, was ${salt.size}"
        }
        return Hkdf.derive(
            ikm = sharedSecret,
            salt = salt,
            info = INFO_SEALED_ATTACHMENT_BACKUP.toByteArray(Charsets.UTF_8),
            length = KeyDerivation.KEY_LENGTH,
        )
    }
}
