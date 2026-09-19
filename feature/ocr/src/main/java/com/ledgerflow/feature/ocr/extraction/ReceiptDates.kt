package com.ledgerflow.feature.ocr.extraction

import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.feature.ocr.recognition.RecognizedPage
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Which day a receipt says it was issued (SPEC.md §5.3, decided by the owner
 * on 2026-09-19).
 *
 * The four rules, in the order they apply:
 *
 * - **(a) The bill's own date.** A date labelled as the invoice's or bill's
 *   wins; else the **earliest** date under a generic "Date" label. A date
 *   labelled as an expiry, best-before, use-by, manufacture, packing or due
 *   date is **never** taken, and neither is an unlabelled one — bigbasket's
 *   delivery slot and payment lines carry dates with no label and are not the
 *   bill's.
 * - **(b) Midnight, local.** The receipt names a day and no clock, which is
 *   the SMS convention exactly — `OccurredAt.effective` then shows the day
 *   with the capture's clock. The owner first chose noon, and chose midnight
 *   once the collision with that rule was found.
 * - **(c) Day first.** `12/09/26` is the twelfth of September, always. A
 *   leading four-digit year is ISO (`2026-09-12`) and unambiguous.
 * - **(d) A plausible day or none.** A date more than one day after the
 *   capture, or more than a year before it, is refused rather than guessed —
 *   and the next candidate is **not** tried in its place, because a refused
 *   bill date is evidence the page was misread, not an invitation to pick a
 *   less authoritative one.
 *
 * **Measured before it was written**, on the device, over the owner's two
 * invoices as `PdfTextLayer` and `ReceiptGeometry` hand them over: Zepto's row
 * reads `Order No.: RGLOJVYNN22994A Date 20-07-2026` — the printed colon after
 * `Date` does not survive the text-layer rebuild, and the date shares its row
 * with the order number — and bigbasket's reads `Invoice Date 2026-09-12`, with
 * a month-name slot date and two unlabelled payment dates elsewhere. So the
 * label is read from the words immediately before the date in the same row,
 * never from punctuation.
 *
 * **Words are whole words** (BUG24's lesson, met again while measuring: a
 * naive month pattern matched `Margin` as March). For the *labels* that is a
 * live rule — `Update` must not read as `Date`, and a test fails without it.
 * For the *month names* it is belt and braces, said rather than implied: every
 * date pattern requires digits right after the month word, so `Margin` cannot
 * match even without the `(?!\p{L})` boundaries, and a mutation sweep removing
 * them reddens nothing.
 */
internal object ReceiptDates {

    /** What a date's label says it is. */
    enum class Label { BILL, GENERIC, EXCLUDED, NONE }

    data class Candidate(val date: LocalDate, val label: Label, val printed: String)

    enum class Refusal { AFTER_CAPTURE, TOO_OLD }

    sealed interface Detection {
        data class Found(val date: LocalDate, val printed: String) : Detection
        data class Refused(val date: LocalDate, val printed: String, val reason: Refusal) : Detection
        data object None : Detection
    }

    /** An extraction with its date applied, and what the date detection found. */
    data class Dated(val extracted: ExtractedTransaction, val detection: Detection)

    /**
     * The one composition the capture screen and the corpus test both use, so
     * what is graded is what ships. A refused or absent date leaves
     * `occurredAt` null, and the review falls back to the capture time as it
     * always has — rule (d)'s "left for the user", with the capture screen
     * saying why.
     */
    fun apply(extracted: ExtractedTransaction, page: RecognizedPage, capturedAt: Long, zone: ZoneId): Dated {
        val capturedOn = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDate()
        val detection = detect(ReceiptGeometry.rows(page).map { it.text }, capturedOn)
        val stamped = (detection as? Detection.Found)
            ?.let { extracted.copy(occurredAt = startOfDayMillis(it.date, zone)) }
            ?: extracted
        return Dated(stamped, detection)
    }

    fun detect(rows: List<String>, capturedOn: LocalDate): Detection {
        val chosen = choose(rows.flatMap(::candidates)) ?: return Detection.None
        return when {
            chosen.date.isAfter(capturedOn.plusDays(1)) ->
                Detection.Refused(chosen.date, chosen.printed, Refusal.AFTER_CAPTURE)
            chosen.date.isBefore(capturedOn.minusYears(1)) ->
                Detection.Refused(chosen.date, chosen.printed, Refusal.TOO_OLD)
            else -> Detection.Found(chosen.date, chosen.printed)
        }
    }

