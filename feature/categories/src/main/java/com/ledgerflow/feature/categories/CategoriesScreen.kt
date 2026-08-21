package com.ledgerflow.feature.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfActionAlignment
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Category, merchant and payment-method management (SPEC.md §5.5).
 *
 * Stateless: state in, one event lambda out (CLAUDE.md §5).
 */
@Composable
public fun CategoriesScreen(
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.dialog?.let { TaxonomyDialogHost(it, state, onEvent) }

    LfScaffold(
        modifier = modifier,
        bottomBar = { AddBar(state, onEvent) },
    ) { padding ->
        // `sm` between the header blocks, not `md`. This screen's header is
        // three stacked bands -- title, section control, ledger control -- above
        // a list that is the only thing here anyone scrolls, so every step of
        // the gap scale between them is charged to the list twice over.
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LfScreenTitle(title = "Organise", modifier = Modifier.weight(1f))
                // A full-screen destination reached from More, so it carries its
                // own way out rather than relying on the gesture alone.
                LfButton(
                    text = "Done",
                    style = LfButtonStyle.Text,
                    onClick = onBack,
                    modifier = Modifier.padding(end = LfTheme.spacing.md),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = LfTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.xs),
            ) {
                LfSegmentedControl(
                    options = TaxonomySection.entries.map { it.label },
                    selectedIndex = state.section.ordinal,
                    onSelect = {
                        onEvent(CategoriesEvent.SectionSelected(TaxonomySection.entries[it]))
                    },
                )
                if (state.section == TaxonomySection.Categories) {
                    // Two disjoint trees, not a filter (Law 2).
                    LfSegmentedControl(
                        options = listOf("Expenses", "Income"),
                        selectedIndex = state.ledger.ordinal,
                        onSelect = {
                            onEvent(CategoriesEvent.LedgerSelected(LedgerType.entries[it]))
                        },
                    )
                }
                state.message?.let { MessageBanner(it, onEvent) }
            }

            when (state.section) {
                TaxonomySection.Categories -> CategoryList(state, onEvent)
                TaxonomySection.Merchants -> MerchantList(state, onEvent)
                TaxonomySection.PaymentMethods -> PaymentMethodList(state, onEvent)
            }
        }
    }
}

@Composable
private fun AddBar(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    val label = when (state.section) {
        TaxonomySection.Categories -> "Add category"
        TaxonomySection.Merchants -> "Add merchant"
        TaxonomySection.PaymentMethods -> "Add payment method"
    }
    // Tighter vertically than horizontally, deliberately. A uniform `lg` inset
    // put 24dp above the button and 24dp below it, and `LfScaffold` already
    // pads the bar for the navigation bar underneath -- so the bottom 24dp was
    // stacked on top of an inset that exists for the same purpose. Both edges
    // came out of the one thing on this screen that has to scroll: the list.
    //
    // `xs` below is the floor, not a taste call. Measured on device: the
    // button's bottom edge, this padding, and the 48dp navigation-bar inset add
    // up exactly to the top of the navigation bar, so anything reclaimed past
    // this puts the button under the system bar (BUG5). The button cannot be
    // pushed down further than this; more list height has to come from the
    // header above it instead.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LfTheme.spacing.lg,
                end = LfTheme.spacing.lg,
                top = LfTheme.spacing.xs,
                bottom = LfTheme.spacing.xs,
            ),
    ) {
        LfButton(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            loading = state.isWorking,
            onClick = {
                onEvent(
                    when (state.section) {
                        TaxonomySection.Categories -> CategoriesEvent.AddCategory(null, null)
                        TaxonomySection.Merchants -> CategoriesEvent.AddMerchant
                        TaxonomySection.PaymentMethods -> CategoriesEvent.AddPaymentMethod
                    },
                )
            },
        )
    }
}

@Composable
private fun MessageBanner(message: String, onEvent: (CategoriesEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = LfTheme.typography.bodyM,
            color = LfTheme.colors.warn,
            modifier = Modifier.weight(1f),
        )
        LfButton(
            text = "OK",
            onClick = { onEvent(CategoriesEvent.MessageDismissed) },
            style = LfButtonStyle.Text,
        )
    }
}

