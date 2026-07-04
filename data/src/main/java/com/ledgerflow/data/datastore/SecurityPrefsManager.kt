package com.ledgerflow.data.datastore

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ledgerflow.core.security.crypto.KeyStoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "security_settings")

@Singleton
class SecurityPrefsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStoreManager: KeyStoreManager
) {
    companion object {
        private val ENCRYPTED_DB_KEY = stringPreferencesKey("encrypted_db_key")
        private val DB_KEY_IV = stringPreferencesKey("db_key_iv")
        private val ENCRYPTED_QUEUE_KEY = stringPreferencesKey("encrypted_queue_key")
        private val QUEUE_KEY_IV = stringPreferencesKey("queue_key_iv")
    }

    /**
     * Resolves the main database passphrase. 
     * If not initialized, generates a random key, encrypts it using Keystore, saves it, and returns the plain key.
     */
    suspend fun getOrCreateDatabasePassphrase(): ByteArray {
        val prefs = context.dataStore.data.first()
        val encryptedKeyBase64 = prefs[ENCRYPTED_DB_KEY]
        val ivBase64 = prefs[DB_KEY_IV]

        if (encryptedKeyBase64 != null && ivBase64 != null) {
            // Decrypt key
            val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val bootKey = keyStoreManager.getOrCreateBootKey()
            return keyStoreManager.decrypt(encryptedKey, iv, bootKey)
        } else {
            // Generate a secure 256-bit (32 bytes) random passphrase
            val rawKey = ByteArray(32)
            SecureRandom().nextBytes(rawKey)
            
            // Encrypt key
            val bootKey = keyStoreManager.getOrCreateBootKey()
            val (encryptedKey, iv) = keyStoreManager.encrypt(rawKey, bootKey)
            
            // Save to DataStore
            context.dataStore.edit { editPrefs ->
                editPrefs[ENCRYPTED_DB_KEY] = Base64.encodeToString(encryptedKey, Base64.NO_WRAP)
                editPrefs[DB_KEY_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
            }
            
            return rawKey
        }
    }

    /**
     * Resolves the pending queue database passphrase.
     * Accessible during boot without requiring biometric auth.
     */
    suspend fun getOrCreateQueueDatabasePassphrase(): ByteArray {
        val prefs = context.dataStore.data.first()
        val encryptedKeyBase64 = prefs[ENCRYPTED_QUEUE_KEY]
        val ivBase64 = prefs[QUEUE_KEY_IV]

        if (encryptedKeyBase64 != null && ivBase64 != null) {
            val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.NO_WRAP)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val bootKey = keyStoreManager.getOrCreateBootKey()
            return keyStoreManager.decrypt(encryptedKey, iv, bootKey)
        } else {
            val rawKey = ByteArray(32)
            SecureRandom().nextBytes(rawKey)
            val bootKey = keyStoreManager.getOrCreateBootKey()
            val (encryptedKey, iv) = keyStoreManager.encrypt(rawKey, bootKey)
            context.dataStore.edit { editPrefs ->
                editPrefs[ENCRYPTED_QUEUE_KEY] = Base64.encodeToString(encryptedKey, Base64.NO_WRAP)
                editPrefs[QUEUE_KEY_IV] = Base64.encodeToString(iv, Base64.NO_WRAP)
            }
            return rawKey
        }
    }
}