    /** Rule (a). Page order breaks ties between bill dates; generic dates take the earliest day. */
    fun choose(candidates: List<Candidate>): Candidate? =
        candidates.firstOrNull { it.label == Label.BILL }
            ?: candidates.filter { it.label == Label.GENERIC }.minByOrNull { it.date }

    /** Rule (b): the day at local midnight, as `occurred_at` stores every time-less source. */
    fun startOfDayMillis(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Every date printed in [row], labelled. Impossible days (`31/02`) are not dates. */
    fun candidates(row: String): List<Candidate> {
        val claimed = mutableListOf<IntRange>()
        val found = mutableListOf<Pair<IntRange, LocalDate>>()
        for ((pattern, read) in PATTERNS) {
            pattern.findAll(row).forEach { match ->
                if (claimed.any { it.first <= match.range.last && match.range.first <= it.last }) return@forEach
                val date = read(match.groupValues) ?: return@forEach
                claimed += match.range
                found += match.range to date
            }
        }
        return found.sortedBy { it.first.first }.map { (range, date) ->
            Candidate(date, labelBefore(row.substring(0, range.first)), row.substring(range))
        }
    }

    /** The label is the last few words before the date, read as whole words. */
    fun labelBefore(prefix: String): Label {
        val words = prefix.lowercase().split(NON_WORD).filter { it.isNotEmpty() }.takeLast(LABEL_WORDS)
        val tail = words.joinToString(" ")
        fun has(phrase: String) = " $tail ".contains(" $phrase ")
        return when {
            EXCLUDED.any(::has) -> Label.EXCLUDED
            BILL.any(::has) -> Label.BILL
            GENERIC.any(::has) -> Label.GENERIC
            else -> Label.NONE
        }
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }

    /** Two-digit years are this century's; receipts are recent by rule (d) anyway. */
    private fun year(text: String): Int = text.toInt().let { if (text.length == 2) CENTURY + it else it }

    private fun month(name: String): Int = MONTHS.indexOfFirst { name.lowercase().startsWith(it) } + 1

    private const val MONTH =
        "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|" +
            "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"

    /** Most specific first, so a longer reading claims its span before a shorter one can. */
    private val PATTERNS: List<Pair<Regex, (List<String>) -> LocalDate?>> = listOf(
        // ISO: a leading four-digit year is never ambiguous.
        Regex("""(?<![\d.])(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?![\d.])""") to
            { g -> date(g[1].toInt(), g[2].toInt(), g[3].toInt()) },
        // 12 Sep 2026, 12-Sep-26, 12th September 2026
        Regex(
            """(?<![\p{L}\d])(\d{1,2})(?:st|nd|rd|th)?[\s\-/.,]*($MONTH)(?!\p{L})\.?[\s\-/.,]*(\d{4}|\d{2})(?!\d)""",
            RegexOption.IGNORE_CASE,
        ) to { g -> date(year(g[3]), month(g[2]), g[1].toInt()) },
        // Sep 12, 2026
        Regex(
            """(?<!\p{L})($MONTH)(?!\p{L})\.?\s*(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""",
            RegexOption.IGNORE_CASE,
        ) to { g -> date(g[3].toInt(), month(g[1]), g[2].toInt()) },
        // Rule (c): 12/09/26, 12-09-2026, 12.09.2026 — day first, always.
        Regex("""(?<![\d.])(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?![\d.%])""") to
            { g -> date(year(g[3]), g[2].toInt(), g[1].toInt()) },
    )

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val EXCLUDED = listOf(
        "exp", "expiry", "expires", "expiring", "best before", "bb", "use by", "use before",
        "mfg", "mfd", "manufactured", "manufacturing", "pkd", "packed", "packing", "due",
        "valid", "validity", "valid till", "valid upto", "warranty",
    )
    private val BILL = listOf(
        "invoice date", "invoice dt", "inv date", "inv dt", "bill date", "bill dt",
        "receipt date", "tax invoice date", "date of invoice", "date of bill",
    )
    private val GENERIC = listOf("date", "dt", "dated")

    private val NON_WORD = Regex("""[^\p{L}\d]+""")
    private const val LABEL_WORDS = 3
    private const val CENTURY = 2000
}
