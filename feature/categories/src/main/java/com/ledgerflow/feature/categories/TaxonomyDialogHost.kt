package com.ledgerflow.feature.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledgerflow.core.designsystem.component.LfDialog
import com.ledgerflow.core.designsystem.component.LfDialogEmphasis
import com.ledgerflow.core.designsystem.component.LfTextField
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.model.PaymentMethodType

/**
 * Every prompt this screen can raise.
 *
 * Dialogs are driven by [CategoriesUiState.dialog] rather than by a `remember`
 * inside the composable, so a rotation mid-typing keeps what was typed. Losing a
 * half-entered category name to a config change is a small version of the
 * problem BUG6 exists to prevent, and the fix is the same one: state belongs
 * above the composition.
 */
@Composable
internal fun TaxonomyDialogHost(
    dialog: TaxonomyDialog,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    when (dialog) {
        is TaxonomyDialog.TextPrompt -> TextPromptDialog(dialog, state, onEvent)
        is TaxonomyDialog.ReassignCategory -> ReassignDialog(dialog, state, onEvent)
        is TaxonomyDialog.MergeMerchant -> MergeDialog(dialog, state, onEvent)
        is TaxonomyDialog.NewPaymentMethod -> PaymentMethodDialog(dialog, state, onEvent)
    }
}

@Composable
private fun TextPromptDialog(
    dialog: TaxonomyDialog.TextPrompt,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    val title = when (dialog.kind) {
        TextPromptKind.NewCategory -> "New category"
        TextPromptKind.NewSubcategory -> "New subcategory"
        TextPromptKind.RenameCategory -> "Rename category"
        TextPromptKind.NewMerchant -> "New merchant"
        TextPromptKind.RenameMerchant -> "Rename merchant"
    }
    val body = when (dialog.kind) {
        TextPromptKind.NewSubcategory ->
            "Inside ${dialog.contextName.orEmpty()}. Subcategories cannot have " +
                "subcategories of their own."
        TextPromptKind.RenameMerchant ->
            "Renaming re-derives how this merchant is matched, so it may now line " +
                "up with another one."
        else -> "Names are unique within their level and ledger."
    }

    LfDialog(
        title = title,
        body = body,
        confirmText = "Save",
        onConfirm = { onEvent(CategoriesEvent.DialogConfirmed) },
        onDismiss = { onEvent(CategoriesEvent.DialogDismissed) },
        detail = {
            Column(verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm)) {
                LfTextField(
                    value = dialog.value,
                    onValueChange = { onEvent(CategoriesEvent.DialogTextChanged(it)) },
                    label = "Name",
                    // Names are proper nouns; this is the one place in the app
                    // where auto-capitalisation helps rather than fights.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                )
                state.message?.let { ErrorText(it) }
            }
        },
    )
}

@Composable
private fun ReassignDialog(
    dialog: TaxonomyDialog.ReassignCategory,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    LfDialog(
        title = "Move ${dialog.affected} entries first",
        body = "\"${dialog.name}\" is still used by ${dialog.affected} " +
            "${if (dialog.affected == 1) "entry" else "entries"}. Choose where they " +
            "should go — nothing is deleted, they just change category.",
        confirmText = "Move and delete",
        // A destructive-adjacent choice, so it must be made deliberately rather
        // than dismissed by tapping outside.
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(CategoriesEvent.DialogConfirmed) },
        onDismiss = { onEvent(CategoriesEvent.DialogDismissed) },
        detail = {
            Column(
                modifier = Modifier
                    .heightIn(max = PICKER_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                dialog.candidates.forEach { choice ->
                    ChoiceRow(
                        label = choice.name,
                        selected = choice.id == dialog.targetId,
                        onClick = { onEvent(CategoriesEvent.DialogTargetSelected(choice.id)) },
                    )
                }
                state.message?.let { ErrorText(it) }
            }
        },
    )
}

@Composable
private fun MergeDialog(
    dialog: TaxonomyDialog.MergeMerchant,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    LfDialog(
        title = "Merge ${dialog.sourceName}",
        body = "Every entry recorded against \"${dialog.sourceName}\" moves to the " +
            "merchant you pick, in both ledgers. This cannot be undone.",
        confirmText = "Merge",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(CategoriesEvent.DialogConfirmed) },
        onDismiss = { onEvent(CategoriesEvent.DialogDismissed) },
        detail = {
            Column(
                modifier = Modifier
                    .heightIn(max = PICKER_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (dialog.candidates.isEmpty()) {
                    Text(
                        text = "There is nothing else to merge into yet.",
                        style = LfTheme.typography.bodyM,
                        color = LfTheme.colors.textSecondary,
                    )
                }
                dialog.candidates.forEach { choice ->
                    ChoiceRow(
                        label = choice.name,
                        selected = choice.id == dialog.targetId,
                        onClick = { onEvent(CategoriesEvent.DialogTargetSelected(choice.id)) },
                    )
                }
                state.message?.let { ErrorText(it) }
            }
        },
    )
}

@Composable
private fun PaymentMethodDialog(
    dialog: TaxonomyDialog.NewPaymentMethod,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    LfDialog(
        title = "New payment method",
        body = "Only the last four digits are stored — never a full card number.",
        confirmText = "Add",
        onConfirm = { onEvent(CategoriesEvent.DialogConfirmed) },
        onDismiss = { onEvent(CategoriesEvent.DialogDismissed) },
        detail = {
            Column(
                modifier = Modifier
                    .heightIn(max = PICKER_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LfTheme.spacing.sm),
            ) {
                LfTextField(
                    value = dialog.label,
                    onValueChange = { onEvent(CategoriesEvent.DialogTextChanged(it)) },
                    label = "Label",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                    ),
                )
                LfTextField(
                    value = dialog.last4,
                    onValueChange = { onEvent(CategoriesEvent.DialogLast4Changed(it)) },
                    label = "Last 4 digits (optional)",
                    // `Number`, not `NumberPassword`. Found on device: the
                    // password type made Samsung Pass offer to save the field as
                    // a credential, and masked digits the user is copying off a
                    // card and needs to check. A last-4 is a label, not a secret
                    // -- treating it as one is both worse UX and worse privacy,
                    // since it invites a password manager to store card data.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    text = "Type",
                    style = LfTheme.typography.label,
                    color = LfTheme.colors.textSecondary,
                )
                PaymentMethodType.entries.forEach { type ->
                    ChoiceRow(
                        label = type.name.lowercase().replace('_', ' '),
                        selected = type == dialog.type,
                        onClick = { onEvent(CategoriesEvent.DialogTypeSelected(type)) },
                    )
                }
                state.message?.let { ErrorText(it) }
            }
        },
    )
}

/**
 * A refusal, shown *inside* the dialog.
 *
 * The repository leaves the dialog open on failure precisely so this can appear
 * next to the field that caused it: "that name is taken" is only actionable
 * beside the name.
 */
@Composable
private fun ErrorText(message: String) {
    Text(text = message, style = LfTheme.typography.bodyM, color = LfTheme.colors.debit)
}

private const val PICKER_MAX_HEIGHT = 280
