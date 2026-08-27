package com.ledgerflow.feature.inbox

import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.QuantityFormat
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Quantity
import com.ledgerflow.core.ui.lineitem.LineItemEditorEvent
import com.ledgerflow.core.ui.lineitem.LineItemEditorState
import com.ledgerflow.core.ui.lineitem.LineItemRow
import com.ledgerflow.core.ui.picker.LfPickerDialog
import com.ledgerflow.core.ui.picker.LfPickerOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Everything the review screen can put in front of the form. */
@Composable
internal fun ReviewDialogs(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    state.picker?.let { PickerDialog(it, state, onEvent) }
    if (state.choosingDate) DateDialog(state, onEvent)
    if (state.confirmingSingleItem) SingleItemDialog(state, onEvent)
}

/**
 * The review screen's picker, over the shared component.
 *
 * The dialog lives in `:core:ui` and is the *same* one the entry form opens.
 * What stays here is the translation — which options this picker offers and
 * what is selected — because `:core:ui` knows no domain type.
 */
@Composable
private fun PickerDialog(
    picker: ReviewPicker,
    state: ReviewUiState,
    onEvent: (ReviewEvent) -> Unit,
) {
    val canCreate = state.canCreateMerchant(picker)
    LfPickerDialog(
        title = picker.title,
        body = picker.body,
        options = picker.optionsFrom(state),
        selectedId = picker.selectedIdIn(state),
        onSelect = { onEvent(ReviewEvent.PickerItemSelected(it)) },
        onDismiss = { onEvent(ReviewEvent.PickerDismissed) },
        emptyMessage = picker.emptyMessage,
        // Merchants only, exactly as in the entry form: it is the one list that
        // grows without bound, and a search box over six payment methods is
        // clutter.
        query = if (picker is ReviewPicker.Merchant) state.merchantQuery else null,
        onQueryChange = { onEvent(ReviewEvent.MerchantQueryChanged(it)) },
        createLabel = if (canCreate) "Add \"${state.merchantQuery.trim()}\"" else null,
        onCreate = if (canCreate) {
            { onEvent(ReviewEvent.MerchantCreateRequested) }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.occurredAt)
    LfDialog(
        title = "Date",
        body = "",
        confirmText = "Set",
        onConfirm = {
            pickerState.selectedDateMillis?.let { onEvent(ReviewEvent.DateSelected(it)) }
                ?: onEvent(ReviewEvent.DateDismissed)
        },
        onDismiss = { onEvent(ReviewEvent.DateDismissed) },
        detail = { DatePicker(state = pickerState, title = null, headline = null) },
    )
}

/**
 * Leaving itemised mode discards the lines, so it asks first.
 *
 * `Warning` emphasis for the same reason the entry form uses it: this is the
 * one control on the screen that destroys work the user typed.
 */
@Composable
private fun SingleItemDialog(state: ReviewUiState, onEvent: (ReviewEvent) -> Unit) {
    val count = state.lines.count { it.hasContent }
    LfDialog(
        title = "Discard the items?",
        body = "Going back to a single item removes the $count " +
            (if (count == 1) "line" else "lines") + " you have entered.",
        confirmText = "Discard items",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(ReviewEvent.SingleItemConfirmed) },
        onDismiss = { onEvent(ReviewEvent.SingleItemDismissed) },
    )
}

// ── Picker vocabulary ────────────────────────────────────────────────────────

private val ReviewPicker.title: String
    get() = when (this) {
        is ReviewPicker.Category -> "Category"
        is ReviewPicker.Subcategory -> "Subcategory"
        ReviewPicker.Merchant -> "Merchant"
        ReviewPicker.PaymentMethod -> "Paid with"
        ReviewPicker.Book -> "Book"
    }

private val ReviewPicker.body: String
    get() = when (this) {
        // Named only when filing a line, so the user can tell which of twelve
        // rows they opened this from (ADR-0018).
        is ReviewPicker.Category -> if (lineKey != null) "For this item." else ""
        is ReviewPicker.Subcategory -> if (lineKey != null) "For this item." else ""
        ReviewPicker.Merchant -> ""
        ReviewPicker.PaymentMethod -> ""
        ReviewPicker.Book -> "The message did not say whether this was money in or out."
    }

private val ReviewPicker.emptyMessage: String
    get() = when (this) {
        is ReviewPicker.Category -> "No categories in this book yet."
        is ReviewPicker.Subcategory -> "This category has no subcategories."
        ReviewPicker.Merchant -> "No merchants yet — type a name to add one."
        ReviewPicker.PaymentMethod -> "No payment methods set up yet."
        ReviewPicker.Book -> ""
    }

private fun ReviewPicker.optionsFrom(state: ReviewUiState): List<LfPickerOption> = when (this) {
    is ReviewPicker.Category -> state.categories
        .filter { !it.isSubcategory }
        .map { LfPickerOption(it.id, it.name) }

    is ReviewPicker.Subcategory -> state.categories
        .filter { it.parentId == parentId }
        .map { LfPickerOption(it.id, it.name) }

    ReviewPicker.Merchant -> state.merchants
        .filter { it.canonicalName.contains(state.merchantQuery.trim(), ignoreCase = true) }
        .map { LfPickerOption(it.id, it.canonicalName) }

    ReviewPicker.PaymentMethod -> state.paymentMethods.map { LfPickerOption(it.id, it.label) }

    ReviewPicker.Book -> LedgerType.entries.map { LfPickerOption(it.name, it.label()) }
}

