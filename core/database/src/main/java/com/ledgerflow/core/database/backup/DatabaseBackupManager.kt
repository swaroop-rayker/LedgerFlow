package com.ledgerflow.core.database.backup

import androidx.room.withTransaction
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.crypto.lfbk.LfbkFailure
import com.ledgerflow.core.crypto.lfbk.LfbkResult
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryGroupEntity
import com.ledgerflow.core.database.entity.CategoryGroupMemberEntity
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.database.entity.MerchantAliasEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.model.LedgerType
import java.io.File
import kotlinx.serialization.json.Json

/** Outcome of writing a backup. */
public sealed interface BackupResult {
    /**
     * The file was written **and** verified by decrypting and parsing it back.
     * Only now may `lastBackupAt` be updated (CLAUDE.md §7).
     */
    public data class Success(val file: File, val rowCount: Int, val fingerprint: String) :
        BackupResult

    public data class Failure(val reason: String) : BackupResult
}

/** Outcome of restoring a backup. */
public sealed interface RestoreResult {
    public data class Success(val rowCount: Int) : RestoreResult
    public data class Failure(val reason: LfbkFailure) : RestoreResult

    /** A backup written by a newer schema. Never guess forward. */
    public data class SchemaTooNew(val backupVersion: Int, val supported: Int) : RestoreResult
}

/**
 * Writes and restores `.lfbk` backups.
 *
 * **The write is atomic and verified**: temp file -> fsync -> decrypt-and-parse
 * -> rename. A backup that has not been round-trip verified is not a backup,
 * and `lastBackupAt` must not be updated for it (CLAUDE.md §7, SPEC.md §8 BUG4).
 * Writing straight to the destination would mean a crash mid-write leaves a
 * truncated file exactly where the previous good backup used to be -- turning a
 * backup system into a data-loss mechanism.
 */
