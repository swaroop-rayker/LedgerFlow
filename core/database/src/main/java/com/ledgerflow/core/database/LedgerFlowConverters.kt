package com.ledgerflow.core.database

import androidx.room.TypeConverter
import com.ledgerflow.core.model.BudgetPeriod
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethodType
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus

/**
 * Room type converters.
 *
 * [Money] maps to `INTEGER`, never `REAL` (Law 3). Enums are stored by `name`,
 * not ordinal: an ordinal silently re-points every existing row if someone
 * reorders the enum, which is the kind of change that looks harmless in review.
 *
 * Unknown enum names decode to null rather than throwing, so a database written
 * by a newer build degrades instead of crashing on read -- consistent with the
 * downgrade guard in SPEC.md §8 (BUG3).
 */
public class LedgerFlowConverters {

    @TypeConverter
    public fun moneyToLong(value: Money?): Long? = value?.minor

    @TypeConverter
    public fun longToMoney(value: Long?): Money? = value?.let(::Money)

    @TypeConverter
    public fun ledgerTypeToString(value: LedgerType?): String? = value?.name

    @TypeConverter
    public fun stringToLedgerType(value: String?): LedgerType? =
        value?.let { name -> LedgerType.entries.firstOrNull { it.name == name } }

    @TypeConverter
    public fun entrySourceToString(value: EntrySource?): String? = value?.name

    @TypeConverter
    public fun stringToEntrySource(value: String?): EntrySource? =
        value?.let { name -> EntrySource.entries.firstOrNull { it.name == name } }

    @TypeConverter
    public fun paymentMethodTypeToString(value: PaymentMethodType?): String? = value?.name

    @TypeConverter
    public fun stringToPaymentMethodType(value: String?): PaymentMethodType? =
        value?.let { name -> PaymentMethodType.entries.firstOrNull { it.name == name } }

    @TypeConverter
    public fun lineItemKindToString(value: LineItemKind?): String? = value?.name

    @TypeConverter
    public fun stringToLineItemKind(value: String?): LineItemKind? =
        value?.let { name -> LineItemKind.entries.firstOrNull { it.name == name } }

    // ── v6: ingest (SPEC.md §5.1, §5.2) ───────────────────────────────────

    @TypeConverter
    public fun rawParseStatusToString(value: RawParseStatus?): String? = value?.name

    @TypeConverter
    public fun stringToRawParseStatus(value: String?): RawParseStatus? =
        value?.let { name -> RawParseStatus.entries.firstOrNull { it.name == name } }

    @TypeConverter
    public fun pendingStatusToString(value: PendingStatus?): String? = value?.name

    @TypeConverter
    public fun stringToPendingStatus(value: String?): PendingStatus? =
        value?.let { name -> PendingStatus.entries.firstOrNull { it.name == name } }

    // -- v9: budgets (SPEC.md 5.7) --------------------------------------

    @TypeConverter
    public fun budgetPeriodToString(value: BudgetPeriod?): String? = value?.name

    @TypeConverter
    public fun stringToBudgetPeriod(value: String?): BudgetPeriod? =
        value?.let { name -> BudgetPeriod.entries.firstOrNull { it.name == name } }
}
