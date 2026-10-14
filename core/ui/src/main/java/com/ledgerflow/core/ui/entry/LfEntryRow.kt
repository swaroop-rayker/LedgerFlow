package com.ledgerflow.core.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType

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
 * are the only two things on the other one. [LfAdaptiveRow] still measures
 * them, so at large font scales they stack rather than clip — but at ordinary
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
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    val shape = RoundedCornerShape(spacing.cornerLarge)

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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, shape)
            .border(HAIRLINE.dp, colors.outline, shape)
            // Tighter on the trailing edge: the delete control is a 48dp touch
            // target around a 16dp glyph, so it carries ~16dp of its own inset
            // and a full `md` on top would leave the icon looking adrift.
            .padding(
                start = spacing.md,
                end = if (deleteIcon != null) spacing.xs else spacing.md,
                top = spacing.sm,
                bottom = spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The merged announcement covers the *content*, not the whole row.
        // `clearAndSetSemantics` clears its descendants, so leaving it on the
        // outer Row would have swallowed the delete button's own label and left
        // a screen-reader user with a control they cannot identify or reach.
        Row(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = item.spokenAs(title, detail) },
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            // `Top`, not `CenterVertically`. The swatch belongs to the name, and
            // centring it against a two-line row parked it between the two
            // lines -- reading as though it labelled the timestamp as much as
            // the merchant. Reported on device.
            verticalAlignment = Alignment.Top,
        ) {
            EntrySwatch(item)

            Column(modifier = Modifier.weight(1f)) {
                // No `maxLines` and no ellipsis: merchant and category are the
                // row's identity, and clipping them is the BUG9 failure one
                // level up from a button label. They wrap instead, which costs
                // a line on the rare long pairing and never hides which entry
                // this is. It has the full width here, so that is rare.
                Text(
                    text = title,
                    style = LfTheme.typography.bodyL,
                    color = colors.textPrimary,
                )
                LfAdaptiveRow(
                    leading = { EntryDetail(detail = detail) },
                    trailing = { EntryAmount(item = item) },
                )
            }
        }

        // An icon, not an `Inline` text action in an `LfActionRow`. The taxonomy
        // cards use the latter, but they carry three actions over a list of a
        // dozen items; this carries one over a list without end, and a text
        // button would add a whole line to every row on the screen whose entire
        // job is being scanned. `LfIconButton` is already a 48dp target and the
        // rows are taller than that, so it costs no height at all.
        if (deleteIcon != null) {
            LfIconButton(
                icon = deleteIcon,
                // Stays centred on the row: unlike the swatch it belongs to the
                // entry as a whole rather than to the naming line.
                modifier = Modifier.align(Alignment.CenterVertically),
                contentDescription = "Delete " + title,
                onClick = onDelete,
            )
        }
    }
}

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
 */
@Composable
private fun EntrySwatch(item: LedgerListItem) {
    val namingLine = with(LocalDensity.current) {
        LfTheme.typography.bodyL.lineHeight.toDp()
    }
    Box(
        modifier = Modifier.height(namingLine),
        contentAlignment = Alignment.Center,
    ) {
        // The display variants, not the raw column: an itemised entry's swatch
        // comes from its largest line item's category (ADR-0018), same as the
        // title text above does.
        val categoryName = item.displayCategoryName
        val categoryColor = item.displayCategoryColorArgb
        if (categoryName != null && categoryColor != null) {
            LfCategoryDot(name = categoryName, colorArgb = categoryColor)
        } else {
            Box(
                modifier = Modifier
                    .size(LfTheme.spacing.lg)
                    .border(HAIRLINE.dp, LfTheme.colors.outline, CircleShape),
            )
        }
    }
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
        style = LfTheme.typography.label,
        color = LfTheme.colors.textTertiary,
        maxLines = NOTE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
    )
}

/** How much, and which way. */
@Composable
private fun EntryAmount(item: LedgerListItem) {
    val colors = LfTheme.colors
    Text(
        // The sign is a glyph, not a stored value: `amount_minor` is positive
        // in both books and direction is carried by `ledger` (Law 2). Colour
        // alone would carry it, but colour alone fails §9.6 for a colour-blind
        // reader — the sign is the same redundancy the category swatch's
        // initial provides for its colour.
        text = MoneyFormat.directional(item.amount.minor, item.currency, item.ledger),
        style = LfTheme.typography.amountM,
        color = if (item.ledger == LedgerType.DEBIT) colors.debit else colors.credit,
        textAlign = TextAlign.End,
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
private const val HAIRLINE = 1
private const val NOTE_MAX_LINES = 1
