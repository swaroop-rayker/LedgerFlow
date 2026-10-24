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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.designsystem.component.LfAdaptiveRow
import com.ledgerflow.core.designsystem.component.LfCategoryInk
import com.ledgerflow.core.designsystem.component.LfIconButton
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.designsystem.format.TimeStamp
import com.ledgerflow.core.designsystem.icon.LfIcons
import com.ledgerflow.core.designsystem.theme.LfTextAlign
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.screenshot.inkDifferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **`LfEntryRow`'s one-node layout draws the same pixels as the nesting it
 * replaced** (PERF-120HZ-PLAN §2.1, §2.2).
 *
 * The row was a Row of a Row of a swatch and a Column, the Column holding the
 * title over an [LfAdaptiveRow]. It is now one `Layout` with the geometry
 * written out, and the side-by-side-or-stacked decision no longer asks the
 * detail for an intrinsic width. Both are claims a golden can only spot-check:
 * the stacking decision depends on the amount, the note, the width and the
 * font scale together, and the goldens hold a handful of those combinations.
 *
 * So the old composition is kept here, verbatim apart from its name, and both
 * are rendered for every combination below and compared to within
 * anti-aliasing ([inkDifferences]). That includes the cases on either side of
 * the stacking threshold (a lakh beside a long note at 280 dp and 2.0) and RTL,
 * where the old [LfAdaptiveRow] did *not* mirror its two children and the new
 * layout must not start to. The reference keeps the old composed swatch too,
 * so the drawn one is held to it here as well. The delete control is today's
 * `LfIconButton` on both sides; `LfIconButtonTest` holds that one to Material's.
 *
 * NATIVE is load-bearing: without it Robolectric measures text zero wide, every
 * row comes out the same, and this passes on any layout at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1200dp")
class LfEntryRowLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Case(
        val item: LedgerListItem,
        val withDate: Boolean,
        val withDelete: Boolean,
        val widthDp: Int,
        val fontScale: Float,
        val direction: LayoutDirection,
    )

    @Test
    fun flatLayout_everyCombination_matchesTheNestedRowPixelForPixel() {
        var case by mutableStateOf<Case?>(null)
        rule.setContent {
            val current = case ?: return@setContent
            // A fresh composition per case. Reusing the nodes across a change of
            // layout direction left one row's detail drawn for the previous
            // direction ("11:02 PM" against "PM 11:02"), a difference between
            // the two rows that neither shows when composed from scratch.
            key(current) { BothRows(current) }
        }

        for (c in CASES) {
            case = c
            rule.waitForIdle()
            val nested = rule.onNodeWithTag(NESTED).captureToImage().toPixelMap()
            val flat = rule.onNodeWithTag(FLAT).captureToImage().toPixelMap()
            assertWithMessage("size, %s", c).that(flat.width to flat.height).isEqualTo(nested.width to nested.height)
            assertWithMessage("pixels differing of %sx%s, %s", nested.width, nested.height, c)
                .that(inkDifferences(nested, flat)).isEmpty()
        }
        assertWithMessage("combinations compared").that(CASES.size).isEqualTo(ITEMS.size * 16)
    }

    @Composable
    private fun BothRows(case: Case) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, case.fontScale),
            LocalLayoutDirection provides case.direction,
        ) {
            LfTheme {
                val deleteIcon = if (case.withDelete) rememberVectorPainter(LfIcons.Delete) else null
                Column(modifier = Modifier.width(case.widthDp.dp)) {
                    Box(Modifier.testTag(NESTED)) { NestedEntryRow(case.item, case.withDate, deleteIcon) }
                    Box(Modifier.testTag(FLAT)) { LfEntryRow(case.item, case.withDate, deleteIcon = deleteIcon) }
                }
            }
        }
    }

    private companion object {
        const val NESTED = "nested"
        const val FLAT = "flat"

        // 18 Aug 2026, 17:32 UTC.
        const val AT = 1_787_074_320_000L

        val BASE = LedgerListItem(
            id = "today",
            ledger = LedgerType.DEBIT,
            amount = Money(6_900),
            currency = "INR",
            occurredAt = AT,
            localDate = 20_683,
            categoryName = "Groceries",
            categoryColorArgb = 0xFF2E7D32.toInt(),
            merchantName = "Big Bazaar",
            note = null,
        )

        val ITEMS = listOf(
            BASE,
            BASE.copy(id = "note", note = "Weekly shop"),
            // The stacking threshold: a lakh beside a note, which sits
            // side-by-side at 360 dp and 1.0 and stacks at 280 dp or 2.0.
            BASE.copy(
                id = "lakh",
                amount = Money(15_400_000),
                note = "Rent for August, paid late after the bank holiday",
            ),
            BASE.copy(
                id = "unfiled",
                amount = Money(25_000),
                merchantName = null,
                categoryName = null,
                categoryColorArgb = null,
            ),
            BASE.copy(
                id = "itemised",
                amount = Money(1_875_200),
                merchantName = "Reliance Fresh",
                categoryName = null,
                categoryColorArgb = null,
                lineItemCategoryName = "Electronics",
                lineItemCategoryColorArgb = 0xFF6A1B9A.toInt(),
                lineItemCategoryCount = 3,
            ),
            BASE.copy(
                id = "credit",
                ledger = LedgerType.CREDIT,
                amount = Money(4_500_000),
                merchantName = "Acme Payroll",
                categoryName = "Salary",
            ),
            // A name long enough to wrap onto a second line.
            BASE.copy(
                id = "long",
                amount = Money(12_300),
                merchantName = "Bharat Petroleum Corporation Highway Services",
                categoryName = "Fuel and travel",
            ),
        )

        val CASES: List<Case> = ITEMS.flatMap { item ->
            listOf(true, false).flatMap { withDelete ->
                listOf(360, 280).flatMap { widthDp ->
                    listOf(1f, 2f).flatMap { fontScale ->
                        LayoutDirection.entries.map { direction ->
                            Case(item, withDate = item.id != "today", withDelete, widthDp, fontScale, direction)
                        }
                    }
                }
            }
        }
    }
}

