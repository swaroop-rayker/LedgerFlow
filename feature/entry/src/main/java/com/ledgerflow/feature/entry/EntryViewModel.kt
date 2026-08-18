package com.ledgerflow.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.designsystem.format.MoneyFormat
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftWrite
import com.ledgerflow.core.domain.ledger.EntryDraft
import com.ledgerflow.core.domain.ledger.EntryCombo
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.ledger.NewLineItem
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.PaymentMethodRepository
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.model.CategoryTree
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PaymentMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The manual entry form (SPEC.md §5.4), and BUG6's countermeasure in practice.
 *
 * Two things here are load-bearing rather than incidental:
 *
 * **The amount is parsed to a `Long` on every keystroke.** The field holds raw
 * text, because reformatting under a moving caret makes a money field
 * unusable; `MoneyFormat.parse` turns it into minor units with integer
 * arithmetic only, so no `Double` ever touches the value (Law 3). ADR-0012
 * records why this replaced the in-app keypad.
 *
 * **Every edit reaches Room within 300 ms.** The debounce is a coalescing
 * window, not a delay before the state is real: the form's state updates
 * immediately and the *write* is what waits. So a process death costs at most
 * the last 300 ms of typing, and usually nothing.
 *
 * Repositories are injected directly rather than through per-operation use
 * cases, which is the pattern `CategoriesViewModel` already sets: a use case
 * that only forwards a call adds a name and a file, not a guarantee.
 * `ApproveTransactionUseCase` is the exception, because there the name *is* the
 * guarantee -- it is the single door Law 1 requires and `LedgerSingleWriterTest`
 * fails the build if anything else opens it.
 */