@Composable
private fun CategoryList(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    // The empty state is about the *live* tree, and the hidden section still has
    // to be reachable underneath it -- a user who hid their last category is
    // exactly the one who needs the way back, and telling them to "add one to
    // get started" while the one they want sits hidden below is the version of
    // this screen that caused the complaint.
    if (state.tree.isEmpty() && state.hidden.isEmpty()) {
        LfEmptyState(
            title = "No categories yet",
            body = "Categories group your spending. Add one to get started.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = LfTheme.spacing.xxl,
        ),
    ) {
        state.tree.forEach { branch ->
            // Keyed per branch rather than per row: the connector rail has to
            // measure against the height of the children it spans, so a branch
            // is one item. Stable keys and a contentType still apply (CLAUDE.md
            // §5) -- without them every edit recomposes the whole list.
            item(key = branch.parent.id, contentType = "branch") {
                CategoryBranch(branch, onEvent)
            }
        }
        hiddenSection(state, onEvent)
    }
}

/**
 * One category and its subcategories, joined by a connector rail.
 *
 * **Compact by design.** The first version gave every row a card with 16dp
 * padding, a divider, and a row of 112dp-minimum outlined buttons — about
 * 140dp per category, so three categories filled a phone screen and the tree
 * structure was invisible under the chrome. The actions now sit on the name's
 * own line as text buttons, which drops a row to roughly its touch-target
 * height and lets the indentation do the work of showing what belongs to what.
 *
 * The rail is a hairline in `outline` with a short stub into each child, so the
 * hierarchy reads at a glance rather than being inferred from indentation
 * alone. Colours are unchanged — this is layout and weight, not palette.
 */
@Composable
private fun CategoryBranch(branch: CategoryTree, onEvent: (CategoriesEvent) -> Unit) {
    val spacing = LfTheme.spacing

    Column(modifier = Modifier.padding(bottom = spacing.sm)) {
        CategoryRow(branch.parent, isChild = false, onEvent = onEvent)

        if (branch.children.isEmpty()) return@Column

        // IntrinsicSize.Min so the rail can fill the exact height of the
        // children beside it; without it `fillMaxHeight` has nothing to
        // measure against and the line collapses.
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .padding(start = spacing.md)
                    .width(RAIL_THICKNESS.dp)
                    .fillMaxHeight()
                    .background(LfTheme.colors.outline),
            )
            Column(modifier = Modifier.weight(1f)) {
                branch.children.forEach { child ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(spacing.md)
                                .height(RAIL_THICKNESS.dp)
                                .background(LfTheme.colors.outline),
                        )
                        CategoryRow(child, isChild = true, onEvent = onEvent)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    isChild: Boolean,
    onEvent: (CategoriesEvent) -> Unit,
) {
    val spacing = LfTheme.spacing

    TaxonomyCard(modifier = Modifier.padding(vertical = spacing.xs)) {
        // Name on its own line, actions beneath — the shape in the sketch.
        //
        // The first attempt put the name *inside* the action `FlowRow`, which
        // made it compete with the buttons for line space: "Food & Dining" plus
        // three labels does not fit, so the row wrapped in the middle of the
        // actions and the card ended up taller than the one it replaced. The
        // name is a heading, not a control; it gets its own line.
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LfCategoryDot(name = category.name, colorArgb = category.colorArgb)
            Text(
                text = category.name,
                style = if (isChild) LfTheme.typography.bodyM else LfTheme.typography.bodyL,
                color = LfTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        // Still a FlowRow (BUG9): at font scale 2.0 three labels do not fit one
        // line, and whole controls wrap rather than words breaking.
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(
                text = "Rename",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(CategoriesEvent.RenameCategory(category.id, category.name)) },
            )
            if (!isChild) {
                LfButton(
                    text = "Add sub",
                    style = LfButtonStyle.Inline,
                    onClick = { onEvent(CategoriesEvent.AddCategory(category.id, category.name)) },
                )
            }
            LfButton(
                // "Hide", like the other two sections. The three said Delete,
                // Hide and Remove for one operation, and only "Hide" was ever
                // accurate: all three set `deleted_at` and leave the row
                // labelling past entries. With ADR-0016 there is a way back, so
                // the honest word is also now the reassuring one -- and "Erase"
                // is free to mean the thing that really does destroy.
                text = "Hide",
                style = LfButtonStyle.Inline,
                onClick = {
                    onEvent(CategoriesEvent.DeleteCategory(category.id, category.name, isChild))
                },
            )
        }
    }
}

