package com.ledgerflow.core.crypto.lfbk

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import java.security.SecureRandom
import org.junit.Test

/**
 * The sealed-attachment container that lives beside the `.lfbk` (ADR-0023).
 *
 * Most of this file is about what the reader must **refuse**, for the reason
 * §5.9 gives the `.lfbk` reader: these files are the half of the backup a user
 * is most likely to move, rename or partially copy, and every refusal here is
 * a sentence a restore report can say out loud instead of a stack trace.
 */
class LfbaContainerTest {

    private val seed = Bip39.toSeed(Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES)))
    private val otherSeed = Bip39.toSeed(Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 9 }))
    private val image = ByteArray(2_048) { (it % 251).toByte() }
    private val id = "01a0b041-b986-7c42-bfc7-417555295497"

    private fun sealed(
        image: ByteArray = this.image,
        id: String = this.id,
        seed: ByteArray = this.seed,
    ) = LfbaContainer.write(image, seed, id, SecureRandom())

    private fun LfbaResult.image(): ByteArray {
        assertThat(this).isInstanceOf(LfbaResult.Success::class.java)
        return (this as LfbaResult.Success).image
    }

    private fun LfbaResult.failure(): LfbaFailure {
        assertThat(this).isInstanceOf(LfbaResult.Failure::class.java)
        return (this as LfbaResult.Failure).reason
    }

    @Test
    fun write_thenRead_returnsTheImageByteForByte() {
        assertThat(LfbaContainer.read(sealed(), seed, id).image()).isEqualTo(image)
    }

    /** The file must not be the image. The one assertion that proves it is sealed. */
    @Test
    fun theSealedBytes_areNotThePlaintext() {
        val bytes = sealed()

        assertThat(bytes).isNotEqualTo(image)
        assertThat(bytes.toList().windowed(64).any { it == image.take(64) }).isFalse()
    }

    /** Fresh salt and nonce per call, so two seals of one image share nothing. */
    @Test
    fun sealingTheSameImageTwice_producesDifferentBytes() {
        assertThat(sealed()).isNotEqualTo(sealed())
    }

    @Test
    fun anEmptyImage_roundTrips() {
        assertThat(LfbaContainer.read(sealed(image = ByteArray(0)), seed, id).image())
            .isEqualTo(ByteArray(0))
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    /** Wrong words: intact file. The distinction `keyCheck` exists for. */
    @Test
    fun theWrongPhrase_isReportedAsWrongPhraseNotCorruption() {
        assertThat(LfbaContainer.read(sealed(), otherSeed, id).failure())
            .isEqualTo(LfbaFailure.WrongPhrase)
    }

    /**
     * **The file-swap case, and why the id is in the header.** Renaming two
     * sealed files past each other yields images that would otherwise decrypt
     * perfectly into the wrong entries.
     */
    @Test
    fun aFileBelongingToAnotherAttachment_isRefusedByName() {
        val other = "01a0b041-0000-7000-8000-000000000000"

        assertThat(LfbaContainer.read(sealed(id = other), seed, id).failure())
            .isEqualTo(LfbaFailure.WrongAttachment(expected = id, found = other))
    }

    /** A damaged ciphertext, with the phrase right. */
    @Test
    fun aFlippedCiphertextByte_isCorrupt() {
        val bytes = sealed().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertThat(LfbaContainer.read(bytes, seed, id).failure()).isEqualTo(LfbaFailure.Corrupt)
    }

    /**
     * **The header is the AAD**, so editing it is detected even though it is
     * not encrypted. Here the declared plaintext length is altered — the field
     * a reader would otherwise trust enough to allocate against.
     */
    @Test
    fun anEditedHeader_failsTheTag() {
        val bytes = sealed()
        val lengthOffset = bytes.size - image.size - 16 /* tag */ - 1

        bytes[lengthOffset] = (bytes[lengthOffset] + 1).toByte()

        assertThat(LfbaContainer.read(bytes, seed, id).failure())
            .isAnyOf(LfbaFailure.Corrupt, LfbaFailure.Malformed("declared length disagrees with image"))
    }

    @Test
    fun someOtherFile_isNotAnLfbaFile() {
        assertThat(LfbaContainer.read("not a receipt".toByteArray(), seed, id).failure())
            .isEqualTo(LfbaFailure.NotAnLfbaFile)
    }

    /** A `.lfbk` is not a `.lfba`, even though the headers rhyme. */
    @Test
    fun anLfbkFile_isNotAnLfbaFile() {
        val backup = LfbkContainer.write("payload".toByteArray(), seed, schemaVersion = 11)

        assertThat(LfbaContainer.read(backup, seed, id).failure())
            .isEqualTo(LfbaFailure.NotAnLfbaFile)
    }

    @Test
    fun aNewerFormatVersion_saysSoRatherThanFailingToParse() {
        // 3, not 2: version 2 is the sealed format this build reads (ADR-0027).
        val future = LfbaContainer.FORMAT_VERSION_SEALED + 1
        val bytes = sealed().also { it[5] = future.toByte() } // formatVersion's low byte

        assertThat(LfbaContainer.read(bytes, seed, id).failure())
            .isEqualTo(LfbaFailure.UnsupportedFormat(future))
    }

    @Test
    fun aTruncatedFile_isMalformed() {
        val bytes = sealed().copyOfRange(0, 20)

        assertThat(LfbaContainer.read(bytes, seed, id).failure())
            .isInstanceOf(LfbaFailure.Malformed::class.java)
    }

    /**
     * **Never allocate an attacker-supplied length** (§5.9's reader hardening).
     * The length is checked before the tag verifies, so it is the one field a
     * reader must bound on its own.
     */
    @Test
    fun anImplausibleLength_isRefusedBeforeAnythingIsAllocated() {
        val bytes = sealed()
        // plaintextLen is the last 8 bytes of the header, immediately before
        // the ciphertext. Set its top byte: ~2^56 bytes.
        val lengthStart = bytes.size - image.size - 16 - 8

        bytes[lengthStart] = 1

        assertThat(LfbaContainer.read(bytes, seed, id).failure())
            .isEqualTo(LfbaFailure.Malformed("implausible image length"))
    }

    // ── The incremental-write check ─────────────────────────────────────────

    /**
     * ADR-0023 writes each image to the tree once and never rewrites it. The
     * question "is this copy current" is therefore asked of the phrase, not of
     * the filesystem: after a rotation every existing copy is sealed under
     * words the user no longer has, and skipping them would leave a folder
     * that quietly cannot be restored.
     */
    @Test
    fun sealedWith_answersForTheCurrentPhraseOnly() {
        val bytes = sealed()

        assertThat(LfbaContainer.sealedWith(bytes, seed)).isTrue()
        assertThat(LfbaContainer.sealedWith(bytes, otherSeed)).isFalse()
    }

    /** It reads a header; anything that is not one is not a current copy. */
    @Test
    fun sealedWith_refusesSomethingThatIsNotAnLfbaFile() {
        assertThat(LfbaContainer.sealedWith("not a receipt".toByteArray(), seed)).isFalse()
        assertThat(LfbaContainer.sealedWith(ByteArray(0), seed)).isFalse()
    }
}
