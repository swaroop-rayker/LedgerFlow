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
    // ── Schema v2 ────────────────────────────────────────────────────────────
    //
    // Defaulted to empty so a backup written by a v1 install still deserializes.
    // Without the defaults, every `.lfbk` a user already holds would fail to
    // parse the moment they updated -- which is the P4 catastrophe §7 exists to
    // prevent, caused by an upgrade rather than by a bug.
    val drafts: List<DraftEntryRow> = emptyList(),
    val merchantAliases: List<MerchantAliasRow> = emptyList(),
    val categoryGroups: List<CategoryGroupRow> = emptyList(),
    val categoryGroupMembers: List<CategoryGroupMemberRow> = emptyList(),
    // ── Schema v6/v7 — the ingest tables ─────────────────────────────────────
    //
    // **These were missing for two schema versions and nothing said so.**
    // `ExportCoversEveryTableTest` counted this class's own `List` properties
    // and checked the CSV export matched — internally consistent, and blind to
    // the schema, so v6 adding six tables kept it green. It was harmless while
    // they were empty and stopped being harmless the moment P2-4 gave
    // `pending_transaction` a writer: a restore would have silently dropped the
    // user's unreviewed approval queue, their edited allowlists, and any parser
    // rule they wrote by hand. The test now reads the committed schema JSON, so
    // the next table cannot go missing the same way.
    //
    // Defaulted to empty for the reason the v2 block above is: a `.lfbk` a user
    // already holds must still parse after they update.
    val smsRaw: List<SmsRawRow> = emptyList(),
    val notificationsRaw: List<NotificationRawRow> = emptyList(),
    val packageAllowlist: List<PackageAllowlistRow> = emptyList(),
    val senderAllowlist: List<SenderAllowlistRow> = emptyList(),
    val parserRules: List<ParserRuleRow> = emptyList(),
    val pendingTransactions: List<PendingTransactionRow> = emptyList(),
    // -- Schema v9 -- budgets (SPEC.md 5.7) -----------------------------------
    //
    // `budget` is here and `daily_rollup` deliberately is not. The two tables
    // arrived in the same migration and they are not the same kind of thing:
    // a budget is user intent that nothing in the app can reconstruct, while
    // every `daily_rollup` row is reproducible from `ledger_entry` joined to
    // `line_item` (ADR-0006). Carrying the rollups would put what is likely
    // the largest table in the database into an uncompressed JSON file the
    // user moves between devices, to restore rows the first reconciliation
    // pass rebuilds anyway. `ExportCoversEveryTableTest` names that exclusion
    // and its reason, so it is a decision rather than the omission Q13 was.
    //
    // Defaulted to empty for the reason the v2 and v6 blocks above are: a
    // `.lfbk` a user already holds must still parse after they update.
    val budgets: List<BudgetRow> = emptyList(),
) {
    /** Total rows, for the post-restore equality assertion and diagnostics. */
    public val rowCount: Int
        get() = appMeta.size + categories.size + merchants.size +
            paymentMethods.size + ledgerEntries.size + lineItems.size +
            drafts.size + merchantAliases.size + categoryGroups.size +
            categoryGroupMembers.size + smsRaw.size + notificationsRaw.size +
            packageAllowlist.size + senderAllowlist.size + parserRules.size +
            pendingTransactions.size + budgets.size
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

/**
 * Unsaved form state, backed up like everything else (§6.1.2).
 *
 * It is tempting to exclude drafts as scratch. They are not: a draft is work the
 * user has done and not yet saved, and a restore that silently drops it is a
 * restore that loses data. More practically, "which tables are in the backup" is
 * a list that rots -- excluding one here means the round-trip test quietly stops
 * covering it, which is how a table ends up outside the durability guarantee
 * without anyone deciding that.
 */
@Serializable
public data class DraftEntryRow(
    val id: String,
    val ledger: String,
    val editingEntryId: String?,
    val editingEntryKey: String,
    val payloadJson: String,
    val payloadVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
public data class MerchantAliasRow(
    val id: String,
    val merchantId: String,
    val alias: String,
    val normalizedAlias: String,
)

@Serializable
public data class CategoryGroupRow(
    val id: String,
    val name: String,
    val colorArgb: Int?,
    val ledgerScope: String,
)

@Serializable
public data class CategoryGroupMemberRow(
    val groupId: String,
    val categoryId: String,
)

/**
 * A captured SMS, backed up verbatim (SPEC.md §5.1, §6.1).
 *
 * **The body is in the backup, and that is spec-literal rather than an
 * oversight.** §16 Q1 (D-09) sets the 90-day purge precisely *because* the raw
 * body is the most sensitive text this app holds and it "sits inside a file that
 * can leave the device in a `.lfbk`" — the retention window is the answer to
 * that exposure, not the backup's absence. Within the window the body is also
 * the only thing that makes an unparseable message replayable against a later
 * ruleset, and it is what a restored `pending_transaction` points at through
 * `raw_ref_id`.
 */
@Serializable
public data class SmsRawRow(
    val id: String,
    val sender: String,
    val body: String,
    val bodyHash: String,
    val receivedAt: Long,
    val simSlot: Int?,
    val parseStatus: String,
    val matchedRuleId: String?,
    val retentionExpiresAt: Long,
)

/** A captured notification (SPEC.md §5.2, §6.1). See [SmsRawRow] on the body. */
@Serializable
public data class NotificationRawRow(
    val id: String,
    val packageName: String,
    val title: String?,
    val body: String,
    val bodyHash: String,
    val postedAt: Long,
    val parseStatus: String,
    val matchedRuleId: String?,
    val retentionExpiresAt: Long,
)

/**
 * Which packages LedgerFlow may read notifications from (D-10).
 *
 * User-editable, which is the whole reason it has to survive a restore: a
 * package the user deliberately disabled must come back disabled, and a package
 * they added must come back at all. Re-seeding from the shipped asset would
 * silently re-enable everything they had turned off.
 */
@Serializable
public data class PackageAllowlistRow(
    val packageName: String,
    val label: String?,
    val enabled: Boolean,
)

/** Which SMS senders count as financial (§5.1). User-editable — see [PackageAllowlistRow]. */
@Serializable
public data class SenderAllowlistRow(
    val senderPattern: String,
    val label: String?,
    val enabled: Boolean,
)

/**
 * One extraction rule (§5.1).
 *
 * Shipped rules are re-seeded from the asset on every launch, so backing those
 * up is redundant — but a rule with `isUserDefined = true` exists **nowhere
 * else**, and the seeder is explicitly forbidden from touching it. Both are
 * exported rather than filtering to user rules only: the filter would be a
 * second place that has to agree with the seeder about what "shipped" means,
 * and a restore that brings back a stale shipped rule is corrected by the next
 * seed, which deletes and replaces every shipped rule for its version.
 */
@Serializable
public data class ParserRuleRow(
    val id: String,
    val rulesetVersion: Int,
    val priority: Int,
    val senderPattern: String,
    val bodyPattern: String,
    val fieldMapJson: String,
    val direction: String?,
    val instrumentHint: String?,
    val confidenceBase: Double,
    val enabled: Boolean,
    val isUserDefined: Boolean,
)

/**
 * One candidate awaiting a human (§5.1, §6.1).
 *
 * **The table this whole fix is urgent for.** A pending row is work the user has
 * not done yet — the same argument [DraftEntryRow] makes about unsaved form
 * state, with more at stake, because a dropped candidate is a transaction that
 * never reaches the ledger and leaves no trace that it was ever going to.
 *
 * Restoring it restores a queue, never a ledger row. Law 1 is unaffected:
 * `status` comes back as whatever it was, and only `ApproveTransactionUseCase`
 * can still move one to `APPROVED`.
 */
@Serializable
public data class PendingTransactionRow(
    val id: String,
    val source: String,
    val dedupeKey: String,
    val suppressedById: String?,
    val rawRefId: String?,
    val extractedJson: String,
    val confidence: Double,
    val status: String,
    val needsManualFill: Boolean,
    val createdAt: Long,
    val reviewedAt: Long?,
    val approvedEntryId: String?,
)

/**
 * A budget row (SPEC.md §5.7, §6.1).
 *
 * `period` is the enum `name`, matching how the column stores it; a restore
 * that met an unknown name would rather drop the row than resurrect a budget
 * whose period it has to guess.
 */
@Serializable
public data class BudgetRow(
    val id: String,
    val categoryId: String,
    val subcategoryId: String?,
    val period: String,
    val amountMinor: Long,
    val startDate: Int,
    val rolloverEnabled: Boolean,
    val alertThresholds: String,
    val deletedAt: Long?,
)
