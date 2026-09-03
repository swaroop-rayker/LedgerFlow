package com.ledgerflow.feature.analytics

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.analytics.AnalyticsFilters
import com.ledgerflow.core.domain.analytics.AnalyticsRange
import com.ledgerflow.core.domain.analytics.AnalyticsSnapshot
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant

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
    /** §5.6's composable filters. [AnalyticsFilters.None] is the fast path. */
    val filters: AnalyticsFilters = AnalyticsFilters.None,
    val showFilterSheet: Boolean = false,
    /** Custom range being picked, or null. */
    val customFrom: Int? = null,
    val customTo: Int? = null,
    val showRangePicker: Boolean = false,
    /** Everything the filter sheet may offer. */
    val allCategories: List<Category> = emptyList(),
    val allMerchants: List<Merchant> = emptyList(),
) {
    /**
     * Distinguishes "nothing yet" from "nothing here".
     *
     * A vault with no entries and a vault still loading look identical if the
     * screen only checks for an empty snapshot, and the first renders as a
     * flash of "no spending" before the real numbers arrive.
     */
    public val showEmptyState: Boolean get() = !isLoading && (snapshot?.isEmpty ?: true)

    /**
     * Whether the empty state is "you have no spending" or "nothing matched".
     *
     * They are different sentences and the difference matters: one invites the
     * user to add an entry, the other to widen the filter. Showing the first
     * when a filter is active is how a screen makes someone think their data is
     * missing.
     */
    public val emptyBecauseFiltered: Boolean get() = showEmptyState && !filters.isEmpty
}

/** Events flow up as one lambda (`CLAUDE.md` §5). */
public sealed interface AnalyticsEvent {
    public data class RangeSelected(val range: AnalyticsRange) : AnalyticsEvent
    public data object ComparisonToggled : AnalyticsEvent
    public data class CategoryExpanded(val categoryId: String?) : AnalyticsEvent
    public data object FiltersClicked : AnalyticsEvent
    public data object FiltersDismissed : AnalyticsEvent
    public data object FiltersCleared : AnalyticsEvent
    public data class FiltersChanged(val filters: AnalyticsFilters) : AnalyticsEvent
    public data object CustomRangeClicked : AnalyticsEvent
    public data class CustomRangePicked(val from: Int, val to: Int) : AnalyticsEvent
    public data object CustomRangeDismissed : AnalyticsEvent
}
