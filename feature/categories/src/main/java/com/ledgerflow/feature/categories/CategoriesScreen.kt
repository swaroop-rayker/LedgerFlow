package com.ledgerflow.feature.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.Dp
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfGroupPosition
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.component.lfGroupSlice
import com.ledgerflow.core.designsystem.component.shape
import com.ledgerflow.core.designsystem.format.DisplayDates
import com.ledgerflow.core.designsystem.format.DisplayLocale
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfPanel
import com.ledgerflow.core.designsystem.theme.LfLayout
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

/**
 * Category, merchant and payment-method management (SPEC.md §5.5).
 *
 * Stateless: state in, one event lambda out (CLAUDE.md §5).
 *
 * **Rows in panels, actions in a sheet** (owner's option A, 2026-10-31). Every
 * row is one line of name, in a panel with hairlines, and tapping it opens its
 * actions ([TaxonomyDialog.RowActions]). The rows used to be a card each with
 * Rename / Add sub / Hide beneath the name, and subcategories nested cards on a
 * rail: the golden's three-category tree took 392 px at 1x, and takes 197 now.
 */
@Composable
public fun CategoriesScreen(
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.dialog?.let { TaxonomyDialogHost(it, state, onEvent) }

    // A pinned header of three stacked bands above a list that is the only
    // thing here anyone scrolls, so every step of the gap scale between them is
    // charged to the list: the switches stay `xs` apart, as they were.
    LfDetailPage(
        title = "Organise",
        modifier = modifier,
        // A full-screen destination reached from Settings, so it carries its
        // own way out rather than relying on the gesture alone.
        // The back arrow beside the title (step F5, owner 2026-10-30), as on
        // every page reached from Profile.
        onBack = onBack,
        filters = {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.space.inlineGap)) {
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
            }
        },
        notices = state.message?.let { message -> { MessageBanner(message, onEvent) } },
        statusContent = if (state.sectionIsEmpty()) {
            { EmptySection(state.section) }
        } else {
            null
        },
        actionBar = { AddBar(state, onEvent) },
        // No gap between items: a merchant list is one panel drawn a slice per
        // row, and slices only meet when nothing sits between them. The
        // category panels and the hidden header space themselves.
        spacing = LfPageSpacing.None,
        pinHeader = true,
    ) {
        when (state.section) {
            TaxonomySection.Categories -> categoryItems(state, onEvent)
            TaxonomySection.Merchants -> merchantItems(state, onEvent)
            TaxonomySection.PaymentMethods -> paymentMethodItems(state, onEvent)
        }
    }
}

/**
 * Empty means nothing live *and* nothing hidden: the hidden section has to stay
 * reachable underneath, since a user who hid their last category is exactly
 * the one who needs the way back.
 */
private fun CategoriesUiState.sectionIsEmpty(): Boolean = hidden.isEmpty() && when (section) {
    TaxonomySection.Categories -> tree.isEmpty()
    TaxonomySection.Merchants -> merchants.isEmpty()
    TaxonomySection.PaymentMethods -> paymentMethods.isEmpty()
}

@Composable
private fun EmptySection(section: TaxonomySection) {
    when (section) {
        TaxonomySection.Categories -> LfEmptyState(
            title = "No categories yet",
            body = "Categories group your spending. Add one to get started.",
        )
        TaxonomySection.Merchants -> LfEmptyState(
            title = "No merchants yet",
            body = "Merchants are created as you record spending, and can be " +
                "renamed or merged here when the same shop shows up twice.",
        )
        TaxonomySection.PaymentMethods ->
            LfEmptyState(title = "No payment methods", body = "Add the cards and accounts you use.")
    }
}

