package com.ledgerflow.core.database

import com.ledgerflow.core.crypto.Dek
import com.ledgerflow.core.crypto.kdf.Pbkdf2
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * What SQLCipher is keyed with: the key it would derive from the DEK, derived
 * once (ADR-0029, BUG56).
 *
 * Handing SQLCipher the DEK as a passphrase made it run PBKDF2-HMAC-SHA512 at
 * 256,000 iterations for **every connection** -- the guard's open, Room's writer
 * and each reader the pool opened, one after another under the pool's lock:
 * five derivations, ~2.1 s of a 2.65 s launch on the owner's phone. This runs
 * the same derivation once and passes its result in SQLCipher's raw-key form,
 * `x'<key><salt>'`, which SQLCipher takes as the derived key itself.
 *
 * **Nothing about the file changes.** Same DEK, same salt, same iterations,
 * same key: no rekey and no migration, and a vault opens under either form.
 * `Bug56_DerivedRawKeyOpensPassphraseVaultTest` proves that on the device, both
 * ways round. If a SQLCipher upgrade changes its default derivation, that test
 * fails: every existing vault was written under the old one, so that is a
 * format migration needing its own ADR, not a constant to bump here.
 *
 * As sensitive as the DEK it came from: bytes rather than a `String`, so it can
 * be zeroed, and never printed.
 */
public class DatabaseKey private constructor(material: ByteArray) {

    private val material: ByteArray = material.copyOf()

    /** Defensive copy, for SQLCipher; the caller zeroes it when done. */
    public fun bytes(): ByteArray = material.copyOf()

    /** Best-effort scrub, as [Dek.destroy]. */
    public fun destroy() {
        material.fill(0)
    }

    override fun toString(): String = "DatabaseKey(REDACTED)"

    public companion object {

        /** SQLCipher 4's default `kdf_iter`. Not restated as a PRAGMA (ADR-0029 rule 3). */
        internal const val KDF_ITERATIONS: Int = 256_000

        /** AES-256. */
        internal const val KEY_LENGTH: Int = 32

        /** SQLCipher 4 keeps the file's first 16 bytes in the clear: the salt. */
        internal const val SALT_LENGTH: Int = 16

        /**
         * The key for [databaseFile] under [dek].
         *
         * **With no file yet there is no salt to read**, so the DEK goes as the
         * passphrase exactly as it did before ADR-0029: SQLCipher makes the salt
         * and derives once, when the file is created.
         *
         * Blocking file I/O and ~0.4 s of CPU: call it off the main thread.
         */
        public fun forFile(databaseFile: File, dek: Dek): DatabaseKey {
            val passphrase = dek.bytes()
            val salt = readSalt(databaseFile) ?: return DatabaseKey(passphrase).also { passphrase.fill(0) }
            val derived = Pbkdf2.derive(passphrase, salt, KDF_ITERATIONS, KEY_LENGTH)
            passphrase.fill(0)
            val raw = rawKey(derived, salt)
            derived.fill(0)
            return DatabaseKey(raw).also { raw.fill(0) }
        }

        private fun readSalt(file: File): ByteArray? {
            if (!file.isFile || file.length() < SALT_LENGTH) return null
            val salt = ByteArray(SALT_LENGTH)
            DataInputStream(FileInputStream(file)).use { it.readFully(salt) }
            return salt
        }

        /** `x'<hex key><hex salt>'` as ASCII bytes, built without a `String`. */
        private fun rawKey(key: ByteArray, salt: ByteArray): ByteArray {
            val out = ByteArray(2 + 2 * (key.size + salt.size) + 1)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            var at = 2
            // Not `key + salt`: that would be one more copy of the key to zero.
            for (part in arrayOf(key, salt)) {
                for (byte in part) {
                    val value = byte.toInt() and BYTE_MASK
                    out[at++] = HEX[value ushr NIBBLE_BITS]
                    out[at++] = HEX[value and NIBBLE_MASK]
                }
            }
            out[at] = '\''.code.toByte()
            return out
        }

        private val HEX = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)
        private const val BYTE_MASK = 0xFF
        private const val NIBBLE_BITS = 4
        private const val NIBBLE_MASK = 0x0F
    }
}
