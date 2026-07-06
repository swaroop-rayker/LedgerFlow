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
        private val DB_CREATED_AT = androidx.datastore.preferences.core.longPreferencesKey("db_created_at")
        private val DB_LAST_OPENED_AT = androidx.datastore.preferences.core.longPreferencesKey("db_last_opened_at")
        private val SEARCH_HISTORY = stringPreferencesKey("search_history")
        private val SAVED_SEARCHES = stringPreferencesKey("saved_searches")
        private val DATABASE_SEEDED = androidx.datastore.preferences.core.booleanPreferencesKey("database_seeded")
    }

    suspend fun saveDatabaseSeeded(seeded: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DATABASE_SEEDED] = seeded
        }
    }

    suspend fun isDatabaseSeeded(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[DATABASE_SEEDED] ?: false
    }

    suspend fun saveDatabaseCreatedTimestamp(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[DB_CREATED_AT] = timestamp
        }
    }

    suspend fun saveDatabaseLastOpenedTimestamp(timestamp: Long) {
        context.dataStore.edit { prefs ->
            prefs[DB_LAST_OPENED_AT] = timestamp
        }
    }

    suspend fun getDatabaseCreatedTimestamp(): Long {
        val prefs = context.dataStore.data.first()
        return prefs[DB_CREATED_AT] ?: 0L
    }

    suspend fun getDatabaseLastOpenedTimestamp(): Long {
        val prefs = context.dataStore.data.first()
        return prefs[DB_LAST_OPENED_AT] ?: 0L
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

    suspend fun getSearchHistory(): List<String> {
        val prefs = context.dataStore.data.first()
        val historyStr = prefs[SEARCH_HISTORY] ?: ""
        if (historyStr.isEmpty()) return emptyList()
        return historyStr.split("\n").filter { it.isNotEmpty() }
    }

    suspend fun saveSearchQuery(query: String) {
        val currentHistory = getSearchHistory().toMutableList()
        currentHistory.remove(query)
        currentHistory.add(0, query)
        if (currentHistory.size > 10) {
            currentHistory.removeAt(currentHistory.lastIndex)
        }
        val historyStr = currentHistory.joinToString("\n")
        context.dataStore.edit { editPrefs ->
            editPrefs[SEARCH_HISTORY] = historyStr
        }
    }

    suspend fun clearSearchHistory() {
        context.dataStore.edit { editPrefs ->
            editPrefs.remove(SEARCH_HISTORY)
        }
    }

    suspend fun getSavedSearches(): List<String> {
        val prefs = context.dataStore.data.first()
        val savedStr = prefs[SAVED_SEARCHES] ?: ""
        if (savedStr.isEmpty()) return emptyList()
        return savedStr.split("\n").filter { it.isNotEmpty() }
    }

    suspend fun saveSearch(query: String) {
        val current = getSavedSearches().toMutableList()
        if (!current.contains(query)) {
            current.add(0, query)
            val savedStr = current.joinToString("\n")
            context.dataStore.edit { editPrefs ->
                editPrefs[SAVED_SEARCHES] = savedStr
            }
        }
    }

    suspend fun deleteSavedSearch(query: String) {
        val current = getSavedSearches().toMutableList()
        if (current.remove(query)) {
            val savedStr = current.joinToString("\n")
            context.dataStore.edit { editPrefs ->
                editPrefs[SAVED_SEARCHES] = savedStr
            }
        }
    }
}
