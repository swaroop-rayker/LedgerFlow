package com.ledgerflow.feature.inbox

import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.QuantityFormat
import com.ledgerflow.core.domain.ingest.ExtractedLineItem
import com.ledgerflow.core.model.LineItemKind
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.Quantity

/**
 * The extractor's lines -> the review form (SPEC.md §5.3, ADR-0022).
 *
 * ## The gap this closes
 *
 * `ReviewUiState.lines` was populated from `review_draft_json` and nothing
 * else, so it only ever held what the *user* had typed. That was correct while
 * every candidate came from an SMS or a notification — a bank message carries
 * one amount and there is nothing to itemise — and it becomes wrong the moment
 * OCR produces a candidate: the bill's lines are in `extracted_json`, the
 * screen read the other column, and an itemised receipt arrived showing zero
 * lines. Extraction would have looked like it did nothing.
 *
 * A draft still wins where one exists — `withEdits` runs after this — because a
 * correction the user typed outranks the reading it corrected.
 *
 * ## Only `ITEM` lines reach the form, and that is a decision
 *
 * [ExtractedLineItem] carries a [LineItemKind]; [ReviewLine] does not, and
 * `newLineItems` builds every line as `ITEM`. So seeding a `TAX` line here
 * would commit tax to `line_item` as a purchase, which item-grain analytics
 * (ADR-0018, `docs/DATAVIZ-PLAN.md` Family B) would then count as something the
 * user bought.
 *
 * Dropping them costs nothing the ledger needs: the entry total is
 * authoritative, and `ApproveTransactionUseCase` writes the shortfall as an
 * `UNALLOCATED` line (§5.4), so tax lands there and the parts still sum to the
 * whole. **Nothing is lost either** — every extracted line, whatever its kind,
 * stays in `extracted_json`, so a review editor that later learns about kinds
 * can show them without the extractor changing.
 *
 * ## Why a line may lose its quantity
 *
 * The editor derives the line total as `unit price × quantity` and shows it
 * read-only, precisely so the three numbers cannot disagree. A receipt's three
 * numbers *can* disagree — OCR reads `2 × 45.00 = 90.50` on a faded slip, or
 * reads a total and a quantity with no unit price at all. When they do, the
 * **total is kept** and the quantity collapses to one, because the total is the
 * figure §5.3's reconciliation weighs against the bill and Law 3's spirit is
 * that money is right or wrong rather than close. A quantity is a description
 * of the line; the total is the line.
 *
 * The quantity therefore survives exactly when it is arithmetically consistent,
 * which is the common case on a printed bill and never a rounded one.
 */
internal fun List<ExtractedLineItem>.toReviewLines(
    currency: String,
    nextKey: () -> String,
): List<ReviewLine> = asSequence()
    .filter { it.kind == LineItemKind.ITEM }
    .map { line -> line.toReviewLine(nextKey(), currency) }
    .toList()

private fun ExtractedLineItem.toReviewLine(key: String, currency: String): ReviewLine {
    val quantity = quantityMilli?.takeIf { it > 0L } ?: Quantity.SCALE
    val unitFromQuantity = unitPrice?.takeIf { candidate ->
        // Consistent only if the editor's own arithmetic reproduces the printed
        // total. `total == null` means the extractor read a unit price and no
        // total, which is consistent by construction -- there is nothing to
        // contradict.
        total == null || candidate * Quantity(quantity) == total
    }

    val (unitMinor, quantityMilliOrOne) = when {
        unitFromQuantity != null -> unitFromQuantity.minor to quantity
        // No usable unit price, or one that does not multiply out. Keep the
        // money, drop the description.
        else -> (total ?: Money.ZERO).minor to Quantity.SCALE
    }

    return ReviewLine(
        key = key,
        name = name,
        unitPriceText = if (unitMinor == 0L) "" else MoneyFormat.plain(unitMinor, currency),
        unitPriceMinor = unitMinor,
        // Blank reads as one, and "1" in a field nobody typed in is noise on
        // the one line a collapsed row has. Printed only when it is not one.
        quantityText = if (quantityMilliOrOne == Quantity.SCALE) {
            ""
        } else {
            QuantityFormat.plain(quantityMilliOrOne)
        },
        quantityMilli = quantityMilliOrOne,
    )
}
