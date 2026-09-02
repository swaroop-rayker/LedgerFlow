package com.ledgerflow.core.database.backup

import com.ledgerflow.core.database.entity.BudgetEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.ParserRuleEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus

/**
 * Row <-> entity, both directions, for every table in a `.lfbk`.
 *
 * Split out of [DatabaseBackupManager] when the ingest tables arrived and the
 * class crossed detekt's function limit. That limit was reporting something
 * real: the manager's job is the *protocol* -- write to a temp file, fsync,
 * decrypt and parse it back, rename -- and translating fourteen row shapes is a
 * different concern that happened to live in the same file.
 *
 * **These are the only two places a backup's field list is written down**, and
 * they are deliberately verbose rather than reflective. A mapper that skipped a
 * column would be caught by `BackupRestoreRoundTripTest`, which seeds every
 * table and compares row-for-row; a reflective one would silently carry a
 * renamed column across and produce a payload no older build could read.
 *
 * Enums cross as their `name`. `valueOf` throws on an unknown value, which is
 * correct here: a restore runs inside one transaction (see
 * [DatabaseBackupManager.restore]) and a payload naming a status this build does
 * not have is a backup from the future, which must roll back rather than land
 * half-applied.
 */

internal fun toSmsRawRow(row: SmsRawEntity) = SmsRawRow(
    id = row.id,
    sender = row.sender,
    body = row.body,
    bodyHash = row.bodyHash,
    receivedAt = row.receivedAt,
    simSlot = row.simSlot,
    parseStatus = row.parseStatus.name,
    matchedRuleId = row.matchedRuleId,
    retentionExpiresAt = row.retentionExpiresAt,
)

internal fun toNotificationRawRow(row: NotificationRawEntity) = NotificationRawRow(
    id = row.id,
    packageName = row.packageName,
    title = row.title,
    body = row.body,
    bodyHash = row.bodyHash,
    postedAt = row.postedAt,
    parseStatus = row.parseStatus.name,
    matchedRuleId = row.matchedRuleId,
    retentionExpiresAt = row.retentionExpiresAt,
)

internal fun toParserRuleRow(row: ParserRuleEntity) = ParserRuleRow(
    id = row.id,
    rulesetVersion = row.rulesetVersion,
    priority = row.priority,
    senderPattern = row.senderPattern,
    bodyPattern = row.bodyPattern,
    fieldMapJson = row.fieldMapJson,
    direction = row.direction,
    instrumentHint = row.instrumentHint,
    confidenceBase = row.confidenceBase,
    enabled = row.enabled,
    isUserDefined = row.isUserDefined,
)

internal fun toPendingRow(row: PendingTransactionEntity) = PendingTransactionRow(
    id = row.id,
    source = row.source.name,
    dedupeKey = row.dedupeKey,
    suppressedById = row.suppressedById,
    rawRefId = row.rawRefId,
    extractedJson = row.extractedJson,
    confidence = row.confidence,
    status = row.status.name,
    needsManualFill = row.needsManualFill,
    createdAt = row.createdAt,
    reviewedAt = row.reviewedAt,
    approvedEntryId = row.approvedEntryId,
)

