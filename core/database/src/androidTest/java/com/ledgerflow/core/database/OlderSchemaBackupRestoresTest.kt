package com.ledgerflow.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.UnlockResult
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.database.backup.DatabaseBackupManager
import com.ledgerflow.core.database.backup.RestoreResult
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A backup written by an **older** schema restores into today's (SESSION-LOG-S14
 * §5 item 7: "no test restores a backup from an older schema").
 *
 * The risk is not the tables: every table added since v1 defaults to an empty
 * list in `BackupPayload`. It is the **rows** — a column added to an existing
 * row class without a default would make every older backup containing such a
 * row fail to decode, and the user would be told their backup is damaged. And
 * it is the **values**: a restored old backup should hold what a migrated old
 * database would, so the payload's defaults must match the migrations'.
 *
 * Each fixture is the JSON **exactly as that version's `BackupPayload` wrote
 * it** — every key it had, none it did not — taken from the payload class at
 * the commit that introduced the version (git history, 2026-09-27):
 * - **v1** (`f71f707`): six tables.
 * - **v7** (`2eea934`): + drafts, aliases, groups and the six ingest tables;
 *   drafts before `amountMinor`/`categoryId`/`merchantId`/`occurredAt`, pending
 *   rows before `reviewDraftJson`.
 * - **v9** (`a1ab5a3`): + budgets, before `lastAlertedThreshold` and
 *   `alertPeriodStart`.
 *
 * Sealed with that version in the container header, as the old writer did, and
 * restored through the real path. Invented rows only; a fixed test phrase.
 */
