package com.ledgerflow.feature.ledger

/**
 * The recency bands the Ledger list groups under (SPEC.md §5.5).
 *
 * **Rolling windows, not calendar ones,** and the distinction is load-bearing
 * rather than pedantic. The list shows the last
 * [com.ledgerflow.core.domain.ledger.LedgerRepository.LIST_WINDOW_DAYS] days,
 * and a calendar month does not tile that: on 19 August the window reaches back
 * to 20 July, and those entries belong to no calendar band the user was
 * offered. Defined as rolling, the four bands tile the window exactly and
 * nothing can fall between them — which is why there is no "Earlier" case here
 * and why adding one would mean the query bound and these bands had drifted
 * apart.
 *
 * Declared in ordinal order, oldest last, matching the list's
 * `local_date DESC` ordering. That is what lets the screen emit a header
 * wherever a row's band differs from the row above it, comparing one neighbour
 * rather than regrouping a list it would first have to materialise
 * (CLAUDE.md §8).
 */
internal enum class RecencyBucket(
    val label: String,
    /**
     * Whether a row in this band has to print its own date.
     *
     * The first two bands *are* a date, so repeating it on every row beneath
     * them is noise. The other two span up to three weeks, where "5:32 pm"
     * alone would not say which day -- so there the date is the point.
     *
     * It lives on the band rather than in the row because the band is what
     * knows: a row cannot tell whether the header above it already answered
     * the question.
     */
    val needsDate: Boolean,
) {
    Today("Today", needsDate = false),
    Yesterday("Yesterday", needsDate = false),
    ThisWeek("This week", needsDate = true),
    ThisMonth("This month", needsDate = true),
}

/**
 * Which band [localDate] falls in, both as days since epoch.
 *
 * A future-dated entry lands in [RecencyBucket.Today] rather than in a band of
 * its own. The date picker permits one — a receipt entered against tomorrow's
 * date is a real thing a user does — and a "Tomorrow" header on a list titled
 * by recency would be a band with one member for one day, then silently empty.
 * The entry still sorts to the top, which is where its date puts it.
 */
internal fun recencyBucketOf(localDate: Int, today: Int): RecencyBucket = when {
    localDate >= today -> RecencyBucket.Today
    localDate == today - 1 -> RecencyBucket.Yesterday
    // Days 2..6 back. Strict `>`, so day 7 falls through to the month band and
    // the two cannot both claim it.
    localDate > today - DAYS_IN_WEEK -> RecencyBucket.ThisWeek
    else -> RecencyBucket.ThisMonth
}

private const val DAYS_IN_WEEK = 7
