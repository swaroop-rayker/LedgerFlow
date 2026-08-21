package com.ledgerflow.core.data.export

import com.ledgerflow.core.database.backup.BackupPayload
import com.ledgerflow.core.model.LedgerType

/** One CSV file inside the export zip. */
internal data class CsvDocument(
    val fileName: String,
    val header: List<String>,
    val rows: List<List<String?>>,
) {
    fun render(): String = CsvWriter.document(header, rows)
}

/**
 * Every table, as the files that go in the zip (ADR-0017).
 *
 * **Driven by [BackupPayload], not by the DAOs.** That is the single decision
 * here worth defending: the backup already owns the list of "every table there
 * is", and enumerating them a second time means schema v6 adds a table to the
 * backup, nobody thinks about the export, and the new table is silently missing
 * from every CSV a user takes -- with nothing failing anywhere.
 * `ExportCoversEveryTableTest` counts the payload's lists by reflection and
 * fails if this file does not produce a matching number of documents, so the
 * omission cannot happen quietly.
 *
 * **Money and timestamps appear twice in every row**: the schema's integer
 * verbatim, and a rendered form beside it. One re-imports without a rounding
 * story, the other is what a human reads. The decimal is assembled by integer
 * arithmetic (Law 3) -- see [CsvWriter.decimal].
 */
internal object CsvTables {

    /**
     * `ledger_entry` becomes two files, one per book.
     *
     * Not because Law 2 requires it -- a CSV derives no figures, so it could not
     * violate it -- but because §5.5 promises the user "separate lists", and an
     * export is the most literal list the app hands over. The rows already
     * arrive per-book from `allForLedger`, so the split costs nothing.
     *
     * The count of documents is therefore payload lists **+ 1**.
     */
    const val LEDGER_ENTRY_SPLIT_EXTRA: Int = 1

    fun documents(payload: BackupPayload): List<CsvDocument> = listOf(
        appMeta(payload),
        categories(payload),
        merchants(payload),
        paymentMethods(payload),
        ledgerEntries(payload, LedgerType.DEBIT),
        ledgerEntries(payload, LedgerType.CREDIT),
        lineItems(payload),
        drafts(payload),
        merchantAliases(payload),
        categoryGroups(payload),
        categoryGroupMembers(payload),
    )

    private fun appMeta(payload: BackupPayload) = CsvDocument(
        fileName = "app_meta.csv",
        header = listOf("key", "value"),
        rows = payload.appMeta.map { listOf(it.key, it.value) },
    )

    private fun categories(payload: BackupPayload) = CsvDocument(
        fileName = "category.csv",
        header = listOf(
            "id", "parent_id", "parent_key", "ledger_scope", "name", "icon",
            "color_argb", "sort_order", "is_system", "deleted_at", "deleted_at_iso",
        ),
        rows = payload.categories.map { row ->
            listOf(
                row.id,
                row.parentId,
                row.parentKey,
                row.ledgerScope,
                row.name,
                row.icon,
                row.colorArgb.toString(),
                row.sortOrder.toString(),
                CsvWriter.boolean(row.isSystem),
                row.deletedAt.toString(),
                // 0 is the taxonomy's "live" sentinel rather than an instant, so
                // rendering it as 1 January 1970 would state something false.
                hiddenAtIso(row.deletedAt),
            )
        },
    )

    private fun merchants(payload: BackupPayload) = CsvDocument(
        fileName = "merchant.csv",
        header = listOf(
            "id", "canonical_name", "normalized_key", "default_category_id",
            "logo_ref", "deleted_at", "deleted_at_iso",
        ),
        rows = payload.merchants.map { row ->
            listOf(
                row.id,
                row.canonicalName,
                row.normalizedKey,
                row.defaultCategoryId,
                row.logoRef,
                row.deletedAt.toString(),
                hiddenAtIso(row.deletedAt),
            )
        },
    )

    private fun paymentMethods(payload: BackupPayload) = CsvDocument(
        fileName = "payment_method.csv",
        header = listOf(
            "id", "type", "label", "issuer", "last4", "color_argb",
            "is_default", "deleted_at", "deleted_at_iso",
        ),
        rows = payload.paymentMethods.map { row ->
            listOf(
                row.id,
                row.type,
                row.label,
                row.issuer,
                // Quoted by the writer because it is a string, which is what
                // stops "0042" arriving in a spreadsheet as the number 42.
                row.last4,
                row.colorArgb?.toString(),
                CsvWriter.boolean(row.isDefault),
                row.deletedAt.toString(),
                hiddenAtIso(row.deletedAt),
            )
        },
    )

