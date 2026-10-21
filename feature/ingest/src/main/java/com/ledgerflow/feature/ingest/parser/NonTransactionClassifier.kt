package com.ledgerflow.feature.ingest.parser

import com.ledgerflow.core.model.NonTransactionKind

/**
 * Recognises a message that is **not a transaction** (SPEC.md §5.1; BUG58,
 * V2-PLAN D6).
 *
 * Allowlisted senders post one-time passwords, balance notices, statements and
 * due-date reminders, offers, collect requests and failed-payment notices from
 * the very sender IDs and packages that post real debits. None matches a rule,
 * and each used to become a confidence-0 candidate with no payee and no amount
 * — a "ghost" the user dismissed by hand.
 *
 * **Runs only after every rule has missed** ([ParserRuleEngine.extract]). A real
 * debit that also mentions a balance or an OTP is caught by its rule first and
 * never reaches here.
 *
 * **Biased to say nothing.** A wrong label hides a payment under "Not
 * transactions"; a missing one leaves a ghost in the queue. The second is the
 * cheaper mistake, so [classify] refuses outright whenever a message says money
 * *moved* — a debit/credit verb next to an amount — whatever else it says.
 * Indian bank alerts routinely end "Never share your OTP", and an unmatched one
 * of those must stay in the queue rather than be filed as a code.
 *
 * **One classifier, both sources** (§5.2): it reads the body and nothing else,
 * so an SMS and a notification saying the same thing get the same label.
 *
 * Pure Kotlin, like the engine, so the golden corpus runs it on the JVM.
 */
public object NonTransactionClassifier {

    /** The label for [body], or null when it may describe money moving. */
    public fun classify(body: String): NonTransactionKind? {
        if (MOVEMENT_VERB.containsMatchIn(body) && AMOUNT.containsMatchIn(body)) return null
        // Most specific first. An EMI reminder also says "maintain balance",
        // and an OTP for a purchase also names an amount and a merchant.
        return when {
            OTP.containsMatchIn(body) && CODE.containsMatchIn(body) -> NonTransactionKind.OTP
            FAILED.containsMatchIn(body) -> NonTransactionKind.FAILED_OR_DECLINED
            REQUEST.containsMatchIn(body) -> NonTransactionKind.PAYMENT_REQUEST
            DUE.containsMatchIn(body) -> NonTransactionKind.STATEMENT_OR_DUE
            BALANCE.containsMatchIn(body) -> NonTransactionKind.BALANCE
            PROMO.containsMatchIn(body) -> NonTransactionKind.PROMO
            else -> null
        }
    }

    private fun words(vararg alternatives: String) =
        Regex("\\b(?:${alternatives.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    /**
     * Past tense only, and not the future passive: "Rs.199 will be debited on
     * 01/10" is a pre-debit notice that money has *not* moved yet. Every UPI
     * e-mandate sends one before it collects (the owner's HDFC notices,
     * 2026-10-21), and the debit itself arrives later as its own message.
     */
    private val MOVEMENT_VERB = Regex(
        "(?<!will be )(?<!to be )\\b(?:debited|credited|spent|withdrawn|transferred|deposited|" +
            "refunded|reversed|received|sent|paid|purchased)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val AMOUNT = Regex("(?:\\bRs\\.?|\\bINR|₹)\\s*\\d", RegexOption.IGNORE_CASE)

    private val OTP = words("OTP", "one[- ]time password", "verification code", "security code")

    /**
     * The code itself. Without one, "OTP" is a mention ("never share your OTP"), not a code.
     *
     * Not part of a longer number, and not either side of a decimal point or a
     * digit grouping ("18,300.25"). A full stop that ends the sentence is fine:
     * "Your OTP is 482913. Do not share" missed on the first cut, which refused
     * any "." after the code (SBI variant, 2026-10-21).
     */
    private val CODE = Regex("(?<![\\d,])(?<!\\d\\.)\\d{4,8}(?![\\d,])(?!\\.\\d)")

    private val FAILED = words("failed", "declined", "unsuccessful", "could not be processed")

    private val REQUEST = words("has requested", "requested money", "collect request", "payment request")

    private val DUE = words(
        "is due", "due on", "due date", "due in", "min(?:imum)? due", "total due", "amount due",
        "overdue", "statement", "EMI", "pay before", "late fee", "late fees",
        // A mandate's pre-debit notice: owed on a date, not yet taken.
        "will be deducted", "will be debited", "e-mandate", "pre-debit",
    )

    private val BALANCE = words("balance", "avl bal", "avbl bal", "available limit", "available credit limit")

    /**
     * Phrases rather than bare words where a bare word misfires: "won" is also
     * "won't", and "flat" is also an address.
     */
    private val PROMO = words(
        "offer", "offers", "cashback", "scratch card", "win up to", "to win", "you won", "reward",
        "rewards", "coupon", "voucher", "use code", "flat (?:rs|inr|₹|\\d+)", "apply now",
        "pre-approved", "pre approved", "loan up to", "KYC", "upgrade", "exclusive", "limited period",
        // The terms-and-conditions line every bank-app promotion ends on (YONO, 2026-10-21).
        "T&Cs? apply",
    )
}
