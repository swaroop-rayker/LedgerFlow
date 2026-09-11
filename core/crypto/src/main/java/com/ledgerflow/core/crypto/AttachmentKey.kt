package com.ledgerflow.core.crypto

import com.ledgerflow.core.crypto.kdf.Hkdf

/**
 * The key that seals attachment files on this device (ADR-0023, amended).
 *
 * ## Why this is not in [KeyDerivation]
 *
 * That file derives from the **BIP-39 seed** and is pinned byte-for-byte by
 * `KeyDerivationGoldenVectorTest`, because every `.lfbk` a user has ever
 * written depends on it. This derives from the **DEK**, and it protects only
 * files that live on this device. The two have different blast radii and
 * different change rules, and putting them in one file would invite a future
 * reader to treat them as one thing — which for the phrase path is the P4
 * catastrophe §7 exists to prevent.
 *
 * Concretely: if this derivation ever changed, existing local attachment files
 * would stop opening — bad, recoverable from the phrase-sealed copy beside the
 * `.lfbk` (ADR-0023), and *nothing to do with backups*. If `KeyDerivation`
 * changed, every backup ever written would be permanently undecryptable.
 *
 * ## Why a derived key rather than the DEK itself
 *
 * ADR-0023 says attachment files are sealed "under the DEK", and when it was
 * written nobody had noticed that **nothing retains the DEK after unlock**:
 * `VaultSession` hands it to `LedgerFlowDatabaseFactory` and drops it, and
 * SQLCipher's copy sits behind `PRAGMA cipher_memory_security = ON`, which
 * keeps it out of swappable memory. Honouring the ADR literally would have
 * meant keeping a *second* copy of the database key in the JVM heap for the
 * whole process lifetime, with none of that protection.
 *
 * So a distinct purpose is derived from the same secret instead. This is the
 * move ADR-0023 itself makes for the backup copy, and its own words apply
 * unchanged: it "introduces no new key material class and no third wrap… it
 * derives a *new purpose* from the existing seed with a distinct `info`
 * string", and §5.9's rule is that "`info` strings are versioned; changing one
 * is a breaking format change" — **adding one is not changing one**. ADR-0011's
 * ban is on adding a wrap to the DEK *path*; this adds none. There is still
 * exactly one DEK, wrapped by exactly two factors.
 *
 * What it buys: a heap compromise yields the user's receipt images and **not**
 * their ledger. That is strictly better than the literal reading, and it is why
 * the amendment was worth writing down rather than quietly implementing.
 *
 * ## The salt is empty, deliberately, and the key is deterministic
 *
 * There is no salt because there is nothing to salt against: the IKM is a DEK,
 * already 32 bytes of uniform randomness from a CSPRNG, which is exactly the
 * case RFC 5869 §3.1 says an extract salt is unnecessary for. `Hkdf` then
 * substitutes HashLen zero bytes per the RFC.
 *
 * **A random salt would be a bug, not an improvement.** An image sealed today
 * has to open tomorrow, so the derivation must be a pure function of the DEK.
 * A per-install salt would have to be stored somewhere, which is one more thing
 * to back up, restore and lose.
 *
 * Per-*file* uniqueness is `AesGcm`'s job and is already handled: it generates
 * a fresh nonce from `SecureRandom` on every call and offers no overload that
 * accepts one.
 */
public object AttachmentKey {

    /**
     * Versioned, and a new string rather than an edited one.
     *
     * `local` is in the name because ADR-0023 defines a *second* seal for the
     * same bytes — the copy written beside the `.lfbk`, under a phrase-derived
     * key with `info = "lfbk-attachment-v1"`. Two seals, two purposes, two
     * strings, and naming them apart is what stops someone later assuming one
     * file can be opened by the other's key.
     */
    private const val INFO_ATTACHMENT_LOCAL = "ledgerflow-attachment-local-v1"

    /**
     * The AES-256 key for `filesDir/attachments/` on this device.
     *
     * Deterministic in [dek]: the same vault always derives the same key, which
     * is what lets a file written in one session open in the next.
     */
    public fun local(dek: Dek): ByteArray = Hkdf.derive(
        ikm = dek.bytes(),
        // See the class KDoc: the IKM is already a uniformly random 256-bit
        // key, so RFC 5869's extract salt has nothing to do here.
        salt = ByteArray(0),
        info = INFO_ATTACHMENT_LOCAL.toByteArray(Charsets.UTF_8),
        length = KeyDerivation.KEY_LENGTH,
    )
}
