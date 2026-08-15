package com.ledgerflow.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.Dek
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
 * Real SQLCipher, real Room, on a real device.
 *
 * Covers the things a JVM test cannot: that the database is genuinely
 * encrypted at rest, that the ledger views behave, and that the schema Room
 * generates actually opens.
 */
@RunWith(AndroidJUnit4::class)
class LedgerFlowDatabaseInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "ledgerflow-test-${System.nanoTime()}.db"
    private val dek = Dek(ByteArray(Dek.LENGTH) { (it * 7).toByte() })

    private lateinit var database: LedgerFlowDatabase

    @Before
    fun setUp() {
        database = LedgerFlowDatabaseFactory.create(context, dek, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.getDatabasePath(databaseName).let { file ->
            file.delete()
            File("${file.path}-wal").delete()
            File("${file.path}-shm").delete()
        }
    }

    private fun seed() = runBlocking {
        database.appMetaDao().putAll(
            listOf(
                AppMetaEntity(AppMetaEntity.KEY_CANARY, AppMetaEntity.CANARY_VALUE),
                AppMetaEntity(AppMetaEntity.KEY_BASE_CURRENCY, "INR"),
            ),
        )
        database.categoryDao().insertAll(
            listOf(
                CategoryEntity(
                    id = "cat-groceries", parentId = null, parentKey = "",
                    ledgerScope = LedgerType.DEBIT, name = "Groceries",
                    icon = "cart", colorArgb = 0x00FF8800, sortOrder = 1,
                ),
                CategoryEntity(
                    id = "cat-salary", parentId = null, parentKey = "",
                    ledgerScope = LedgerType.CREDIT, name = "Salary",
                    icon = "wallet", colorArgb = 0x005FD0A6, sortOrder = 1,
                ),
            ),
        )
        database.merchantDao().insertAll(
            listOf(MerchantEntity("mer-1", "Big Bazaar", "BIGBAZAAR", null, null)),
        )
        database.paymentMethodDao().insertAll(
            listOf(
                PaymentMethodEntity(
                    id = "pm-1", type = PaymentMethodType.UPI, label = "GPay",
                    issuer = "HDFC", last4 = "4321", colorArgb = null,
                ),
            ),
        )
        database.ledgerEntryDao().insertEntryWithLineItems(
            entry = entry("entry-debit", LedgerType.DEBIT, 125_000),
            lineItems = listOf(
                LineItemEntity(
                    id = "li-1", entryId = "entry-debit", position = 0,
                    name = "Rice", normalizedName = "RICE",
                    unitPriceMinor = 60_000, totalMinor = Money(60_000),
                    kind = LineItemKind.ITEM, categoryId = null, subcategoryId = null,
                ),
            ),
        )
        database.ledgerEntryDao().insertEntryWithLineItems(
            entry = entry("entry-credit", LedgerType.CREDIT, 5_000_000),
            lineItems = emptyList(),
        )
    }

    private fun entry(id: String, ledger: LedgerType, minor: Long) = LedgerEntryEntity(
        id = id,
        ledger = ledger,
        amountMinor = Money(minor),
        currency = "INR",
        originalAmountMinor = null,
        originalCurrency = null,
        fxRateMicro = null,
        occurredAt = 1_760_000_000_000L,
        localDate = 20_400,
        merchantId = "mer-1",
        categoryId = if (ledger == LedgerType.DEBIT) "cat-groceries" else "cat-salary",
        subcategoryId = null,
        paymentMethodId = "pm-1",
        note = "seeded",
        source = EntrySource.MANUAL,
        sourceRefId = null,
        createdAt = 1_760_000_000_000L,
        updatedAt = 1_760_000_000_000L,
    )

    @Test
    fun seedThenRead_roundTripsEveryTable() = runBlocking {
        seed()

        assertThat(database.appMetaDao().value(AppMetaEntity.KEY_CANARY))
            .isEqualTo(AppMetaEntity.CANARY_VALUE)
        assertThat(database.categoryDao().all()).hasSize(2)
        assertThat(database.merchantDao().all()).hasSize(1)
        assertThat(database.paymentMethodDao().all()).hasSize(1)
        assertThat(database.ledgerEntryDao().allForLedger(LedgerType.DEBIT)).hasSize(1)
        assertThat(database.ledgerEntryDao().allForLedger(LedgerType.CREDIT)).hasSize(1)
        assertThat(database.ledgerEntryDao().allLineItems()).hasSize(1)
    }

    /** Law 2: the views must be disjoint, and totals must never combine. */
    @Test
    fun ledgerViews_areDisjoint() = runBlocking {
        seed()

        val debitTotal = database.ledgerEntryDao().debitTotal(0, Int.MAX_VALUE)
        val creditTotal = database.ledgerEntryDao().creditTotal(0, Int.MAX_VALUE)

        assertThat(debitTotal).isEqualTo(125_000L)
        assertThat(creditTotal).isEqualTo(5_000_000L)
        assertThat(database.ledgerEntryDao().countForLedger(LedgerType.DEBIT)).isEqualTo(1)
        assertThat(database.ledgerEntryDao().countForLedger(LedgerType.CREDIT)).isEqualTo(1)
    }

    /** Money survives as an exact integer; no float ever touches it (Law 3). */
    @Test
    fun money_roundTripsExactly() = runBlocking {
        seed()

        val stored = database.ledgerEntryDao().allForLedger(LedgerType.DEBIT).single()

        assertThat(stored.amountMinor).isEqualTo(Money(125_000))
        assertThat(stored.amountMinor.minor).isEqualTo(125_000L)
    }

    /** The child rows must go with the parent -- ON DELETE CASCADE, FKs on. */
    @Test
    fun foreignKeysAreEnforced() = runBlocking {
        seed()

        val orphan = LineItemEntity(
            id = "li-orphan", entryId = "does-not-exist", position = 0,
            name = "x", normalizedName = "X", unitPriceMinor = null,
            totalMinor = Money(1), kind = LineItemKind.ITEM,
            categoryId = null, subcategoryId = null,
        )

        val error = runCatching {
            database.ledgerEntryDao().insertLineItems(listOf(orphan))
        }.exceptionOrNull()

        assertThat(error).isNotNull()
    }

    /**
     * The file on disk must not be a readable SQLite database.
     *
     * This is the assertion that actually proves SQLCipher is doing something:
     * a plaintext SQLite file starts with the magic string "SQLite format 3".
     */
    @Test
    fun databaseFileIsEncryptedAtRest() = runBlocking {
        seed()
        database.close()

        val header = context.getDatabasePath(databaseName).inputStream().use { stream ->
            ByteArray(16).also { stream.read(it) }
        }

        assertThat(String(header, Charsets.US_ASCII)).doesNotContain("SQLite format")
    }

    @Test
    fun walCheckpoint_succeeds() = runBlocking {
        seed()

        WalCheckpointObserver(database) { throw it }.checkpoint()

        assertThat(database.ledgerEntryDao().countForLedger(LedgerType.DEBIT)).isEqualTo(1)
    }
}
