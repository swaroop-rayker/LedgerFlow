package com.ledgerflow.core.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.component.lfCategoryDot
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.ui.LfEntryRowTokens

/**
 * One committed entry, as the Ledger lists it (SPEC.md §5.5).
 *
 * **Two lines, and the same two on every row.** Naming across the top; the
 * timestamp and the amount sharing the second line, stamp left and amount
 * right. A uniform height is what makes a list scannable, and this shape holds
 * it whatever the row contains.
 *
 * It took two measured failures to arrive at, both worth recording because each
 * looks like the fix for the other:
 *
 * 1. Timestamp beside the note, amount in a column of its own. A row of
 *    `-₹1,54,000.00` squeezed the left column until the *stamp* ellipsised to
 *    "18 Aug, 5:32 …" — the one field the row exists to add was the field that
 *    got clipped.
 * 2. Timestamp stacked under the amount. That fixed the clipping and made the
 *    right-hand column as wide as **the wider of the two**, which for anything
 *    under about ₹100 is the *stamp*, not the amount. So every row paid a
 *    ~120dp column whatever it held, and once the delete control took its 48dp
 *    the naming block fell under its floor and every row stacked into three
 *    lines.
 *
 * Neither field competes here: the name owns a line, and the stamp and amount
 * are the only two things on the other one. They are still measured by
 * [LfAdaptiveRow]'s rule, so at large font scales they stack rather than clip — but at ordinary
 * scales they always fit, which is what keeps the list dense.
 *
 * Still denser than the Organise cards (CLAUDE.md §5): those spend their second
 * line on a row of controls, this one spends it on data.
 *
 * Lifted from `:feature:ledger` for Home's "Recent spending" and "Recent
 * income" (V2-PLAN BUG-C), so the two screens show an entry the same way and
 * cannot drift apart: features never depend on each other (CLAUDE.md §3), and a
 * second copy is how they would. The move changed no pixel of the Ledger; its
 * goldens are the proof.
 *
 * [deleteIcon] null means a read-only row (Home), and the trailing inset
 * returns to `md` because there is no touch target carrying its own.
 *
 * @param withDate false under a band header that already says the day.
 */
@Composable
public fun LfEntryRow(
    item: LedgerListItem,
    withDate: Boolean,
    modifier: Modifier = Modifier,
    deleteIcon: Painter? = null,
    onDelete: () -> Unit = {},
) {
    val tokens = LfEntryRowTokens
    val shape = RoundedCornerShape(tokens.corner)

    // Merchant *and* category, on one line. They were stacked — merchant as the
    // title, category demoted to the grey line — which read as a hierarchy that
    // does not exist: they are two independent facts about the same entry, and
    // most rows have both. Either alone degrades to just itself, with no
    // dangling separator.
    val title = item.entryTitle()
    // The band header already says "Today"; repeating the date under it is
    // noise, and dropping it is also what keeps the second line comfortable on
    // the rows the user looks at most.
    val stamp = TimeStamp.of(item.occurredAt, withDate)
    val note = item.note?.takeIf { it.isNotBlank() }
    val detail = listOfNotNull(stamp, note).joinToString(ENTRY_SEPARATOR)
    val gapPx = with(LocalDensity.current) { tokens.gap.roundToPx() }

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.container, shape)
            .border(tokens.borderWidth, tokens.border, shape)
            // Tighter on the trailing edge: the delete control is a 48dp touch
            // target around a 16dp glyph, so it carries ~16dp of its own inset
            // and a full `md` on top would leave the icon looking adrift.
            .padding(
                start = tokens.insetStart,
                end = if (deleteIcon != null) tokens.insetEndBesideAction else tokens.insetEnd,
                top = tokens.insetVertical,
                bottom = tokens.insetVertical,
            )
            // The merged announcement covers the *content*, not the delete
            // control. The button's `clickable` merges its own subtree, so a
            // merging parent leaves it a separate node with its own label --
            // `clearAndSetSemantics` here would swallow it and leave a
            // screen-reader user with a control they cannot identify or reach.
            // The texts clear their own semantics, so this description is the
            // only thing the row says.
            .semantics(mergeDescendants = true) { contentDescription = item.spokenAs(title, detail) },
        content = {
            EntrySwatch(item)
            // No `maxLines` and no ellipsis: merchant and category are the
            // row's identity, and clipping them is the BUG9 failure one level
            // up from a button label. They wrap instead, which costs a line on
            // the rare long pairing and never hides which entry this is. It has
            // the full width here, so that is rare.
            Text(
                text = title,
                modifier = Modifier.clearAndSetSemantics {},
                style = tokens.titleStyle,
                color = tokens.title,
            )
            EntryDetail(detail = detail)
            EntryAmount(item = item)
            // An icon, not an `Inline` text action in an `LfActionRow`. The
            // taxonomy cards use the latter, but they carry three actions over a
            // list of a dozen items; this carries one over a list without end,
            // and a text button would add a whole line to every row on the
            // screen whose entire job is being scanned. `LfIconButton` is
            // already a 48dp target and the rows are taller than that, so it
            // costs no height at all.
            if (deleteIcon != null) {
                LfIconButton(
                    icon = deleteIcon,
                    contentDescription = "Delete " + title,
                    onClick = onDelete,
                )
            }
        },
    ) { measurables, constraints ->
        entryRowLayout(measurables, constraints, gapPx)
    }
}

