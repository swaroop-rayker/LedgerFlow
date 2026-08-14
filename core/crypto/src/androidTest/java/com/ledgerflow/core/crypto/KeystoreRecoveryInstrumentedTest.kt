package com.ledgerflow.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Android Keystore, on a real device.
 *
 * The JVM tests cover the decision logic with a fake; this covers the binding
 * that the fake stands in for -- StrongBox fallback, key invalidation, and the
 * flag that must never be true.
 *
 * **This is the test the multi-wrap design exists to satisfy:** destroy the
 * Keystore key, recover from 24 words, lose nothing.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreRecoveryInstrumentedTest {

    private val alias = "ledgerflow_test_kek_a"
    private lateinit var directory: File
    private lateinit var keystore: AndroidKeystoreKek
    private lateinit var manager: DekManager

    private val mnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 42 })

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // filesDir, never cacheDir (Law 5).
        directory = File(context.filesDir, "crypto-test-${System.nanoTime()}").apply { mkdirs() }
        keystore = AndroidKeystoreKek(alias)
        keystore.delete()
        manager = DekManager(FileWrappedDekStore(directory), keystore)
    }

    @After
    fun tearDown() {
        keystore.delete()
        directory.deleteRecursively()
    }

    @Test
    fun initialize_createsKeystoreBackedWrapAndUnlocksSilently() {
        val created = manager.initialize(mnemonic)
        assertThat(created).isInstanceOf(UnlockResult.Success::class.java)

        val unlocked = manager.unlockWithKeystore()

        assertThat((unlocked as UnlockResult.Success).dek.bytes())
            .isEqualTo((created as UnlockResult.Success).dek.bytes())
    }

    /**
     * Simulates what a factory reset, a device migration, or a biometric
     * re-enrollment does to the Keystore entry, and asserts the user loses
     * nothing (SPEC.md §7.3).
     */
    @Test
    fun keystoreKeyDestroyed_phraseRecoversDekWithZeroDataLoss() {
        val created = (manager.initialize(mnemonic) as UnlockResult.Success).dek.bytes()

        keystore.delete()

        assertThat(manager.unlockWithKeystore())
            .isEqualTo(UnlockResult.Failure(UnlockFailure.KeystoreUnavailable))

        val recovered = manager.unlockWithPhrase(mnemonic)
        assertThat((recovered as UnlockResult.Success).dek.bytes()).isEqualTo(created)

        // Self-healing: a fresh Keystore key was generated and the DEK
        // re-wrapped, so the next launch is silent again without re-typing.
        val afterHealing = manager.unlockWithKeystore()
        assertThat((afterHealing as UnlockResult.Success).dek.bytes()).isEqualTo(created)
    }

    @Test
    fun keystoreKey_isNotBoundToUserAuthentication() {
        // The decisive property: if the DEK-wrapping key required user
        // authentication, adding a fingerprint would invalidate it permanently
        // and destroy the user's data. Asserted behaviourally -- the key must
        // be usable with no authentication having taken place in this process.
        assertThat(keystore.create()).isTrue()

        val aad = WrappedDekBlob.aad(KekId.KEYSTORE, ByteArray(0))
        val payload = ByteArray(Dek.LENGTH) { 1 }
        val sealed = requireNotNull(keystore.seal(payload, aad))

        assertThat(keystore.open(sealed, aad))
            .isInstanceOf(com.ledgerflow.core.crypto.keystore.KeystoreOpen.Success::class.java)
    }

    @Test
    fun wrappedBlobs_areWrittenInsideFilesDir() {
        manager.initialize(mnemonic)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val phraseBlob = File(directory, KekId.PHRASE.fileName)

        assertThat(phraseBlob.isFile).isTrue()
        assertThat(phraseBlob.canonicalPath).startsWith(context.filesDir.canonicalPath)
    }

    @Test
    fun tamperedKeystoreBlob_reportsFailureAndPhraseStillRecovers() {
        val created = (manager.initialize(mnemonic) as UnlockResult.Success).dek.bytes()

        File(directory, KekId.KEYSTORE.fileName).apply {
            val bytes = readBytes()
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
            writeBytes(bytes)
        }

        assertThat(manager.unlockWithKeystore())
            .isEqualTo(UnlockResult.Failure(UnlockFailure.AuthenticationFailed))

        // Damage to one factor never costs the data -- that is the whole point
        // of wrapping the DEK independently under each.
        val recovered = manager.unlockWithPhrase(mnemonic)
        assertThat((recovered as UnlockResult.Success).dek.bytes()).isEqualTo(created)
    }
}
