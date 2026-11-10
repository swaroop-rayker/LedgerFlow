package com.ledgerflow.feature.tools.recurring

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.recurring.Cadence
import com.ledgerflow.core.domain.recurring.OccurrenceOutcome
import com.ledgerflow.core.domain.recurring.RecurringItem
import com.ledgerflow.core.domain.recurring.RecurringMatch
import com.ledgerflow.core.domain.usecase.RunRecurringPassUseCase
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.testing.inbox.RecordingInboxNotifier
import com.ledgerflow.core.testing.recurring.FakeRecurringRepository
import com.ledgerflow.core.testing.recurring.RecordingRecurringReminders
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The daily recurring pass (V2-PLAN §4.2; owner 2026-11-09): matched, then
 * reminded, then (once the window has closed with nothing captured) an Inbox
 * candidate, or skipped for an item that only reminds. Never more than one
 * reminder or candidate for a due date.
 */
class RunRecurringPassUseCaseTest {

    private val repository = FakeRecurringRepository()
    private val reminders = RecordingRecurringReminders()
    private val inbox = RecordingInboxNotifier()

    private val due = LocalDate.of(2026, 12, 1).toEpochDay().toInt()

    private fun on(day: Int) = RunRecurringPassUseCase(
        repository,
        reminders,
        inbox,
        Clock {
            LocalDate.ofEpochDay(day.toLong()).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        },
    )

    private fun rent(toInbox: Boolean = true, remindDaysBefore: Int = 1) = RecurringItem(
        id = "rent", name = "Rent", ledger = LedgerType.DEBIT, amount = Money(25_000_00), amountVaries = false,
        merchantId = "landlord", categoryId = null, subcategoryId = null, cadence = Cadence.MONTHLY,
        everyDays = null, anchorDate = due, nextDue = due, remindDaysBefore = remindDaysBefore,
        toInbox = toInbox, paused = false,
    ).also { repository.items.value = listOf(it) }

    private fun nextDue() = repository.items.value.single().nextDue

    @Test
    fun tooEarly_doesNothing() = runBlocking<Unit> {
        rent()

        on(due - 4)()

        assertThat(reminders.posted).isEmpty()
        assertThat(nextDue()).isEqualTo(due)
    }

    @Test
    fun theDayBefore_remindsOnce() = runBlocking<Unit> {
        rent()

        on(due - 1)()
        on(due - 1)()
        on(due)()

        assertThat(reminders.posted).containsExactly("rent" to due)
        assertThat(repository.occurrences["rent" to due]?.outcome).isEqualTo(OccurrenceOutcome.WAITING)
        assertThat(nextDue()).isEqualTo(due)
    }

    @Test
    fun somethingCapturedEarly_matchesAndNeedsNoReminder() = runBlocking<Unit> {
        rent()
        repository.matches["rent" to due] = RecurringMatch.Entry("e-1")

        on(due - 2)()

        assertThat(reminders.posted).isEmpty()
        assertThat(repository.occurrences["rent" to due]?.outcome).isEqualTo(OccurrenceOutcome.MATCHED)
        assertThat(repository.occurrences["rent" to due]?.entryId).isEqualTo("e-1")
        assertThat(nextDue()).isEqualTo(LocalDate.of(2027, 1, 1).toEpochDay().toInt())
        assertThat(repository.candidates).isEmpty()
    }

    @Test
    fun insideTheWindow_waitsForACapture() = runBlocking<Unit> {
        rent()

        on(due + 2)()

        assertThat(repository.candidates).isEmpty()
        assertThat(nextDue()).isEqualTo(due)
    }

    @Test
    fun afterTheWindow_withNothingCaptured_writesOneCandidate_andAnnouncesIt() = runBlocking<Unit> {
        rent()

        on(due + 3)()
        on(due + 3)()

        assertThat(repository.candidates).containsExactly("rent" to due)
        assertThat(inbox.notified).containsExactly("recurring:rent:$due")
        assertThat(repository.occurrences["rent" to due]?.outcome).isEqualTo(OccurrenceOutcome.CANDIDATE)
        assertThat(nextDue()).isEqualTo(LocalDate.of(2027, 1, 1).toEpochDay().toInt())
    }

    @Test
    fun aReminderOnlyItem_isSkipped_neverACandidate() = runBlocking<Unit> {
        rent(toInbox = false)

        on(due + 3)()

        assertThat(repository.candidates).isEmpty()
        assertThat(inbox.notified).isEmpty()
        assertThat(repository.occurrences["rent" to due]?.outcome).isEqualTo(OccurrenceOutcome.SKIPPED)
    }

    @Test
    fun aCandidateThatCannotBeWritten_isTriedAgainNextTime() = runBlocking<Unit> {
        rent()
        repository.candidatesWork = false

        on(due + 3)()

        assertThat(nextDue()).isEqualTo(due)
        assertThat(repository.occurrences["rent" to due]).isNull()
    }

    /** Off since before December: four due dates have closed by 5 March, none reminded, April ahead. */
    @Test
    fun aPhoneThatWasOff_catchesUp() = runBlocking<Unit> {
        rent()
        val march = LocalDate.of(2027, 3, 5).toEpochDay().toInt()

        on(march)()

        assertThat(repository.candidates.map { LocalDate.ofEpochDay(it.second.toLong()).monthValue })
            .containsExactly(12, 1, 2, 3).inOrder()
        assertThat(reminders.posted).isEmpty()
        assertThat(nextDue()).isEqualTo(LocalDate.of(2027, 4, 1).toEpochDay().toInt())
    }

    @Test
    fun aRemindedDueDate_keepsItsReminderStamp_whenResolved() = runBlocking<Unit> {
        rent()
        on(due - 1)()

        on(due + 3)()

        assertThat(repository.occurrences["rent" to due]?.remindedAt).isNotNull()
        assertThat(repository.occurrences["rent" to due]?.outcome).isEqualTo(OccurrenceOutcome.CANDIDATE)
    }
}
