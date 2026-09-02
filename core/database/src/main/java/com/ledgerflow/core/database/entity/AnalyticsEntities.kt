package com.ledgerflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money

/**
 * A per-category spending budget (SPEC.md §5.7, §6.1).
 *
 * **This table is user intent and nothing else in the app can reconstruct it.**
 * That is what separates it from [DailyRollupEntity] beside it: a wrong rollup
 * is rebuilt from `ledger_entry` in a second, a lost budget is gone. It is in
 * the `.lfbk` payload for that reason, and the round-trip test asserts it.
 *
 * **Debit only, and the column set says so by omission.** §5.7 scopes budgets
 * to the debit ledger, and §6.1's DDL carries no `ledger` column, so there is
 * no such thing as a credit budget to express. The Law 2 obligation therefore
 * lands on the *reads*: every progress query against `daily_rollup` binds
 * `ledger = 'DEBIT'`, and `LedgerIsolationTest` is extended to `daily_rollup`
 * so a query that forgets fails the build rather than quietly summing income
 * into a spending budget.
 *
 * **No foreign key on `category_id`,** matching `ledger_entry`: `category_id`
 * has no key there either (ADR-0016), because taxonomy deletion is governed by
 * a reassign-or-block rule in code that a `SET NULL` would silently pre-empt.
 * A budget pointing at a category the user removed is handled the same way.
 */
@Entity(
    tableName = "budget",
    indices = [
        // Progress is looked up per category, for the categories on screen.
        Index(value = ["category_id"]),
    ],
)
public data class BudgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    /** Null means the budget covers the whole category, subcategories included. */
    @ColumnInfo(name = "subcategory_id")
    val subcategoryId: String?,

    @ColumnInfo(name = "period")
    val period: BudgetPeriod,

    /** Base currency, always (SPEC.md §5.8). Law 3: minor units in an INTEGER. */
    @ColumnInfo(name = "amount_minor")
    val amountMinor: Money,

    /** Days since epoch — the same encoding as `ledger_entry.local_date`. */
    @ColumnInfo(name = "start_date")
    val startDate: Int,

    @ColumnInfo(name = "rollover_enabled", defaultValue = "0")
    val rolloverEnabled: Boolean = false,

    /**
     * Percentages, comma-separated, ascending. §5.7 defaults to `80,100`.
     *
     * A string rather than a child table because it is a short list the user
     * edits as a whole and nothing ever queries across budgets by threshold.
     */
    @ColumnInfo(name = "alert_thresholds", defaultValue = "80,100")
    val alertThresholds: String = "80,100",

    /**
     * Soft delete, nullable, unlike `category.deleted_at`.
     *
     * §6.1.1 made that column `NOT NULL DEFAULT 0` to give a unique index
     * something to collide on. There is no uniqueness constraint here — two
     * budgets on one category is a user error, not a schema error — so the
     * column keeps §6.1's nullable form and means what it reads as.
     */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)

/**
 * The materialized analytics table (SPEC.md §5.6, §6.1; ADR-0006).
 *
 * **Derived, entirely.** Every row is reproducible from `ledger_entry` joined
 * to `line_item`, which is what lets ADR-0006 say the base tables win on any
 * disagreement, and what lets the `.lfbk` payload leave this table out and
 * rebuild it on restore instead of carrying what would be the largest table in
 * the database as uncompressed JSON.
 *
 * **Fed at line grain where an entry has lines** (ADR-0018). A ₹1,000 bill
 * split ₹600/₹400 writes two rows.
 *
 * **`txn_count` counts distinct `ledger_entry` rows**, so that bill writes
 * `1` to *both* rows — one payment happened, not two (§5.6, decided at P3).
 * The consequence a caller has to know: the column is additive across every
 * dimension **except** [categoryId] and [subcategoryId], because an entry
 * carries exactly one date, one merchant and one payment method and only the
 * categories fan out. An all-categories "N transactions" figure is a
 * `COUNT(DISTINCT id)` over the base tables, never a `SUM(txn_count)`.
 *
 * **`''` is the "this dimension does not apply" sentinel, never `NULL`**
 * (§6.1.1). Room requires non-null primary-key fields, and SQLite treats
 * `NULL`s as distinct inside a composite key — so a nullable dimension would
 * both fail codegen and fan one logical bucket out into rows that can never
 * merge. Writers map `NULL` to `''`.
 */
@Entity(
    tableName = "daily_rollup",
    primaryKeys = [
        "local_date",
        "ledger",
        "category_id",
        "subcategory_id",
        "merchant_id",
        "payment_method_id",
    ],
    indices = [
        // The dominant query is "one book, a date range" — every chart in §5.6
        // and the 5Y < 300 ms budget in §11. The primary key leads with
        // `local_date`, so on its own it makes that a scan filtered by ledger
        // rather than a seek; this index leads with `ledger` and makes the
        // partition physical in the B-tree, which is what ADR-0002 asks of
        // every index that touches a partitioned read.
        Index(value = ["ledger", "local_date"]),
    ],
)
public data class DailyRollupEntity(
    /** Days since epoch. Same encoding as `ledger_entry.local_date`. */
    @ColumnInfo(name = "local_date")
    val localDate: Int,

    @ColumnInfo(name = "ledger")
    val ledger: LedgerType,

    @ColumnInfo(name = "category_id", defaultValue = "")
    val categoryId: String = "",

    @ColumnInfo(name = "subcategory_id", defaultValue = "")
    val subcategoryId: String = "",

    @ColumnInfo(name = "merchant_id", defaultValue = "")
    val merchantId: String = "",

    @ColumnInfo(name = "payment_method_id", defaultValue = "")
    val paymentMethodId: String = "",

    /** Base currency, always. Law 3. */
    @ColumnInfo(name = "sum_minor")
    val sumMinor: Money,

    /** Distinct entries, not lines. See the class note. */
    @ColumnInfo(name = "txn_count")
    val txnCount: Int,
)
