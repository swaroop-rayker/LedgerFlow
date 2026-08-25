package com.ledgerflow.feature.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.common.time.LocalDates
import com.ledgerflow.core.domain.ledger.DraftRepository
import com.ledgerflow.core.domain.ledger.DraftSummary
import com.ledgerflow.core.domain.ledger.LedgerError
import com.ledgerflow.core.domain.ledger.LedgerRepository
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.usecase.DeleteEntryUseCase
import com.ledgerflow.core.model.LedgerListItem
import com.ledgerflow.core.model.LedgerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The two books, read (SPEC.md §5.5, §9.3).
 *
 * This class exists because the screen it drives did not have one, which is the
 * whole of BUG10: `LedgerScreen` was a hardcoded empty state that told the user
 * their entries would appear there and had no way to show them. The save path
 * was never at fault — `ApproveTransactionUseCase` commits, and
 * `LedgerRepositoryInstrumentedTest` has covered that since P1.
 *
 * Every read is a cold flow off the repository, so an approval *or a deletion*
 * lands in the list because the database changed rather than because anything
 * told the screen to refresh. Room invalidates the `PagingSource`, the `Pager`
 * builds a new one, and the list updates — including while this screen is on
 * top, which is what makes it safe for either to happen anywhere.
 *
 * Deletion goes through [DeleteEntryUseCase] rather than the repository
 * directly. That is not ceremony: it is the single audited door
 * `LedgerSingleWriterTest` enforces for the one operation that can rewrite past
 * totals.
 */