@Composable
private fun AddBar(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    val label = when (state.section) {
        TaxonomySection.Categories -> "Add category"
        TaxonomySection.Merchants -> "Add merchant"
        // "Add method", not "Add payment method": under the Payment tab the
        // noun is already said, and the long form was clipped at font scale
        // 2.0 (BUG44).
        TaxonomySection.PaymentMethods -> "Add method"
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
    LfActionBar {
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

/**
 * Why a change was refused ("Groceries" already exists here), as a warning
 * notice: the shared notice, in the amber the line already used (E3).
 */
@Composable
private fun MessageBanner(message: String, onEvent: (CategoriesEvent) -> Unit) {
    LfNotice(
        tone = LfNoticeTone.Warning,
        title = message,
        actionLabel = "OK",
        onAction = { onEvent(CategoriesEvent.MessageDismissed) },
    )
}

// The empty state is about the *live* tree, and the hidden section still has to
// be reachable underneath it -- a user who hid their last category is exactly
// the one who needs the way back, and telling them to "add one to get started"
// while the one they want sits hidden below is the version of this screen that
// caused the complaint. See [sectionIsEmpty].
private fun LazyListScope.categoryItems(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    state.tree.forEach { branch ->
        // Keyed per branch rather than per row: a branch is one panel, and a
        // panel is one container. Stable keys and a contentType still apply
        // (CLAUDE.md §5) -- without them every edit recomposes the whole list.
        item(key = branch.parent.id, contentType = "branch") {
            CategoryBranch(branch, onEvent)
        }
    }
    hiddenSection(state, onEvent)
}

/**
 * One category and its subcategories: one panel, a row each, hairlines between.
 *
 * Subcategories are indented so their swatch sits under the parent's name, and
 * their hairline starts there too, so the indent alone says what belongs to
 * what. That replaces the connector rail, which needed nested cards to read
 * against and a whole branch measured at intrinsic height to draw.
 */
@Composable
private fun CategoryBranch(branch: CategoryTree, onEvent: (CategoriesEvent) -> Unit) {
    val childInset = childRowInset()
    LfPanel(modifier = Modifier.padding(bottom = LfTheme.space.itemGap)) {
        CategoryRow(branch.parent, isChild = false, onEvent = onEvent)
        branch.children.forEach { child ->
            // Inset at the end as well, as `lfGroupSlice` draws the merchant
            // and hidden panels' hairlines: one panel shape on the screen.
            LfDivider(modifier = Modifier.padding(start = childInset, end = LfTheme.space.rowInset))
            CategoryRow(child, isChild = true, onEvent = onEvent)
        }
    }
}

/** Where a subcategory's swatch starts: under its parent's name. */
@Composable
private fun childRowInset(): Dp = LfTheme.space.rowInset + LfLayout.categoryDotSize + LfTheme.space.itemGap

@Composable
private fun CategoryRow(
    category: Category,
    isChild: Boolean,
    onEvent: (CategoriesEvent) -> Unit,
) {
    OrganiseRow(
        title = category.name,
        titleStyle = if (isChild) LfTheme.type.bodySmall else LfTheme.type.body,
        startInset = if (isChild) childRowInset() else LfTheme.space.rowInset,
        leading = { LfCategoryDot(name = category.name, colorArgb = category.colorArgb) },
        onClick = {
            onEvent(CategoriesEvent.RowActionsOpened(TaxonomyRow.Category(category.id, category.name, isChild)))
        },
    )
}

/**
 * The one row this screen draws, in all three sections and under Hidden.
 *
 * One line of name, with a second, muted one only where there is a fact to
 * read (a payment method's type, when something was hidden). No buttons: the
 * row itself opens its actions, which is what keeps it at a touch target's
 * height. A row that only presents one thing should cost about two lines
 * (`CLAUDE.md` §5), and this costs one.
 *
 * TalkBack reads it as a button whose action is "show actions", so the tap is
 * announced before it is made rather than discovered.
 */
@Composable
private fun OrganiseRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    titleStyle: TextStyle = LfTheme.type.body,
    startInset: Dp = LfTheme.space.rowInset,
    position: LfGroupPosition? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val space = LfTheme.space
    // A lazy row draws its own slice of one panel (`lfGroupSlice`): merchants,
    // payment methods and hidden rows cannot share a parent the way a category
    // branch's rows do. Clipped to the slice's corners so a tap's ripple keeps
    // the panel's outline.
    val slice = position?.let {
        Modifier
            .clip(it.shape(LfLayout.panelRadius))
            .lfGroupSlice(
                position = it,
                container = LfTheme.colors.surface.raised,
                edge = LfTheme.colors.border.subtle,
                edgeWidth = LfTheme.borders.hairline,
                radius = LfLayout.panelRadius,
                dividerInset = space.rowInset,
            )
    } ?: Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(slice)
            .heightIn(min = LfLayout.minTouchTarget)
            .clickable(onClickLabel = SHOW_ACTIONS, role = Role.Button, onClick = onClick)
            .padding(
                start = startInset,
                end = space.rowInset,
                top = space.rowInsetVertical,
                bottom = space.rowInsetVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(space.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = titleStyle, color = LfTheme.colors.text.primary)
            detail?.let {
                Text(text = it, style = LfTheme.type.label, color = LfTheme.colors.text.muted)
            }
        }
    }
}

private fun LazyListScope.merchantItems(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    val merchants = state.merchants
    items(merchants.size, key = { merchants[it].id }, contentType = { "merchant" }) { index ->
        MerchantRow(merchants[index], lfGroupPosition(index, merchants.size), onEvent)
    }
    hiddenSection(state, onEvent)
}

@Composable
private fun MerchantRow(merchant: Merchant, position: LfGroupPosition, onEvent: (CategoriesEvent) -> Unit) {
    val name = merchant.canonicalName
    OrganiseRow(
        title = name,
        position = position,
        onClick = { onEvent(CategoriesEvent.RowActionsOpened(TaxonomyRow.Merchant(merchant.id, name))) },
    )
}

private fun LazyListScope.paymentMethodItems(state: CategoriesUiState, onEvent: (CategoriesEvent) -> Unit) {
    val methods = state.paymentMethods
    items(methods.size, key = { methods[it].id }, contentType = { "method" }) { index ->
        PaymentMethodRow(methods[index], lfGroupPosition(index, methods.size), onEvent)
    }
    hiddenSection(state, onEvent)
}

@Composable
private fun PaymentMethodRow(method: PaymentMethod, position: LfGroupPosition, onEvent: (CategoriesEvent) -> Unit) {
    OrganiseRow(
        title = buildString {
            append(method.label)
            method.last4?.let { append(" ····$it") }
            if (method.isDefault) append("  · default")
        },
        // The type keeps its own line rather than joining the one above: label,
        // last-4, default and type is four facts abreast with no hierarchy, and
        // the label is the one being scanned for.
        detail = method.type.name.lowercase().replace('_', ' '),
        position = position,
        onClick = {
            onEvent(
                CategoriesEvent.RowActionsOpened(
                    TaxonomyRow.PaymentMethod(method.id, method.label, method.isDefault),
                ),
            )
        },
    )
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
        HiddenRow(hidden[index], lfGroupPosition(index, hidden.size), onEvent)
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
 *
 * A full touch target tall: it sits directly against the rows above and below
 * it, with no list gap between, so a shorter one would share their touch area.
 */
@Composable
private fun HiddenHeader(count: Int, expanded: Boolean, onEvent: (CategoriesEvent) -> Unit) {
    val space = LfTheme.space
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LfLayout.minTouchTarget)
            .clickable { onEvent(CategoriesEvent.HiddenToggled) }
            .padding(vertical = space.rowInsetVertical),
        horizontalArrangement = Arrangement.spacedBy(space.inlineGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) "\u25be" else "\u25b8",
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
        )
        Text(
            text = "Hidden ($count)",
            style = LfTheme.type.label,
            color = LfTheme.colors.text.muted,
            maxLines = 1,
        )
    }
}