@HiltViewModel
public class EntryViewModel @Inject constructor(
    private val approveTransaction: ApproveTransactionUseCase,
    private val drafts: DraftRepository,
    private val ledgerRepository: LedgerRepository,
    private val categories: CategoryRepository,
    private val merchants: MerchantRepository,
    private val paymentMethods: PaymentMethodRepository,
    private val clock: Clock,
    private val ids: Uuid7Generator,
) : ViewModel() {

    private val form = MutableStateFlow(Form(occurredAt = clock.nowMillis()))
    private val currency = MutableStateFlow(EntryUiState.DEFAULT_CURRENCY)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ledgerScoped: kotlinx.coroutines.flow.Flow<LedgerScoped> = form
        .map { it.ledger }
        .distinctUntilChanged()
        .flatMapLatest { ledger ->
            combine(
                categories.observeTree(ledger),
                merchants.observeAll(),
                paymentMethods.observeAll(),
                ledgerRepository.observeRecentCombos(ledger, COMBO_LIMIT),
                drafts.observe(ledger),
            ) { tree, merchantList, methods, combos, unsaved ->
                LedgerScoped(tree, merchantList, methods, combos, unsaved)
            }
        }

    public val state: StateFlow<EntryUiState> =
        combine(form, ledgerScoped, currency) { form, scoped, currencyCode ->
            form.toUiState(scoped, currencyCode, clock.nowMillis())
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            EntryUiState(occurredAt = clock.nowMillis()),
        )

    init {
        viewModelScope.launch {
            currency.value = ledgerRepository.baseCurrency() ?: EntryUiState.DEFAULT_CURRENCY
        }
        viewModelScope.launch { collectDraftWrites() }
    }

    /**
     * The 300 ms debounce (BUG6).
     *
     * A coalescing window rather than a delay before the state is real: the
     * form updates on the keystroke and only the *write* waits, so a process
     * death costs at most the last fraction of a second of typing.
     */
    @OptIn(FlowPreview::class)
    private suspend fun collectDraftWrites() {
        // Only a form the user has touched is persisted. Writing on open would
        // create a draft for a form nobody typed in, and the next launch would
        // offer to resume one nobody started.
        //
        // The write re-reads `form.value` instead of using the debounced value,
        // and re-checks `dirty`. That is what closes the save race: `filter`
        // runs before `debounce`, so a clean form never reaches the window and
        // therefore cannot cancel a tick already pending in it. Found on
        // device -- saving an entry discarded the draft, then a tick from the
        // keystroke before it wrote the whole thing straight back, and the next
        // entry opened pre-filled with the one just saved.
        form.filter { it.dirty }
            // Keyed on the payload, not the whole form. `persist` writes the
            // new draft id back into `form`, and observing that emission sent
            // the collector straight back round to save again -- every draft
            // written twice, on the hottest path in the app. The payload is
            // what a draft *is*; the id is where it lives.
            .distinctUntilChangedBy { it.toPayload() }
            .debounce(DRAFT_DEBOUNCE_MS)
            .collect {
                val current = form.value
                if (current.dirty) persist(current)
            }
    }

    /**
     * Routed by concern rather than handled in one block.
     *
     * The outer `when` stays exhaustive over the sealed type -- no `else` -- so
     * a new event fails to compile until it is placed. The sub-handlers'
     * `else -> Unit` is unreachable by construction: each is only ever entered
     * through the group that lists its cases.
     */
    public fun onEvent(event: EntryEvent) {
        when (event) {
            is EntryEvent.LedgerSelected -> switchLedger(event.ledger)
            is EntryEvent.AmountChanged -> changeAmount(event.text)

            is EntryEvent.NoteChanged,
            EntryEvent.DateRequested,
            is EntryEvent.DateSelected,
            EntryEvent.DateDismissed,
            -> onDetailEvent(event)

            is EntryEvent.PickerOpened,
            is EntryEvent.PickerItemSelected,
            EntryEvent.PickerDismissed,
            is EntryEvent.ComboSelected,
            -> onAssignmentEvent(event)

            EntryEvent.LineItemAdded,
            is EntryEvent.LineItemNameChanged,
            is EntryEvent.LineItemAmountChanged,
            is EntryEvent.LineItemRemoved,
            -> onLineItemEvent(event)

            is EntryEvent.DraftOpened,
            is EntryEvent.DraftDiscardRequested,
            EntryEvent.DraftDiscardConfirmed,
            EntryEvent.DraftDiscardDismissed,
            EntryEvent.NewDraftStarted,
            EntryEvent.SaveRequested,
            EntryEvent.DiscardRequested,
            EntryEvent.DiscardConfirmed,
            EntryEvent.DiscardDismissed,
            EntryEvent.MessageDismissed,
            EntryEvent.ResumeNoticeDismissed,
            -> onDraftEvent(event)
        }
    }

    /** The stack, saving, and the two dismissals that close a notice. */
    private fun onDraftEvent(event: EntryEvent) {
        when (event) {
            is EntryEvent.DraftOpened -> openDraft(event.id)
            is EntryEvent.DraftDiscardRequested -> form.update {
                it.copy(discardingDraftId = event.id)
            }
            EntryEvent.DraftDiscardConfirmed -> form.value.discardingDraftId?.let(::discardDraft)
            EntryEvent.DraftDiscardDismissed -> form.update { it.copy(discardingDraftId = null) }
            EntryEvent.NewDraftStarted -> startNewDraft()

            EntryEvent.SaveRequested -> save()
            EntryEvent.DiscardRequested -> form.update { it.copy(confirmingDiscard = true) }
            EntryEvent.DiscardConfirmed -> startFresh()
            EntryEvent.DiscardDismissed -> form.update { it.copy(confirmingDiscard = false) }

            EntryEvent.MessageDismissed -> form.update { it.copy(message = null) }
            EntryEvent.ResumeNoticeDismissed -> form.update { it.copy(resumedFromDraft = false) }
            else -> Unit
        }
    }

    // ── Amount ──────────────────────────────────────────────────────────────

    /**
     * Keeps the field's text and the stored `Long` in step.
     *
     * The text is never rewritten here. Echoing back a reformatted value would
     * fight the caret on every keystroke, and "12." would become "12.00" before
     * the user has finished the number they were typing.
     */
    private fun changeAmount(text: String) {
        edit {
            it.copy(
                amountText = text,
                amountMinor = MoneyFormat.parse(text, currency.value),
            )
        }
    }

    // ── Note and date ───────────────────────────────────────────────────────

    private fun onDetailEvent(event: EntryEvent) {
        when (event) {
            is EntryEvent.NoteChanged -> edit { it.copy(note = event.value) }
            EntryEvent.DateRequested -> form.update { it.copy(choosingDate = true) }
            is EntryEvent.DateSelected -> edit {
                it.copy(occurredAt = event.epochMillis, choosingDate = false)
            }
            EntryEvent.DateDismissed -> form.update { it.copy(choosingDate = false) }
            else -> Unit
        }
    }

    // ── Category, merchant, payment method ──────────────────────────────────

    private fun onAssignmentEvent(event: EntryEvent) {
        when (event) {
            is EntryEvent.PickerOpened -> form.update { it.copy(picker = event.picker) }
            EntryEvent.PickerDismissed -> form.update { it.copy(picker = null) }
            is EntryEvent.PickerItemSelected -> applyPick(event.id)
            is EntryEvent.ComboSelected -> applyCombo(event.index)
            else -> Unit
        }
    }

    private fun applyPick(id: String?) {
        val picker = form.value.picker ?: return
        edit { current ->
            when (picker) {
                // Changing the category invalidates the subcategory: §6.1.1's
                // invariant is that the subcategory's parent *is* the category,
                // and keeping a stale one would be the exact row the approval
                // refuses -- discovered at Save rather than at the tap.
                EntryPicker.Category -> current.copy(categoryId = id, subcategoryId = null)
                is EntryPicker.Subcategory -> current.copy(subcategoryId = id)
                EntryPicker.Merchant -> current.copy(merchantId = id)
                EntryPicker.PaymentMethod -> current.copy(paymentMethodId = id)
            }.copy(picker = null)
        }
    }

    private fun applyCombo(index: Int) {
        val combo = state.value.combos.getOrNull(index) ?: return
        edit {
            it.copy(
                categoryId = combo.categoryId,
                subcategoryId = combo.subcategoryId,
                merchantId = combo.merchantId,
                paymentMethodId = combo.paymentMethodId,
            )
        }
    }

    // ── Line items ──────────────────────────────────────────────────────────

    private fun onLineItemEvent(event: EntryEvent) {
        when (event) {
            EntryEvent.LineItemAdded -> edit {
                it.copy(lineItems = it.lineItems + EntryLineItem(key = ids.generate()))
            }
            is EntryEvent.LineItemNameChanged -> editLine(event.key) { it.copy(name = event.value) }
            is EntryEvent.LineItemAmountChanged -> editLine(event.key) {
                it.copy(
                    amountText = event.text,
                    amountMinor = MoneyFormat.parse(event.text, currency.value),
                )
            }
            is EntryEvent.LineItemRemoved -> edit {
                it.copy(lineItems = it.lineItems.filterNot { line -> line.key == event.key })
            }
            else -> Unit
        }
    }

    private fun editLine(key: String, transform: (EntryLineItem) -> EntryLineItem) {
        edit { current ->
            current.copy(
                lineItems = current.lineItems.map { if (it.key == key) transform(it) else it },
            )
        }
    }

    // ── Ledger switching ────────────────────────────────────────────────────

    /**
     * Switching book swaps the whole form, it does not filter it.
     *
     * The two ledgers are disjoint (Law 2) and D-06 gives each its own draft
     * slot, so the current form is flushed *immediately* rather than left to the
     * debounce -- otherwise up to 300 ms of typing is thrown away by a tap that
     * looks like navigation.
     */
    private fun switchLedger(ledger: LedgerType) {
        if (ledger == form.value.ledger) return
        viewModelScope.launch {
            val outgoing = form.value
            if (outgoing.dirty) persist(outgoing)
            // A fresh form in the other book. The outgoing one is not lost --
            // it was just written, and it is in that book's stack.
            form.value = Form(
                ledger = ledger,
                occurredAt = clock.nowMillis(),
                isRestoring = false,
                formGeneration = outgoing.formGeneration + 1,
            )
        }
    }

    // ── Drafts (BUG6) ───────────────────────────────────────────────────────

    /**
     * Loads one draft into the form.
     *
     * The form no longer resumes anything on its own (ADR-0013). It opens
     * empty and the stack shows what is unsaved, because with many drafts
     * "resume the most recent" would be the app guessing which of several
     * half-finished entries the user meant. Whatever is currently in the form
     * is flushed first so switching between drafts never costs a keystroke.
     */
    private fun openDraft(id: String) {
        viewModelScope.launch {
            val outgoing = form.value
            if (outgoing.dirty) persist(outgoing)

            val draft = drafts.find(id) ?: return@launch
            val payload = draft.payloadIfReadable(EntryDraftCodec.VERSION)
                ?.let(EntryDraftCodec::decode)
                ?: return@launch

            form.value = payload.toForm(draft, clock.nowMillis(), currency.value)
        }
    }

    /**
     * Clears the form for a new entry, leaving anything already typed in the
     * stack rather than destroying it.
     */
    private fun startNewDraft() {
        viewModelScope.launch {
            val outgoing = form.value
            if (outgoing.dirty) persist(outgoing)
            form.value = Form(
                ledger = outgoing.ledger,
                occurredAt = clock.nowMillis(),
                isRestoring = false,
                formGeneration = outgoing.formGeneration + 1,
            )
        }
    }

    private fun discardDraft(id: String) {
        viewModelScope.launch {
            form.update { it.copy(discardingDraftId = null) }
            drafts.discard(id)
            if (form.value.draftId == id) {
                form.value = Form(ledger = form.value.ledger, occurredAt = clock.nowMillis())
            }
        }
    }

    /**
     * Writes the form and remembers the id it was given.
     *
     * Keeping the id is what makes the 300 ms debounce update one row instead
     * of depositing a new draft every tick now that the unique index is gone
     * (ADR-0013).
     */
    private suspend fun persist(current: Form) {
        val saved = drafts.save(
            DraftWrite(
                id = current.draftId,
                ledger = current.ledger,
                editingEntryId = current.editingEntryId,
                payloadJson = EntryDraftCodec.encode(current.toPayload()),
                payloadVersion = EntryDraftCodec.VERSION,
            ),
        )
        form.update { if (it.draftId == null) it.copy(draftId = saved.id) else it }
    }

    /**
     * "Start fresh" over a resumed draft.
     *
     * The only place this ViewModel deletes user input, and it is reached only
     * from an explicit confirmation. That is the whole distinction D-06 draws
     * between one row per in-flight entry and a singleton that would have
     * destroyed the same data without anyone choosing it.
     */
    private fun startFresh() {
        val current = form.value
        viewModelScope.launch {
            current.draftId?.let { drafts.discard(it) }
            form.value = Form(ledger = current.ledger, occurredAt = clock.nowMillis())
        }
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    private fun save() {
        val current = form.value
        if (!current.canSave) return

        form.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            when (val result = approveTransaction(current.toRequest())) {
                is LedgerResult.Success -> {
                    // The draft goes only after the entry is committed. Clearing
                    // it first would lose the form if the approval were refused.
                    current.draftId?.let { drafts.discard(it) }
                    // And the form is emptied, not merely marked saved. Leaving
                    // the committed values in a form that is still `dirty` is
                    // what let the debounce write them back as a fresh draft.
                    form.value = Form(
                        ledger = current.ledger,
                        occurredAt = clock.nowMillis(),
                        savedEntryId = result.value.id,
                        isRestoring = false,
                        formGeneration = current.formGeneration + 1,
                    )
                }

                is LedgerResult.Failure -> form.update {
                    it.copy(isSaving = false, message = result.error.toMessage())
                }
            }
        }
    }

    private fun Form.toRequest(): ApprovalRequest = ApprovalRequest(
        ledger = ledger,
        amount = Money(amountMinor),
        occurredAt = occurredAt,
        assignment = EntryAssignment(
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            merchantId = merchantId,
            paymentMethodId = paymentMethodId,
        ),
        note = note.trim().ifEmpty { null },
        // §5.4: manual entry does not route through pending_transaction, so
        // there is nothing for source_ref_id to point at.
        lineItems = lineItems
            .filter { it.name.isNotBlank() || it.amountMinor != 0L }
            .map { NewLineItem(name = it.name, total = Money(it.amountMinor)) },
    )

    // ── State plumbing ──────────────────────────────────────────────────────

    /** Marks the form dirty, which is what arms the draft write. */
    private fun edit(transform: (Form) -> Form) {
        form.update { transform(it).copy(dirty = true) }
    }


}