/** Hairline. Thin enough to read as a connector rather than as a border. */
private const val RAIL_THICKNESS = 1

/**
 * The one card shape this screen uses, for all three sections.
 *
 * Categories got this treatment first and merchants and payment methods kept
 * `LfCard` with `Outlined` buttons, which made a single screen read as two
 * designs: a tall card with a divider and pill buttons next to a compact
 * bordered row with text actions. The heavier shape also spent roughly twice
 * the vertical space per item on lists whose whole job is letting you scan what
 * you have -- three merchants filled the screen.
 *
 * Hairline border rather than elevation: on a surface this small elevation
 * reads as a shadow smear instead of depth, and the border is what keeps the
 * category tree's nesting rail legible against the card edge.
 */
@Composable
private fun TaxonomyCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = LfTheme.spacing
    val shape = RoundedCornerShape(spacing.cornerSmall)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LfTheme.colors.surfaceRaised, shape)
            .border(1.dp, LfTheme.colors.outline, shape)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        content = content,
    )
}

@Composable
private fun MerchantList(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    val merchants = state.merchants
    if (merchants.isEmpty() && state.hidden.isEmpty()) {
        LfEmptyState(
            title = "No merchants yet",
            body = "Merchants are created as you record spending, and can be " +
                "renamed or merged here when the same shop shows up twice.",
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = LfTheme.spacing.xxl,
        ),
    ) {
        items(merchants.size, key = { merchants[it].id }, contentType = { "merchant" }) { index ->
            MerchantCard(merchants[index], onEvent)
        }
        hiddenSection(state, onEvent)
    }
}

@Composable
private fun MerchantCard(merchant: Merchant, onEvent: (CategoriesEvent) -> Unit) {
    val name = merchant.canonicalName
    TaxonomyCard {
        // Name on its own line, actions beneath -- a category row's shape. The
        // three labels share one line only because they are `Inline`: as
        // `Outlined` pills, Rename + Merge + Hide overflowed the card width and
        // the third dropped to a row of its own.
        Text(
            text = name,
            style = LfTheme.typography.bodyL,
            color = LfTheme.colors.textPrimary,
        )
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(
                text = "Rename",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(CategoriesEvent.RenameMerchant(merchant.id, name)) },
            )
            LfButton(
                text = "Merge",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(CategoriesEvent.StartMergeMerchant(merchant.id, name)) },
            )
            LfButton(
                text = "Hide",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(CategoriesEvent.DeleteMerchant(merchant.id, name)) },
            )
        }
    }
}

@Composable
private fun PaymentMethodList(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    val methods = state.paymentMethods
    if (methods.isEmpty() && state.hidden.isEmpty()) {
        LfEmptyState(title = "No payment methods", body = "Add the cards and accounts you use.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = LfTheme.spacing.xxl,
        ),
    ) {
        items(methods.size, key = { methods[it].id }, contentType = { "method" }) { index ->
            PaymentMethodCard(methods[index], onEvent)
        }
        hiddenSection(state, onEvent)
    }
}

@Composable
private fun PaymentMethodCard(method: PaymentMethod, onEvent: (CategoriesEvent) -> Unit) {
    TaxonomyCard {
        Text(
            text = buildString {
                append(method.label)
                method.last4?.let { append(" ····$it") }
                if (method.isDefault) append("  · default")
            },
            style = LfTheme.typography.bodyL,
            color = LfTheme.colors.textPrimary,
        )
        // The type keeps its own line rather than joining the one above: label,
        // last-4, default and type is four facts abreast with no hierarchy, and
        // the label is the one being scanned for.
        Text(
            text = method.type.name.lowercase().replace('_', ' '),
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
        )
        LfActionRow(alignment = LfActionAlignment.End) {
            if (!method.isDefault) {
                LfButton(
                    text = "Make default",
                    style = LfButtonStyle.Inline,
                    onClick = { onEvent(CategoriesEvent.SetDefaultPaymentMethod(method.id)) },
                )
            }
            LfButton(
                text = "Hide",
                style = LfButtonStyle.Inline,
                onClick = {
                    onEvent(CategoriesEvent.DeletePaymentMethod(method.id, method.label))
                },
            )
        }
    }
}

