package com.ledgerflow.core.ui.lineitem

import androidx.compose.runtime.Immutable

/**
 * One line of an itemised entry, as the editor renders it (SPEC.md §5.4,
 * ADR-0018).
 *
 * Everything here is either raw text the user typed or a string the host has
 * already resolved. That is deliberate and it is what lets this composite live
 * in `:core:ui`: the editor does no money arithmetic, looks nothing up, and
 * knows no domain type, so `:feature:entry` today and `:feature:inbox`'s review
 * screen at P2 can drive the same component without either of them reaching
 * into the other (CLAUDE.md §3 — features never depend on features).
 *
 * The text fields hold raw text rather than parsed values for the reason the
 * entry amount does: reformatting a number under a moving caret makes the field
 * unusable. Parsing happens once, in the host's ViewModel.
 */
@Immutable
public data class LineItemRow(
    /**
     * Client-side identity, stable across edits.
     *
     * Not the eventual `line_item.id`, which the approval mints. Without it,
     * removing the second of three rows re-keys the third and Compose reuses
     * the wrong text field's state.
     */
    val key: String,
    val name: String = "",
    val unitPriceText: String = "",
    val quantityText: String = "",
    /** `unit price × quantity`, formatted by the host. Never computed here. */
    val totalText: String = "",
    /** "×2". Null when the quantity is one — the common case needs no decoration. */
    val quantityLabel: String? = null,
    val categoryName: String? = null,
    val subcategoryName: String? = null,
) {
    /** "Groceries · Dairy", or just the category, or null when nothing is filed. */
    public val filedAs: String?
        get() = listOfNotNull(categoryName, subcategoryName)
            .joinToString(" · ")
            .ifBlank { null }
}

/**
 * The editor's whole state.
 *
 * @param rows in display order, which is the order they will be stored in.
 * @param expandedKey the one row open for editing, or null when all are
 *   collapsed. One at a time is what keeps a twelve-line grocery bill
 *   scannable; a list where every row is a form is a list nobody reads.
 * @param summary "₹840 of ₹1,000 · ₹160 left", built by the host because it owns
 *   the money. Null when there is nothing to reconcile yet.
 * @param balanced whether the lines add up to the entry total. Drives the
 *   summary's colour only — an unbalanced entry is saved, not refused (§5.4),
 *   and the difference becomes an `UNALLOCATED` line.
 */
@Immutable
public data class LineItemEditorState(
    val rows: List<LineItemRow> = emptyList(),
    val expandedKey: String? = null,
    val summary: String? = null,
    val balanced: Boolean = true,
)

/**
 * What the editor asks its host to do.
 *
 * It changes nothing itself. Every one of these is a request the ViewModel
 * answers, including which row is expanded — that lives in the host's state
 * rather than in a `remember` here, so a rotation mid-edit does not collapse
 * the row the user is typing in. Losing half-entered work to a config change is
 * BUG6 in miniature and the fix is the same one: state belongs above the
 * composition.
 */
public sealed interface LineItemEditorEvent {

    public data object AddRequested : LineItemEditorEvent

    /** Tapping a collapsed row opens it; tapping the open row's "Done" closes it. */
    public data class Expanded(val key: String) : LineItemEditorEvent

    public data object Collapsed : LineItemEditorEvent

    public data class NameChanged(val key: String, val value: String) : LineItemEditorEvent

    public data class UnitPriceChanged(val key: String, val text: String) : LineItemEditorEvent

    public data class QuantityChanged(val key: String, val text: String) : LineItemEditorEvent

    public data class CategoryRequested(val key: String) : LineItemEditorEvent

    /** Only reachable once the line has a category — a subcategory needs its parent. */
    public data class SubcategoryRequested(val key: String) : LineItemEditorEvent

    public data class RemoveRequested(val key: String) : LineItemEditorEvent
}