    /**
     * One book's entries.
     *
     * Filtered in Kotlin rather than read per-book, because the payload is
     * already materialised and re-reading the database would mean the export's
     * two halves could come from two different instants.
     */
    private fun ledgerEntries(payload: BackupPayload, ledger: LedgerType) = CsvDocument(
        fileName = "ledger_entry_${ledger.name.lowercase()}.csv",
        header = listOf(
            "id", "ledger", "amount_minor", "amount", "currency",
            "original_amount_minor", "original_amount", "original_currency",
            "fx_rate_micro", "fx_rate", "occurred_at", "occurred_at_iso",
            "local_date", "merchant_id", "category_id", "subcategory_id",
            "payment_method_id", "note", "source", "source_ref_id",
            "is_recurring", "created_at", "created_at_iso", "updated_at",
            "updated_at_iso", "deleted_at", "deleted_at_iso",
        ),
        rows = payload.ledgerEntries.filter { it.ledger == ledger.name }.map { row ->
            listOf(
                row.id,
                row.ledger,
                row.amountMinor.toString(),
                CsvWriter.decimal(row.amountMinor),
                row.currency,
                row.originalAmountMinor?.toString(),
                CsvWriter.decimal(row.originalAmountMinor),
                row.originalCurrency,
                row.fxRateMicro?.toString(),
                // Micro-units: six decimal places, not two. The rate is not
                // money and does not share money's scale.
                CsvWriter.decimal(row.fxRateMicro, scale = FX_RATE_SCALE),
                row.occurredAt.toString(),
                CsvWriter.timestamp(row.occurredAt),
                row.localDate.toString(),
                row.merchantId,
                row.categoryId,
                row.subcategoryId,
                row.paymentMethodId,
                row.note,
                row.source,
                row.sourceRefId,
                CsvWriter.boolean(row.isRecurring),
                row.createdAt.toString(),
                CsvWriter.timestamp(row.createdAt),
                row.updatedAt.toString(),
                CsvWriter.timestamp(row.updatedAt),
                // `ledger_entry.deleted_at` is nullable, unlike the taxonomy's,
                // so null genuinely means live and needs no sentinel handling.
                row.deletedAt?.toString(),
                CsvWriter.timestamp(row.deletedAt),
            )
        },
    )

    private fun lineItems(payload: BackupPayload) = CsvDocument(
        fileName = "line_item.csv",
        header = listOf(
            "id", "entry_id", "position", "name", "normalized_name",
            "quantity_milli", "quantity", "unit_price_minor", "unit_price",
            "total_minor", "total", "kind", "category_id", "subcategory_id",
        ),
        rows = payload.lineItems.map { row ->
            listOf(
                row.id,
                row.entryId,
                row.position.toString(),
                row.name,
                row.normalizedName,
                row.quantityMilli.toString(),
                // Milli-units: three decimal places. "1.500 kg" is the point of
                // storing it this way rather than as a count.
                CsvWriter.decimal(row.quantityMilli, scale = QUANTITY_SCALE),
                row.unitPriceMinor?.toString(),
                CsvWriter.decimal(row.unitPriceMinor),
                row.totalMinor.toString(),
                CsvWriter.decimal(row.totalMinor),
                row.kind,
                row.categoryId,
                row.subcategoryId,
            )
        },
    )

    /**
     * Drafts, payload JSON and all.
     *
     * Exported for the reason `BackupPayload` gives for backing them up: a draft
     * is work the user has done and not saved, and an export that drops it is
     * lossy about something they would notice. The JSON lands in one field, which
     * the writer quotes and escapes -- it is full of commas and quotes, and this
     * is the case that would corrupt every column after it if the escaping were
     * wrong.
     */
    private fun drafts(payload: BackupPayload) = CsvDocument(
        fileName = "draft_entry.csv",
        header = listOf(
            "id", "ledger", "editing_entry_id", "editing_entry_key",
            "payload_json", "payload_version", "created_at", "created_at_iso",
            "updated_at", "updated_at_iso",
        ),
        rows = payload.drafts.map { row ->
            listOf(
                row.id,
                row.ledger,
                row.editingEntryId,
                row.editingEntryKey,
                row.payloadJson,
                row.payloadVersion.toString(),
                row.createdAt.toString(),
                CsvWriter.timestamp(row.createdAt),
                row.updatedAt.toString(),
                CsvWriter.timestamp(row.updatedAt),
            )
        },
    )

    private fun merchantAliases(payload: BackupPayload) = CsvDocument(
        fileName = "merchant_alias.csv",
        header = listOf("id", "merchant_id", "alias", "normalized_alias"),
        rows = payload.merchantAliases.map {
            listOf(it.id, it.merchantId, it.alias, it.normalizedAlias)
        },
    )

    private fun categoryGroups(payload: BackupPayload) = CsvDocument(
        fileName = "category_group.csv",
        header = listOf("id", "name", "color_argb", "ledger_scope"),
        rows = payload.categoryGroups.map {
            listOf(it.id, it.name, it.colorArgb?.toString(), it.ledgerScope)
        },
    )

    private fun categoryGroupMembers(payload: BackupPayload) = CsvDocument(
        fileName = "category_group_member.csv",
        header = listOf("group_id", "category_id"),
        rows = payload.categoryGroupMembers.map { listOf(it.groupId, it.categoryId) },
    )

    /** Null for a live row, so the column reads as blank rather than as 1970. */
    private fun hiddenAtIso(deletedAt: Long): String? =
        if (deletedAt == 0L) null else CsvWriter.timestamp(deletedAt)

    private const val FX_RATE_SCALE = 6
    private const val QUANTITY_SCALE = 3
}
