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
        is TaxonomyDialog.ConfirmDelete -> ConfirmDeleteDialog(dialog, state, onEvent)
        is TaxonomyDialog.ConfirmErase -> ConfirmEraseDialog(dialog, state, onEvent)
        is TaxonomyDialog.ReassignBeforeErase -> ReassignBeforeEraseDialog(dialog, state, onEvent)
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

/**
 * The mis-tap guard, worded per target.
 *
 * Each of the three says what actually happens, because they genuinely differ
 * and a confirmation that overstates the damage is as bad as one that
 * understates it — both teach the user that the words are boilerplate. A
 * merchant keeps labelling past entries; a payment method is scrubbed from
 * them; a category may still have to be re-assigned in a second step.
 */
@Composable
private fun ConfirmDeleteDialog(
    dialog: TaxonomyDialog.ConfirmDelete,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    val noun = when (dialog.target) {
        DeleteTarget.Category -> "category"
        DeleteTarget.Subcategory -> "subcategory"
        DeleteTarget.Merchant -> "merchant"
        DeleteTarget.PaymentMethod -> "payment method"
    }
    val body = when (dialog.target) {
        DeleteTarget.Category ->
            "Its subcategories go with it. Entries already filed under it keep " +
                "their amounts — if any exist, you'll be asked where to move them next. " +
                "You can bring it back from Hidden."
        DeleteTarget.Subcategory ->
            "Entries already filed under it keep their amounts — if any exist, " +
                "you'll be asked where to move them next. You can bring it back " +
                "from Hidden."
        DeleteTarget.Merchant ->
            "Past entries keep showing it, so your history reads the same. You " +
                "can bring it back from Hidden."
        DeleteTarget.PaymentMethod ->
            "Past entries lose the record of which method was used, and their " +
                "amounts are untouched. You can bring it back from Hidden, but " +
                "not that record."
    }
    LfDialog(
        title = "Hide \"${dialog.name}\"?",
        body = "This $noun stops being offered. $body",
        confirmText = "Hide",
        // Warning emphasis also stops an outside tap from standing in for an
        // answer, which for a confirmation would defeat the point.
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(CategoriesEvent.DialogConfirmed) },
        onDismiss = { onEvent(CategoriesEvent.DialogDismissed) },
        detail = { state.message?.let { ErrorText(it) } },
    )
}

/**
 * The mis-tap guard on the irreversible one (ADR-0016).
 *
 * Three things this has to do that [ConfirmDeleteDialog] does not, and each of
 * them was decided by the bin first:
 *
 * - **Name the row.** A dialog that only asks "are you sure?" is one people
 *   learn to tap through.
 * - **Say it cannot be undone**, in those words. "Erase" is the app's verb for
 *   permanence, but a verb is not a warning.
 * - **Tell the user to export, not offer to back up.** The app cannot back up
 *   for them: `.lfbk` is phrase-derived and the app never holds the 24 words
 *   (ADR-0011). A dialog that offered would be lying about what it can do.
 *
 * `Warning` emphasis also stops an outside tap standing in for an answer, which
 * on this dialog would be the difference between a dismissal and a destroy.
 */
@Composable
private fun ConfirmEraseDialog(
    dialog: TaxonomyDialog.ConfirmErase,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    val noun = when (dialog.target) {
        DeleteTarget.Category, DeleteTarget.Subcategory -> "category"
        DeleteTarget.Merchant -> "merchant"
        DeleteTarget.PaymentMethod -> "payment method"
    }
    LfDialog(
        title = "Erase \"${dialog.name}\"?",
        body = "This $noun is removed from your vault for good. This cannot be " +
            "undone — export first if you might want it back.",
        confirmText = "Erase",
        emphasis = LfDialogEmphasis.Warning,
        onConfirm = { onEvent(CategoriesEvent.DialogConfirmed) },
        onDismiss = { onEvent(CategoriesEvent.DialogDismissed) },
        detail = { state.message?.let { ErrorText(it) } },
    )
}

/**
 * Where the entries go, asked *because* the row is about to stop existing.
 *
 * Reads almost like [ReassignDialog] and says the opposite thing in one place:
 * that one can promise "nothing is deleted, they just change category", and this
 * one cannot. Rounding the two to a single dialog would mean one of the two
 * sentences being false whenever it was shown, and it is the reassuring one that
 * would be false.
 */
@Composable
private fun ReassignBeforeEraseDialog(
    dialog: TaxonomyDialog.ReassignBeforeErase,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    val noun = if (dialog.target == DeleteTarget.Merchant) "merchant" else "category"
    val entries = entryNoun(dialog.affected)
    LfDialog(
        title = "Move ${dialog.affected} $entries first",
        body = "\"${dialog.name}\" is still on ${dialog.affected} $entries, " +
            "including any in your bin. Choose where they go — then the $noun is " +
            "erased and cannot be brought back.",
        confirmText = "Move and erase",
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
                        text = "There is nowhere else to move them yet. Add another " +
                            "$noun first.",
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
private fun ReassignDialog(
    dialog: TaxonomyDialog.ReassignCategory,
    state: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    LfDialog(
        // Both halves pluralise. The body already did; the title said "Move 1
        // entries first", which is the kind of thing that reads as machine
        // output and quietly tells the user nobody looked at this screen.
        title = "Move ${dialog.affected} ${entryNoun(dialog.affected)} first",
        body = "\"${dialog.name}\" is still used by ${dialog.affected} " +
            "${entryNoun(dialog.affected)}. Choose where they " +
            "should go — nothing is deleted, they just change category.",
        // "hide", not "delete" -- this dialog ends in a soft delete, and every
        // other control on the screen now calls that hiding. "Delete" here was
        // the last place the old vocabulary survived, and it is the worst place
        // to leave it: the sentence above promises nothing is deleted.
        confirmText = "Move and hide",
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

/** "entry" or "entries". One place, so the two re-assign dialogs cannot drift. */
private fun entryNoun(count: Int): String = if (count == 1) "entry" else "entries"