/**
 * `LfEntryRow` as it was before PERF-120HZ-PLAN §2.1, kept as the reference the
 * flat layout is held to. Do not "tidy" this into the new shape: the point is
 * that it is the old one. Its swatch is the old composed dot too (§2.2), so the
 * comparison covers the drawn one as well.
 */
// Long because it is the old row verbatim; splitting it would stop it being that.
@Suppress("LongMethod")
@Composable
private fun NestedEntryRow(item: LedgerListItem, withDate: Boolean, deleteIcon: Painter?) {
    val spacing = LfTheme.spacing
    val colors = LfTheme.colors
    val shape = RoundedCornerShape(spacing.cornerLarge)
    val title = item.entryTitle()
    val stamp = TimeStamp.of(item.occurredAt, withDate)
    val note = item.note?.takeIf { it.isNotBlank() }
    val detail = listOfNotNull(stamp, note).joinToString(ENTRY_SEPARATOR)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.outline, shape)
            .padding(
                start = spacing.md,
                end = if (deleteIcon != null) spacing.xs else spacing.md,
                top = spacing.sm,
                bottom = spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            val namingLine = with(LocalDensity.current) { LfTheme.typography.bodyL.lineHeight.toDp() }
            Box(modifier = Modifier.height(namingLine), contentAlignment = Alignment.Center) {
                val categoryName = item.displayCategoryName
                val categoryColor = item.displayCategoryColorArgb
                if (categoryName != null && categoryColor != null) {
                    NestedCategoryDot(name = categoryName, colorArgb = categoryColor)
                } else {
                    Box(
                        modifier = Modifier
                            .size(LfTheme.spacing.lg)
                            .border(1.dp, LfTheme.colors.outline, CircleShape),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = LfTheme.typography.bodyL, color = colors.textPrimary)
                LfAdaptiveRow(
                    leading = {
                        Text(
                            text = detail,
                            style = LfTheme.typography.label,
                            color = LfTheme.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailing = {
                        Text(
                            text = MoneyFormat.directional(item.amount.minor, item.currency, item.ledger),
                            style = LfTheme.typography.amountM,
                            color = if (item.ledger == LedgerType.DEBIT) colors.debit else colors.credit,
                            // The one change to the old composition: the screen's
                            // end, as the real row aligns it since BUG61, where
                            // `TextAlign.End` now means the paragraph's end.
                            textAlign = LfTextAlign.end,
                        )
                    },
                )
            }
        }
        if (deleteIcon != null) {
            LfIconButton(
                icon = deleteIcon,
                modifier = Modifier.align(Alignment.CenterVertically),
                contentDescription = "Delete " + title,
                onClick = {},
            )
        }
    }
}

/** `LfCategoryDot` as it was before PERF-120HZ-PLAN §2.2: a `Box` around a `Text`. */
@Composable
private fun NestedCategoryDot(name: String, colorArgb: Int) {
    val diameter = LfTheme.spacing.lg
    val initialSize = with(LocalDensity.current) { (diameter * 0.5f).toSp() }
    Box(
        modifier = Modifier.size(diameter).background(Color(colorArgb), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercase().orEmpty(),
            style = LfTheme.typography.label.copy(fontSize = initialSize, lineHeight = TextUnit.Unspecified),
            color = LfCategoryInk,
        )
    }
}