/**
 * One hidden row: what it was and when it went. Its sheet holds the two ways
 * out, Restore before Erase.
 *
 * The same row and panel the live rows use -- one shape per screen. Nothing
 * else marks it as hidden, because it does not need to: it is under a header
 * that says so.
 */
@Composable
private fun HiddenRow(item: HiddenTaxonomy, position: LfGroupPosition, onEvent: (CategoriesEvent) -> Unit) {
    OrganiseRow(
        title = item.name,
        // One line for both facts. They are short, and stacking "Hidden 19 Aug"
        // above "with 2 subcategories" would make a hidden row taller than the
        // live row it is a copy of.
        detail = listOfNotNull("Hidden ${hiddenStamp(item.hiddenAt)}", item.detail).joinToString(" · "),
        position = position,
        onClick = { onEvent(CategoriesEvent.RowActionsOpened(TaxonomyRow.Hidden(item.id, item.name))) },
    )
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
 *
 * The locale is read from `LocalLocale`, for the reason set out on
 * [com.ledgerflow.core.designsystem.format.TimeStamp]: `Locale.getDefault()` is
 * a process-wide field, not observable state, so a composable resolving through
 * it keeps printing the old locale's month abbreviation until something
 * unrelated recomposes it. `LocalLocale.current.platformLocale` is observable
 * and non-null, which also removes the fallback this line used to need.
 */
@Composable
private fun hiddenStamp(millis: Long): String {
    val locale = DisplayLocale.composed
    // Read here, outside `remember`, so a change of date order redraws it.
    val pattern = DisplayDates.dayMonthPattern()
    return remember(millis, locale, pattern) {
        DateTimeFormatter.ofPattern(pattern, locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
    }
}
private const val HIDDEN_HEADER_KEY = "hidden-header"

/** What TalkBack says a row's tap does: "double-tap to show actions". */
private const val SHOW_ACTIONS = "show actions"

/** A tappable row inside a picker dialog. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = if (selected) "● $label" else "○ $label",
        style = LfTheme.type.bodySmall,
        color = if (selected) LfTheme.colors.action.primary else LfTheme.colors.text.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = LfTheme.space.rowInsetVertical),
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
 * the ones most likely to break at scale: a name above a stamp and a detail on
 * one line. `@PreviewFontScale` is the point of it -- at 2.0 the detail line has
 * to wrap rather than clip.
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
