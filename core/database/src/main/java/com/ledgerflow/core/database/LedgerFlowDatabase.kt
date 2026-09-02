package com.ledgerflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.ledgerflow.core.database.dao.AppMetaDao
import com.ledgerflow.core.database.dao.BudgetDao
import com.ledgerflow.core.database.dao.CategoryDao
import com.ledgerflow.core.database.dao.CategoryGroupDao
import com.ledgerflow.core.database.dao.DraftEntryDao
import com.ledgerflow.core.database.dao.LedgerEntryDao
import com.ledgerflow.core.database.dao.LedgerTaxonomyDao
import com.ledgerflow.core.database.dao.MerchantAliasDao
import com.ledgerflow.core.database.dao.MerchantDao
import com.ledgerflow.core.database.dao.NotificationRawDao
import com.ledgerflow.core.database.dao.PackageAllowlistDao
import com.ledgerflow.core.database.dao.ParserRuleDao
import com.ledgerflow.core.database.dao.PaymentMethodDao
import com.ledgerflow.core.database.dao.PendingTransactionDao
import com.ledgerflow.core.database.dao.SenderAllowlistDao
import com.ledgerflow.core.database.dao.SmsRawDao
import com.ledgerflow.core.database.entity.AppMetaEntity
import com.ledgerflow.core.database.entity.BudgetEntity
import com.ledgerflow.core.database.entity.CategoryEntity
import com.ledgerflow.core.database.entity.CategoryGroupEntity
import com.ledgerflow.core.database.entity.CategoryGroupMemberEntity
import com.ledgerflow.core.database.entity.CreditEntryView
import com.ledgerflow.core.database.entity.DailyRollupEntity
import com.ledgerflow.core.database.entity.DebitEntryView
import com.ledgerflow.core.database.entity.DraftEntryEntity
import com.ledgerflow.core.database.entity.LedgerEntryEntity
import com.ledgerflow.core.database.entity.LineItemEntity
import com.ledgerflow.core.database.entity.MerchantAliasEntity
import com.ledgerflow.core.database.entity.MerchantEntity
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.ParserRuleEntity
import com.ledgerflow.core.database.entity.PaymentMethodEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.database.entity.SmsRawEntity

/**
 * The encrypted ledger database.
 *
 * Schema v1 carries only what Phase 0 needs (SPEC.md §6.1). `pending_transaction`,
 * `sms_raw`, `notification_raw` and `daily_rollup` arrive with the features that
 * use them -- an unused table is a migration liability, and every table here has
 * to be carried through every future migration whether it holds data or not.
 *
 * `exportSchema = true` is mandatory: the JSONs in `schemas/` are committed and
 * `scripts/guard-schema.sh` treats them as append-only. That guard is the BUG8
 * countermeasure and it can only work if schemas are actually written out.
 */
@Database(
    entities = [
        AppMetaEntity::class,
        CategoryEntity::class,
        MerchantEntity::class,
        PaymentMethodEntity::class,
        LedgerEntryEntity::class,
        LineItemEntity::class,
        // v2 (SPEC.md §6.1.2, D-06).
        DraftEntryEntity::class,
        MerchantAliasEntity::class,
        CategoryGroupEntity::class,
        CategoryGroupMemberEntity::class,
        // v6 (SPEC.md §5.1, §5.2, §6.1) — the ingest and approval queue.
        SmsRawEntity::class,
        NotificationRawEntity::class,
        PackageAllowlistEntity::class,
        SenderAllowlistEntity::class,
        ParserRuleEntity::class,
        PendingTransactionEntity::class,
        // v9 (SPEC.md §5.6, §5.7, §6.1) — analytics and budgets.
        BudgetEntity::class,
        DailyRollupEntity::class,
    ],
    views = [
        DebitEntryView::class,
        CreditEntryView::class,
    ],
    version = LedgerFlowDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(LedgerFlowConverters::class)
public abstract class LedgerFlowDatabase : RoomDatabase() {

    public abstract fun appMetaDao(): AppMetaDao
    public abstract fun categoryDao(): CategoryDao
    public abstract fun merchantDao(): MerchantDao
    public abstract fun paymentMethodDao(): PaymentMethodDao
    public abstract fun ledgerEntryDao(): LedgerEntryDao
    public abstract fun ledgerTaxonomyDao(): LedgerTaxonomyDao
    public abstract fun draftEntryDao(): DraftEntryDao
    public abstract fun merchantAliasDao(): MerchantAliasDao
    public abstract fun categoryGroupDao(): CategoryGroupDao

    // v6 — ingest (SPEC.md §5.1, §5.2).
    public abstract fun smsRawDao(): SmsRawDao
    public abstract fun notificationRawDao(): NotificationRawDao
    public abstract fun packageAllowlistDao(): PackageAllowlistDao
    public abstract fun senderAllowlistDao(): SenderAllowlistDao
    public abstract fun parserRuleDao(): ParserRuleDao

    /**
     * The approval queue. Declared at v6, first written at P2-4.
     *
     * Reaching it is not reaching the ledger: Law 1's single writer into
     * `ledger_entry` is `ApproveTransactionUseCase` and nothing on this DAO can
     * take that path.
     */
    public abstract fun pendingTransactionDao(): PendingTransactionDao

    public abstract fun budgetDao(): BudgetDao

    public companion object {
        /**
         * v2 adds `draft_entry` (BUG6), `merchant_alias`, and the two
         * category-group tables. Purely additive — see `MIGRATION_1_2`.
         *
         * v3 drops `draft_entry`'s unique slot index so a book can hold many
         * in-flight entries at once (ADR-0013, superseding D-06) — see
         * `MIGRATION_2_3`.
         *
         * v4 and v5 denormalise summary columns out of `draft_entry`'s payload
         * so the drafts stack can render a row without parsing JSON.
         *
         * v6 adds the ingest side: the two raw tables, the two allowlists, the
         * parser ruleset, and `pending_transaction` — the approval queue Law 1
         * is about. Purely additive; no existing table is touched. `sms_raw`
         * and `notification_raw` were named in §6.1 from the start and stayed
         * out of the schema until P2 needed them. `pending_line_item` is still
         * absent on purpose: nothing at P2 can produce an itemised candidate,
         * so it lands at P4 with OCR (§16 Q7).
         *
         * v7 adds `parser_rule.instrument_hint` — one column, for a rule that
         * knows the instrument its messages describe even when they do not say
         * so. Additive; nothing existing is touched.
         *
         * v8 adds `pending_transaction.review_draft_json` — BUG6 applied to the
         * Inbox. A back press pops the review destination and destroys its
         * ViewModel, so typing that never reached disk was gone; this is where
         * it lands. Deliberately NOT a `draft_entry` row: §5.4 keeps the two
         * queues apart, and a half-reviewed message in the drafts stack could
         * be discarded in one place and stay alive in the other. Additive —
         * see `MIGRATION_7_8`.
         *
         * v9 adds `budget` and `daily_rollup` — P3's two tables, both named in
         * §6.1 from the start and both held back until something wrote to them.
         * Purely additive; no existing table is touched. They are not the same
         * kind of thing and the difference matters: `daily_rollup` is derived
         * and a wrong one is rebuilt from `ledger_entry` (ADR-0006), while
         * `budget` is user intent that nothing in the app can reconstruct —
         * which is why it joins the `.lfbk` payload at v9 and `daily_rollup`
         * deliberately does not. See `MIGRATION_8_9`.
         */
        public const val VERSION: Int = 9

        /** Lives in `databases/`, never `cacheDir` or external storage (Law 5). */
        public const val DATABASE_NAME: String = "ledgerflow.db"
    }
}
