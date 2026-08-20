package com.ledgerflow.core.data.ledger

import com.ledgerflow.core.database.dao.EntryComboRow
import com.ledgerflow.core.database.dao.DeletedEntryRow
import com.ledgerflow.core.database.dao.LedgerListRow
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.domain.ledger.EntryCombo
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntryOrigin
import com.ledgerflow.core.model.ForeignAmount
import com.ledgerflow.core.model.DeletedEntry
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItem
import com.ledgerflow.core.model.Money

/**
 * Room row <-> domain model for the ledger.
 *
 * The domain model drops `deleted_at`, `currency`'s redundancy with
 * `app_meta.baseCurrency`, and `normalized_name` — all storage mechanics.
 * `normalized_name` in particular is derived by [ItemNameNormalizer] and
 * exposing it would invite a caller to set it, at which point the search index
 * and the name it indexes can disagree.
 *
 * The three foreign-currency columns collapse into one nullable
 * [ForeignAmount]: in the schema they are independently nullable, which lets
 * "an amount with no rate" exist as a row. Off the database they are one thing
 * or nothing.
 */
internal fun LedgerEntryEntity.toDomain(lineItems: List<LineItemEntity>): LedgerEntry = LedgerEntry(
    id = id,
    ledger = ledger,
    amount = amountMinor,
    currency = currency,
    occurredAt = occurredAt,
    localDate = localDate,
    assignment = EntryAssignment(
        categoryId = categoryId,
        subcategoryId = subcategoryId,
        merchantId = merchantId,
        paymentMethodId = paymentMethodId,
    ),
    note = note,
    origin = EntryOrigin(source = source, refId = sourceRefId),
    foreign = toForeignAmount(),
    isRecurring = isRecurring,
    lineItems = lineItems.sortedBy { it.position }.map { it.toDomain() },
)

/**
 * Null unless all three columns are present.
 *
 * A partial trio is not a half-known conversion, it is a corrupt row: without
 * the rate there is nothing relating the two amounts, and §5.8 forbids
 * inventing one. The approval path refuses to write such a row, so this is
 * defence against a future writer rather than a case that exists today.
 */
private fun LedgerEntryEntity.toForeignAmount(): ForeignAmount? {
    val amount = originalAmountMinor ?: return null
    val currency = originalCurrency ?: return null
    val rate = fxRateMicro ?: return null
    return ForeignAmount(amountMinor = amount, currency = currency, fxRateMicro = rate)
}

internal fun LineItemEntity.toDomain(): LineItem = LineItem(
    id = id,
    position = position,
    name = name,
    quantityMilli = quantityMilli,
    unitPrice = unitPriceMinor?.let(::Money),
    total = totalMinor,
    kind = kind,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
)

internal fun EntryComboRow.toDomain(): EntryCombo = EntryCombo(
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    uses = uses,
    lastUsedAt = lastUsedAt,
)

/** `COALESCE(editing_entry_id, '')`, in one place so it cannot be spelled two ways. */
internal fun editingEntryKeyOf(editingEntryId: String?): String = editingEntryId ?: ""

/**
 * A paged list row -> the list's model.
 *
 * [ledger] comes from the caller rather than from the row, and that is the
 * honest shape: the column is not in the projection because the *statement*
 * already decided the book -- `pagingDebits` reads `debit_entries` and nothing
 * else can come back from it. Selecting `ledger` per row would be reading a
 * constant off disk for every visible entry, and worse, it would suggest the
 * result set could contain both (Law 2).
 */
internal fun LedgerListRow.toDomain(ledger: LedgerType): LedgerListItem = LedgerListItem(
    id = id,
    ledger = ledger,
    amount = amountMinor,
    currency = currency,
    occurredAt = occurredAt,
    localDate = localDate,
    categoryName = categoryName,
    categoryColorArgb = categoryColorArgb,
    merchantName = merchantName,
    note = note,
)

/**
 * A binned row -> the bin's model.
 *
 * `deleted_at` is non-null by construction: the statement that produced this
 * row filtered on `deleted_at IS NOT NULL`, so a null here would mean the
 * query had changed underneath the mapper. `requireNotNull` says that out loud
 * rather than defaulting to zero and rendering 1 January 1970.
 */
internal fun DeletedEntryRow.toDomain(): DeletedEntry = DeletedEntry(
    id = id,
    ledger = ledger,
    amount = amountMinor,
    currency = currency,
    occurredAt = occurredAt,
    deletedAt = deletedAt,
    categoryName = categoryName,
    categoryColorArgb = categoryColorArgb,
    subcategoryName = subcategoryName,
    merchantName = merchantName,
    note = note,
)
