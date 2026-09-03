package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.Money
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One observed payment to a merchant. [localDate] is days since epoch.
 */
public data class Occurrence(
    val localDate: Int,
    val amount: Money,
)

/**
 * A merchant whose payments look like a subscription (`SPEC.md` §5.6, A8).
 *
 * [intervalDays] is the mean gap, rounded — what the user reads as "about every
 * 30 days". [regularity] is σ/μ over the gaps: **lower is more regular**, and
 * §5.6 sets the threshold at 0.25.
 *
 * [nextExpected] is the projection A10's runway sums. It is the last occurrence
 * plus the mean interval, which is a forecast and is labelled as one — this app
 * does not have a schedule, it has a pattern.
 */
public data class RecurringMerchant(
    val merchantId: String,
    val name: String,
    val occurrences: Int,
    val intervalDays: Int,
    val regularity: Double,
    val typicalAmount: Money,
    val lastSeen: Int,
    val nextExpected: Int,
)

/**
 * Interval clustering over a merchant's payment dates (`SPEC.md` §5.6).
 *
 * **A pure function on purpose.** It is arithmetic with a correct answer, so it
 * is unit-tested on the JVM rather than inferred from a screen — the same
 * argument `LfAxisTicks` makes. It also means the rule §5.6 states can be read
 * off the code: at least [MINIMUM_OCCURRENCES] payments, and a coefficient of
 * variation below [MAXIMUM_IRREGULARITY].
 *
 * **σ/μ, and Law 3 is not in the way.** Law 3 bans `Float`/`Double` for a
 * *monetary amount*. The gaps here are day counts and their ratio is a
 * dimensionless statistic — `CLAUDE.md` §2 names σ/μ explicitly as legitimately
 * real-valued. The money in [RecurringMerchant.typicalAmount] stays `Long`.
 *
 * **The median, not the mean, for the typical amount.** A subscription that
 * changed price once, or a single annual charge among monthlies, drags a mean
 * somewhere the user has never actually paid. The median is always a figure
 * that really appeared on a statement.
 */
public object RecurringDetection {

    /** §5.6: "≥3 occurrences". Two payments define one gap, which has no spread. */
    public const val MINIMUM_OCCURRENCES: Int = 3

    /** §5.6: "σ/μ < 0.25". */
    public const val MAXIMUM_IRREGULARITY: Double = 0.25

    /**
     * Anything longer than this is not a subscription anyone is tracking.
     *
     * Two purchases fourteen months apart have a small σ/μ if there are three
     * of them, and calling that "recurring" is technically true and useless.
     */
    private const val MAXIMUM_INTERVAL_DAYS = 400

    /**
     * @param occurrences one merchant's payments, in any order.
     * @return null when the merchant does not qualify.
     */
    public fun detect(
        merchantId: String,
        name: String,
        occurrences: List<Occurrence>,
    ): RecurringMerchant? {
        val dates = occurrences.map { it.localDate }.sorted()
        // Two payments on the same day are one event as far as a subscription
        // is concerned, so a zero gap is dropped rather than counted -- left in,
        // it drags the mean down and the spread up, and a real monthly charge
        // with one duplicate stops being detected.
        val gaps = dates.zipWithNext { a, b -> b - a }.filter { it > 0 }
        if (!qualifies(occurrences.size, gaps)) return null

        val mean = gaps.average()
        val variance = gaps.sumOf { gap -> (gap - mean) * (gap - mean) } / gaps.size
        val regularity = sqrt(variance) / mean
        if (regularity >= MAXIMUM_IRREGULARITY) return null

        val interval = mean.roundToInt()
        val lastSeen = dates.last()
        return RecurringMerchant(
            merchantId = merchantId,
            name = name,
            occurrences = dates.size,
            intervalDays = interval,
            regularity = regularity,
            typicalAmount = medianAmount(occurrences),
            lastSeen = lastSeen,
            nextExpected = lastSeen + interval,
        )
    }

    /**
     * A10's runway: detected charges expected to fall on or before [through]
     * (`SPEC.md` §5.6).
     *
     * **A projection, and only ever one step ahead.** A merchant is counted at
     * most once even if two intervals would fit inside the window, because the
     * second would be a forecast built on a forecast. The honest statement is
     * "these are due", not "this is what the month will cost".
     *
     * Charges whose next date has already passed are excluded: a subscription
     * that was due last week and has not appeared is a question about the
     * ingest, not money still to leave the account.
     */
    public fun runway(
        detected: List<RecurringMerchant>,
        today: Int,
        through: Int,
    ): List<RecurringMerchant> = detected
        .filter { it.nextExpected in today..through }
        .sortedBy { it.nextExpected }

    /**
     * The structural gates, before the statistics are worth computing.
     *
     * Gathered into one predicate rather than three early returns — same rules,
     * and it keeps [detect] to a single guard clause plus the σ/μ test that is
     * the actual subject of the function.
     */
    private fun qualifies(occurrenceCount: Int, gaps: List<Int>): Boolean {
        if (occurrenceCount < MINIMUM_OCCURRENCES) return false
        if (gaps.size < MINIMUM_OCCURRENCES - 1) return false
        val mean = gaps.average()
        return mean > 0.0 && mean <= MAXIMUM_INTERVAL_DAYS
    }

    private fun medianAmount(occurrences: List<Occurrence>): Money {
        val sorted = occurrences.map { it.amount.minor }.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            Money(sorted[middle])
        } else {
            // Integer mean of the two middles, rounding half away from zero.
            // Never a `Double` round-trip: this is money (Law 3).
            val low = sorted[middle - 1]
            val high = sorted[middle]
            val sum = low + high
            Money(sum / 2 + if (abs(sum) % 2 == 1L) sum.compareTo(0L) else 0)
        }
    }
}
