package com.ledgerflow.core.common.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * When a captured message's transaction is treated as having happened.
 *
 * **Every bank SMS in the corpus states a date and no clock.** All twenty
 * fixtures in `testdata/sms/`, including the four real ones off the owner's
 * phone, carry `On 27/08/26` and nothing more, so the parser resolves them
 * through `LocalDate.atStartOfDay` and `occurred_at` lands on **midnight**.
 *
 * That is fine as a stored value and wrong as a displayed one — rendered
 * literally, essentially every SMS-derived row in the app reads `12:00 am`
 * (SPEC.md §16, the `occurred_at` note).
 *
 * **This is the one definition of the fix**, and it lives here rather than in
 * the formatter because two things need it and they must not disagree: the
 * stamp the user reads, and the key the "Unsaved" section sorts by. Deriving
 * the display from a blend while sorting on the raw value produced a list whose
 * visible times ran 2:49 pm, 4:24 pm, 2:47 pm — found on the device, and
 * invisible to every test that checked either half alone.
 *
 * **Nothing is written.** `occurred_at` still holds midnight; this decides what
 * is drawn and what is ordered. Changing the column's meaning is a separate
 * decision about ledger data and has not been taken.
 */
public object OccurredAt {

    /**
     * The message's day, with the clock it never stated filled in from capture.
     *
     * Returns [occurredAt] unchanged when the message *did* state a time.
     *
     * **The date is never taken from [capturedAt].** A re-triaged message
     * (SPEC.md §16 Q14 re-admits previously-rejected SMS) can be captured days
     * after it was sent, and showing the wrong *day* is worse than an
     * approximate time — the day is what a user reconciles against a bank
     * statement.
     *
     * Exact midnight is read as "no clock" because that is what it means in
     * practice: a bank does not state `00:00`, and a payment landing on the
     * millisecond of midnight is not a case worth preferring over every real
     * SMS in the corpus.
     */
    public fun effective(
        occurredAt: Long,
        capturedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val stated = Instant.ofEpochMilli(occurredAt).atZone(zone)
        if (stated.toLocalTime() != LocalTime.MIDNIGHT) return occurredAt

        val captureTime = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalTime()
        return stated.with(captureTime).toInstant().toEpochMilli()
    }

    /**
     * The same, for a candidate that may have no stated date at all.
     *
     * A message that named no day still happened, and capture time is a fact
     * about something real rather than a guess — the same fallback
     * `ApprovePendingUseCase` applies when it builds the ledger entry.
     */
    public fun effectiveOrCapture(
        occurredAt: Long?,
        capturedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = occurredAt?.let { effective(it, capturedAt, zone) } ?: capturedAt
}