// ── Pure mapping, hoisted out of the ViewModel ──────────────────────────────
//
// None of this needs an instance, and keeping it inside the class pushed it
// past detekt's 20-function ceiling. That limit measures cohesion, and the
// honest reading is that it was right: a form-to-request mapper is not a
// responsibility of the object that owns the form's lifetime.

private data class Form(
    /** Null until the first debounced write gives this form a row. */
    val draftId: String? = null,
    val editingEntryId: String? = null,
    val ledger: LedgerType = LedgerType.DEBIT,
    /** Exactly as typed. Never rewritten by the ViewModel; see `changeAmount`. */
    val amountText: String = "",
    val amountMinor: Long = 0L,
    val categoryId: String? = null,
    val subcategoryId: String? = null,
    val merchantId: String? = null,
    val paymentMethodId: String? = null,
    val note: String = "",
    val occurredAt: Long = 0L,
    val lineItems: List<EntryLineItem> = emptyList(),
    val picker: EntryPicker? = null,
    val choosingDate: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val discardingDraftId: String? = null,
    val resumedFromDraft: Boolean = false,
    val dirty: Boolean = false,
    val isRestoring: Boolean = true,
    val formGeneration: Int = 0,
    val isSaving: Boolean = false,
    val savedEntryId: String? = null,
    val message: String? = null,
) {
    val canSave: Boolean get() = amountMinor > 0L && !isSaving
}

