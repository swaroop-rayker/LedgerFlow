package com.ledgerflow.feature.ingest.parser

import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.ExtractionField
import com.ledgerflow.core.domain.ingest.InstrumentHint
import com.ledgerflow.core.domain.ingest.ParserRule
import com.ledgerflow.core.domain.ingest.RawIngestEvent

/** What the engine made of one message. */
public sealed interface ExtractionResult {

    /** A rule matched. [ruleId] is recorded on the raw row for the test bench and for debugging. */
    public data class Matched(
        val ruleId: String,
        val extracted: ExtractedTransaction,
    ) : ExtractionResult

    /**
     * No rule matched.
     *
     * **Not a failure, and never a reason to drop the message** (§5.1). An
     * unparseable message from an allowlisted sender is exactly the material the
     * ruleset has to grow against, and it still becomes a `PENDING` row with
     * `confidence = 0` and `needs_manual_fill = 1`.
     */
    public data object Unmatched : ExtractionResult
}

/**
 * The shared rule engine (SPEC.md §5.1, §5.2).
 *
 * **One engine, both sources.** An SMS and a GPay notification arrive here as
 * the same [RawIngestEvent] and are matched against the same ruleset; the only
 * difference is which field a rule's `senderPattern` is tested against —
 * `sender` for SMS, `packageName` for a notification — and §5.2 says that is the
 * only source-specific thing there is. There is deliberately no `when` on
 * `sourceType` anywhere below except in [matchField], which is that one line.
 *
 * Pure Kotlin: no Android, no database, no clock. That is what lets the golden
 * corpus run as a JVM test over `testdata/`, which is where the real
 * verification lives (§12) — the corpus is the spec for this class, not the
 * unit tests.
 *
 * **First match wins, in priority order.** Not best-match: a scoring contest
 * between rules is unpredictable to a user editing them, and §5.1's rule editor
 * means users will edit them. `priority` then `id` makes the winner a fact
 * anyone can work out by reading the list.
 */
public class ParserRuleEngine(rules: List<ParserRule>) {

    /**
     * Compiled once, in the order they will be tried.
     *
     * Regex compilation is not free and this runs per message in a worker; a
     * rule whose pattern does not compile is dropped here rather than throwing
     * per message, because one bad user-written rule must not stop every other
     * rule from working.
     */
    private val compiled: List<CompiledRule> = rules
        .filter { it.enabled }
        .sortedWith(compareBy({ it.priority }, { it.id }))
        .mapNotNull(CompiledRule::from)

    /** How many rules survived compilation. The loader reports a shortfall. */
    public val usableRuleCount: Int get() = compiled.size

    public fun extract(event: RawIngestEvent): ExtractionResult {
        val matchField = matchField(event)
        compiled.forEach { rule ->
            if (!rule.sender.containsMatchIn(matchField)) return@forEach
            val match = rule.body.find(event.body) ?: return@forEach
            return ExtractionResult.Matched(rule.rule.id, rule.build(match, event.body))
        }
        return ExtractionResult.Unmatched
    }

    /**
     * The one source-specific line in the engine (§5.2).
     *
     * A notification's package is what identifies its origin; an SMS has a
     * sender address and no package. Falling back to `sender` keeps a
     * notification rule able to match on the app's label if someone writes one
     * that way.
     */
    private fun matchField(event: RawIngestEvent): String = event.packageName ?: event.sender

