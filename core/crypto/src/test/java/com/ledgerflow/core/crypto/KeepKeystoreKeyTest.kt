package com.ledgerflow.core.crypto

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import org.junit.Before
import org.junit.Test

/**
 * `keepKeystoreKey` (ADR-0033 f): a restore that replaces a vault wraps the new
 * DEK under the Keystore key that already exists, so the vault it set aside can
 * be put back and still open with no words.
 *
 * The scenario throughout: one phone, so one Keystore; the vault being replaced
 * has its wraps in one store, and the restored vault writes its own into a
 * second, empty one — exactly what setting the first one's files aside leaves.
 */
class KeepKeystoreKeyTest {

    private lateinit var keystore: FakeKeystoreKek
    private lateinit var oldStore: InMemoryWrappedDekStore
    private lateinit var newStore: InMemoryWrappedDekStore
    private lateinit var oldDek: ByteArray

    private val oldWords: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 7 })
    private val backupWords: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 9 })

    @Before
    fun setUp() {
        keystore = FakeKeystoreKek()
        oldStore = InMemoryWrappedDekStore()
        newStore = InMemoryWrappedDekStore()
        oldDek = (DekManager(oldStore, keystore).initialize(oldWords) as UnlockResult.Success).dek.bytes()
    }

    /** The old vault's wraps, put back: does its Keystore wrap still open with no words? */
    private fun oldVaultOpensSilently(): Boolean =
        (DekManager(oldStore, keystore).unlockWithKeystore() as? UnlockResult.Success)
            ?.dek?.bytes()?.contentEquals(oldDek) == true

    @Test
    fun initialize_keepingTheKey_makesNoNewKeyAndTheOldWrapStillOpens() {
        val keysBefore = keystore.createCount

        val restored = DekManager(newStore, keystore).initialize(backupWords, keepKeystoreKey = true)

        assertThat(restored).isInstanceOf(UnlockResult.Success::class.java)
        assertThat(keystore.createCount).isEqualTo(keysBefore)
        assertThat(oldVaultOpensSilently()).isTrue()
    }

    @Test
    fun initialize_keepingTheKey_theNewVaultOpensSilentlyToo() {
        val newDek = (DekManager(newStore, keystore).initialize(backupWords, keepKeystoreKey = true)
            as UnlockResult.Success).dek.bytes()

        val reopened = DekManager(newStore, keystore).unlockWithKeystore()

        assertThat((reopened as UnlockResult.Success).dek.bytes()).isEqualTo(newDek)
    }

    /** The behaviour every other caller keeps: a new key, and the old wrap no longer opens. */
    @Test
    fun initialize_byDefault_stillMakesANewKey() {
        val keysBefore = keystore.createCount

        DekManager(newStore, keystore).initialize(backupWords)

        assertThat(keystore.createCount).isEqualTo(keysBefore + 1)
        assertThat(oldVaultOpensSilently()).isFalse()
    }

    /** A key that cannot seal is no use to anyone: it is replaced, and the new vault still opens. */
    @Test
    fun initialize_keepingAnInvalidatedKey_makesANewOne() {
        keystore.invalidated = true
        val keysBefore = keystore.createCount

        val newDek = (DekManager(newStore, keystore).initialize(backupWords, keepKeystoreKey = true)
            as UnlockResult.Success).dek.bytes()

        assertThat(keystore.createCount).isEqualTo(keysBefore + 1)
        val reopened = DekManager(newStore, keystore).unlockWithKeystore()
        assertThat((reopened as UnlockResult.Success).dek.bytes()).isEqualTo(newDek)
    }

    /** The phrase is still written first and verified; keeping the key changes only KEK-A. */
    @Test
    fun initialize_keepingTheKey_stillWritesThePhraseWrap() {
        DekManager(newStore, keystore).initialize(backupWords, keepKeystoreKey = true)

        val byWords = DekManager(newStore, keystore).unlockWithPhrase(backupWords)

        assertThat(byWords).isInstanceOf(UnlockResult.Success::class.java)
        assertThat(newStore.exists(KekId.PHRASE)).isTrue()
    }

    /** A resumed restore that is replacing a vault keeps the key the same way. */
    @Test
    fun unlockWithPhrase_keepingTheKey_makesNoNewKey() {
        DekManager(newStore, keystore).initialize(backupWords, keepKeystoreKey = true)
        val keysBefore = keystore.createCount

        DekManager(newStore, keystore).unlockWithPhrase(backupWords, keepKeystoreKey = true)

        assertThat(keystore.createCount).isEqualTo(keysBefore)
        assertThat(oldVaultOpensSilently()).isTrue()
    }

    /** Recovery's self-healing is unchanged: the default still regenerates. */
    @Test
    fun unlockWithPhrase_byDefault_stillRegenerates() {
        val keysBefore = keystore.createCount

        DekManager(oldStore, keystore).unlockWithPhrase(oldWords)

        assertThat(keystore.createCount).isEqualTo(keysBefore + 1)
    }
}