/**
 * What this tab has hidden, at the foot of what it has (ADR-0016).
 *
 * **A section inside the list, not a screen of its own.** The bin earned a
 * destination because a deleted entry is looked for without knowing which book
 * it was in; a hidden merchant is looked for by someone already standing on the
 * Merchants tab, and sending them to Settings to find it would be further from
 * the thing than the button that hid it.
 *
 * **Collapsed until asked for**, and the header carries the count so the tap is
 * an informed one. It is a `LazyListScope` extension rather than a composable so
 * the rows stay real list items -- keyed, typed, and recycled like every other
 * row -- instead of one enormous item holding a `Column` of them.
 */
private fun LazyListScope.hiddenSection(
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    if (state.hidden.isEmpty()) return

    item(key = HIDDEN_HEADER_KEY, contentType = "hiddenHeader") {
        HiddenHeader(state.hidden.size, state.hiddenExpanded, onEvent)
    }
    if (!state.hiddenExpanded) return

    val hidden = state.hidden
    items(
        hidden.size,
        key = { "hidden-" + hidden[it].id },
        contentType = { "hidden" },
    ) { index ->
        HiddenCard(hidden[index], onEvent)
    }
}

/**
 * The disclosure row.
 *
 * A text row rather than a button: it is a heading that happens to toggle, and
 * a full-width control here would read as the screen's primary action on a
 * screen whose primary action is the pinned Add bar. The glyph is the
 * affordance, and it is on the leading edge so it mirrors in RTL with the text
 * rather than drifting away from it.
 */
@Composable
private fun HiddenHeader(count: Int, expanded: Boolean, onEvent: (CategoriesEvent) -> Unit) {
    val spacing = LfTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(CategoriesEvent.HiddenToggled) }
            .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "\u25be" else "\u25b8",
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
        )
        Text(
            text = "Hidden ($count)",
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
            maxLines = 1,
        )
    }
}

/**
 * One hidden row: what it was, when it went, and the two ways out.
 *
 * The same [TaxonomyCard] the live rows use -- one shape per screen. Nothing
 * else marks it as hidden, because it does not need to: it is under a header
 * that says so, and giving hidden rows their own treatment would put two card
 * designs on one screen to restate a fact the section already carries.
 *
 * `Restore` sits before `Erase` in reading order, so the recoverable action is
 * the one a thumb reaches first and the destructive one is furthest from the
 * gesture that opened the section.
 */
@Composable
private fun HiddenCard(item: HiddenTaxonomy, onEvent: (CategoriesEvent) -> Unit) {
    TaxonomyCard {
        Text(
            text = item.name,
            style = LfTheme.typography.bodyL,
            color = LfTheme.colors.textPrimary,
        )
        // One line for both facts. They are short, and stacking "Hidden 19 Aug"
        // above "with 2 subcategories" would make a hidden row taller than the
        // live row it is a copy of.
        Text(
            text = listOfNotNull("Hidden ${hiddenStamp(item.hiddenAt)}", item.detail)
                .joinToString(" · "),
            style = LfTheme.typography.label,
            color = LfTheme.colors.textTertiary,
        )
        LfActionRow(alignment = LfActionAlignment.End) {
            LfButton(
                text = "Restore",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(CategoriesEvent.RestoreHidden(item.id, item.name)) },
            )
            LfButton(
                text = "Erase",
                style = LfButtonStyle.Inline,
                onClick = { onEvent(CategoriesEvent.EraseHidden(item.id, item.name)) },
            )
        }
    }
}

