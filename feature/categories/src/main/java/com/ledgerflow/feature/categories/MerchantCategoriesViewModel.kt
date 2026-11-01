package com.ledgerflow.feature.categories

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.domain.taxonomy.MerchantCategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.HiddenTaxonomy
import com.ledgerflow.core.model.MerchantCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One category and how many live merchants are filed under it. */
@Immutable
public data class MerchantCategoryRow(val category: MerchantCategory, val merchants: Int)

/**
 * Organise > Merchants > Merchant categories (V2-6, V2-PLAN §5.1): the list
 * itself. Filing merchants happens on the Merchants tab; this page names,
 * adds, hides and restores the categories.
 */
@Immutable
public data class MerchantCategoriesUiState(
    val rows: List<MerchantCategoryRow> = emptyList(),
    val hidden: List<HiddenTaxonomy> = emptyList(),
    val hiddenExpanded: Boolean = false,
    val dialog: MerchantCategoryDialog? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

/** What the page is asking, held as state so a rotation keeps it (as Organise's dialogs are). */
public sealed interface MerchantCategoryDialog {
    /** New ([editingId] null) or rename. */
    public data class Name(val editingId: String?, val value: String) : MerchantCategoryDialog

    /** A row's sheet: Rename, Hide. */
    public data class Actions(val id: String, val name: String) : MerchantCategoryDialog

    /**
     * Hide, and where its merchants go (§5.1). [moveTo] starts on
     * Uncategorised, which always exists; [merchants] is named in the
     * question so nothing moves unannounced.
     */
    public data class Hide(
        val id: String,
        val name: String,
        val merchants: Int,
        val candidates: List<TaxonomyChoice>,
        val moveTo: String = UNCATEGORISED_CHOICE,
    ) : MerchantCategoryDialog
}

public sealed interface MerchantCategoriesEvent {
    public data object Add : MerchantCategoriesEvent
    public data class RowTapped(val id: String, val name: String) : MerchantCategoriesEvent
    public data class Rename(val id: String, val name: String) : MerchantCategoriesEvent
    public data class AskHide(val id: String, val name: String) : MerchantCategoriesEvent
    public data class Restore(val id: String) : MerchantCategoriesEvent
    public data object HiddenToggled : MerchantCategoriesEvent
    public data class TextChanged(val value: String) : MerchantCategoriesEvent
    public data class TargetSelected(val id: String) : MerchantCategoriesEvent
    public data object Confirmed : MerchantCategoriesEvent
    public data object Dismissed : MerchantCategoriesEvent
}

@HiltViewModel
public class MerchantCategoriesViewModel @Inject constructor(
    private val categories: MerchantCategoryRepository,
    merchants: MerchantRepository,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    public val state: StateFlow<MerchantCategoriesUiState> = combine(
        local,
        categories.observeAll(),
        categories.observeHidden(),
        merchants.observeAll(),
    ) { local, live, hidden, merchants ->
        val counts = merchants.groupingBy { it.merchantCategoryId }.eachCount()
        MerchantCategoriesUiState(
            rows = live.map { MerchantCategoryRow(it, counts[it.id] ?: 0) },
            hidden = hidden,
            hiddenExpanded = local.hiddenExpanded,
            dialog = local.dialog,
            isWorking = local.isWorking,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MerchantCategoriesUiState())

    public fun onEvent(event: MerchantCategoriesEvent) {
        when (event) {
            MerchantCategoriesEvent.Add -> open(MerchantCategoryDialog.Name(editingId = null, value = ""))
            is MerchantCategoriesEvent.RowTapped -> open(MerchantCategoryDialog.Actions(event.id, event.name))
            is MerchantCategoriesEvent.Rename -> open(MerchantCategoryDialog.Name(event.id, event.name))
            is MerchantCategoriesEvent.AskHide -> askHide(event.id, event.name)
            is MerchantCategoriesEvent.Restore -> run { categories.restore(event.id) }
            MerchantCategoriesEvent.HiddenToggled -> local.update { it.copy(hiddenExpanded = !it.hiddenExpanded) }
            is MerchantCategoriesEvent.TextChanged -> local.update { current ->
                val dialog = current.dialog as? MerchantCategoryDialog.Name ?: return@update current
                current.copy(dialog = dialog.copy(value = event.value))
            }
            is MerchantCategoriesEvent.TargetSelected -> local.update { current ->
                val dialog = current.dialog as? MerchantCategoryDialog.Hide ?: return@update current
                current.copy(dialog = dialog.copy(moveTo = event.id))
            }
            MerchantCategoriesEvent.Confirmed -> confirm()
            MerchantCategoriesEvent.Dismissed -> local.update { it.copy(dialog = null, message = null) }
        }
    }

    private fun open(dialog: MerchantCategoryDialog) = local.update { it.copy(dialog = dialog, message = null) }

    private fun askHide(id: String, name: String) {
        val rows = state.value.rows
        val candidates = rows.filterNot { it.category.id == id }
            .map { TaxonomyChoice(it.category.id, it.category.name) } +
            TaxonomyChoice(UNCATEGORISED_CHOICE, UNCATEGORISED)
        val count = rows.firstOrNull { it.category.id == id }?.merchants ?: 0
        open(MerchantCategoryDialog.Hide(id, name, count, candidates))
    }

    private fun confirm() {
        when (val dialog = local.value.dialog) {
            null, is MerchantCategoryDialog.Actions -> Unit
            is MerchantCategoryDialog.Name -> run {
                val id = dialog.editingId
                if (id == null) categories.create(dialog.value) else categories.rename(id, dialog.value)
            }
            is MerchantCategoryDialog.Hide -> run { categories.delete(dialog.id, dialog.moveTo.ifEmpty { null }) }
        }
    }

    /** A refusal keeps the dialog open with the reason beside what was typed, as Organise does. */
    private fun run(block: suspend () -> TaxonomyResult<*>) {
        viewModelScope.launch {
            local.update { it.copy(isWorking = true) }
            val outcome = block()
            local.update {
                when (outcome) {
                    is TaxonomyResult.Success -> it.copy(isWorking = false, dialog = null, message = null)
                    is TaxonomyResult.Failure -> it.copy(isWorking = false, message = outcome.error.toMessage())
                }
            }
        }
    }

    private data class Local(
        val hiddenExpanded: Boolean = false,
        val dialog: MerchantCategoryDialog? = null,
        val isWorking: Boolean = false,
        val message: String? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