private data class LedgerScoped(
    val tree: List<CategoryTree>,
    val merchants: List<Merchant>,
    val paymentMethods: List<PaymentMethod>,
    val combos: List<EntryCombo>,
    val unsaved: List<EntryDraft>,
)

private fun Form.toUiState(
    scoped: LedgerScoped,
    currencyCode: String,
    now: Long,
): EntryUiState {
    // The form's own draft is not offered back to it as something to open.
    val unsavedCards = scoped.unsaved
        .filter { it.id != draftId }
        .mapNotNull { it.toCard(currencyCode, now) }

    return EntryUiState(
        ledger = ledger,
        amountText = amountText,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        categoryId = categoryId,
        subcategoryId = subcategoryId,
        merchantId = merchantId,
        paymentMethodId = paymentMethodId,
        note = note,
        occurredAt = occurredAt,
        lineItems = lineItems,
        tree = scoped.tree,
        merchants = scoped.merchants,
        paymentMethods = scoped.paymentMethods,
        combos = scoped.combos.mapNotNull { it.toChip(scoped) },
        unsaved = unsavedCards,
        openDraftId = draftId,
        discardingDraft = unsavedCards.firstOrNull { it.id == discardingDraftId },
        picker = picker,
        choosingDate = choosingDate,
        confirmingDiscard = confirmingDiscard,
        resumedFromDraft = resumedFromDraft,
        isRestoring = isRestoring,
        formGeneration = formGeneration,
        isSaving = isSaving,
        savedEntryId = savedEntryId,
        message = message,
    )
}

