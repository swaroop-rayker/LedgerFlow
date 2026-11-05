package com.ledgerflow.feature.categories

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.ledgerflow.core.designsystem.component.LfButton
import com.ledgerflow.core.designsystem.component.LfCategoryDot
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfNotice
import com.ledgerflow.core.designsystem.component.LfNoticeTone
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.component.lfGroupPosition
import com.ledgerflow.core.designsystem.pattern.LfActionBar
import com.ledgerflow.core.designsystem.pattern.LfDetailPage
import com.ledgerflow.core.designsystem.pattern.LfOptionsSheet
import com.ledgerflow.core.designsystem.pattern.LfPageSpacing
import com.ledgerflow.core.designsystem.pattern.LfSheetOption
import com.ledgerflow.core.designsystem.theme.LfLayout
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.MerchantCategory

/**
 * Organise > Merchants > Merchant categories (V2-6, V2-PLAN §5.1): the kinds
 * of place, each with how many merchants it holds, in one panel. A row opens
 * Rename and Hide; hiding asks where its merchants go. Hidden ones wait at
 * the foot with Restore, as hidden merchants do on the tab this page is
 * reached from.
 */
@Composable
public fun MerchantCategoriesScreen(
    state: MerchantCategoriesUiState,
    onEvent: (MerchantCategoriesEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MerchantCategoryDialogs(state, onEvent)

    LfDetailPage(
        title = "Merchant categories",
        subtitle = "What kind of place each merchant is. File merchants on the Merchants tab.",
        modifier = modifier,
        onBack = onBack,
        notices = state.message?.takeIf { state.dialog == null }?.let { message ->
            {
                LfNotice(
                    tone = LfNoticeTone.Warning,
                    title = message,
                    actionLabel = "OK",
                    onAction = { onEvent(MerchantCategoriesEvent.Dismissed) },
                )
            }
        },
        actionBar = {
            LfActionBar {
                LfButton(
                    text = "Add category",
                    modifier = Modifier.fillMaxWidth(),
                    loading = state.isWorking,
                    onClick = { onEvent(MerchantCategoriesEvent.Add) },
                )
            }
        },
        // One panel drawn a slice per row, as the Merchants tab is.
        spacing = LfPageSpacing.None,
    ) {
        val rows = state.rows
        items(rows.size, key = { rows[it].category.id }, contentType = { "merchant-category" }) { index ->
            val row = rows[index]
            OrganiseRow(
                title = row.category.name,
                detail = merchantCount(row.merchants),
                position = lfGroupPosition(index, rows.size),
                leading = { LfCategoryDot(name = row.category.name, colorArgb = row.category.colorArgb) },
                onClick = { onEvent(MerchantCategoriesEvent.RowTapped(row.category.id, row.category.name)) },
            )
        }
        hiddenCategories(state, onEvent)
    }
}

/** Hidden categories at the foot, collapsed until asked for, each restored by a tap. */
private fun LazyListScope.hiddenCategories(
    state: MerchantCategoriesUiState,
    onEvent: (MerchantCategoriesEvent) -> Unit,
) {
    if (state.hidden.isEmpty()) return
    item(key = "hidden-header", contentType = "hiddenHeader") {
        // Organise's own header, so a hidden list reads the same on both pages.
        HiddenHeader(state.hidden.size, state.hiddenExpanded) { onEvent(MerchantCategoriesEvent.HiddenToggled) }
    }
    if (!state.hiddenExpanded) return
    val hidden = state.hidden
    items(hidden.size, key = { "hidden-" + hidden[it].id }, contentType = { "hidden" }) { index ->
        OrganiseRow(
            title = hidden[index].name,
            detail = "Hidden. Tap to restore.",
            position = lfGroupPosition(index, hidden.size),
            onClick = { onEvent(MerchantCategoriesEvent.Restore(hidden[index].id)) },
        )
    }
}

internal fun merchantCount(count: Int): String = when (count) {
    0 -> "No merchants"
    1 -> "1 merchant"
    else -> "$count merchants"
}

@Composable
private fun MerchantCategoryDialogs(state: MerchantCategoriesUiState, onEvent: (MerchantCategoriesEvent) -> Unit) {
    val dismiss = { onEvent(MerchantCategoriesEvent.Dismissed) }
    when (val dialog = state.dialog) {
        null -> Unit
        is MerchantCategoryDialog.Actions -> LfOptionsSheet(
            title = dialog.name,
            options = listOf(
                LfSheetOption(
                    label = "Rename",
                    onClick = { onEvent(MerchantCategoriesEvent.Rename(dialog.id, dialog.name)) },
                ),
                LfSheetOption(
                    label = "Hide",
                    onClick = { onEvent(MerchantCategoriesEvent.AskHide(dialog.id, dialog.name)) },
                ),
            ),
            onDismiss = dismiss,
        )
        is MerchantCategoryDialog.Name -> LfDialog(
            title = if (dialog.editingId == null) "New merchant category" else "Rename merchant category",
            body = "A kind of place, such as Groceries or Fuel.",
            confirmText = if (dialog.editingId == null) "Add" else "Rename",
            onConfirm = { onEvent(MerchantCategoriesEvent.Confirmed) },
            onDismiss = dismiss,
            detail = {
                LfTextField(
                    value = dialog.value,
                    onValueChange = { onEvent(MerchantCategoriesEvent.TextChanged(it)) },
                    label = "Name",
                    error = state.message,
                )
            },
        )
        is MerchantCategoryDialog.Hide -> HideDialog(dialog, onEvent)
    }
}

/** Hiding asks where its merchants go (§5.1), and names how many. */
@Composable
private fun HideDialog(dialog: MerchantCategoryDialog.Hide, onEvent: (MerchantCategoriesEvent) -> Unit) {
    LfDialog(
        title = "Hide ${dialog.name}?",
        body = if (dialog.merchants == 0) {
            "No merchant is filed under it. You can restore it later."
        } else {
            "Its ${merchantCount(dialog.merchants)} move to the category you pick, and Analytics " +
                "files their spending there. You can restore it later; they stay where they moved."
        },
        confirmText = "Hide",
        onConfirm = { onEvent(MerchantCategoriesEvent.Confirmed) },
        onDismiss = { onEvent(MerchantCategoriesEvent.Dismissed) },
        detail = if (dialog.merchants == 0) {
            null
        } else {
            {
                Column(
                    modifier = Modifier
                        .heightIn(max = LfLayout.dialogListMax)
                        .verticalScroll(rememberScrollState()),
                ) {
                    dialog.candidates.forEach { choice ->
                        ChoiceRow(
                            label = choice.name,
                            selected = choice.id == dialog.moveTo,
                            onClick = { onEvent(MerchantCategoriesEvent.TargetSelected(choice.id)) },
                        )
                    }
                }
            }
        },
    )
}

@PreviewScreenSizes
@PreviewFontScale
@PreviewLightDark
@Composable
private fun MerchantCategoriesPreview() {
    LfTheme {
        MerchantCategoriesScreen(
            state = MerchantCategoriesUiState(
                rows = listOf(
                    MerchantCategoryRow(MerchantCategory("1", "Groceries", 0xFF2F7E50.toInt(), 0, true), 4),
                    MerchantCategoryRow(MerchantCategory("2", "Fuel", 0xFFAB5C15.toInt(), 1, true), 0),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
