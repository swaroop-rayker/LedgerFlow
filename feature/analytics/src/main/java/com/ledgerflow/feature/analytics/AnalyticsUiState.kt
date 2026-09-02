package com.ledgerflow.feature.analytics

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.model.LedgerType

/**
 * Analytics, A1–A5 (`SPEC.md` §5.6).
 *
 * `@Immutable` and one class for the screen, per `CLAUDE.md` §5. The snapshot is
 * carried whole rather than flattened into a field per view: the five views are
 * five readings of the *same* window, and splitting them into independent state
 * is what would allow a donut from one range to sit beside a bar chart from
 * another.
 *
 * **`ledger` is fixed per screen and not a filter** (§5.6). There is no toggle
 * here that could produce a netted figure, because there is no state in which
 * both books are on (Law 2).
 */
@Immutable
public data class AnalyticsUiState(
    val ledger: LedgerType = LedgerType.DEBIT,
    val range: AnalyticsRange = AnalyticsRange.MONTH,
    val comparePrevious: Boolean = true,
    val isLoading: Boolean = true,
    val snapshot: AnalyticsSnapshot? = null,
    val baseCurrency: String = "INR",
    /**
     * Which category's subcategories are open (A3), or null.
     *
     * State, not a ViewModel field: `CLAUDE.md` §5 hoists it here, and holding
     * it outside the `StateFlow` would mean emitting an unchanged value to
     * announce the change -- which `StateFlow` conflates away, so the list
     * would simply not expand.
     */
    val expandedCategoryId: String? = null,
) {
    /**
     * Distinguishes "nothing yet" from "nothing here".
     *
     * A vault with no entries and a vault still loading look identical if the
     * screen only checks for an empty snapshot, and the first renders as a
     * flash of "no spending" before the real numbers arrive.
     */
    public val showEmptyState: Boolean get() = !isLoading && (snapshot?.isEmpty ?: true)
}

/** Events flow up as one lambda (`CLAUDE.md` §5). */
public sealed interface AnalyticsEvent {
    public data class RangeSelected(val range: AnalyticsRange) : AnalyticsEvent
    public data object ComparisonToggled : AnalyticsEvent
    public data class CategoryExpanded(val categoryId: String?) : AnalyticsEvent
}