@RunWith(AndroidJUnit4::class)
class OlderSchemaBackupRestoresTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "ledgerflow_older_schema_kek_a"
    private val databaseName = "older-schema-${System.nanoTime()}.db"
    private val mnemonic: List<String> = Bip39.fromEntropy(ByteArray(Bip39.ENTROPY_BYTES) { 21 })

    private lateinit var keyDir: File
    private lateinit var keystore: AndroidKeystoreKek
    private lateinit var database: LedgerFlowDatabase

    @Before
    fun setUp() {
        keyDir = File(context.filesDir, "older-schema-keys-${System.nanoTime()}").apply { mkdirs() }
        keystore = AndroidKeystoreKek(alias).apply { delete() }
        val dek = (DekManager(FileWrappedDekStore(keyDir), keystore).initialize(mnemonic) as UnlockResult.Success).dek
        database = LedgerFlowDatabaseFactory.create(context, dek, databaseName)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        keystore.delete()
        context.deleteDatabase(databaseName)
        keyDir.deleteRecursively()
    }

    private fun restore(json: String, schemaVersion: Int): RestoreResult = runBlocking {
        val file = File(keyDir, "v$schemaVersion.lfbk")
        file.writeBytes(LfbkContainer.write(json.toByteArray(), Bip39.toSeed(mnemonic), schemaVersion))
        DatabaseBackupManager(database).restore(file, Bip39.toSeed(mnemonic))
    }

    private fun restored() = runBlocking { DatabaseBackupManager(database).export() }

    @Test
    fun aV1Backup_restoresEveryRow() {
        assertThat(restore(V1, schemaVersion = 1)).isInstanceOf(RestoreResult.Success::class.java)

        val now = restored()
        assertThat(now.categories.map { it.id }).contains("cat-groceries")
        assertThat(now.merchants.map { it.id }).containsExactly("m-zepto")
        assertThat(now.paymentMethods.map { it.id }).containsExactly("pm-upi")
        val entry = now.ledgerEntries.single()
        assertThat(entry.id).isEqualTo("e-1")
        assertThat(entry.amountMinor).isEqualTo(12_345L)
        assertThat(now.lineItems.single().entryId).isEqualTo("e-1")
        // Tables that did not exist at v1 come back empty, not broken.
        assertThat(now.drafts).isEmpty()
        assertThat(now.pendingTransactions).isEmpty()
        assertThat(now.budgets).isEmpty()
        // Every vault writes the canary and every backup carries app_meta, so a
        // real v1 backup holds v1's canary ("canary" = "LedgerFlow-canary-v1",
        // unchanged since f71f707). It must still satisfy today's check, or an
        // old backup restores into a vault that routes to Recovery every launch.
        assertThat(runBlocking { DatabaseCanary.verify(database) }).isEqualTo(CanaryResult.Valid)
    }

    /** Draft and pending columns added after v7 get exactly what their migrations give them. */
    @Test
    fun aV7Backup_restores_withTheLaterColumnsAsTheMigrationsWouldFillThem() {
        assertThat(restore(V7, schemaVersion = 7)).isInstanceOf(RestoreResult.Success::class.java)

        val now = restored()
        val draft = now.drafts.single()
        // Migration_3_4 / Migration_4_5: DEFAULT 0, NULL, NULL, DEFAULT 0.
        assertWithMessage("draft.amountMinor").that(draft.amountMinor).isEqualTo(0L)
        assertWithMessage("draft.categoryId").that(draft.categoryId).isNull()
        assertWithMessage("draft.merchantId").that(draft.merchantId).isNull()
        assertWithMessage("draft.occurredAt").that(draft.occurredAt).isEqualTo(0L)
        assertThat(draft.payloadJson).isEqualTo("{\"v\":1}")
        // Migration_7_8: NULL.
        val pending = now.pendingTransactions.single()
        assertWithMessage("pending.reviewDraftJson").that(pending.reviewDraftJson).isNull()
        assertThat(pending.status).isEqualTo("PENDING")
        assertThat(now.merchantAliases.single().alias).isEqualTo("ZEPTO MARKETPLACE")
        assertThat(now.ledgerEntries.single().id).isEqualTo("e-1")
    }

    /** Budget columns added after v9 get exactly what Migration_9_10 gives them. */
    @Test
    fun aV9Backup_restores_withTheLaterBudgetColumnsAsTheMigrationWouldFillThem() {
        assertThat(restore(V9, schemaVersion = 9)).isInstanceOf(RestoreResult.Success::class.java)

        val budget = restored().budgets.single()
        assertThat(budget.amountMinor).isEqualTo(500_000L)
        // Migration_9_10: DEFAULT 0, DEFAULT 0.
        assertWithMessage("budget.lastAlertedThreshold").that(budget.lastAlertedThreshold).isEqualTo(0)
        assertWithMessage("budget.alertPeriodStart").that(budget.alertPeriodStart).isEqualTo(0)
    }

    /**
     * v13 (V2-6): a v12 backup carries no merchant categories and its merchants
     * no category. They restore Uncategorised, exactly as `MIGRATION_12_13`
     * leaves a migrated v12 database; the launch seed fills the defaults after.
     */
    @Test
    fun aV12Backup_restores_withEveryMerchantUncategorised() {
        assertThat(restore(V12, schemaVersion = 12)).isInstanceOf(RestoreResult.Success::class.java)

        val payload = restored()
        assertThat(payload.merchants.single().merchantCategoryId).isNull()
        assertThat(payload.merchantCategories).isEmpty()
        assertThat(payload.merchantAliases.single().merchantId).isEqualTo("m-zepto")
    }

    /**
     * A v13 backup's receipts carry no `removedFromPhoneAt` (V2-5, ADR-0035).
     * They restore as on the phone, exactly as `MIGRATION_13_14` leaves a
     * migrated v13 database, and every other column comes through.
     */
    @Test
    fun aV13Backup_restores_withEveryReceiptOnThePhone() {
        assertThat(restore(V13, schemaVersion = 13)).isInstanceOf(RestoreResult.Success::class.java)

        val receipt = restored().attachments.single()
        assertThat(receipt.id).isEqualTo("att-1")
        assertThat(receipt.entryId).isEqualTo("e-1")
        assertThat(receipt.bytes).isEqualTo(412_000L)
        assertThat(receipt.removedFromPhoneAt).isNull()
    }

    /**
     * A v14 backup carries no goals (V2-7, V2-PLAN 4.1). It restores with
     * none, exactly as `MIGRATION_14_15` leaves a migrated v14 database, and
     * every v14 row comes through.
     */
    @Test
    fun aV14Backup_restores_withNoGoals() {
        assertThat(restore(V14, schemaVersion = 14)).isInstanceOf(RestoreResult.Success::class.java)

        val payload = restored()
        assertThat(payload.savingsGoals).isEmpty()
        assertThat(payload.goalContributions).isEmpty()
        assertThat(payload.attachments.single().removedFromPhoneAt).isEqualTo(1_760_000_000_000L)
    }

    /** A v15 backup carries no recurring items (V2-7, V2-PLAN 4.2); its goals come through. */
    @Test
    fun aV15Backup_restores_withNoRecurringItems() {
        assertThat(restore(V15, schemaVersion = 15)).isInstanceOf(RestoreResult.Success::class.java)

        val payload = restored()
        assertThat(payload.recurringItems).isEmpty()
        assertThat(payload.recurringOccurrences).isEmpty()
        assertThat(payload.recurringDismissals).isEmpty()
        assertThat(payload.savingsGoals.single().name).isEqualTo("Fund")
    }

    private companion object {
        /** The six tables v1 had, in v1's row shapes (unchanged since). */
        const val CORE = """
            "appMeta": [{"key": "canary", "value": "LedgerFlow-canary-v1"},
                {"key": "fixture.origin", "value": "older-schema-test"}],
            "categories": [{"id": "cat-groceries", "parentId": null, "parentKey": "", "ledgerScope": "DEBIT",
                "name": "Groceries", "icon": "cart", "colorArgb": -16711936, "sortOrder": 1,
                "isSystem": false, "deletedAt": 0}],
            "merchants": [{"id": "m-zepto", "canonicalName": "Zepto", "normalizedKey": "zepto",
                "defaultCategoryId": "cat-groceries", "logoRef": null, "deletedAt": 0}],
            "paymentMethods": [{"id": "pm-upi", "type": "UPI", "label": "UPI", "issuer": null,
                "last4": null, "colorArgb": null, "isDefault": true, "deletedAt": 0}],
            "ledgerEntries": [{"id": "e-1", "ledger": "DEBIT", "amountMinor": 12345, "currency": "INR",
                "originalAmountMinor": null, "originalCurrency": null, "fxRateMicro": null,
                "occurredAt": 1755000000000, "localDate": 20312, "merchantId": "m-zepto",
                "categoryId": "cat-groceries", "subcategoryId": null, "paymentMethodId": "pm-upi",
                "note": null, "source": "MANUAL", "sourceRefId": null, "isRecurring": false,
                "createdAt": 1755000000000, "updatedAt": 1755000000000, "deletedAt": null}],
            "lineItems": [{"id": "li-1", "entryId": "e-1", "position": 0, "name": "Milk",
                "normalizedName": "milk", "quantityMilli": 1000, "unitPriceMinor": 12345,
                "totalMinor": 12345, "kind": "ITEM", "categoryId": "cat-groceries", "subcategoryId": null}]
        """

        /** What v2..v7 added, as v7 wrote it: drafts and pending rows before their later columns. */
        const val V7_ADDITIONS = """
            "drafts": [{"id": "d-1", "ledger": "DEBIT", "editingEntryId": null, "editingEntryKey": "",
                "payloadJson": "{\"v\":1}", "payloadVersion": 1,
                "createdAt": 1755000000000, "updatedAt": 1755000000000}],
            "merchantAliases": [{"id": "a-1", "merchantId": "m-zepto", "alias": "ZEPTO MARKETPLACE",
                "normalizedAlias": "zepto marketplace"}],
            "categoryGroups": [],
            "categoryGroupMembers": [],
            "smsRaw": [],
            "notificationsRaw": [],
            "packageAllowlist": [],
            "senderAllowlist": [],
            "parserRules": [],
            "pendingTransactions": [{"id": "p-1", "source": "SMS", "dedupeKey": "k-1",
                "suppressedById": null, "rawRefId": null, "extractedJson": "{}", "confidence": 0.5,
                "status": "PENDING", "needsManualFill": false, "createdAt": 1755000000000,
                "reviewedAt": null, "approvedEntryId": null}]
        """

        val V1 = """{"schemaVersion": 1, "createdAt": 1755000000000, $CORE}"""

        val V7 = """{"schemaVersion": 7, "createdAt": 1755000000000, $CORE, $V7_ADDITIONS}"""

        /** v9 = v7 + budgets, as v9 wrote them: before the two alert columns of v10. */
        val V9 = """{"schemaVersion": 9, "createdAt": 1755000000000, $CORE, $V7_ADDITIONS,
            "budgets": [{"id": "b-1", "categoryId": "cat-groceries", "subcategoryId": null,
                "period": "MONTHLY", "amountMinor": 500000, "startDate": 20300,
                "rolloverEnabled": false, "alertThresholds": "80,100", "deletedAt": null}]}"""

        /** v12 = v9's rows as v12 wrote them: no `merchantCategories`, no `merchantCategoryId`. */
        val V12 = """{"schemaVersion": 12, "createdAt": 1755000000000, $CORE, $V7_ADDITIONS,
            "budgets": [], "attachments": [], "itemCategoryMemory": []}"""

        /** v13: one receipt as v13 wrote it, with no `removedFromPhoneAt`. */
        val V13 = """{"schemaVersion": 13, "createdAt": 1755000000000, $CORE, $V7_ADDITIONS,
            "budgets": [], "itemCategoryMemory": [], "merchantCategories": [],
            "attachments": [{"id": "att-1", "entryId": "e-1", "filePath": "att-1.lfa",
                "mime": "image/png", "sha256": "hash-1", "bytes": 412000, "createdAt": 1755000000000}]}"""

        /** v14: v13's receipt, removed from the phone, and no goal lists. */
        val V14 = """{"schemaVersion": 14, "createdAt": 1755000000000, $CORE, $V7_ADDITIONS,
            "budgets": [], "itemCategoryMemory": [], "merchantCategories": [],
            "attachments": [{"id": "att-1", "entryId": "e-1", "filePath": "att-1.lfa",
                "mime": "image/png", "sha256": "hash-1", "bytes": 412000, "createdAt": 1755000000000,
                "removedFromPhoneAt": 1760000000000}]}"""

        /** v15: one goal, and no recurring lists. */
        val V15 = """{"schemaVersion": 15, "createdAt": 1755000000000, $CORE, $V7_ADDITIONS,
            "budgets": [], "itemCategoryMemory": [], "merchantCategories": [], "attachments": [],
            "savingsGoals": [{"id": "g-1", "name": "Fund", "targetMinor": 10000000, "targetDate": null,
                "linkedCategoryId": null, "countFrom": null, "sortOrder": 0, "createdAt": 1, "updatedAt": 1}],
            "goalContributions": []}"""
    }
}
