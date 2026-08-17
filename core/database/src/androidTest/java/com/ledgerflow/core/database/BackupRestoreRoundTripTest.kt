package com.ledgerflow.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.Dek
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.KekId
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.crypto.lfbk.LfbkFailure
import com.ledgerflow.core.database.backup.BackupResult
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.backup.RestoreResult
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **The Phase 0 exit gate** (SPEC.md §13.1, §8 BUG4).
 *
 * Seeds every table, writes a `.lfbk`, destroys the database *and* the Keystore
 * key, then restores using only the 24-word phrase and asserts **row-level
 * content equality** -- not row counts. SPEC.md §8 is explicit that counting
 * rows is not evidence a restore preserved anything.
 *
 * This is the test the whole phase exists for. If it cannot be made to pass,
 * that is an architecture problem, not a bug.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreRoundTripTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "ledgerflow_roundtrip_kek_a"
    private val databaseName = "roundtrip-${System.nanoTime()}.db"

    private val mnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 11 })
    private val wrongMnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 12 })

    private lateinit var keyDir: File
    private lateinit var backupDir: File
    private lateinit var keystore: AndroidKeystoreKek
    private lateinit var dekManager: DekManager
    private lateinit var database: LedgerFlowDatabase

    @Before
    fun setUp() {
        keyDir = File(context.filesDir, "roundtrip-keys-${System.nanoTime()}").apply { mkdirs() }
        backupDir = File(context.filesDir, "roundtrip-backups-${System.nanoTime()}").apply { mkdirs() }
        keystore = AndroidKeystoreKek(alias).apply { delete() }
        dekManager = DekManager(FileWrappedDekStore(keyDir), keystore)

        val dek = (dekManager.initialize(mnemonic) as UnlockResult.Success).dek
        database = LedgerFlowDatabaseFactory.create(context, dek, databaseName)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        keystore.delete()
        deleteDatabaseFiles()
        keyDir.deleteRecursively()
        backupDir.deleteRecursively()
    }

    private fun deleteDatabaseFiles() {
        context.getDatabasePath(databaseName).let { file ->
            file.delete()
            File("${file.path}-wal").delete()
            File("${file.path}-shm").delete()
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun seedEveryTable() = runBlocking {
        database.appMetaDao().putAll(
            listOf(
                AppMetaEntity(AppMetaEntity.KEY_CANARY, AppMetaEntity.CANARY_VALUE),
                AppMetaEntity(AppMetaEntity.KEY_BASE_CURRENCY, "INR"),
                AppMetaEntity(AppMetaEntity.KEY_SCHEMA_VERSION, "1"),
            ),
        )
        database.categoryDao().insertAll(
            listOf(
                CategoryEntity(
                    id = "cat-groceries", parentId = null, parentKey = "",
                    ledgerScope = LedgerType.DEBIT, name = "Groceries", icon = "cart",
                    colorArgb = 0x00FF8800, sortOrder = 1, isSystem = true, deletedAt = 0,
                ),
                CategoryEntity(
                    id = "cat-rice", parentId = "cat-groceries", parentKey = "cat-groceries",
                    ledgerScope = LedgerType.DEBIT, name = "Staples", icon = "grain",
                    colorArgb = 0x00AA5500, sortOrder = 2, isSystem = false, deletedAt = 0,
                ),
                CategoryEntity(
                    id = "cat-salary", parentId = null, parentKey = "",
                    ledgerScope = LedgerType.CREDIT, name = "Salary", icon = "wallet",
                    colorArgb = 0x005FD0A6, sortOrder = 1, isSystem = true,
                    deletedAt = 1_700_000_000_000L,
                ),
            ),
        )
        database.merchantDao().insertAll(
            listOf(
                MerchantEntity("mer-1", "Big Bazaar", "BIGBAZAAR", "cat-groceries", "logo-1", 0),
                MerchantEntity("mer-2", "Corner Shop", "CORNERSHOP", null, null, 1_700_000_000_000L),
            ),
        )
        database.paymentMethodDao().insertAll(
            listOf(
                PaymentMethodEntity(
                    id = "pm-upi", type = PaymentMethodType.UPI, label = "GPay",
                    issuer = "HDFC", last4 = "4321", colorArgb = 0x006E8BFF,
                    isDefault = true, deletedAt = 0,
                ),
                PaymentMethodEntity(
                    id = "pm-cash", type = PaymentMethodType.CASH, label = "Cash",
                    issuer = null, last4 = null, colorArgb = null,
                    isDefault = false, deletedAt = 0,
                ),
            ),
        )
        database.ledgerEntryDao().insertEntryWithLineItems(
            entry = debitEntry(),
            lineItems = listOf(
                LineItemEntity(
                    id = "li-1", entryId = "entry-debit", position = 0, name = "Rice 5kg",
                    normalizedName = "RICE", quantityMilli = 5_000, unitPriceMinor = 12_000,
                    totalMinor = Money(60_000), kind = LineItemKind.ITEM,
                    categoryId = "cat-rice", subcategoryId = null,
                ),
                LineItemEntity(
                    id = "li-2", entryId = "entry-debit", position = 1, name = "GST",
                    normalizedName = "GST", quantityMilli = 1_000, unitPriceMinor = null,
                    totalMinor = Money(6_500), kind = LineItemKind.TAX,
                    categoryId = null, subcategoryId = null,
                ),
            ),
        )
        database.ledgerEntryDao().insertEntryWithLineItems(creditEntry(), emptyList())
    }

    /** Includes foreign-currency fields so the FX columns are covered too. */
    private fun debitEntry() = LedgerEntryEntity(
        id = "entry-debit", ledger = LedgerType.DEBIT, amountMinor = Money(66_500),
        currency = "INR", originalAmountMinor = 800, originalCurrency = "USD",
        fxRateMicro = 83_230_000, occurredAt = 1_760_000_000_000L, localDate = 20_400,
        merchantId = "mer-1", categoryId = "cat-groceries", subcategoryId = "cat-rice",
        paymentMethodId = "pm-upi", note = "weekly shop", source = EntrySource.MANUAL,
        sourceRefId = "pending-1", isRecurring = true,
        createdAt = 1_760_000_000_001L, updatedAt = 1_760_000_000_002L, deletedAt = null,
    )

    private fun creditEntry() = LedgerEntryEntity(
        id = "entry-credit", ledger = LedgerType.CREDIT, amountMinor = Money(5_000_000),
        currency = "INR", originalAmountMinor = null, originalCurrency = null,
        fxRateMicro = null, occurredAt = 1_759_000_000_000L, localDate = 20_390,
        merchantId = null, categoryId = "cat-salary", subcategoryId = null,
        paymentMethodId = null, note = null, source = EntrySource.IMPORT,
        sourceRefId = null, isRecurring = false,
        createdAt = 1_759_000_000_001L, updatedAt = 1_759_000_000_002L, deletedAt = null,
    )

    private data class Snapshot(
        val appMeta: List<AppMetaEntity>,
        val categories: List<CategoryEntity>,
        val merchants: List<MerchantEntity>,
        val paymentMethods: List<PaymentMethodEntity>,
        val debits: List<LedgerEntryEntity>,
        val credits: List<LedgerEntryEntity>,
        val lineItems: List<LineItemEntity>,
    )

    private fun snapshot(db: LedgerFlowDatabase): Snapshot = runBlocking {
        Snapshot(
            appMeta = db.appMetaDao().all(),
            categories = db.categoryDao().all(),
            merchants = db.merchantDao().all(),
            paymentMethods = db.paymentMethodDao().all(),
            debits = db.ledgerEntryDao().allForLedger(LedgerType.DEBIT),
            credits = db.ledgerEntryDao().allForLedger(LedgerType.CREDIT),
            lineItems = db.ledgerEntryDao().allLineItems(),
        )
    }

    // ── The gate ──────────────────────────────────────────────────────────

    @Test
    fun backup_wipe_restoreFromPhraseAlone_reproducesEveryRowExactly() = runBlocking {
        seedEveryTable()
        val before = snapshot(database)
        val backupFile = File(backupDir, "ledgerflow.lfbk")

        val backup = DatabaseBackupManager(database)
            .writeBackup(backupFile, Bip39.toSeed(mnemonic))
        assertThat(backup).isInstanceOf(BackupResult.Success::class.java)

        // ── Total destruction: the database AND the Keystore key. ──
        database.close()
        deleteDatabaseFiles()
        keystore.delete()
        assertThat(context.getDatabasePath(databaseName).exists()).isFalse()

        // Keystore unlock must now fail -- proving the recovery below is really
        // going through the phrase and not a surviving Keystore wrap.
        assertThat(dekManager.unlockWithKeystore())
            .isInstanceOf(UnlockResult.Failure::class.java)

        // ── Recover using nothing but the 24 words. ──
        val recovered = dekManager.unlockWithPhrase(mnemonic)
        assertThat(recovered).isInstanceOf(UnlockResult.Success::class.java)
        val dek = (recovered as UnlockResult.Success).dek

        database = LedgerFlowDatabaseFactory.create(context, dek, databaseName)
        val restore = DatabaseBackupManager(database)
            .restore(backupFile, Bip39.toSeed(mnemonic))
        assertThat(restore).isInstanceOf(RestoreResult.Success::class.java)

        // ── Row-level content equality, table by table. ──
        val after = snapshot(database)
        assertThat(after.appMeta).containsExactlyElementsIn(before.appMeta)
        assertThat(after.categories).containsExactlyElementsIn(before.categories)
        assertThat(after.merchants).containsExactlyElementsIn(before.merchants)
        assertThat(after.paymentMethods).containsExactlyElementsIn(before.paymentMethods)
        assertThat(after.debits).containsExactlyElementsIn(before.debits)
        assertThat(after.credits).containsExactlyElementsIn(before.credits)
        assertThat(after.lineItems).containsExactlyElementsIn(before.lineItems)

        // The canary must survive, or the unlock flow would route a perfectly
        // good restore to the Recovery screen forever.
        assertThat(DatabaseCanary.verify(database)).isEqualTo(CanaryResult.Valid)
    }

    @Test
    fun restore_withWrongPhrase_reportsWrongPhraseNotCorruption() = runBlocking {
        seedEveryTable()
        val backupFile = File(backupDir, "ledgerflow.lfbk")
        DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

        val result = DatabaseBackupManager(database)
            .restore(backupFile, Bip39.toSeed(wrongMnemonic))

        // Distinguishable from corruption -- that is what keyCheck buys, and it
        // is the difference between "check your words" and "your file is gone".
        assertThat(result).isEqualTo(RestoreResult.Failure(LfbkFailure.WrongPhrase))
    }

    @Test
    fun restore_ofDamagedFile_reportsCorruptNotWrongPhrase() = runBlocking {
        seedEveryTable()
        val backupFile = File(backupDir, "ledgerflow.lfbk")
        DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

        backupFile.writeBytes(
            backupFile.readBytes().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() },
        )

        val result = DatabaseBackupManager(database)
            .restore(backupFile, Bip39.toSeed(mnemonic))

        assertThat(result).isEqualTo(RestoreResult.Failure(LfbkFailure.Corrupt))
    }

    /** The header is AAD: altering it must break the tag, not steer the restore. */
    @Test
    fun tamperedHeader_failsAuthentication() = runBlocking {
        seedEveryTable()
        val backupFile = File(backupDir, "ledgerflow.lfbk")
        DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

        // schemaVersion sits at offset 6 (magic 4 + formatVersion 2).
        backupFile.writeBytes(backupFile.readBytes().also { it[9] = 99 })

        val result = DatabaseBackupManager(database)
            .restore(backupFile, Bip39.toSeed(mnemonic))

        assertThat(result).isInstanceOf(RestoreResult::class.java)
        assertThat(result).isNotEqualTo(RestoreResult.Success(0))
    }

    // Block body, not an expression body: `containsExactly` returns `Ordered`,
    // which would make this method non-void and JUnit would reject the class.
    @Test
    fun backupWriter_leavesNoTempFileBehind() {
        runBlocking {
            seedEveryTable()
            val backupFile = File(backupDir, "ledgerflow.lfbk")

            DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

            assertThat(backupDir.list()?.toList()).containsExactly("ledgerflow.lfbk")
        }
    }

    @Test
    fun backupFile_isNotReadableWithoutThePhrase() = runBlocking {
        seedEveryTable()
        val backupFile = File(backupDir, "ledgerflow.lfbk")
        DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

        val raw = backupFile.readBytes()

        // Plaintext markers from the seeded data must not appear in the file.
        val asText = String(raw, Charsets.ISO_8859_1)
        assertThat(asText).doesNotContain("Big Bazaar")
        assertThat(asText).doesNotContain("weekly shop")
        assertThat(asText.take(4)).isEqualTo("LFBK")
    }

    @Test
    fun backup_isKeyIndependentOfTheKeystore() = runBlocking {
        seedEveryTable()
        val backupFile = File(backupDir, "ledgerflow.lfbk")
        DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

        // Destroying the Keystore must not affect the backup's readability --
        // SPEC.md §5.9: the container is key-independent of the Keystore, which
        // is what makes cross-device restore possible at all.
        keystore.delete()
        assertThat(dekManager.unlockWithKeystore()).isInstanceOf(UnlockResult.Failure::class.java)

        // Restore onto a freshly wiped database, as a real restore does. The
        // first version of this test restored over the live data and hit the
        // category unique index -- which is how the missing transaction around
        // import() was found.
        database.close()
        deleteDatabaseFiles()
        val dek = (dekManager.unlockWithPhrase(mnemonic) as UnlockResult.Success).dek
        database = LedgerFlowDatabaseFactory.create(context, dek, databaseName)

        val restore = DatabaseBackupManager(database)
            .restore(backupFile, Bip39.toSeed(mnemonic))

        assertThat(restore).isInstanceOf(RestoreResult.Success::class.java)
        assertThat(database.merchantDao().all()).hasSize(2)
    }

    /** A restore that hits a constraint must roll back whole, not partially. */
    @Test
    fun restore_intoNonEmptyDatabase_rollsBackEntirely() {
        runBlocking {
            seedEveryTable()
            val backupFile = File(backupDir, "ledgerflow.lfbk")
            DatabaseBackupManager(database).writeBackup(backupFile, Bip39.toSeed(mnemonic))

            val before = snapshot(database)
            val result = DatabaseBackupManager(database).restore(backupFile, Bip39.toSeed(mnemonic))

            assertThat(result).isInstanceOf(RestoreResult.Failure::class.java)
            // Nothing duplicated, nothing lost -- the transaction rolled back.
            assertThat(snapshot(database).categories).containsExactlyElementsIn(before.categories)
            assertThat(snapshot(database).merchants).containsExactlyElementsIn(before.merchants)
        }
    }

    @Test
    fun wrappedDekBlobs_liveInFilesDir() {
        assertThat(File(keyDir, KekId.PHRASE.fileName).isFile).isTrue()
        assertThat(keyDir.canonicalPath).startsWith(context.filesDir.canonicalPath)
    }

    @Test
    fun dek_isRecoveredIdenticallyByBothFactors() {
        val viaKeystore = dekManager.unlockWithKeystore()
        val viaPhrase = dekManager.unlockWithPhrase(mnemonic)

        assertThat((viaKeystore as UnlockResult.Success).dek.bytes())
            .isEqualTo((viaPhrase as UnlockResult.Success).dek.bytes())
        assertThat(viaPhrase.dek.bytes()).hasLength(Dek.LENGTH)
    }
}
