package com.ledgerflow.core.domain.analytics

import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.Money

/**
 * C1 — capture coverage (`docs/DATAVIZ-PLAN.md` Family C).
 *
 * How much of the window's spending **arrived by itself** versus how much the
 * user typed. It is the one figure in the catalogue that measures the app
 * rather than the money, and it needs no new schema: `ledger_entry.source`
 * already records where every entry came from.
 *
 * **Three buckets, not two, and the third is the honest one.** Automatic and
 * manual are obvious. An **imported** entry is neither — nobody typed it and no
 * parser read it — so folding it into "automatic" would inflate the number this
 * surface exists to report, and folding it into "by hand" would understate it.
 * It gets its own bucket and is hidden when empty.
 *
 * **By value *and* by count**, because they answer different questions and
 * routinely disagree: one large rent transfer typed by hand can put "captured"
 * under 50% by value while it is 90% by count. Reporting only the first would
 * make an app that captures almost everything look broken.
 */
public data class CaptureCoverage(
    val automatic: CaptureShare,
    val manual: CaptureShare,
    val imported: CaptureShare,
) {
    /** Everything, for the denominators. */
    public val totalMinor: Long
        get() = automatic.amount.minor + manual.amount.minor + imported.amount.minor

    public val totalCount: Int
        get() = automatic.count + manual.count + imported.count

    /** True when the window has nothing to describe, so the section can hide. */
    public val isEmpty: Boolean get() = totalCount == 0

    /**
     * Automatic share **by value**, 0..100, rounded to a whole percent.
     *
     * Integer arithmetic on minor units (Law 3): the numerator is money and
     * never becomes a `Double` on the way to a percentage.
     */
    public val automaticPercentByValue: Int
        get() = percent(automatic.amount.minor, totalMinor)

    /** Automatic share **by count**, 0..100. */
    public val automaticPercentByCount: Int
        get() = percent(automatic.count.toLong(), totalCount.toLong())

    private fun percent(part: Long, whole: Long): Int =
        if (whole <= 0L) 0 else ((part * PERCENT + whole / 2) / whole).toInt()

    public companion object {
        private const val PERCENT = 100L

        public val Empty: CaptureCoverage = CaptureCoverage(
            automatic = CaptureShare.Empty,
            manual = CaptureShare.Empty,
            imported = CaptureShare.Empty,
        )

        /**
         * Which sources count as captured.
         *
         * SMS, notification and OCR all mean the amount reached the app without
         * anyone typing it — which is what this surface measures. `IMPORT` is
         * deliberately absent; see the class doc.
         */
        public val AutomaticSources: Set<EntrySource> =
            setOf(EntrySource.SMS, EntrySource.NOTIFICATION, EntrySource.OCR)

        /** Folds per-source totals into the three buckets. */
        public fun from(bySource: Map<EntrySource, CaptureShare>): CaptureCoverage {
            fun fold(sources: Set<EntrySource>) = sources.fold(CaptureShare.Empty) { acc, source ->
                acc + (bySource[source] ?: CaptureShare.Empty)
            }
            return CaptureCoverage(
                automatic = fold(AutomaticSources),
                manual = fold(setOf(EntrySource.MANUAL)),
                imported = fold(setOf(EntrySource.IMPORT)),
            )
        }
    }
}

/** One bucket's money and entry count. */
public data class CaptureShare(val amount: Money, val count: Int) {

    public operator fun plus(other: CaptureShare): CaptureShare = CaptureShare(
        amount = Money(amount.minor + other.amount.minor),
        count = count + other.count,
    )

    public companion object {
        public val Empty: CaptureShare = CaptureShare(Money(0L), 0)
    }
}
