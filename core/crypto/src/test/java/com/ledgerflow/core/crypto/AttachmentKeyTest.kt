package com.ledgerflow.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The local attachment key (ADR-0023, amended).
 *
 * The properties that matter are all about *stability* and *separation*: a
 * file sealed in one session has to open in the next, and compromising this
 * key must not hand over the database.
 */
class AttachmentKeyTest {

    private fun dek(fill: Byte) = Dek(ByteArray(KeyDerivation.KEY_LENGTH) { fill })

    /**
     * **The property the whole feature rests on.**
     *
     * An image sealed today opens tomorrow only if this is a pure function of
     * the DEK. A random salt, a timestamp, or anything drawn from the
     * environment would make yesterday's receipts unreadable and the symptom
     * would be images that vanish rather than an error.
     */
    @Test
    fun theKeyIsDeterministicInTheDek() {
        assertThat(AttachmentKey.local(dek(7)))
            .isEqualTo(AttachmentKey.local(dek(7)))
    }

    @Test
    fun differentVaultsDeriveDifferentKeys() {
        assertThat(AttachmentKey.local(dek(7)))
            .isNotEqualTo(AttachmentKey.local(dek(8)))
    }

    /**
     * It must not *be* the DEK.
     *
     * The entire reason for the amendment: a heap compromise should yield the
     * receipt images and not the ledger. If this ever returned the DEK's own
     * bytes, that separation would be gone and nothing else would notice.
     */
    @Test
    fun theKeyIsNotTheDekItself() {
        val key = dek(7)
        assertThat(AttachmentKey.local(key)).isNotEqualTo(key.bytes())
    }

    /**
     * Distinct from every other purpose derived in this codebase.
     *
     * Not a paranoia test: these are all HKDF over related secrets, and an
     * `info` string copy-pasted from one to another is a silent collision that
     * would make two unrelated things share a key.
     */
    @Test
    fun theKeyIsDistinctFromThePhraseDerivedPurposes() {
        val material = ByteArray(KeyDerivation.KEY_LENGTH) { 7 }
        val salt = ByteArray(KeyDerivation.SALT_LENGTH) { 3 }

        val attachment = AttachmentKey.local(Dek(material))

        assertThat(attachment).isNotEqualTo(KeyDerivation.kekB(material, salt))
        assertThat(attachment).isNotEqualTo(KeyDerivation.backupKey(material, salt))
    }

    @Test
    fun theKeyIsAes256Sized() {
        assertThat(AttachmentKey.local(dek(7))).hasLength(KeyDerivation.KEY_LENGTH)
    }

    /**
     * A committed vector, so a refactor that changes the derivation is loud.
     *
     * **Verified against an independent RFC 5869 implementation, not recorded
     * from this code's own output.** A vector copied out of the thing it
     * tests only proves the code agrees with itself; this one proves it agrees
     * with the RFC. The reference run was HMAC-SHA256 HKDF with `ikm` = bytes
     * 0x00..0x1f, an empty salt (so HashLen zero bytes per §2.2), and this
     * module's `info` string.
     *
     * Weaker than `KeyDerivationGoldenVectorTest` in consequence, and that is
     * deliberate: changing this orphans local files that ADR-0023's
     * phrase-sealed backup copy can still restore, where changing that one
     * orphans every backup ever written. It must still never change
     * *silently* — re-recording it means every receipt already on a device
     * stops opening.
     */
    @Test
    fun theDerivationMatchesItsCommittedVector() {
        val hex = AttachmentKey.local(Dek(ByteArray(KeyDerivation.KEY_LENGTH) { it.toByte() }))
            .joinToString("") { "%02x".format(it) }

        assertThat(hex).isEqualTo(EXPECTED)
    }

    private companion object {
        const val EXPECTED = "80c04dfe789140a37bcec4869e9fe1c2748c9195f15b919cb9df649f815cd398"
    }
}
