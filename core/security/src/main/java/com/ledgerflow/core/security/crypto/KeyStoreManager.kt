package com.ledgerflow.core.security.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyStoreManager @Inject constructor() {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val BOOT_KEY_ALIAS = "LedgerFlowBootKey"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    /**
     * Retrieves or generates a boot-accessible AES Master Key.
     * This key is stored in the hardware-backed keystore and is accessible without user authentication (so background SMS services can use it).
     */
    fun getOrCreateBootKey(): SecretKey {
        if (keyStore.containsAlias(BOOT_KEY_ALIAS)) {
            val entry = keyStore.getEntry(BOOT_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey?.let { return it }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            BOOT_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
        }.build()

        keyGenerator.init(parameterSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts the raw data using the specified SecretKey (AES-GCM).
     * Returns a pair of (encryptedBytes, initializationVector)
     */
    fun encrypt(data: ByteArray, secretKey: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encryptedData = cipher.doFinal(data)
        return Pair(encryptedData, cipher.iv)
    }

    /**
     * Decrypts the encrypted data using the specified SecretKey, ciphertext, and IV (AES-GCM).
     */
    fun decrypt(encryptedData: ByteArray, iv: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(encryptedData)
    }
}
