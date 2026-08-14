package com.ledgerflow.core.crypto.bip39

import com.ledgerflow.core.crypto.kdf.Pbkdf2
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer

/** Why a mnemonic was rejected. Typed so the UI can say something useful. */
public sealed interface MnemonicError {
    /** LedgerFlow only ever issues 24-word phrases (256-bit entropy). */
    public data class WrongWordCount(val actual: Int, val expected: Int) : MnemonicError

    /** Not in the BIP-39 English wordlist. Position is 1-based, for the UI. */
    public data class UnknownWord(val word: String, val position: Int) : MnemonicError

    /** Every word is real but the phrase as a whole is not a valid mnemonic. */
    public data object ChecksumMismatch : MnemonicError
}

/** Result of the cheap validation pass. */
public sealed interface MnemonicCheck {
    public data object Valid : MnemonicCheck
    public data class Invalid(val error: MnemonicError) : MnemonicCheck
}

/**
 * BIP-39 mnemonic handling.
 *
 * The wordlist ships as a classpath resource whose bytes are pinned by
 * `.gitattributes` and asserted by [Bip39WordlistIntegrityTest]. A dependency
 * was rejected in ADR-0010: the entropy/checksum logic has to be owned and
 * golden-tested regardless, so a library that still needs wrapping does not
 * earn its place.
 *
 * **Ordering rule (CLAUDE.md §7):** [validate] is microseconds and [toSeed] is
 * 2048 rounds of HMAC-SHA512. Always validate first. Running the KDF on a
 * mistyped phrase burns real time and presents as a hang on the Recovery
 * screen -- the one screen where the user is already anxious.
 */
public object Bip39 {

    /** LedgerFlow uses 256-bit entropy exclusively (SPEC.md §7.2). */
    public const val WORD_COUNT: Int = 24
    public const val ENTROPY_BYTES: Int = 32

    private const val BITS_PER_WORD = 11
    private const val WORDLIST_SIZE = 2048

    /** 32 entropy bytes + 1 checksum byte = 24 words x 11 bits, exactly. */
    private const val PAYLOAD_BYTES = ENTROPY_BYTES + 1

    /** log2(Byte.SIZE_BITS): converts a bit offset to a byte offset. */
    private const val BIT_TO_BYTE_SHIFT = 3

    /** Masks a bit offset down to its position within a byte. */
    private const val BIT_IN_BYTE_MASK = Byte.SIZE_BITS - 1
    private const val RESOURCE = "/bip39/english.txt"

    private const val SEED_ITERATIONS = 2048
    private const val SEED_LENGTH = 64
    private const val SEED_SALT_PREFIX = "mnemonic"

    private val wordlist: List<String> by lazy { loadWordlist() }
    private val wordIndex: Map<String, Int> by lazy {
        wordlist.withIndex().associate { (index, word) -> word to index }
    }

    /** The 2048-word BIP-39 English list, in canonical order. */
    public fun words(): List<String> = wordlist

    /** Generates a fresh 24-word phrase from 256 bits of [random]. */
    public fun generate(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(ENTROPY_BYTES)
        random.nextBytes(entropy)
        return fromEntropy(entropy)
    }

