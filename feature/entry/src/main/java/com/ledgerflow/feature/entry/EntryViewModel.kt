package com.ledgerflow.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSlot
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
 * **The amount is a `Long` from the first keystroke.** The keypad appends
 * digits in minor units and this holds the running total; there is no string
 * form of the amount at any point, so there is nothing for a later refactor to
 * parse into a `Double` (Law 3).
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
            ) { tree, merchantList, methods, combos ->
                LedgerScoped(tree, merchantList, methods, combos)
            }
        }

    public val state: StateFlow<EntryUiState> =
        combine(form, ledgerScoped, currency) { form, scoped, currencyCode ->
            form.toUiState(scoped, currencyCode)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            EntryUiState(occurredAt = clock.nowMillis()),
        )

    init {
        viewModelScope.launch {
            currency.value = ledgerRepository.baseCurrency() ?: EntryUiState.DEFAULT_CURRENCY
        }
        viewModelScope.launch { resume(LedgerType.DEBIT) }
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
            is EntryEvent.DigitsPressed,
            EntryEvent.BackspacePressed,
            -> onAmountEvent(event)

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
            is EntryEvent.LineItemDigitsChanged,
            is EntryEvent.LineItemRemoved,
            -> onLineItemEvent(event)

            EntryEvent.SaveRequested -> save()
            EntryEvent.DiscardRequested -> form.update { it.copy(confirmingDiscard = true) }
            EntryEvent.DiscardConfirmed -> startFresh()
            EntryEvent.DiscardDismissed -> form.update { it.copy(confirmingDiscard = false) }

            EntryEvent.MessageDismissed -> form.update { it.copy(message = null) }
            EntryEvent.ResumeNoticeDismissed -> form.update { it.copy(resumedFromDraft = false) }
        }
    }

    // ── Amount ──────────────────────────────────────────────────────────────

    private fun onAmountEvent(event: EntryEvent) {
        when (event) {
            is EntryEvent.DigitsPressed -> edit { it.copy(amountMinor = it.amountMinor.append(event.digits)) }
            EntryEvent.BackspacePressed -> edit { it.copy(amountMinor = it.amountMinor / RADIX) }
            else -> Unit
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
            is EntryEvent.LineItemDigitsChanged -> editLine(event.key) {
                it.copy(amountMinor = event.digits.toMinorUnits())
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
            resume(ledger)
        }
    }

    // ── Drafts (BUG6) ───────────────────────────────────────────────────────

    private suspend fun resume(ledger: LedgerType) {
        val draft = drafts.find(DraftSlot(ledger))
        val payload = draft?.payloadIfReadable(EntryDraftCodec.VERSION)?.let(EntryDraftCodec::decode)

        form.update { current ->
            // **Never overwrite input the user has already given.** The draft
            // read is asynchronous, so between the form opening and this
            // returning there is a window in which someone can start typing --
            // and a restore that landed on top of their first keystrokes would
            // be BUG6 committed by BUG6's own countermeasure.
            //
            // The window is a single indexed row read and nobody is fast enough
            // in practice, which is exactly why it is guarded rather than
            // reasoned about. Their typing wins: it is the fresher intent, and
            // the draft row itself is untouched until their own next debounce.
            //
            // A ledger switch is not this case. It calls resume() while the
            // form still holds the *outgoing* book, so the ledgers differ and
            // the replacement is what it asked for.
            if (current.dirty && current.ledger == ledger) {
                current
            } else {
                payload?.toForm(ledger, clock.nowMillis())
                    ?: Form(ledger = ledger, occurredAt = clock.nowMillis())
            }
        }
    }

    private suspend fun persist(current: Form) {
        drafts.save(
            slot = DraftSlot(current.ledger),
            payloadJson = EntryDraftCodec.encode(current.toPayload()),
            payloadVersion = EntryDraftCodec.VERSION,
        )
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
        val ledger = form.value.ledger
        viewModelScope.launch {
            drafts.discard(DraftSlot(ledger))
            form.value = Form(ledger = ledger, occurredAt = clock.nowMillis())
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
                    drafts.discard(DraftSlot(current.ledger))
                    // And the form is emptied, not merely marked saved. Leaving
                    // the committed values in a form that is still `dirty` is
                    // what let the debounce write them back as a fresh draft.
                    form.value = Form(
                        ledger = current.ledger,
                        occurredAt = clock.nowMillis(),
                        savedEntryId = result.value.id,
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
    val ledger: LedgerType = LedgerType.DEBIT,
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
    val resumedFromDraft: Boolean = false,
    val dirty: Boolean = false,
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
)

private fun Form.toUiState(scoped: LedgerScoped, currencyCode: String) = EntryUiState(
    ledger = ledger,
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
    picker = picker,
    choosingDate = choosingDate,
    confirmingDiscard = confirmingDiscard,
    resumedFromDraft = resumedFromDraft,
    isSaving = isSaving,
    savedEntryId = savedEntryId,
    message = message,
)

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

private fun EntryDraftPayload.toForm(ledger: LedgerType, now: Long) = Form(
    ledger = ledger,
    amountMinor = amountMinor,
    categoryId = categoryId,
    subcategoryId = subcategoryId,
    merchantId = merchantId,
    paymentMethodId = paymentMethodId,
    note = note,
    occurredAt = if (occurredAt == 0L) now else occurredAt,
    lineItems = lineItems.map { EntryLineItem(it.key, it.name, it.amountMinor) },
    // A resumed draft is already worth persisting: the user may switch
    // ledger or close the app without touching a field, and the row they
    // came back to must not be the one thing that fails to survive.
    dirty = true,
    resumedFromDraft = true,
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

/** Appends keypad digits right-to-left, refusing anything past the ceiling. */
private fun Long.append(digits: String): Long = digits.fold(this) { total, character ->
    val next = total * RADIX + (character - '0')
    if (next > MAX_AMOUNT_MINOR) total else next
}

/** A typed line-item amount: digits only, read as minor units. */
private fun String.toMinorUnits(): Long = filter(Char::isDigit)
    .takeLast(MAX_AMOUNT_DIGITS)
    .fold(0L) { total, character -> total * RADIX + (character - '0') }

/**
 * BUG6's window (§6.1.2). Long enough to coalesce a burst of keystrokes into one
 * upsert, short enough that a process death costs a fragment of a second of
 * typing rather than a form.
 */
private const val DRAFT_DEBOUNCE_MS = 300L

private const val STOP_TIMEOUT_MS = 5_000L
private const val COMBO_LIMIT = 8
private const val RADIX = 10L

/**
 * Ceiling on a typed amount: 9,999,999,999.99 in a two-decimal currency.
 *
 * Not a product limit -- it is what stops `amount * 10 + digit` overflowing a
 * `Long` after nineteen taps, which would silently turn a large expense into a
 * negative one.
 */
private const val MAX_AMOUNT_MINOR = 999_999_999_999L
private const val MAX_AMOUNT_DIGITS = 12

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
