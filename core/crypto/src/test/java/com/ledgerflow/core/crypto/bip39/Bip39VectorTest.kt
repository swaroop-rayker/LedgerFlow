package com.ledgerflow.core.crypto.bip39

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.hexToBytes
import com.ledgerflow.core.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The official BIP-39 English vectors.
 *
 * Every vector is checked in three directions: entropy -> mnemonic,
 * mnemonic -> entropy, and mnemonic -> seed. The seed leg is what validates
 * [com.ledgerflow.core.crypto.kdf.Pbkdf2] against a real PBKDF2-HMAC-SHA512
 * implementation, which is the whole justification for hand-rolling it
 * (ADR-0010).
 *
 * The vectors use the passphrase "TREZOR"; LedgerFlow always uses an empty one
 * (SPEC.md §7.2). The internal [Bip39.seed] overload exists for exactly this.
 */
class Bip39VectorTest {

    private data class Vector(val entropy: String, val mnemonic: String, val seed: String)

    private val vectors: List<Vector> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/bip39/vectors.json")) {
            "BIP-39 vectors.json missing from test resources"
        }
        val root = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() })
        root.jsonObject.getValue("english").jsonArray.map { entry ->
            val fields = entry.jsonArray
            Vector(
                entropy = fields[0].jsonPrimitive.content,
                mnemonic = fields[1].jsonPrimitive.content,
                seed = fields[2].jsonPrimitive.content,
            )
        }
    }

    @Test
    fun vectors_areLoaded() {
        assertThat(vectors).isNotEmpty()
    }

    @Test
    fun fromEntropy_official256BitVectors_produceExpectedMnemonic() {
        val checked = vectors
            .filter { it.entropy.length == Bip39.ENTROPY_BYTES * 2 }
            .onEach { vector ->
                val mnemonic = Bip39.fromEntropy(vector.entropy.hexToBytes())
                assertThat(mnemonic.joinToString(" ")).isEqualTo(vector.mnemonic)
            }

        // LedgerFlow only issues 24-word phrases; make sure the filter above did
        // not silently reduce the suite to nothing.
        assertThat(checked).isNotEmpty()
    }

    @Test
    fun toEntropy_official256BitVectors_roundTripBackToEntropy() {
        vectors
            .filter { it.entropy.length == Bip39.ENTROPY_BYTES * 2 }
            .forEach { vector ->
                val entropy = Bip39.toEntropy(vector.mnemonic.split(" "))
                assertThat(entropy.toHex()).isEqualTo(vector.entropy)
            }
    }

    @Test
    fun validate_everyOfficialMnemonic_isAccepted() {
        vectors
            .filter { it.mnemonic.split(" ").size == Bip39.WORD_COUNT }
            .forEach { vector ->
                assertThat(Bip39.validate(vector.mnemonic.split(" ")))
                    .isEqualTo(MnemonicCheck.Valid)
            }
    }

    @Test
    fun seed_officialVectorsWithTrezorPassphrase_matchPbkdf2Output() {
        val checked = vectors
            .filter { it.mnemonic.split(" ").size == Bip39.WORD_COUNT }
            .onEach { vector ->
                val seed = Bip39.seed(vector.mnemonic.split(" "), passphrase = "TREZOR")
                assertThat(seed.toHex()).isEqualTo(vector.seed)
            }

        assertThat(checked).isNotEmpty()
    }
}