private fun ReviewPicker.selectedIdIn(state: ReviewUiState): String? = when (this) {
    is ReviewPicker.Category ->
        lineKey?.let { key -> state.lines.firstOrNull { it.key == key }?.categoryId }
            ?: state.categoryId

    is ReviewPicker.Subcategory ->
        lineKey?.let { key -> state.lines.firstOrNull { it.key == key }?.subcategoryId }
            ?: state.subcategoryId

    ReviewPicker.Merchant -> state.merchantId
    ReviewPicker.PaymentMethod -> state.paymentMethodId
    ReviewPicker.Book -> state.ledger?.name
}

/**
 * Whether to offer creating the typed merchant.
 *
 * Compared on the trimmed name against what already exists, so typing a name
 * the list contains selects rather than duplicates.
 */
private fun ReviewUiState.canCreateMerchant(picker: ReviewPicker): Boolean {
    if (picker !is ReviewPicker.Merchant) return false
    val typed = merchantQuery.trim()
    if (typed.isEmpty()) return false
    return merchants.none { it.canonicalName.equals(typed, ignoreCase = true) }
}

// ── Line editor glue ─────────────────────────────────────────────────────────

/**
 * The editor's view of the lines: names resolved, amounts formatted.
 *
 * Built here rather than in the composable because it needs the taxonomy and
 * the base currency, and a stateless composable has no business looking either
 * up (CLAUDE.md §5).
 */
internal fun ReviewUiState.editorState(): LineItemEditorState {
    if (!itemised) return LineItemEditorState()

    val names = categories.associate { it.id to it.name }
    val total = MoneyFormat.parse(amountText, DEFAULT_CURRENCY_CODE)
    val remainder = total - lines.sumOf { it.amountMinor }

    return LineItemEditorState(
        rows = lines.map { line ->
            LineItemRow(
                key = line.key,
                name = line.name,
                unitPriceText = line.unitPriceText,
                quantityText = line.quantityText,
                totalText = MoneyFormat.symbolised(line.amountMinor, DEFAULT_CURRENCY_CODE),
                // Null at one. "×1" on every row of a grocery list is a column
                // of noise on the one line a collapsed row has to work with.
                quantityLabel = line.quantityMilli
                    .takeIf { it != Quantity.SCALE }
                    ?.let { "×" + QuantityFormat.plain(it) },
                categoryName = line.categoryId?.let(names::get),
                subcategoryName = line.subcategoryId?.let(names::get),
            )
        },
        expandedKey = expandedLineKey,
        summary = allocationSummary(remainder),
        balanced = remainder == 0L,
    )
}

/**
 * What is still unaccounted for: "₹160 left".
 *
 * Shown rather than corrected: §5.4 allows saving an unbalanced set and the
 * approval records the difference as an `UNALLOCATED` line, so hiding it here
 * would make that row appear from nowhere.
 */
private fun ReviewUiState.allocationSummary(remainder: Long): String? = when {
    lines.none { it.hasContent } -> null
    remainder == 0L -> "All allocated"
    remainder > 0L -> MoneyFormat.symbolised(remainder, DEFAULT_CURRENCY_CODE) + " left"
    else -> MoneyFormat.symbolised(-remainder, DEFAULT_CURRENCY_CODE) + " over"
}

/** The shared editor's events, in this screen's vocabulary. */
internal fun LineItemEditorEvent.toReviewEvent(): ReviewEvent = when (this) {
    LineItemEditorEvent.AddRequested -> ReviewEvent.LineAdded
    is LineItemEditorEvent.Expanded -> ReviewEvent.LineExpanded(key)
    LineItemEditorEvent.Collapsed -> ReviewEvent.LineCollapsed
    is LineItemEditorEvent.NameChanged -> ReviewEvent.LineNameChanged(key, value)
    is LineItemEditorEvent.UnitPriceChanged -> ReviewEvent.LineUnitPriceChanged(key, text)
    is LineItemEditorEvent.QuantityChanged -> ReviewEvent.LineQuantityChanged(key, text)
    is LineItemEditorEvent.RemoveRequested -> ReviewEvent.LineRemoved(key)
    is LineItemEditorEvent.CategoryRequested -> ReviewEvent.LineCategoryRequested(key)
    is LineItemEditorEvent.SubcategoryRequested -> ReviewEvent.LineSubcategoryRequested(key)
}

internal fun Long.asLocalDate(): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

/**
 * The formatter's currency for line figures.
 *
 * `amount_minor` is always base currency (D-02) and onboarding guarantees one
 * exists; this is the fallback the ViewModel also uses before `app_meta`
 * answers. It affects grouping and the minor-unit exponent, not the value.
 */
private const val DEFAULT_CURRENCY_CODE = "INR"