/**
 * A combination, labelled with what it will fill in.
 *
 * Dropped when its category no longer resolves: a chip naming a deleted
 * category would fill the form with an id the approval then refuses, which
 * is a worse experience than the chip simply not being there.
 */
private fun EntryCombo.toChip(scoped: LedgerScoped): EntryComboChip? {
    val category = scoped.tree.firstOrNull { it.parent.id == categoryId }?.parent ?: return null
    val merchant = scoped.merchants.firstOrNull { it.id == merchantId }?.canonicalName
    val subcategory = scoped.tree
        .flatMap { it.children }
        .firstOrNull { it.id == subcategoryId }
        ?.name

    return EntryComboChip(
        label = listOfNotNull(merchant ?: category.name, subcategory).joinToString(" · "),
        categoryId = categoryId,
        subcategoryId = subcategoryId,
        merchantId = merchantId,
        paymentMethodId = paymentMethodId,
    )
}

private fun EntryDraftPayload.toForm(draft: EntryDraft, now: Long, currencyCode: String) = Form(
    draftId = draft.id,
    editingEntryId = draft.editingEntryId,
    ledger = draft.ledger,
    // Re-derived rather than stored. The payload keeps the *value*, so a draft
    // written before the keypad was removed still restores correctly and
    // `payload_version` stays at 1 -- nobody's in-flight entry is orphaned by
    // an input-method change.
    amountText = amountMinor.asAmountText(currencyCode),
    amountMinor = amountMinor,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    note = note,
    occurredAt = if (occurredAt == 0L) now else occurredAt,
    lineItems = lineItems.map {
        EntryLineItem(
            key = it.key,
            name = it.name,
            amountText = it.amountMinor.asAmountText(currencyCode),
            amountMinor = it.amountMinor,
        )
    },
    // A resumed draft is already worth persisting: the user may switch
    // ledger or close the app without touching a field, and the row they
    // came back to must not be the one thing that fails to survive.
    dirty = true,
    resumedFromDraft = true,
    isRestoring = false,
)

