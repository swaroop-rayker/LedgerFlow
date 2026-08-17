package com.ledgerflow.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ledgerflow.core.crypto.Dek
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Builds the SQLCipher-backed Room database, keyed from `:core:crypto`.
 *
 * Note what is *not* here: the destructive-migration fallback. Law 4 bans it,
 * `scripts/guard-schema.sh` greps for it, and its absence is the difference
 * between "the upgrade failed" and "the user's ledger is gone".
 *
 * (Deliberately not spelling that API's name with parentheses: the guard
 * matches the call form, and it cannot tell a comment from code. Naming it here
 * would fail the build -- which is the guard working, if bluntly.)
 */
public object LedgerFlowDatabaseFactory {

    /**
     * @param dek the unwrapped data encryption key. The caller owns the unlock
     *   flow (SPEC.md §7.3); this function assumes the DEK is already recovered
     *   and never attempts recovery or wiping of its own.
     */
    public fun create(
        context: Context,
        dek: Dek,
        databaseName: String = LedgerFlowDatabase.DATABASE_NAME,
    ): LedgerFlowDatabase {
        loadNativeLibrary()

        // SQLCipher takes the key as a byte array so it can be zeroed; passing a
        // String would leave the key in the JVM string pool until GC.
        val factory = SupportOpenHelperFactory(dek.bytes(), PragmaHook, false)

        return Room.databaseBuilder(context, LedgerFlowDatabase::class.java, databaseName)
            .openHelperFactory(factory)
            // WAL is required for the ON_STOP checkpoint discipline (BUG2).
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(ForeignKeysCallback)
            .build()
    }

    private fun loadNativeLibrary() {
        System.loadLibrary("sqlcipher")
    }

    /**
     * SQLCipher PRAGMAs (SPEC.md §7.1).
     *
     * `cipher_page_size` (4096) and `kdf_iter` (256000) are already the
     * SQLCipher 4 defaults and are deliberately left alone -- the spec's
     * instruction is "do NOT lower kdf_iter", and restating a default is how it
     * later gets "tuned" by someone who does not know why it is there.
     *
     * `cipher_memory_security` is not on by default, so it is set explicitly.
     * It keeps key material and decrypted pages out of swappable memory.
     */
    private object PragmaHook : SQLiteDatabaseHook {
        override fun preKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection?) {
            connection?.execute("PRAGMA cipher_memory_security = ON;", emptyArray(), null)
        }

        override fun postKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection?) {
            // Intentionally empty: defaults are correct for page size and KDF.
        }
    }

    /**
     * Room enables foreign keys per connection, but WAL connections created
     * outside Room's normal path do not inherit it. Setting it in the callback
     * makes `PRAGMA foreign_key_check` after a migration meaningful.
     */
    private object ForeignKeysCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON;")
        }
    }
}
