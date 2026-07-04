package com.ledgerflow.services.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BackupEngineTest {

    @Test
    fun testEncryptionDecryptionCycle() {
        val originalText = "LedgerFlow secure backup content test payload!"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)
        val password = "StrongBackupPassword123!".toCharArray()

        // Encrypt
        val encryptedPayload = BackupEngine.encrypt(originalBytes, password)
        assertNotNull(encryptedPayload)

        // Decrypt
        val decryptedBytes = BackupEngine.decrypt(encryptedPayload, password)
        assertNotNull(decryptedBytes)

        // Verify equality
        assertArrayEquals(originalBytes, decryptedBytes)
    }
}
