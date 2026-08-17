package com.ledgerflow.feature.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfActionRow
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfButtonStyle
import com.ledgerflow.core.designsystem.component.LfCard
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfDivider
import com.ledgerflow.core.designsystem.component.LfEmptyState
import com.ledgerflow.core.designsystem.component.LfScaffold
import com.ledgerflow.core.designsystem.component.LfScreenTitle
import com.ledgerflow.core.designsystem.component.LfSegmentedControl
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.PaymentMethod

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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.md),
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
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
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
                TaxonomySection.Categories -> CategoryList(state.tree, onEvent)
                TaxonomySection.Merchants -> MerchantList(state.merchants, onEvent)
                TaxonomySection.PaymentMethods -> PaymentMethodList(state.paymentMethods, onEvent)
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
    Column(modifier = Modifier.fillMaxWidth().padding(LfTheme.spacing.lg)) {
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
private fun CategoryList(tree: List<CategoryTree>, onEvent: (CategoriesEvent) -> Unit) {
    if (tree.isEmpty()) {
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
        tree.forEach { branch ->
            // Stable keys and a contentType per row shape (CLAUDE.md §5): without
            // them every edit recomposes the whole list instead of one row.
            item(key = branch.parent.id, contentType = "parent") {
                CategoryRow(branch.parent, isChild = false, onEvent = onEvent)
            }
            items(
                count = branch.children.size,
                key = { branch.children[it].id },
                contentType = { "child" },
            ) { index ->
                CategoryRow(branch.children[index], isChild = true, onEvent = onEvent)
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
    LfCard(
        modifier = Modifier.padding(start = if (isChild) LfTheme.spacing.lg else LfTheme.spacing.xs),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LfCategoryDot(name = category.name, colorArgb = category.colorArgb)
                Text(
                    text = category.name,
                    style = LfTheme.typography.bodyL,
                    color = LfTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            // Separates the name from its actions, so the buttons read as a
            // distinct region of the card rather than as more of the same block.
            LfDivider()
            LfActionRow {
                LfButton(
                    text = "Rename",
                    style = LfButtonStyle.Outlined,
                    onClick = { onEvent(CategoriesEvent.RenameCategory(category.id, category.name)) },
                )
                if (!isChild) {
                    LfButton(
                        text = "Add sub",
                        style = LfButtonStyle.Outlined,
                        onClick = {
                            onEvent(CategoriesEvent.AddCategory(category.id, category.name))
                        },
                    )
                }
                // System categories are renameable but not deletable, so the
                // control is absent rather than present-and-failing.
                if (!category.isSystem) {
                    LfButton(
                        text = "Delete",
                        style = LfButtonStyle.Outlined,
                        onClick = { onEvent(CategoriesEvent.DeleteCategory(category.id, category.name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MerchantList(merchants: List<Merchant>, onEvent: (CategoriesEvent) -> Unit) {
    if (merchants.isEmpty()) {
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
    ) {
        items(merchants.size, key = { merchants[it].id }, contentType = { "merchant" }) { index ->
            val merchant = merchants[index]
            LfCard {
                Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
                    Text(
                        text = merchant.canonicalName,
                        style = LfTheme.typography.bodyL,
                        color = LfTheme.colors.textPrimary,
                    )
                    LfDivider()
                    LfActionRow {
                        LfButton(
                            text = "Rename",
                            style = LfButtonStyle.Outlined,
                            onClick = {
                                onEvent(
                                    CategoriesEvent.RenameMerchant(merchant.id, merchant.canonicalName),
                                )
                            },
                        )
                        LfButton(
                            text = "Merge",
                            style = LfButtonStyle.Outlined,
                            onClick = {
                                onEvent(
                                    CategoriesEvent.StartMergeMerchant(
                                        merchant.id,
                                        merchant.canonicalName,
                                    ),
                                )
                            },
                        )
                        LfButton(
                            text = "Hide",
                            style = LfButtonStyle.Outlined,
                            onClick = { onEvent(CategoriesEvent.DeleteMerchant(merchant.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodList(methods: List<PaymentMethod>, onEvent: (CategoriesEvent) -> Unit) {
    if (methods.isEmpty()) {
        LfEmptyState(title = "No payment methods", body = "Add the cards and accounts you use.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = LfTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
    ) {
        items(methods.size, key = { methods[it].id }, contentType = { "method" }) { index ->
            val method = methods[index]
            LfCard {
                Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
                    Text(
                        text = buildString {
                            append(method.label)
                            method.last4?.let { append(" ····$it") }
                            if (method.isDefault) append("  · default")
                        },
                        style = LfTheme.typography.bodyL,
                        color = LfTheme.colors.textPrimary,
                    )
                    Text(
                        text = method.type.name.lowercase().replace('_', ' '),
                        style = LfTheme.typography.label,
                        color = LfTheme.colors.textTertiary,
                    )
                    LfDivider()
                    LfActionRow {
                        if (!method.isDefault) {
                            LfButton(
                                text = "Make default",
                                style = LfButtonStyle.Outlined,
                                onClick = {
                                    onEvent(CategoriesEvent.SetDefaultPaymentMethod(method.id))
                                },
                            )
                        }
                        LfButton(
                            text = "Remove",
                            style = LfButtonStyle.Outlined,
                            onClick = { onEvent(CategoriesEvent.DeletePaymentMethod(method.id)) },
                        )
                    }
                }
            }
        }
    }
}

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