public class DatabaseBackupManager(
    private val database: LedgerFlowDatabase,
    private val json: Json = Json { encodeDefaults = true },
) {

    /**
     * @param seed the BIP-39 seed. **Never a passphrase** -- a `.lfbk` can leave
     *   the device, so only the 256-bit phrase may protect it.
     */
    public suspend fun writeBackup(destination: File, seed: ByteArray): BackupResult {
        val payload = export()
        val bytes = LfbkContainer.write(
            payload = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(),
            seed = seed,
            schemaVersion = LedgerFlowDatabase.VERSION,
        )

        val temp = File(destination.parentFile, "${destination.name}.tmp")
        val written = runCatching {
            destination.parentFile?.mkdirs()
            temp.outputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
                // fsync: without it the rename can be ordered before the data
                // reaches disk, and a power loss leaves an empty file.
                stream.fd.sync()
            }
        }
        if (written.isFailure) {
            temp.delete()
            return BackupResult.Failure("write failed: ${written.exceptionOrNull()?.message}")
        }
        return promoteIfVerified(temp, destination, seed, payload.rowCount, bytes)
    }

    /**
     * Verifies the file that actually landed on disk, then renames it into
     * place. Verifying the in-memory bytes instead would prove nothing about
     * the file, which is the only thing a restore will ever see.
     */
    private fun promoteIfVerified(
        temp: File,
        destination: File,
        seed: ByteArray,
        expectedRows: Int,
        bytes: ByteArray,
    ): BackupResult {
        val verified = runCatching {
            when (val read = LfbkContainer.read(temp.readBytes(), seed)) {
                is LfbkResult.Failure -> null
                is LfbkResult.Success ->
                    json.decodeFromString(BackupPayload.serializer(), String(read.payload))
            }
        }.getOrNull()

        if (verified == null || verified.rowCount != expectedRows) {
            temp.delete()
            return BackupResult.Failure("verification failed; backup discarded")
        }
        if (!temp.renameTo(destination)) {
            temp.delete()
            return BackupResult.Failure("atomic rename failed")
        }
        return BackupResult.Success(destination, expectedRows, LfbkContainer.fingerprint(bytes))
    }

    public suspend fun restore(source: File, seed: ByteArray): RestoreResult {
        val read = when (val result = LfbkContainer.read(source.readBytes(), seed)) {
            is LfbkResult.Failure -> return RestoreResult.Failure(result.reason)
            is LfbkResult.Success -> result
        }
        if (read.schemaVersion > LedgerFlowDatabase.VERSION) {
            return RestoreResult.SchemaTooNew(read.schemaVersion, LedgerFlowDatabase.VERSION)
        }

        val payload = json.decodeFromString(BackupPayload.serializer(), String(read.payload))

        // One transaction for the whole restore. Found by a test that restored
        // into a non-empty database: the unique index on `category` fired
        // partway through and left a half-populated ledger behind. A restore
        // that can fail into a partial state is worse than one that refuses --
        // the user would be looking at some of their data and no indication
        // that the rest is missing.
        return runCatching {
            database.withTransaction { import(payload) }
            RestoreResult.Success(payload.rowCount)
        }.getOrElse { error ->
            RestoreResult.Failure(
                LfbkFailure.Malformed("restore rolled back: ${error.message}"),
            )
        }
    }

    /**
     * Every row of every table, as one materialised payload.
     *
     * **Public because the CSV export consumes it too** (ADR-0017), and that
     * sharing is the point rather than a convenience. This function is the
     * codebase's single answer to "which tables are there"; a second
     * enumeration living in the export path would mean a table added at schema
     * v6 reaches the backup and silently misses every CSV a user takes, with
     * nothing failing anywhere. `ExportCoversEveryTableTest` counts the lists
     * returned here and fails if the export does not produce a file for each.
     *
     * Read-only, so widening it does not widen the write surface
     * `LedgerSingleWriterTest` guards -- that permit is about
     * [BackupPayload]-driven *inserts*, which stay confined to [restore].
     */
    public suspend fun export(): BackupPayload = BackupPayload(
        schemaVersion = LedgerFlowDatabase.VERSION,
        createdAt = System.currentTimeMillis(),
        appMeta = database.appMetaDao().all().map { AppMetaRow(it.key, it.value) },
        categories = database.categoryDao().all().map { row ->
            CategoryRow(
                row.id, row.parentId, row.parentKey, row.ledgerScope.name, row.name,
                row.icon, row.colorArgb, row.sortOrder, row.isSystem, row.deletedAt,
            )
        },
        merchants = database.merchantDao().all().map { row ->
            MerchantRow(
                row.id, row.canonicalName, row.normalizedKey,
                row.defaultCategoryId, row.logoRef, row.deletedAt,
            )
        },
        paymentMethods = database.paymentMethodDao().all().map { row ->
            PaymentMethodRow(
                row.id, row.type.name, row.label, row.issuer, row.last4,
                row.colorArgb, row.isDefault, row.deletedAt,
            )
        },
        // Both ledgers are exported, but separately and never combined (Law 2).
        ledgerEntries = LedgerType.entries.flatMap { ledger ->
            database.ledgerEntryDao().allForLedger(ledger)
        }.map(::toRow),
        lineItems = database.ledgerEntryDao().allLineItems().map { row ->
            LineItemRow(
                row.id, row.entryId, row.position, row.name, row.normalizedName,
                row.quantityMilli, row.unitPriceMinor, row.totalMinor.minor,
                row.kind.name, row.categoryId, row.subcategoryId,
            )
        },
        // Schema v2. Unsaved drafts are included deliberately -- see DraftEntryRow.
        drafts = database.draftEntryDao().all().map { row ->
            DraftEntryRow(
                row.id, row.ledger.name, row.editingEntryId, row.editingEntryKey,
                row.payloadJson, row.payloadVersion, row.createdAt, row.updatedAt,
            )
        },
        merchantAliases = database.merchantAliasDao().all().map { row ->
            MerchantAliasRow(row.id, row.merchantId, row.alias, row.normalizedAlias)
        },
        categoryGroups = database.categoryGroupDao().all().map { row ->
            CategoryGroupRow(row.id, row.name, row.colorArgb, row.ledgerScope.name)
        },
        categoryGroupMembers = database.categoryGroupDao().allMembers().map { row ->
            CategoryGroupMemberRow(row.groupId, row.categoryId)
        },
        // Schema v6/v7 -- the ingest tables. Absent from this payload until
        // P2-4's follow-up, which is why `ExportCoversEveryTableTest` now reads
        // the committed schema JSON instead of counting this class's own fields.
        smsRaw = database.smsRawDao().all().map(::toSmsRawRow),
        notificationsRaw = database.notificationRawDao().all().map(::toNotificationRawRow),
        packageAllowlist = database.packageAllowlistDao().all().map { row ->
            PackageAllowlistRow(row.packageName, row.label, row.enabled)
        },
        senderAllowlist = database.senderAllowlistDao().all().map { row ->
            SenderAllowlistRow(row.senderPattern, row.label, row.enabled)
        },
        parserRules = database.parserRuleDao().all().map(::toParserRuleRow),
        // The user's unreviewed approval queue. Law 1 is untouched: these come
        // back as candidates with whatever status they had, and only
        // ApproveTransactionUseCase can still move one to APPROVED.
        pendingTransactions = database.pendingTransactionDao().all().map(::toPendingRow),
        // Schema v9. `budget` is user intent and nothing can rebuild it, so it
        // is in the payload from the day the table exists rather than the day
        // the feature ships. `daily_rollup` is absent on purpose -- derived,
        // and rebuilt on restore (ADR-0006).
        budgets = database.budgetDao().all().map(::toBudgetRow),
    )

    /**
     * Inserts in FK order: parents before children, entries before line items.
     * The reverse order would trip `PRAGMA foreign_keys = ON` mid-restore and
     * leave a half-populated database.
     */
    private suspend fun import(payload: BackupPayload) {
        database.appMetaDao().putAll(payload.appMeta.map { AppMetaEntity(it.key, it.value) })
        database.categoryDao().insertAll(payload.categories.map(::toCategory))
        database.merchantDao().insertAll(payload.merchants.map(::toMerchant))
        database.paymentMethodDao().insertAll(payload.paymentMethods.map(::toPaymentMethod))
        payload.ledgerEntries.forEach { database.ledgerEntryDao().insertEntry(toEntry(it)) }
        database.ledgerEntryDao().insertLineItems(payload.lineItems.map(::toLineItem))

        // v2, and still in FK order: aliases and drafts reference merchants and
        // entries, group members reference both a group and a category.
        database.merchantAliasDao().insertAll(
            payload.merchantAliases.map {
                MerchantAliasEntity(it.id, it.merchantId, it.alias, it.normalizedAlias)
            },
        )
        database.draftEntryDao().insertAll(
            payload.drafts.map {
                DraftEntryEntity(
                    id = it.id,
                    ledger = LedgerType.valueOf(it.ledger),
                    editingEntryId = it.editingEntryId,
                    editingEntryKey = it.editingEntryKey,
                    payloadJson = it.payloadJson,
                    payloadVersion = it.payloadVersion,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
        )
        database.categoryGroupDao().insertAll(
            payload.categoryGroups.map {
                CategoryGroupEntity(it.id, it.name, it.colorArgb, LedgerType.valueOf(it.ledgerScope))
            },
        )
        database.categoryGroupDao().insertAllMembers(
            payload.categoryGroupMembers.map {
                CategoryGroupMemberEntity(it.groupId, it.categoryId)
            },
        )

        importIngestTables(payload)
    }

    /**
     * v6/v7. **None of these tables declares a foreign key** -- deliberately,
     * see `PendingTransactionEntity`'s KDoc: a pending row must survive the raw
     * row it came from being purged, and the evidence that a duplicate was
     * suppressed must survive the winner being discarded. So there is no FK
     * order to respect here. They are still inserted raw-tables-first, so that
     * reading this tells the same story the pipeline does.
     */
    private suspend fun importIngestTables(payload: BackupPayload) {
        database.smsRawDao().insertAll(payload.smsRaw.map(::toSmsRaw))
        database.notificationRawDao().insertAll(
            payload.notificationsRaw.map(::toNotificationRaw),
        )
        database.packageAllowlistDao().insertAll(
            payload.packageAllowlist.map {
                PackageAllowlistEntity(it.packageName, it.label, it.enabled)
            },
        )
        database.senderAllowlistDao().insertAll(
            payload.senderAllowlist.map {
                SenderAllowlistEntity(it.senderPattern, it.label, it.enabled)
            },
        )
        database.parserRuleDao().insertAll(payload.parserRules.map(::toParserRule))
        database.pendingTransactionDao().insertAll(
            payload.pendingTransactions.map(::toPendingTransaction),
        )
        // A row whose `period` this build does not recognise is dropped rather
        // than defaulted: a budget silently restored as MONTHLY when it was
        // QUARTERLY is a wrong number on the Dashboard with nothing to trace
        // it to, which is worse than a budget the user notices is missing.
        database.budgetDao().insertAll(payload.budgets.mapNotNull(::toBudget))
        // `daily_rollup` is derived and deliberately absent from the payload
        // (ADR-0006), so it is rebuilt here rather than restored. A restored
        // install must open onto correct analytics, not onto empty charts
        // waiting for a nightly worker -- and rebuilding is strictly better
        // than carrying what would be the largest table in the database as
        // uncompressed JSON in a file the user moves between devices.
        database.dailyRollupDao().recomputeAll(LedgerType.DEBIT)
        database.dailyRollupDao().recomputeAll(LedgerType.CREDIT)
    }
}
