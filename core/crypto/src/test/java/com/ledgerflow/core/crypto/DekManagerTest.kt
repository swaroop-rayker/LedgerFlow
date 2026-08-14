package com.ledgerflow.core.crypto

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.bip39.MnemonicError
import org.junit.Before
import org.junit.Test

/**
 * The unlock state machine (SPEC.md §7.3).
 *
 * The point of these tests is not that AES works -- it is that every failure
 * mode routes somewhere survivable. If any of these ever start returning a
 * "wipe" outcome, the durability guarantee in §7 has been broken.
 */
class DekManagerTest {

    private lateinit var store: InMemoryWrappedDekStore
    private lateinit var keystore: FakeKeystoreKek
    private lateinit var manager: DekManager

    private val mnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 7 })
    private val otherMnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 9 })

    @Before
    fun setUp() {
        store = InMemoryWrappedDekStore()
        keystore = FakeKeystoreKek()
        manager = DekManager(store, keystore)
    }

    private fun initialize(): Dek =
        (manager.initialize(mnemonic) as UnlockResult.Success).dek

    @Test
    fun initialize_writesBothWrappedBlobs() {
        initialize()

        assertThat(store.exists(KekId.PHRASE)).isTrue()
        assertThat(store.exists(KekId.KEYSTORE)).isTrue()
        assertThat(manager.isInitialized()).isTrue()
    }

    @Test
    fun initialize_thenUnlockWithKeystore_returnsSameDek() {
        val created = initialize()

        val unlocked = manager.unlockWithKeystore()

        assertThat(unlocked).isInstanceOf(UnlockResult.Success::class.java)
        assertThat((unlocked as UnlockResult.Success).dek.bytes())
            .isEqualTo(created.bytes())
    }

    @Test
    fun initialize_thenUnlockWithPhrase_returnsSameDek() {
        val created = initialize()

        val unlocked = manager.unlockWithPhrase(mnemonic)

        assertThat((unlocked as UnlockResult.Success).dek.bytes()).isEqualTo(created.bytes())
    }

    /**
     * The scenario the entire multi-wrap design exists for: the Keystore key is
     * destroyed (factory reset, biometric re-enrollment, device migration) and
     * the user still gets their data back from 24 words -- with zero data loss.
     */
    @Test
    fun keystoreInvalidated_phraseRecoversDekAndRewraps() {
        val created = initialize()

        keystore.invalidated = true
        assertThat(manager.unlockWithKeystore())
            .isEqualTo(UnlockResult.Failure(UnlockFailure.KeystoreUnavailable))

        val recovered = manager.unlockWithPhrase(mnemonic)
        assertThat((recovered as UnlockResult.Success).dek.bytes()).isEqualTo(created.bytes())

        // Self-healing: the next launch must be silent again.
        val afterHealing = manager.unlockWithKeystore()
        assertThat((afterHealing as UnlockResult.Success).dek.bytes()).isEqualTo(created.bytes())
    }

    @Test
    fun keystoreDeleted_isReportedAsUnavailableNotCorruption() {
        initialize()
        keystore.delete()

        assertThat(manager.unlockWithKeystore())
            .isEqualTo(UnlockResult.Failure(UnlockFailure.KeystoreUnavailable))
    }

    @Test
    fun wrongPhrase_failsAuthenticationAndLeavesBlobsIntact() {
        initialize()

        val result = manager.unlockWithPhrase(otherMnemonic)

        assertThat(result).isEqualTo(UnlockResult.Failure(UnlockFailure.AuthenticationFailed))
        // Nothing was destroyed. A wrong phrase is a typo, not a reason to lose
        // a ledger (CLAUDE.md §7).
        assertThat(store.exists(KekId.PHRASE)).isTrue()
        assertThat(store.exists(KekId.KEYSTORE)).isTrue()
    }

    @Test
    fun invalidMnemonic_isRejectedBeforeAnyKdfWork() {
        initialize()

        val result = manager.unlockWithPhrase(List(Bip39.WORD_COUNT) { "abandon" })

        assertThat(result).isEqualTo(
            UnlockResult.Failure(UnlockFailure.InvalidMnemonic(MnemonicError.ChecksumMismatch)),
        )
    }

    @Test
    fun unknownWord_reportsPositionForTheUi() {
        initialize()
        val typo = mnemonic.toMutableList().apply { this[3] = "zzzz" }

        val result = manager.unlockWithPhrase(typo)

        assertThat(result).isEqualTo(
            UnlockResult.Failure(
                UnlockFailure.InvalidMnemonic(MnemonicError.UnknownWord("zzzz", position = 4)),
            ),
        )
    }

    @Test
    fun noBlobs_reportsNotInitialized() {
        assertThat(manager.unlockWithKeystore())
            .isEqualTo(UnlockResult.Failure(UnlockFailure.NotInitialized))
        assertThat(manager.unlockWithPhrase(mnemonic))
            .isEqualTo(UnlockResult.Failure(UnlockFailure.NotInitialized))
    }

    @Test
    fun corruptedPhraseBlob_failsAuthenticationRatherThanCrashing() {
        initialize()
        store.corrupt(KekId.PHRASE)

        assertThat(manager.unlockWithPhrase(mnemonic))
            .isEqualTo(UnlockResult.Failure(UnlockFailure.AuthenticationFailed))
    }

    /**
     * A device with a broken Keystore must still be usable through the phrase
     * on every launch. Degraded, not bricked.
     */
    @Test
    fun keystoreCreationFailure_stillInitialisesAndRecoversByPhrase() {
        keystore.creationFails = true

        val created = manager.initialize(mnemonic)

        assertThat(created).isInstanceOf(UnlockResult.Success::class.java)
        assertThat(store.exists(KekId.PHRASE)).isTrue()
        assertThat(store.exists(KekId.KEYSTORE)).isFalse()

        val recovered = manager.unlockWithPhrase(mnemonic)
        assertThat((recovered as UnlockResult.Success).dek.bytes())
            .isEqualTo((created as UnlockResult.Success).dek.bytes())
    }

    @Test
    fun initialize_whenPhraseBlobCannotBePersisted_failsRatherThanReportingSuccess() {
        store.writeFails = true

        val result = manager.initialize(mnemonic)

        // Reporting success here would hand the caller a DEK that nothing can
        // ever recover -- the worst possible outcome.
        assertThat(result).isInstanceOf(UnlockResult.Failure::class.java)
    }

    @Test
    fun dek_doesNotLeakMaterialInToString() {
        val dek = initialize()

        assertThat(dek.toString()).isEqualTo("Dek(REDACTED)")
    }
}
