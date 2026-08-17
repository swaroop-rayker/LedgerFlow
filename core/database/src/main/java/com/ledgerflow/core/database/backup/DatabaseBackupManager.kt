package com.ledgerflow.core.database.backup

import androidx.room.withTransaction
import com.ledgerflow.core.crypto.lfbk.LfbkContainer
import com.ledgerflow.core.crypto.lfbk.LfbkFailure
import com.ledgerflow.core.crypto.lfbk.LfbkResult
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.CategoryGroupEntity
import com.ledgerflow.core.database.entity.CategoryGroupMemberEntity
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantAliasEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
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

    private suspend fun export(): BackupPayload = BackupPayload(
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
    )

    private fun toRow(entry: LedgerEntryEntity) = LedgerEntryRow(
        id = entry.id,
        ledger = entry.ledger.name,
        amountMinor = entry.amountMinor.minor,
        currency = entry.currency,
        originalAmountMinor = entry.originalAmountMinor,
        originalCurrency = entry.originalCurrency,
        fxRateMicro = entry.fxRateMicro,
        occurredAt = entry.occurredAt,
        localDate = entry.localDate,
        merchantId = entry.merchantId,
        categoryId = entry.categoryId,
        subcategoryId = entry.subcategoryId,
        paymentMethodId = entry.paymentMethodId,
        note = entry.note,
        source = entry.source.name,
        sourceRefId = entry.sourceRefId,
        isRecurring = entry.isRecurring,
        createdAt = entry.createdAt,
        updatedAt = entry.updatedAt,
        deletedAt = entry.deletedAt,
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
    }

    private fun toCategory(row: CategoryRow) = CategoryEntity(
        id = row.id, parentId = row.parentId, parentKey = row.parentKey,
        ledgerScope = LedgerType.valueOf(row.ledgerScope), name = row.name,
        icon = row.icon, colorArgb = row.colorArgb, sortOrder = row.sortOrder,
        isSystem = row.isSystem, deletedAt = row.deletedAt,
    )

    private fun toMerchant(row: MerchantRow) = MerchantEntity(
        row.id, row.canonicalName, row.normalizedKey,
        row.defaultCategoryId, row.logoRef, row.deletedAt,
    )

    private fun toPaymentMethod(row: PaymentMethodRow) = PaymentMethodEntity(
        id = row.id, type = PaymentMethodType.valueOf(row.type), label = row.label,
        issuer = row.issuer, last4 = row.last4, colorArgb = row.colorArgb,
        isDefault = row.isDefault, deletedAt = row.deletedAt,
    )

    private fun toEntry(row: LedgerEntryRow) = LedgerEntryEntity(
        id = row.id,
        ledger = LedgerType.valueOf(row.ledger),
        amountMinor = Money(row.amountMinor),
        currency = row.currency,
        originalAmountMinor = row.originalAmountMinor,
        originalCurrency = row.originalCurrency,
        fxRateMicro = row.fxRateMicro,
        occurredAt = row.occurredAt,
        localDate = row.localDate,
        merchantId = row.merchantId,
        categoryId = row.categoryId,
        subcategoryId = row.subcategoryId,
        paymentMethodId = row.paymentMethodId,
        note = row.note,
        source = EntrySource.valueOf(row.source),
        sourceRefId = row.sourceRefId,
        isRecurring = row.isRecurring,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        deletedAt = row.deletedAt,
    )

    private fun toLineItem(row: LineItemRow) = LineItemEntity(
        id = row.id, entryId = row.entryId, position = row.position,
        name = row.name, normalizedName = row.normalizedName,
        quantityMilli = row.quantityMilli, unitPriceMinor = row.unitPriceMinor,
        totalMinor = Money(row.totalMinor), kind = LineItemKind.valueOf(row.kind),
        categoryId = row.categoryId, subcategoryId = row.subcategoryId,
    )
}