/**
 * The row's one measure policy (PERF-120HZ-PLAN §2.1).
 *
 * This used to be thirteen layout nodes: a Row holding a Row holding the
 * swatch and a Column, the Column holding the title and an [LfAdaptiveRow],
 * with the swatch and the delete control three nodes each. The first Ledger
 * frame after a tab switch composes every visible row inside its measure pass,
 * and that pass was 10-13 ms of a 15-21 ms frame. It is now six: this layout,
 * its three texts, and one leaf each for the swatch and the control, placed at
 * the old pixels.
 *
 * In order: swatch, title, detail, amount, and the delete control when there is
 * one. The geometry is the old nesting's, written out:
 *
 * - The delete control takes its 48 dp first, and the content gets the rest,
 *   less `sm`. Each sits centred on the row's height, as the outer
 *   `CenterVertically` Row centred them.
 * - Within the content, the swatch and the text column are top-aligned, `sm`
 *   apart. The swatch belongs to the name; centring it against a two-line row
 *   parked it between the two lines, which read as though it labelled the
 *   timestamp as much as the merchant (reported on device).
 * - The text column is the title over [LfAdaptiveRow]'s rule: the amount
 *   first, at the full column width, then the detail beside it if it fits on
 *   one line in what is left, and below it otherwise.
 *
 * **The detail is measured once, without an intrinsic pass.** The old rule asked
 * the detail for its `maxIntrinsicWidth` before measuring it. The detail is one
 * line (`NOTE_MAX_LINES`), so measured at the full column width it comes out at
 * exactly that intrinsic width when it fits, and at the column width, which is
 * wider than what the amount leaves, when it does not. So "its measured width
 * exceeds what is left" is the same test, and a fitting single line lays out the
 * same at either width. `LfEntryRowLayoutTest` holds this to the old nesting,
 * pixel for pixel to within anti-aliasing.
 *
 * **RTL mirrors as the old Rows did, and only as far as they did.** The Rows
 * mirrored the swatch, the column and the button, and the title is placed from
 * the start edge. [LfAdaptiveRow] places with `place`, not `placeRelative`, so
 * its detail and amount kept their left and right in RTL; that is reproduced
 * rather than changed here, so this commit moves no pixel.
 */
private fun MeasureScope.entryRowLayout(
    measurables: List<Measurable>,
    constraints: Constraints,
    gapPx: Int,
): MeasureResult {
    val width = constraints.maxWidth
    val button = measurables.getOrNull(BUTTON_SLOT)?.measure(Constraints(maxWidth = width))
    val contentWidth = if (button != null) (width - button.width - gapPx).coerceAtLeast(0) else width

    val swatch = measurables[SWATCH_SLOT].measure(Constraints(maxWidth = contentWidth))
    val textWidth = (contentWidth - swatch.width - gapPx).coerceAtLeast(0)
    val textConstraints = Constraints(maxWidth = textWidth)
    val title = measurables[TITLE_SLOT].measure(textConstraints)
    val amount = measurables[AMOUNT_SLOT].measure(textConstraints)
    val detail = measurables[DETAIL_SLOT].measure(textConstraints)
    val stacked = detail.width > textWidth - amount.width - gapPx

    val figuresHeight =
        if (stacked) detail.height + gapPx + amount.height else maxOf(detail.height, amount.height)
    val contentHeight = maxOf(swatch.height, title.height + figuresHeight)
    val height = constraints.constrainHeight(maxOf(contentHeight, button?.height ?: 0))

    return layout(width, height) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        // Positions are worked out left-to-right and mirrored by hand, because
        // the detail and amount must *not* mirror (see above), so neither
        // `place` nor `placeRelative` is right for every child.
        fun mirrored(x: Int, w: Int): Int = if (rtl) width - x - w else x

        val contentTop = Alignment.CenterVertically.align(contentHeight, height)
        swatch.place(mirrored(0, swatch.width), contentTop)

        val textLeft = mirrored(swatch.width + gapPx, textWidth)
        val titleX = if (rtl) textLeft + textWidth - title.width else textLeft
        title.place(titleX, contentTop)

        val detailTop = contentTop + title.height
        val amountX = textLeft + textWidth - amount.width
        if (stacked) {
            detail.place(textLeft, detailTop)
            amount.place(amountX, detailTop + detail.height + gapPx)
        } else {
            detail.place(textLeft, detailTop + (figuresHeight - detail.height) / 2)
            amount.place(amountX, detailTop + (figuresHeight - amount.height) / 2)
        }

        if (button != null) {
            button.place(
                mirrored(contentWidth + gapPx, button.width),
                Alignment.CenterVertically.align(button.height, height),
            )
        }
    }
}