    private class CompiledRule(
        val rule: ParserRule,
        val sender: Regex,
        val body: Regex,
    ) {

        fun build(match: MatchResult, body: String): ExtractedTransaction {
            fun group(field: ExtractionField): String? = rule.fieldMap[field]
                ?.let { name -> runCatching { match.groups[name]?.value }.getOrNull() }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val currency = group(ExtractionField.CURRENCY)?.let(::normalizeCurrency) ?: DEFAULT_CURRENCY
            val amount = group(ExtractionField.AMOUNT)?.let { MoneyText.parse(it, currency) }
            val balance = group(ExtractionField.AVAILABLE_BALANCE)
                ?.let { MoneyText.parse(it, currency) }

            val direction = rule.direction
                ?: group(ExtractionField.DIRECTION)?.let(::directionFrom)
                ?: ExtractedDirection.UNKNOWN

            val extracted = ExtractedTransaction(
                amount = amount,
                currency = currency,
                direction = direction,
                merchantRaw = group(ExtractionField.MERCHANT_RAW),
                accountLast4 = group(ExtractionField.ACCOUNT_LAST4)?.takeLast(LAST4_LENGTH),
                instrumentHint = rule.instrumentHint
                    ?: group(ExtractionField.INSTRUMENT_HINT)?.let(::instrumentFrom)
                    ?: instrumentFrom(body),
                referenceNo = group(ExtractionField.REFERENCE_NO),
                occurredAt = group(ExtractionField.OCCURRED_AT)?.let(DateText::parse),
                availableBalance = balance,
            )
            return extracted.copy(confidence = confidenceFor(extracted))
        }

        /**
         * The rule's base, reduced by what it failed to find.
         *
         * A rule that matched but produced no amount is much weaker evidence
         * than the same rule producing amount, direction and merchant — and the
         * review screen sorts on this. Reducing rather than adding keeps
         * [ParserRule.confidenceBase] meaning "at best".
         */
        fun confidenceFor(extracted: ExtractedTransaction): Double {
            var confidence = rule.confidenceBase
            if (extracted.amount == null) confidence -= NO_AMOUNT_PENALTY
            if (extracted.direction == ExtractedDirection.UNKNOWN) confidence -= NO_DIRECTION_PENALTY
            if (extracted.merchantRaw == null) confidence -= NO_MERCHANT_PENALTY
            return confidence.coerceIn(0.0, 1.0)
        }

        companion object {

            fun from(rule: ParserRule): CompiledRule? = runCatching {
                CompiledRule(
                    rule = rule,
                    sender = Regex(rule.senderPattern, RegexOption.IGNORE_CASE),
                    // MULTILINE so `$` means end-of-line: the notification
                    // adapter joins a notification's fields with newlines, and
                    // a rule anchoring a merchant capture to the end of the
                    // title is how it avoids swallowing the chatter below it.
                    body = Regex(
                        rule.bodyPattern,
                        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
                    ),
                )
            }.getOrNull()

            const val DEFAULT_CURRENCY = "INR"
            const val LAST4_LENGTH = 4
            const val NO_AMOUNT_PENALTY = 0.5
            const val NO_DIRECTION_PENALTY = 0.2
            const val NO_MERCHANT_PENALTY = 0.1

            /** `Rs`, `Rs.`, `INR`, `₹` all mean the same thing in these messages. */
            fun normalizeCurrency(text: String): String = when (text.trim().uppercase().trimEnd('.')) {
                "RS", "INR", "₹", "RS ", "RUPEES" -> "INR"
                else -> text.trim().uppercase().takeIf { it.length == CURRENCY_CODE_LENGTH }
                    ?: DEFAULT_CURRENCY
            }

            const val CURRENCY_CODE_LENGTH = 3

            fun directionFrom(text: String): ExtractedDirection {
                val lower = text.lowercase()
                return when {
                    // "debited", "debit", "spent", "paid", "withdrawn", "sent"
                    DEBIT_WORDS.any { it in lower } -> ExtractedDirection.DEBIT
                    CREDIT_WORDS.any { it in lower } -> ExtractedDirection.CREDIT
                    else -> ExtractedDirection.UNKNOWN
                }
            }

            val DEBIT_WORDS = listOf("debit", "spent", "paid", "withdraw", "sent", "purchase")
            val CREDIT_WORDS = listOf("credit", "received", "deposit", "refund", "cashback")

            fun instrumentFrom(text: String): InstrumentHint {
                val lower = text.lowercase()
                return when {
                    "upi" in lower || "vpa" in lower || "@" in lower -> InstrumentHint.UPI
                    "atm" in lower -> InstrumentHint.ATM
                    "card" in lower -> InstrumentHint.CARD
                    "neft" in lower || "imps" in lower || "rtgs" in lower ||
                        "netbank" in lower -> InstrumentHint.NETBANKING
                    "wallet" in lower -> InstrumentHint.WALLET
                    else -> InstrumentHint.UNKNOWN
                }
            }
        }
    }
}