@HiltViewModel
public class LedgerViewModel @Inject constructor(
    private val ledger: LedgerRepository,
    private val drafts: DraftRepository,
    private val deleteEntry: DeleteEntryUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** Everything the user is doing; everything else is the database. */
    private val local = MutableStateFlow(LocalState())

    init {
        // A draft has no currency column -- §5.8 stamps one at approval -- so
        // the screen formats draft amounts in the install's base currency,
        // which is the currency they will be saved in.
        viewModelScope.launch {
            val code = ledger.baseCurrency() ?: return@launch
            local.update { it.copy(currencyCode = code) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val book: Flow<LedgerType> = local.map { it.ledger }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val hasEntries: Flow<Boolean> = book.flatMapLatest { ledger.observeHasEntries(it) }

    /**
     * The unsaved entries in the book on screen.
     *
     * A plain list, not a `PagingData`: drafts are bounded by §6.1.2's 30-day
     * sweep and by how many entries a person leaves half-typed at once, so
     * paging them would be machinery with nothing to do.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pending: Flow<List<DraftSummary>> =
        book.flatMapLatest { drafts.observeSummaries(it) }

    public val state: StateFlow<LedgerUiState> =
        combine(local, hasEntries, pending) { local, hasAny, drafts ->
            uiState(local, hasAny, drafts)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            // `isLoaded = false`: the seed knows nothing yet, and the screen
            // has to be able to tell that from "this book is empty".
            initialValue = LedgerUiState(
                today = LocalDates.of(clock.nowMillis()),
                windowDays = LedgerRepository.LIST_WINDOW_DAYS,
            ),
        )

    /**
     * The selected book's entries, paged and windowed.
     *
     * `flatMapLatest` over the *book alone* — `distinctUntilChanged` by way of
     * `map` — so opening a confirmation dialog does not rebuild the pager and
     * throw the user back to the top of the list mid-question.
     *
     * Switching the tab is switching *partitions*, and only one is on screen,
     * so keeping the other book's query live would pay for rows nobody is
     * looking at. Neither shape can mix the books —
     * [LedgerRepository.observeEntries] takes a [LedgerType] and there is no
     * variant returning both (Law 2).
     *
     * The window is recomputed per emission rather than captured once at
     * construction, so a session left open across midnight re-bounds on the
     * next tab switch instead of pinning yesterday's boundary.
     *
     * `cachedIn(viewModelScope)` is load-bearing, not hygiene. Without it the
     * pages are re-fetched from row zero on every configuration change and on
     * every recomposition that re-collects, and `LazyPagingItems` would throw
     * on a second collector.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val entries: Flow<PagingData<LedgerListItem>> = book
        .flatMapLatest { ledger.observeEntries(it, since = windowStart()) }
        .cachedIn(viewModelScope)

    public fun onEvent(event: LedgerEvent) {
        when (event) {
            is LedgerEvent.LedgerSelected -> local.update { it.copy(ledger = event.ledger) }

            is LedgerEvent.DeleteRequested -> ask(
                LedgerConfirmation.DeleteEntry(event.id, event.label),
            )
            is LedgerEvent.DiscardRequested -> ask(
                LedgerConfirmation.DiscardDraft(event.id, event.label),
            )
            LedgerEvent.ConfirmationAccepted -> accept()
            LedgerEvent.ConfirmationDismissed -> local.update { it.copy(confirmation = null) }

            LedgerEvent.MessageDismissed -> local.update { it.copy(message = null) }
        }
    }

    private fun ask(confirmation: LedgerConfirmation) {
        local.update { it.copy(confirmation = confirmation) }
    }

    /**
     * Answers whichever question is open.
     *
     * Exhaustive over the sealed type with no `else`, so a third kind of
     * confirmation cannot ship without someone deciding what accepting it
     * does.
     */
    private fun accept() {
        when (val confirmation = local.value.confirmation) {
            null -> Unit
            is LedgerConfirmation.DeleteEntry -> delete(confirmation)
            is LedgerConfirmation.DiscardDraft -> discard(confirmation)
        }
    }

    /**
     * Removes the entry, then closes the question.
     *
     * The dialog closes either way. Unlike a taxonomy rename there is nothing
     * to correct and nothing the user typed to preserve — a refusal here means
     * the row is already gone, so leaving the question open would be asking
     * again about something that no longer exists.
     */
    private fun delete(confirmation: LedgerConfirmation.DeleteEntry) {
        viewModelScope.launch {
            val outcome = deleteEntry(local.value.ledger, confirmation.id)
            local.update {
                it.copy(
                    confirmation = null,
                    message = (outcome as? LedgerResult.Failure)?.error?.toMessage(),
                )
            }
        }
    }

    /**
     * Throws away an unsaved entry.
     *
     * No result to inspect: `discard` is a delete by id, and a draft that is
     * already gone is the outcome the user asked for. The list re-emits from
     * the database, so the row leaves because the row left.
     */
    private fun discard(confirmation: LedgerConfirmation.DiscardDraft) {
        viewModelScope.launch {
            drafts.discard(confirmation.id)
            local.update { it.copy(confirmation = null) }
        }
    }

    private fun uiState(
        local: LocalState,
        hasAnyEntries: Boolean,
        pending: List<DraftSummary>,
    ) = LedgerUiState(
        ledger = local.ledger,
        today = LocalDates.of(clock.nowMillis()),
        hasAnyEntries = hasAnyEntries,
        windowDays = LedgerRepository.LIST_WINDOW_DAYS,
        currencyCode = local.currencyCode,
        pending = pending,
        // Every caller of this builds state from a real emission; the seed
        // below is the only state that is not loaded.
        isLoaded = true,
        confirmation = local.confirmation,
        message = local.message,
    )

    /**
     * The oldest day the list shows.
     *
     * A bound on the *view*. Nothing is deleted at this boundary — see
     * [LedgerRepository.LIST_WINDOW_DAYS].
     */
    private fun windowStart(): Int =
        LocalDates.of(clock.nowMillis()) - LedgerRepository.LIST_WINDOW_DAYS

    private data class LocalState(
        val ledger: LedgerType = LedgerType.DEBIT,
        val currencyCode: String = DEFAULT_CURRENCY,
        val confirmation: LedgerConfirmation? = null,
        val message: String? = null,
    )

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The refusals this screen can produce, as sentences.
 *
 * Only [LedgerError.EntryNotFound] is reachable from here — the rest belong to
 * the approval path — but the `when` stays exhaustive so a new error cannot be
 * added without someone deciding what the Ledger should say about it.
 */
internal fun LedgerError.toMessage(): String = when (this) {
    is LedgerError.EntryNotFound -> "That entry is already gone."

    LedgerError.AmountNotPositive,
    LedgerError.SubcategoryWithoutCategory,
    is LedgerError.UnknownCategory,
    is LedgerError.CategoryNotInLedger,
    is LedgerError.SubcategoryNotUnderCategory,
    is LedgerError.UnknownMerchant,
    is LedgerError.UnknownPaymentMethod,
    LedgerError.BaseCurrencyMissing,
    is LedgerError.ForeignCurrencyIsBase,
    LedgerError.ForeignRateNotPositive,
    is LedgerError.LineItemRefusal,
    -> "That didn't work. Try again."
}
