package com.ledgerflow.core.domain.recurring

import java.time.LocalDate

/**
 * When an item is due (V2-PLAN §4.2). Arithmetic with a correct answer, so it
 * is unit-tested rather than read off a screen.
 *
 * **Monthly, quarterly and yearly count from the anchor, never from the last
 * due date**: an item anchored on the 31st falls on the last day of a shorter
 * month (30 Apr, 28 or 29 Feb) and is back on the 31st the next month that
 * has one (owner, 2026-11-09). Stepping from the last due date instead would
 * leave it on the 28th for ever after February.
 */
public object RecurringSchedule {

    /** A due date is matched from this many days before it… */
    public const val MATCH_DAYS_BEFORE: Int = 3

    /** …to this many after, and only then may it become an Inbox candidate. */
    public const val MATCH_DAYS_AFTER: Int = 2

    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_QUARTER = 3
    private const val MONTHS_PER_YEAR = 12

    /** The first due date after [dueDate]. */
    public fun following(cadence: Cadence, everyDays: Int?, anchor: Int, dueDate: Int): Int = when (cadence) {
        Cadence.WEEKLY -> dueDate + DAYS_PER_WEEK
        Cadence.EVERY_N_DAYS -> dueDate + requireNotNull(everyDays) { "EVERY_N_DAYS without a number of days" }
        Cadence.MONTHLY -> nextByMonths(anchor, dueDate, 1)
        Cadence.QUARTERLY -> nextByMonths(anchor, dueDate, MONTHS_PER_QUARTER)
        Cadence.YEARLY -> nextByMonths(anchor, dueDate, MONTHS_PER_YEAR)
    }

    /** The first day [dueDate]'s match window can no longer be reached by a capture: the day after it closes. */
    public fun windowClosesAfter(dueDate: Int): Int = dueDate + MATCH_DAYS_AFTER

    private fun nextByMonths(anchor: Int, dueDate: Int, step: Int): Int {
        val start = LocalDate.ofEpochDay(anchor.toLong())
        var k = 1L
        while (true) {
            // `plusMonths` clamps to the month's last day, from the anchor each time.
            val candidate = start.plusMonths(k * step).toEpochDay().toInt()
            if (candidate > dueDate) return candidate
            k++
        }
    }
}