private fun Form.toPayload() = EntryDraftPayload(
    amountMinor = amountMinor,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    note = note,
    occurredAt = occurredAt,
    lineItems = lineItems.map { DraftLineItem(it.key, it.name, it.amountMinor) },
)

/**
 * Everything the user has typed.
 *
 * Separate from [EntryUiState] because that class also carries the taxonomy
 * the pickers read, which comes from the database and must never be written
 * into a draft payload.
 */

/**
 * A draft, summarised for the stack.
 *
 * Decoded here rather than in `:core:data` because the payload's shape is this
 * screen's business -- the repository carries it as an opaque string on purpose.
 * A draft this build cannot read is dropped from the stack rather than shown as
 * a blank card, and the row stays on disk untouched (§6.1.2).
 */
private fun EntryDraft.toCard(currencyCode: String, now: Long): EntryDraftCard? {
    val payload = payloadIfReadable(EntryDraftCodec.VERSION)?.let(EntryDraftCodec::decode)
        ?: return null

    return EntryDraftCard(
        id = id,
        amountMinor = payload.amountMinor,
        currencyCode = currencyCode,
        note = payload.note.ifBlank { null },
        lineItemCount = payload.lineItems.count { it.name.isNotBlank() },
        updatedAt = updatedAt,
        age = relativeAge(now - updatedAt),
    )
}

/**
 * "just now", "12m", "3h", "2d" -- coarse on purpose.
 *
 * A draft's age answers one question ("is this from this shopping trip or last
 * week?"), and a precise timestamp on a card this size is noise. Capped at the
 * 30-day retention, past which the sweep has taken it anyway.
 */
private fun relativeAge(elapsedMillis: Long): String {
    val minutes = elapsedMillis / MILLIS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        minutes < 1L -> "just now"
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        hours < HOURS_PER_DAY -> "${hours}h"
        else -> "${days}d"
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/**
 * A stored amount, rendered back into the field's text.
 *
 * Zero is empty rather than "0.00" so a restored draft with no amount shows the
 * placeholder, exactly as a fresh form does.
 */
private fun Long.asAmountText(currencyCode: String): String =
    if (this == 0L) "" else MoneyFormat.plain(this, currencyCode)

/**
 * BUG6's window (§6.1.2). Long enough to coalesce a burst of keystrokes into one
 * upsert, short enough that a process death costs a fragment of a second of
 * typing rather than a form.
 */
private const val DRAFT_DEBOUNCE_MS = 300L

private const val STOP_TIMEOUT_MS = 5_000L
private const val COMBO_LIMIT = 8

/**
 * A refusal, in a sentence the user can act on.
 *
 * Every case is reachable: the taxonomy can change under an open form, so
 * "that category is gone" is not a theoretical branch. Exhaustive over the
 * sealed type with no `else`, so a new [LedgerError] cannot ship without
 * someone deciding what the form says about it.
 */
private fun LedgerError.toMessage(): String = when (this) {
    LedgerError.AmountNotPositive -> "Enter an amount first."
    LedgerError.SubcategoryWithoutCategory -> "Choose a category before a subcategory."
    is LedgerError.UnknownCategory -> "That category is no longer available. Choose another."
    is LedgerError.CategoryNotInLedger -> "That category belongs to the other ledger."
    is LedgerError.SubcategoryNotUnderCategory ->
        "That subcategory is not inside the category you chose."
    is LedgerError.UnknownMerchant -> "That merchant is no longer available."
    is LedgerError.UnknownPaymentMethod -> "That payment method is no longer available."
    LedgerError.BaseCurrencyMissing -> "Your ledger has no base currency yet. Finish setup first."
    is LedgerError.ForeignCurrencyIsBase -> "A foreign amount needs a different currency."
    LedgerError.ForeignRateNotPositive -> "Enter an exchange rate above zero."
    is LedgerError.LineItemNameBlank -> "Line ${position + 1} needs a name."
}
