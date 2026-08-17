package com.ledgerflow.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.NewPaymentMethod
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyError
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Category, merchant and payment-method management (SPEC.md §5.5).
 *
 * Reads are cold flows off the repositories, so an edit shows up because the
 * database changed rather than because this class remembered to update a list —
 * which is what keeps the screen honest after a merge repoints rows it is not
 * looking at.
 */
@HiltViewModel
public class CategoriesViewModel @Inject constructor(
    private val categories: CategoryRepository,
    private val merchants: MerchantRepository,
    private val paymentMethods: PaymentMethodRepository,
) : ViewModel() {

    /** Everything the user is doing; everything else is derived from the database. */
    private val local = MutableStateFlow(LocalState())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val tree = local
        .map { it.ledger }
        .flatMapLatest { ledger -> categories.observeTree(ledger) }

    public val state: StateFlow<CategoriesUiState> = combine(
        local,
        tree,
        merchants.observeAll(),
        paymentMethods.observeAll(),
    ) { local, tree, merchants, methods ->
        CategoriesUiState(
            section = local.section,
            ledger = local.ledger,
            tree = tree,
            merchants = merchants,
            paymentMethods = methods,
            dialog = local.dialog,
            isWorking = local.isWorking,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CategoriesUiState())

    /**
     * Routed by concern rather than handled in one block.
     *
     * The outer `when` stays exhaustive over the sealed type -- no `else` -- so
     * a new event still fails to compile until it is placed. The sub-handlers'
     * `else -> Unit` is unreachable by construction: each is only ever reached
     * through the group that lists its cases.
     */
    public fun onEvent(event: CategoriesEvent) {
        when (event) {
            is CategoriesEvent.SectionSelected -> local.update { it.copy(section = event.section) }
            is CategoriesEvent.LedgerSelected -> local.update { it.copy(ledger = event.ledger) }
            CategoriesEvent.MessageDismissed -> local.update { it.copy(message = null) }

            is CategoriesEvent.AddCategory,
            is CategoriesEvent.RenameCategory,
            is CategoriesEvent.DeleteCategory,
            -> onCategoryEvent(event)

            CategoriesEvent.AddMerchant,
            is CategoriesEvent.RenameMerchant,
            is CategoriesEvent.StartMergeMerchant,
            is CategoriesEvent.DeleteMerchant,
            -> onMerchantEvent(event)

            CategoriesEvent.AddPaymentMethod,
            is CategoriesEvent.SetDefaultPaymentMethod,
            is CategoriesEvent.DeletePaymentMethod,
            -> onPaymentMethodEvent(event)

            is CategoriesEvent.DialogTextChanged,
            is CategoriesEvent.DialogTargetSelected,
            is CategoriesEvent.DialogTypeSelected,
            is CategoriesEvent.DialogLast4Changed,
            CategoriesEvent.DialogConfirmed,
            CategoriesEvent.DialogDismissed,
            -> onDialogEvent(event)
        }
    }

    private fun onCategoryEvent(event: CategoriesEvent) {
        when (event) {
            is CategoriesEvent.AddCategory -> prompt(
                if (event.parentId == null) {
                    TextPromptKind.NewCategory
                } else {
                    TextPromptKind.NewSubcategory
                },
                contextId = event.parentId,
                contextName = event.parentName,
            )
            is CategoriesEvent.RenameCategory -> prompt(
                TextPromptKind.RenameCategory,
                value = event.currentName,
                contextId = event.id,
            )
            is CategoriesEvent.DeleteCategory -> deleteCategory(event.id, event.name, null)
            else -> Unit
        }
    }

    private fun onMerchantEvent(event: CategoriesEvent) {
        when (event) {
            CategoriesEvent.AddMerchant -> prompt(TextPromptKind.NewMerchant)
            is CategoriesEvent.RenameMerchant -> prompt(
                TextPromptKind.RenameMerchant,
                value = event.currentName,
                contextId = event.id,
            )
            is CategoriesEvent.StartMergeMerchant -> startMerge(event.id, event.name)
            is CategoriesEvent.DeleteMerchant -> run { merchants.delete(event.id) }
            else -> Unit
        }
    }

    private fun onPaymentMethodEvent(event: CategoriesEvent) {
        when (event) {
            CategoriesEvent.AddPaymentMethod ->
                local.update { it.copy(dialog = TaxonomyDialog.NewPaymentMethod()) }
            is CategoriesEvent.SetDefaultPaymentMethod -> run { paymentMethods.setDefault(event.id) }
            is CategoriesEvent.DeletePaymentMethod -> run { paymentMethods.delete(event.id) }
            else -> Unit
        }
    }

    private fun onDialogEvent(event: CategoriesEvent) {
        when (event) {
            is CategoriesEvent.DialogTextChanged -> updateDialogText(event.value)
            is CategoriesEvent.DialogTargetSelected -> updateDialogTarget(event.id)
            is CategoriesEvent.DialogTypeSelected -> local.update { current ->
                val dialog = current.dialog as? TaxonomyDialog.NewPaymentMethod
                    ?: return@update current
                current.copy(dialog = dialog.copy(type = event.type))
            }
            is CategoriesEvent.DialogLast4Changed -> local.update { current ->
                val dialog = current.dialog as? TaxonomyDialog.NewPaymentMethod
                    ?: return@update current
                // Digits only, at most four: §5.5 stores a last-4, never a card
                // number, and the field should make that impossible to get wrong
                // rather than truncating silently on save.
                //
                // `takeLast`, not `take`, and it matters: someone who pastes a
                // full PAN into a field labelled "last 4 digits" means the last
                // four. Keeping the first four would store a plausible-looking
                // number that identifies nothing, and match no SMS at P2. It
                // also matches what the repository does with a value that
                // arrives from anywhere other than this screen.
                current.copy(
                    dialog = dialog.copy(
                        last4 = event.value.filter(Char::isDigit).takeLast(LAST4_LENGTH),
                    ),
                )
            }
            CategoriesEvent.DialogConfirmed -> confirm()
            CategoriesEvent.DialogDismissed -> local.update { it.copy(dialog = null) }
            else -> Unit
        }
    }

    private fun prompt(
        kind: TextPromptKind,
        value: String = "",
        contextId: String? = null,
        contextName: String? = null,
    ) {
        local.update {
            it.copy(dialog = TaxonomyDialog.TextPrompt(kind, value, contextId, contextName))
        }
    }

    private fun updateDialogText(value: String) = local.update { current ->
        when (val dialog = current.dialog) {
            is TaxonomyDialog.TextPrompt -> current.copy(dialog = dialog.copy(value = value))
            is TaxonomyDialog.NewPaymentMethod -> current.copy(dialog = dialog.copy(label = value))
            else -> current
        }
    }

    private fun updateDialogTarget(id: String) = local.update { current ->
        when (val dialog = current.dialog) {
            is TaxonomyDialog.ReassignCategory -> current.copy(dialog = dialog.copy(targetId = id))
            is TaxonomyDialog.MergeMerchant -> current.copy(dialog = dialog.copy(targetId = id))
            else -> current
        }
    }

    private fun confirm() {
        when (val dialog = local.value.dialog) {
            null -> Unit
            is TaxonomyDialog.TextPrompt -> confirmTextPrompt(dialog)
            is TaxonomyDialog.ReassignCategory ->
                deleteCategory(dialog.id, dialog.name, dialog.targetId)
            is TaxonomyDialog.MergeMerchant -> dialog.targetId?.let { target ->
                run { merchants.merge(dialog.sourceId, target) }
            }
            is TaxonomyDialog.NewPaymentMethod -> run {
                paymentMethods.create(
                    NewPaymentMethod(
                        type = dialog.type,
                        label = dialog.label,
                        last4 = dialog.last4.ifEmpty { null },
                    ),
                )
            }
        }
    }

    private fun confirmTextPrompt(dialog: TaxonomyDialog.TextPrompt) {
        val name = dialog.value
        when (dialog.kind) {
            TextPromptKind.NewCategory, TextPromptKind.NewSubcategory -> run {
                categories.create(
                    NewCategory(
                        ledger = local.value.ledger,
                        name = name,
                        parentId = dialog.contextId,
                    ),
                )
            }
            TextPromptKind.RenameCategory ->
                run { categories.rename(requireNotNull(dialog.contextId), name) }
            TextPromptKind.NewMerchant -> run { merchants.createOrGet(name) }
            TextPromptKind.RenameMerchant ->
                run { merchants.rename(requireNotNull(dialog.contextId), name) }
        }
    }

    private fun startMerge(id: String, name: String) {
        val candidates = state.value.merchants
            .filterNot { it.id == id }
            .map { MerchantChoice(it.id, it.canonicalName) }
        local.update {
            it.copy(dialog = TaxonomyDialog.MergeMerchant(id, name, candidates))
        }
    }

    /**
     * Delete, and turn a `ReassignRequired` refusal into the question it implies.
     *
     * The count comes back from the repository rather than being queried here:
     * asking separately would race an approval landing between the two calls and
     * report a number that was already wrong.
     */
    private fun deleteCategory(id: String, name: String, reassignTo: String?) {
        viewModelScope.launch {
            local.update { it.copy(isWorking = true) }
            val outcome = categories.delete(id, reassignTo)
            local.update { it.copy(isWorking = false) }

            val error = (outcome as? TaxonomyResult.Failure)?.error
            if (error is TaxonomyError.ReassignRequired) {
                local.update {
                    it.copy(
                        dialog = TaxonomyDialog.ReassignCategory(
                            id = id,
                            name = name,
                            affected = error.affectedEntries,
                            candidates = reassignCandidates(id),
                        ),
                    )
                }
            } else {
                finish(outcome)
            }
        }
    }

    /**
     * Where the orphaned entries may go: live categories in the same book,
     * excluding the one being deleted and its own children.
     */
    private fun reassignCandidates(excludingId: String): List<CategoryChoice> =
        state.value.tree.flatMap { branch -> listOf(branch.parent) + branch.children }
            .filterNot { it.id == excludingId || it.parentId == excludingId }
            .map { CategoryChoice(it.id, it.name) }

    /** Runs a repository call, closing the dialog only if it succeeded. */
    private fun run(block: suspend () -> TaxonomyResult<*>) {
        viewModelScope.launch {
            local.update { it.copy(isWorking = true) }
            val outcome = block()
            local.update { it.copy(isWorking = false) }
            finish(outcome)
        }
    }

    /**
     * A refusal leaves the dialog open with the message attached.
     *
     * Closing it would throw away what the user typed and give them nothing to
     * correct -- "that name is taken" is only useful next to the name.
     */
    private fun finish(outcome: TaxonomyResult<*>) {
        when (outcome) {
            is TaxonomyResult.Success -> local.update { it.copy(dialog = null, message = null) }
            is TaxonomyResult.Failure ->
                local.update { it.copy(message = outcome.error.toMessage()) }
        }
    }

    private data class LocalState(
        val section: TaxonomySection = TaxonomySection.Categories,
        val ledger: LedgerType = LedgerType.DEBIT,
        val dialog: TaxonomyDialog? = null,
        val isWorking: Boolean = false,
        val message: String? = null,
    )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val LAST4_LENGTH = 4
    }
}

/** Every refusal, as a sentence the user can act on. */
internal fun TaxonomyError.toMessage(): String = when (this) {
    is TaxonomyError.DuplicateName -> "\"$name\" already exists here."
    TaxonomyError.BlankName -> "Give it a name first."
    TaxonomyError.NotFound -> "That's already gone."
    TaxonomyError.SystemProtected ->
        "Built-in categories can be renamed but not deleted — something has to " +
            "hold uncategorised spending."
    TaxonomyError.InvalidParent ->
        "Categories go two levels deep, and a subcategory has to sit under a " +
            "category in the same ledger."
    is TaxonomyError.ReassignRequired ->
        "$affectedEntries entries still use this. Choose where they should go."
    TaxonomyError.SameSourceAndTarget -> "Pick a different one to merge into."
}
