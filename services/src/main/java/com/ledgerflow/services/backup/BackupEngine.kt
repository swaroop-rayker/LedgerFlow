package com.ledgerflow.services.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupEngine {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_LENGTH = 256
    private const val ITERATIONS = 100000

    /**
     * Derives an AES 256-bit key from a password and salt using PBKDF2
     */
    fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_DERIVATION_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /**
     * Encrypts plain bytes using AES-GCM
     */
    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val key = deriveKey(password, salt)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(data)
        
        // Output format: [16-byte salt] + [12-byte IV] + [Ciphertext]
        return salt + cipher.iv + ciphertext
    }

    /**
     * Decrypts encrypted bytes using AES-GCM and the password
     */
    fun decrypt(encryptedPayload: ByteArray, password: CharArray): ByteArray {
        if (encryptedPayload.size < 28) {
            throw IllegalArgumentException("Payload size is too small.")
        }
        val salt = encryptedPayload.sliceArray(0 until 16)
        val iv = encryptedPayload.sliceArray(16 until 28)
        val ciphertext = encryptedPayload.sliceArray(28 until encryptedPayload.size)
        
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        return cipher.doFinal(ciphertext)
    }
}
