package com.ledgerflow.core.database.backup

import kotlinx.serialization.Serializable

/**
 * The decrypted contents of a `.lfbk` backup: every row of every table.
 *
 * A **logical** export rather than a copy of the database file. The file would
 * be simpler, but it is SQLCipher-encrypted with the DEK, so restoring it onto
 * a new device would require carrying the DEK too -- and SPEC.md §7.5 is
 * explicit that a backup which cannot be restored without the original key
 * material is the exact failure mode Android Auto Backup already has.
 *
 * A logical export also lets a restore from an older `schemaVersion` be
 * migrated forward, which a raw file cannot do without replaying the whole
 * migration chain.
 *
 * Serialised as JSON. Not the most compact choice, but it is inspectable, has
 * an obvious versioning story, and the payload is encrypted anyway. Revisit if
 * a real ledger ever makes the size matter.
 */
@Serializable
public data class BackupPayload(
    val schemaVersion: Int,
    val createdAt: Long,
    val appMeta: List<AppMetaRow>,
    val categories: List<CategoryRow>,
    val merchants: List<MerchantRow>,
    val paymentMethods: List<PaymentMethodRow>,
    val ledgerEntries: List<LedgerEntryRow>,
    val lineItems: List<LineItemRow>,
) {
    /** Total rows, for the post-restore equality assertion and diagnostics. */
    public val rowCount: Int
        get() = appMeta.size + categories.size + merchants.size +
            paymentMethods.size + ledgerEntries.size + lineItems.size
}

@Serializable
public data class AppMetaRow(val key: String, val value: String)

@Serializable
public data class CategoryRow(
    val id: String,
    val parentId: String?,
    val parentKey: String,
    val ledgerScope: String,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val isSystem: Boolean,
    val deletedAt: Long,
)

@Serializable
public data class MerchantRow(
    val id: String,
    val canonicalName: String,
    val normalizedKey: String,
    val defaultCategoryId: String?,
    val logoRef: String?,
    val deletedAt: Long,
)

@Serializable
public data class PaymentMethodRow(
    val id: String,
    val type: String,
    val label: String,
    val issuer: String?,
    val last4: String?,
    val colorArgb: Int?,
    val isDefault: Boolean,
    val deletedAt: Long,
)

@Serializable
public data class LedgerEntryRow(
    val id: String,
    val ledger: String,
    val amountMinor: Long,
    val currency: String,
    val originalAmountMinor: Long?,
    val originalCurrency: String?,
    val fxRateMicro: Long?,
    val occurredAt: Long,
    val localDate: Int,
    val merchantId: String?,
    val categoryId: String?,
    val subcategoryId: String?,
    val paymentMethodId: String?,
    val note: String?,
    val source: String,
    val sourceRefId: String?,
    val isRecurring: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Serializable
public data class LineItemRow(
    val id: String,
    val entryId: String,
    val position: Int,
    val name: String,
    val normalizedName: String,
    val quantityMilli: Long,
    val unitPriceMinor: Long?,
    val totalMinor: Long,
    val kind: String,
    val categoryId: String?,
    val subcategoryId: String?,
)