    /** Encodes [entropy] (32 bytes) as a 24-word mnemonic. */
    public fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) {
            "Entropy must be $ENTROPY_BYTES bytes, was ${entropy.size}"
        }
        val payload = ByteArray(PAYLOAD_BYTES)
        entropy.copyInto(payload)
        payload[ENTROPY_BYTES] = checksumByte(entropy)
        return List(WORD_COUNT) { position -> wordlist[readIndex(payload, position)] }
    }

    /**
     * Cheap structural + checksum validation. **No KDF work happens here.**
     *
     * Call this before [toSeed] on every user-entered phrase.
     */
    public fun validate(words: List<String>): MnemonicCheck {
        if (words.size != WORD_COUNT) {
            return MnemonicCheck.Invalid(MnemonicError.WrongWordCount(words.size, WORD_COUNT))
        }
        val payload = ByteArray(PAYLOAD_BYTES)
        words.forEachIndexed { position, word ->
            val index = wordIndex[word]
                ?: return MnemonicCheck.Invalid(MnemonicError.UnknownWord(word, position + 1))
            writeIndex(payload, position, index)
        }

        val entropy = payload.copyOf(ENTROPY_BYTES)
        return if (payload[ENTROPY_BYTES] == checksumByte(entropy)) {
            MnemonicCheck.Valid
        } else {
            MnemonicCheck.Invalid(MnemonicError.ChecksumMismatch)
        }
    }

    /**
     * Recovers the 32 bytes of entropy behind a mnemonic.
     *
     * Requires an already-[validate]d phrase.
     */
    public fun toEntropy(words: List<String>): ByteArray {
        require(validate(words) is MnemonicCheck.Valid) { "Mnemonic failed validation" }
        val payload = ByteArray(PAYLOAD_BYTES)
        words.forEachIndexed { position, word ->
            writeIndex(payload, position, requireNotNull(wordIndex[word]) { "Word not in wordlist" })
        }
        return payload.copyOf(ENTROPY_BYTES)
    }

    /**
     * The BIP-39 seed: PBKDF2-HMAC-SHA512(mnemonic, "mnemonic", 2048, 64).
     *
     * This is the 512-bit *seed*, not the 256-bit entropy. SPEC.md §7.2 pins
     * which one feeds HKDF, because shipping one and later assuming the other
     * would make every existing backup undecryptable.
     *
     * The optional BIP-39 passphrase (the "25th word") is deliberately empty:
     * supporting it would reintroduce a user-chosen secret into the backup
     * path, which D-03 forbids.
     *
     * Expensive. [validate] first.
     */
    public fun toSeed(words: List<String>): ByteArray = seed(words, passphrase = "")

    /**
     * BIP-39 seed with an explicit passphrase.
     *
     * `internal` on purpose: LedgerFlow's own derivation always passes an empty
     * passphrase, and no production caller may do otherwise. It exists so the
     * unit tests can check this PBKDF2 implementation against the official
     * BIP-39 vectors, which are all generated with the passphrase "TREZOR" --
     * validating against real vectors is worth more than hiding the parameter.
     */
    internal fun seed(words: List<String>, passphrase: String): ByteArray {
        require(validate(words) is MnemonicCheck.Valid) { "Mnemonic failed validation" }
        return Pbkdf2.derive(
            password = normalize(words.joinToString(" ")).toByteArray(Charsets.UTF_8),
            salt = normalize(SEED_SALT_PREFIX + passphrase).toByteArray(Charsets.UTF_8),
            iterations = SEED_ITERATIONS,
            keyLength = SEED_LENGTH,
        )
    }

    /**
     * Splits raw user input into words: NFKD-normalised, lowercased, collapsed
     * whitespace. Users paste phrases with newlines, double spaces and stray
     * capitals; none of that should read as a wrong phrase.
     */
    public fun parse(input: String): List<String> =
        normalize(input).lowercase().split(" ", "\n", "\t", "\r")
            .filter { it.isNotBlank() }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKD)

    /**
     * ENT/32 checksum bits. For LedgerFlow's fixed 256-bit entropy that is
     * exactly 8 bits, so entropy + checksum is exactly [PAYLOAD_BYTES] and no
     * partial-byte handling is needed.
     */
    private fun checksumByte(entropy: ByteArray): Byte =
        MessageDigest.getInstance("SHA-256").digest(entropy)[0]

    /** Reads the 11-bit big-endian word index at [position] out of [payload]. */
    private fun readIndex(payload: ByteArray, position: Int): Int {
        var value = 0
        val start = position * BITS_PER_WORD
        for (offset in 0 until BITS_PER_WORD) {
            val bitPosition = start + offset
            val byte = payload[bitPosition ushr BIT_TO_BYTE_SHIFT].toInt()
            val bit = (byte shr (BIT_IN_BYTE_MASK - (bitPosition and BIT_IN_BYTE_MASK))) and 1
            value = (value shl 1) or bit
        }
        return value
    }

    /** Writes an 11-bit word index into [payload] at [position]. */
    private fun writeIndex(payload: ByteArray, position: Int, index: Int) {
        val start = position * BITS_PER_WORD
        for (offset in 0 until BITS_PER_WORD) {
            if ((index shr (BITS_PER_WORD - 1 - offset)) and 1 == 1) {
                val bitPosition = start + offset
                val byteIndex = bitPosition ushr BIT_TO_BYTE_SHIFT
                val mask = 1 shl (BIT_IN_BYTE_MASK - (bitPosition and BIT_IN_BYTE_MASK))
                payload[byteIndex] = (payload[byteIndex].toInt() or mask).toByte()
            }
        }
    }

    private fun loadWordlist(): List<String> {
        val stream = requireNotNull(Bip39::class.java.getResourceAsStream(RESOURCE)) {
            "BIP-39 wordlist resource $RESOURCE is missing from the artifact"
        }
        val loaded = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        check(loaded.size == WORDLIST_SIZE) {
            "BIP-39 wordlist must have $WORDLIST_SIZE words, found ${loaded.size}"
        }
        return loaded
    }
}