/**
 * The day something was hidden, in the device's locale.
 *
 * Date only. The bin prints a time because it shows a *transaction*, which
 * happened at a moment; hiding a category is housekeeping, and the hour it
 * happened at tells the user nothing they would use to choose between two rows.
 *
 * Written here rather than shared with the Ledger's `occurredStamp`, which is
 * `internal` to `:feature:ledger` -- features never depend on features
 * (CLAUDE.md §3). Six lines duplicated is the cheaper side of that rule than a
 * formatter promoted to `:core:ui` for two callers that format different things.
 */
@Composable
private fun hiddenStamp(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return remember(millis, locale) {
        DateTimeFormatter.ofPattern(HIDDEN_DATE_PATTERN, locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
    }
}

private const val HIDDEN_DATE_PATTERN = "d MMM"
private const val HIDDEN_HEADER_KEY = "hidden-header"

/** A tappable row inside a picker dialog. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = if (selected) "● $label" else "○ $label",
        style = LfTheme.typography.bodyM,
        color = if (selected) LfTheme.colors.accent else LfTheme.colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = LfTheme.spacing.sm),
    )
}

private val TaxonomySection.label: String
    get() = when (this) {
        TaxonomySection.Categories -> "Categories"
        TaxonomySection.Merchants -> "Merchants"
        TaxonomySection.PaymentMethods -> "Payment"
    }

// ── Previews (CLAUDE.md §5) ───────────────────────────────────────────────

private fun sampleCategory(id: String, name: String, parent: String? = null, system: Boolean = false) =
    Category(
        id = id, parentId = parent, ledger = LedgerType.DEBIT, name = name,
        icon = "", colorArgb = 0xFF3E6AD6.toInt(), sortOrder = 0, isSystem = system,
    )

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun CategoriesPreview() {
    LfTheme {
        CategoriesScreen(
            state = CategoriesUiState(
                tree = listOf(
                    CategoryTree(
                        parent = sampleCategory("1", "Food & Dining", system = true),
                        children = listOf(sampleCategory("2", "Groceries", parent = "1", system = true)),
                    ),
                    CategoryTree(parent = sampleCategory("3", "Transport"), children = emptyList()),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

/**
 * The hidden section, open (ADR-0016).
 *
 * Its own preview rather than a flag on the others, because the rows it adds are
 * the ones most likely to break at scale: a name, a stamp and a detail on one
 * line, above two actions. `@PreviewFontScale` is the point of it -- at 2.0 the
 * `LfActionRow` has to wrap Restore and Erase as whole controls rather than
 * breaking a label (BUG9), and the detail line has to wrap rather than clip.
 */
@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun HiddenMerchantsPreview() {
    LfTheme {
        CategoriesScreen(
            state = CategoriesUiState(
                section = TaxonomySection.Merchants,
                merchants = listOf(
                    Merchant("1", "Big Bazaar", "bigbazaar", null, null),
                ),
                hidden = listOf(
                    HiddenTaxonomy("2", "Amazon", hiddenAt = HIDDEN_PREVIEW_AT),
                    HiddenTaxonomy(
                        "3",
                        "Reliance Fresh 1182",
                        hiddenAt = HIDDEN_PREVIEW_AT,
                    ),
                ),
                hiddenExpanded = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

/** A fixed instant so the preview does not re-render differently each day. */
private const val HIDDEN_PREVIEW_AT = 1_755_000_000_000L

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun PaymentMethodsPreview() {
    LfTheme {
        CategoriesScreen(
            state = CategoriesUiState(
                section = TaxonomySection.PaymentMethods,
                paymentMethods = listOf(
                    PaymentMethod(
                        id = "1", type = com.ledgerflow.core.model.PaymentMethodType.CASH,
                        label = "Cash", issuer = null, last4 = null, colorArgb = null,
                        isDefault = true,
                    ),
                    PaymentMethod(
                        id = "2", type = com.ledgerflow.core.model.PaymentMethodType.CREDIT_CARD,
                        label = "HDFC Card", issuer = "HDFC", last4 = "4821",
                        colorArgb = null, isDefault = false,
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