private const val SWATCH_SLOT = 0
private const val TITLE_SLOT = 1
private const val DETAIL_SLOT = 2
private const val AMOUNT_SLOT = 3
private const val BUTTON_SLOT = 4

/**
 * The row's name: merchant and category, or [ENTRY_UNFILED].
 *
 * The category half reads `displayCategoryName`, not `categoryName` directly
 * (ADR-0018): an itemised entry has no category of its own, so its largest line
 * item's stands in, with "+2" appended when the bill spans more than one.
 * Without this an itemised row fell into the same unfiled bucket as a genuinely
 * uncategorised one — a Reliance Fresh bill split across groceries and
 * electronics read as filed under nothing at all.
 */
public fun LedgerListItem.entryTitle(): String {
    val categoryLabel = displayCategoryName?.let { name ->
        additionalCategoryCount?.let { extra -> "$name +$extra" } ?: name
    }
    return listOfNotNull(merchantName, categoryLabel)
        .joinToString(ENTRY_SEPARATOR)
        .ifEmpty { ENTRY_UNFILED }
}

/**
 * The category's colour, on the naming line.
 *
 * Centred inside a box exactly one naming line tall, rather than nudged down by
 * a hand-picked padding. The line box is what the swatch has to align with, and
 * it is the thing that grows with the font scale — a fixed offset would drift
 * away from the text it is aligning to the moment the user changes their type
 * size.
 *
 * The slot is reserved even for an entry filed under nothing, so names stay in
 * a scannable column instead of stepping in and out by 24dp on every unfiled
 * row.
 *
 * **One leaf node, drawn** (PERF-120HZ-PLAN §2.2). It was a `Box` one line tall
 * centring an [LfCategoryDot], itself a `Box` around a `Text`: four nodes per
 * row for a coloured circle. `wrapContentHeight` centres the 24dp square in the
 * line box exactly as `Alignment.Center` did, and the swatch or the ring is
 * drawn into it.
 */
@Composable
private fun EntrySwatch(item: LedgerListItem) {
    val namingLine = with(LocalDensity.current) {
        LfEntryRowTokens.titleStyle.lineHeight.toDp()
    }
    // The display variants, not the raw column: an itemised entry's swatch
    // comes from its largest line item's category (ADR-0018), same as the
    // title text above does.
    val categoryName = item.displayCategoryName
    val categoryColor = item.displayCategoryColorArgb
    val square = Modifier
        .height(namingLine)
        .wrapContentHeight()
        .size(LfEntryRowTokens.swatchSize)
    Spacer(
        modifier = if (categoryName != null && categoryColor != null) {
            square.lfCategoryDot(name = categoryName, colorArgb = categoryColor)
        } else {
            square.border(LfEntryRowTokens.borderWidth, LfEntryRowTokens.emptySwatchRing, CircleShape)
        },
    )
}

/** When it happened, and anything the user wrote about it. */
@Composable
private fun EntryDetail(detail: String) {
    // Truncates, and that is a deliberate asymmetry: the stamp in front of it
    // is a fixed width the layout can plan for, so the only thing that can
    // overflow is the note — free text, and the one field on this row the user
    // did not pick from a list.
    Text(
        text = detail,
        modifier = Modifier.clearAndSetSemantics {},
        style = LfEntryRowTokens.detailStyle,
        color = LfEntryRowTokens.detail,
        maxLines = NOTE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/** How much, and which way. */
@Composable
private fun EntryAmount(item: LedgerListItem) {
    val tokens = LfEntryRowTokens
    Text(
        // The sign is a glyph, not a stored value: `amount_minor` is positive
        // in both books and direction is carried by `ledger` (Law 2). Colour
        // alone would carry it, but colour alone fails §9.6 for a colour-blind
        // reader — the sign is the same redundancy the category swatch's
        // initial provides for its colour.
        text = MoneyFormat.directional(item.amount.minor, item.currency, item.ledger),
        modifier = Modifier.clearAndSetSemantics {},
        style = tokens.amountStyle,
        color = if (item.ledger == LedgerType.DEBIT) tokens.outgoing else tokens.incoming,
        textAlign = LfTextAlign.end,
    )
}

/**
 * What TalkBack says for a row.
 *
 * Spelled out rather than assembled from the visible strings, twice over:
 * `MoneyFormat` ships a spoken form because screen readers skip currency glyphs
 * (SPEC.md §9.6), and the `-`/`+` prefix is skipped just as often — so the
 * direction becomes a word here rather than a character.
 */
private fun LedgerListItem.spokenAs(title: String, detail: String): String =
    listOf(MoneyFormat.spokenDirectional(amount.minor, currency, ledger), title, detail)
        .filter { it.isNotEmpty() }
        .joinToString(ENTRY_SEPARATOR)

/** Between the facts on one line: "Big Bazaar · Groceries". */
public const val ENTRY_SEPARATOR: String = " · "

/** An entry filed under nothing. §5.1 writes these when a parse fails. */
public const val ENTRY_UNFILED: String = "Unfiled"

/** Hairline. The same weight the taxonomy cards and the nesting rail use. */
private const val NOTE_MAX_LINES = 1
