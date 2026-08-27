package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * §3.1's "same transaction?" rule. P2-5.
 *
 * The shapes below are taken from the real corpus rather than invented: a bank
 * SMS carries an account, a reference and usually a payee name; a UPI app's
 * notification carries an amount and a payee and nothing else. The measurement
 * that produced this rule is in [DedupeKey]'s KDoc — 14 of 16 matched SMS
 * fixtures extract `accountLast4`, and 0 of 5 matched notification fixtures do.
 */
class DuplicateMatcherTest {

    /** The owner's real HDFC debit, as the ruleset extracts it. */
    private val bankSms = ExtractedTransaction(
        amount = Money(200L),
        direction = ExtractedDirection.DEBIT,
        merchantRaw = "RAMESH KUMAR",
        accountLast4 = "1234",
        instrumentHint = InstrumentHint.UPI,
        referenceNo = "999999999998",
        occurredAt = 1_787_788_800_000L,
        confidence = 0.9,
    )

    /** The paying app's notification for the same payment. */
    private val appNotification = ExtractedTransaction(
        amount = Money(200L),
        direction = ExtractedDirection.DEBIT,
        merchantRaw = "Ramesh Kumar",
        instrumentHint = InstrumentHint.UPI,
        confidence = 0.7,
    )

    /**
     * **The case the whole mechanism exists for.** Different fields, not
     * different values: the SMS has an account and a reference the notification
     * has never heard of, and nothing they both carry disagrees.
     */
    @Test
    fun isSameTransaction_bankSmsAndAppNotification_forOnePayment() {
        assertThat(DuplicateMatcher.isSameTransaction(bankSms, appNotification)).isTrue()
        // Symmetric, or the answer would depend on arrival order.
        assertThat(DuplicateMatcher.isSameTransaction(appNotification, bankSms)).isTrue()
    }

    /**
     * A notification that carries nothing but the amount still merges.
     *
     * Absence is not disagreement. Requiring a shared field here is exactly what
     * §3.1's `accountLast4 ?: merchantNormalized` did, and it is why the spec's
     * key could never fire across sources.
     */
    @Test
    fun isSameTransaction_whenOneSideCarriesNothingToCompare() {
        val bare = ExtractedTransaction(
            amount = Money(200L),
            direction = ExtractedDirection.DEBIT,
            confidence = 0.4,
        )

        assertThat(DuplicateMatcher.isSameTransaction(bankSms, bare)).isTrue()
    }

    @Test
    fun isSameTransaction_false_whenBothNameAnAccountAndTheyDiffer() {
        val otherAccount = bankSms.copy(accountLast4 = "5678")

        assertThat(DuplicateMatcher.isSameTransaction(bankSms, otherAccount)).isFalse()
    }

    @Test
    fun isSameTransaction_false_whenBothNameAMerchantAndTheyDiffer() {
        val otherPayee = appNotification.copy(merchantRaw = "Nandhana Palace")

        assertThat(DuplicateMatcher.isSameTransaction(bankSms, otherPayee)).isFalse()
    }

    /**
     * The rail's own identifier for the payment. When both sides quote one and
     * they differ, these are two payments however alike they look.
     */
    @Test
    fun isSameTransaction_false_whenBothQuoteAReferenceAndTheyDiffer() {
        val otherReference = bankSms.copy(referenceNo = "111111111111")

        assertThat(DuplicateMatcher.isSameTransaction(bankSms, otherReference)).isFalse()
    }

    /**
     * Merchant comparison goes through [com.ledgerflow.core.domain.taxonomy.MerchantNormalizer],
     * so the two sources' casing and rail noise do not read as disagreement —
     * and so dedupe and the taxonomy cannot disagree about what one shop is
     * (§5.5).
     */
    @Test
    fun isSameTransaction_normalisesMerchantsBeforeComparing() {
        val smsForm = bankSms.copy(merchantRaw = "SWIGGY*ORDER123", accountLast4 = null)
        val appForm = appNotification.copy(merchantRaw = "Swiggy")

        assertThat(DuplicateMatcher.isSameTransaction(smsForm, appForm)).isTrue()
    }

    /**
     * A blank counts as absent, not as a value that disagrees.
     *
     * A rule whose optional group matched nothing yields `""` often enough that
     * treating it as a real value would make dedupe depend on how someone wrote
     * a regex.
     */
    @Test
    fun isSameTransaction_treatsBlankAsAbsent() {
        val blanks = appNotification.copy(merchantRaw = "   ", referenceNo = "")

        assertThat(DuplicateMatcher.isSameTransaction(bankSms, blanks)).isTrue()
    }

    /**
     * §3.1's ±3 minutes, and the one number this file pins.
     *
     * Asserted rather than assumed because the window is applied by a SQL range
     * in another module, and a constant that quietly changed would widen or
     * narrow dedupe with nothing failing.
     */
    @Test
    fun window_isThreeMinutes() {
        assertThat(DuplicateMatcher.WINDOW_MILLIS).isEqualTo(180_000L)
    }
}