internal fun toRow(entry: LedgerEntryEntity) = LedgerEntryRow(
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

internal fun toSmsRaw(row: SmsRawRow) = SmsRawEntity(
    id = row.id,
    sender = row.sender,
    body = row.body,
    bodyHash = row.bodyHash,
    receivedAt = row.receivedAt,
    simSlot = row.simSlot,
    parseStatus = RawParseStatus.valueOf(row.parseStatus),
    matchedRuleId = row.matchedRuleId,
    retentionExpiresAt = row.retentionExpiresAt,
)

internal fun toNotificationRaw(row: NotificationRawRow) = NotificationRawEntity(
    id = row.id,
    packageName = row.packageName,
    title = row.title,
    body = row.body,
    bodyHash = row.bodyHash,
    postedAt = row.postedAt,
    parseStatus = RawParseStatus.valueOf(row.parseStatus),
    matchedRuleId = row.matchedRuleId,
    retentionExpiresAt = row.retentionExpiresAt,
)

internal fun toParserRule(row: ParserRuleRow) = ParserRuleEntity(
    id = row.id,
    rulesetVersion = row.rulesetVersion,
    priority = row.priority,
    senderPattern = row.senderPattern,
    bodyPattern = row.bodyPattern,
    fieldMapJson = row.fieldMapJson,
    direction = row.direction,
    instrumentHint = row.instrumentHint,
    confidenceBase = row.confidenceBase,
    enabled = row.enabled,
    isUserDefined = row.isUserDefined,
)

internal fun toPendingTransaction(row: PendingTransactionRow) = PendingTransactionEntity(
    id = row.id,
    source = EntrySource.valueOf(row.source),
    dedupeKey = row.dedupeKey,
    suppressedById = row.suppressedById,
    rawRefId = row.rawRefId,
    extractedJson = row.extractedJson,
    confidence = row.confidence,
    status = PendingStatus.valueOf(row.status),
    needsManualFill = row.needsManualFill,
    createdAt = row.createdAt,
    reviewedAt = row.reviewedAt,
    approvedEntryId = row.approvedEntryId,
)

internal fun toCategory(row: CategoryRow) = CategoryEntity(
    id = row.id, parentId = row.parentId, parentKey = row.parentKey,
    ledgerScope = LedgerType.valueOf(row.ledgerScope), name = row.name,
    icon = row.icon, colorArgb = row.colorArgb, sortOrder = row.sortOrder,
    isSystem = row.isSystem, deletedAt = row.deletedAt,
)

internal fun toMerchant(row: MerchantRow) = MerchantEntity(
    row.id, row.canonicalName, row.normalizedKey,
    row.defaultCategoryId, row.logoRef, row.deletedAt,
)

internal fun toPaymentMethod(row: PaymentMethodRow) = PaymentMethodEntity(
    id = row.id, type = PaymentMethodType.valueOf(row.type), label = row.label,
    issuer = row.issuer, last4 = row.last4, colorArgb = row.colorArgb,
    isDefault = row.isDefault, deletedAt = row.deletedAt,
)

internal fun toEntry(row: LedgerEntryRow) = LedgerEntryEntity(
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

internal fun toLineItem(row: LineItemRow) = LineItemEntity(
    id = row.id, entryId = row.entryId, position = row.position,
    name = row.name, normalizedName = row.normalizedName,
    quantityMilli = row.quantityMilli, unitPriceMinor = row.unitPriceMinor,
    totalMinor = Money(row.totalMinor), kind = LineItemKind.valueOf(row.kind),
    categoryId = row.categoryId, subcategoryId = row.subcategoryId,
)

/**
 * Schema v9. Returns `null` for a `period` this build does not recognise —
 * a `.lfbk` written by a newer version, most plausibly. Dropping the row is
 * the deliberate choice: a budget restored as the wrong period is a wrong
 * figure on the Dashboard with nothing to trace it to, and the converters
 * take the same position on unknown enum names for the same reason.
 */
internal fun toBudget(row: BudgetRow): BudgetEntity? {
    val period = BudgetPeriod.entries.firstOrNull { it.name == row.period } ?: return null
    return BudgetEntity(
        id = row.id,
        categoryId = row.categoryId,
        subcategoryId = row.subcategoryId,
        period = period,
        amountMinor = Money(row.amountMinor),
        startDate = row.startDate,
        rolloverEnabled = row.rolloverEnabled,
        alertThresholds = row.alertThresholds,
        deletedAt = row.deletedAt,
    )
}

internal fun toBudgetRow(row: BudgetEntity) = BudgetRow(
    id = row.id,
    categoryId = row.categoryId,
    subcategoryId = row.subcategoryId,
    period = row.period.name,
    amountMinor = row.amountMinor.minor,
    startDate = row.startDate,
    rolloverEnabled = row.rolloverEnabled,
    alertThresholds = row.alertThresholds,
    deletedAt = row.deletedAt,
)
