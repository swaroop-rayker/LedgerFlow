package com.ledgerflow.core.database

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.Dek
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.migration.MigrationAssessment
import com.ledgerflow.core.database.migration.PreMigrationGuard
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG56 (ADR-0029): every SQLCipher connection derived the key from the DEK
 * itself, five times per launch, ~2.1 s of Home's 2.65 s to fully drawn.
 *
 * The fix derives once and keys every connection with the result. That is
 * only a fix if the result **is** the key SQLCipher derived before: same file,
 * same key, no rekey. So this proves it both ways round on real SQLCipher --
 * a vault made under the passphrase opens under the derived raw key, pages
 * written under the raw key read back under the passphrase -- and that a wrong
 * DEK opens neither.
 *
 * **If this fails after a SQLCipher upgrade, the library's default derivation
 * changed** (KDF, iterations or salt). Every existing vault was written under
 * the old default, so that is a format migration needing its own ADR -- never
 * a constant to bump in [DatabaseKey], and never this test adjusted to match.
 */
@RunWith(AndroidJUnit4::class)
class Bug56_DerivedRawKeyOpensPassphraseVaultTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "bug56-${System.nanoTime()}.db"
    private val file: File get() = context.getDatabasePath(name)
    private val dek = Dek(ByteArray(Dek.LENGTH) { (it * 11 + 3).toByte() })

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        listOf("", "-wal", "-shm", "-journal").forEach { File("${file.path}$it").delete() }
    }

    @Test
    fun passphraseVault_opensUnderTheDerivedRawKey() {
        createUnderPassphrase(rows = listOf("canary"))

        val started = SystemClock.elapsedRealtime()
        val key = DatabaseKey.forFile(file, dek)
        val derivedIn = SystemClock.elapsedRealtime() - started
        val raw = key.bytes()

        // The raw form, not a passphrase that happens to work: x'<64 hex><32 hex>'.
        assertThat(raw.size).isEqualTo(RAW_KEY_LENGTH)
        assertThat(raw.copyOfRange(0, 2).toString(Charsets.US_ASCII)).isEqualTo("x'")

        val rawStarted = SystemClock.elapsedRealtime()
        assertThat(readRows(raw)).containsExactly("canary")
        val rawOpenIn = SystemClock.elapsedRealtime() - rawStarted

        val passphraseStarted = SystemClock.elapsedRealtime()
        assertThat(readRows(dek.bytes())).containsExactly("canary")
        val passphraseOpenIn = SystemClock.elapsedRealtime() - passphraseStarted

        // ADR-0029's premise, recorded where the device can be asked again.
        Log.i(TAG, "derive=${derivedIn}ms rawOpen=${rawOpenIn}ms passphraseOpen=${passphraseOpenIn}ms")
    }

    @Test
    fun pagesWrittenUnderTheRawKey_readBackUnderThePassphrase() {
        createUnderPassphrase(rows = listOf("before"))
        val raw = DatabaseKey.forFile(file, dek).bytes()

        open(raw, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Enough rows to dirty pages beyond the first.
            repeat(MANY) { db.execSQL("INSERT INTO t(v) VALUES (?)", arrayOf<Any>("after-$it")) }
        }

        val rows = readRows(dek.bytes())
        assertThat(rows).hasSize(MANY + 1)
        assertThat(rows).contains("before")
        assertThat(rows).contains("after-${MANY - 1}")
    }

    @Test
    fun aWrongDek_opensNeitherWay() {
        createUnderPassphrase(rows = listOf("canary"))
        val wrong = Dek(ByteArray(Dek.LENGTH) { (it * 13 + 1).toByte() })

        assertThat(runCatching { readRows(DatabaseKey.forFile(file, wrong).bytes()) }.isFailure).isTrue()
        assertThat(runCatching { readRows(wrong.bytes()) }.isFailure).isTrue()
    }

    @Test
    fun noFileYet_isTheDekAsThePassphrase() {
        assertThat(file.exists()).isFalse()

        assertThat(DatabaseKey.forFile(file, dek).bytes()).isEqualTo(dek.bytes())
    }

    /** The app's own path: Room creates under the passphrase, the next launch opens raw. */
    @Test
    fun roomVault_createdOnce_reopensUnderTheDerivedKey_andTheGuardReadsIt() = runBlocking {
        LedgerFlowDatabaseFactory.create(context, dek, name).let { created ->
            DatabaseCanary.write(created)
            created.close()
        }

        val key = DatabaseKey.forFile(file, dek)
        assertThat(key.bytes().size).isEqualTo(RAW_KEY_LENGTH)
        val guard = PreMigrationGuard(file, File(context.filesDir, "bug56-snapshots"))
        assertThat(guard.assess(key.bytes())).isEqualTo(MigrationAssessment.NotNeeded)

        val reopened = LedgerFlowDatabaseFactory.create(context, key, name)
        try {
            assertThat(DatabaseCanary.verify(reopened)).isEqualTo(CanaryResult.Valid)
            assertThat(reopened.appMetaDao().value(AppMetaEntity.KEY_CANARY))
                .isEqualTo(AppMetaEntity.CANARY_VALUE)
        } finally {
            reopened.close()
            key.destroy()
        }
    }

    private fun createUnderPassphrase(rows: List<String>) {
        open(dek.bytes(), SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY).use { db ->
            db.execSQL("CREATE TABLE t(v TEXT NOT NULL)")
            rows.forEach { db.execSQL("INSERT INTO t(v) VALUES (?)", arrayOf<Any>(it)) }
        }
    }

    private fun readRows(key: ByteArray): List<String> =
        open(key, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT v FROM t", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }

    private fun open(key: ByteArray, flags: Int): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, key, null, flags, null)

    private companion object {
        const val TAG = "Bug56"

        /** `x'` + 64 hex (key) + 32 hex (salt) + `'`. */
        const val RAW_KEY_LENGTH = 99
        const val MANY = 500
    }
}
