package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * §3.1's dedupe key — the bucket half. P2-4 stores it, P2-5 matches on it.
 *
 * The key deliberately no longer carries the minute or the discriminator. Both
 * were measured against the real corpus and found to diverge by source, so they
 * moved to where they work: the window is a range on `created_at`, and the
 * discriminator is [DuplicateMatcher]'s contradiction check. What is tested here
 * is that the bucket is coarse enough to *contain* both sources' view of one
 * payment, and still fine enough not to put a credit in with a debit.
 */
class DedupeKeyTest {

    private fun extraction(
        amountMinor: Long? = 78_800L,
        direction: ExtractedDirection = ExtractedDirection.DEBIT,
        merchantRaw: String? = null,
        accountLast4: String? = null,
        occurredAt: Long? = null,
    ) = ExtractedTransaction(
        amount = amountMinor?.let(::Money),
        direction = direction,
        merchantRaw = merchantRaw,
        accountLast4 = accountLast4,
        occurredAt = occurredAt,
        confidence = 0.9,
    )

    @Test
    fun compute_isAmountAndDirection() {
        val key = DedupeKey.compute(extraction(accountLast4 = "1234"), rawRefId = "raw-1")

        assertThat(key).isEqualTo("78800|DEBIT")
    }

    /**
     * **The reason §3.1 exists, and the case its literal key could not serve.**
     *
     * One ₹788 UPI payment, two sources. The bank SMS carries an account and a
     * date; the GPay notification carries a merchant and neither. Under the
     * spec's four-component key these produced different buckets on *both*
     * variable components. They must land in one bucket for the matcher to get
     * a chance at them.
     */
    @Test
    fun aBankSmsAndAnAppNotification_forOnePayment_shareABucket() {
        val fromSms = DedupeKey.compute(
            // Real HDFC shape: account, and a date that parses to midnight.
            extraction(accountLast4 = "1234", occurredAt = 1_787_788_800_000L),
            rawRefId = "raw-sms",
        )
        val fromNotification = DedupeKey.compute(
            // Real GPay shape: a merchant, no account, no date at all.
            extraction(merchantRaw = "Nandhana Palace"),
            rawRefId = "raw-notif",
        )

        assertThat(fromSms).isEqualTo(fromNotification)
    }

    @Test
    fun compute_separatesTheTwoBooks_soACreditNeverSuppressesADebit() {
        val debit = DedupeKey.compute(
            extraction(direction = ExtractedDirection.DEBIT),
            rawRefId = "raw-1",
        )
        val credit = DedupeKey.compute(
            extraction(direction = ExtractedDirection.CREDIT),
            rawRefId = "raw-2",
        )

        assertThat(debit).isNotEqualTo(credit)
    }

    @Test
    fun compute_separatesDifferentAmounts() {
        val small = DedupeKey.compute(extraction(amountMinor = 200L), rawRefId = "raw-1")
        val large = DedupeKey.compute(extraction(amountMinor = 78_800L), rawRefId = "raw-2")

        assertThat(small).isNotEqualTo(large)
    }

    /**
     * **§5.1's never-drop rule, defended one step further along.**
     *
     * Two unparseable messages in one window have nothing to match on: no
     * amount, no direction, no account, no merchant. A shared bucket would make
     * them candidates for each other and P2-5 would suppress the second as a
     * copy of the first — a financial SMS made invisible by the dedupe layer
     * instead of by the parser, which is the same defect wearing a different
     * hat.
     */
    @Test
    fun twoUnparseableMessages_neverShareABucket() {
        val first = DedupeKey.compute(ExtractedTransaction(), rawRefId = "raw-1")
        val second = DedupeKey.compute(ExtractedTransaction(), rawRefId = "raw-2")

        assertThat(first).isNotEqualTo(second)
        assertThat(DedupeKey.isUnkeyed(first)).isTrue()
        assertThat(DedupeKey.isUnkeyed(second)).isTrue()
    }

    @Test
    fun isUnkeyed_isFalse_forABucketWithContent() {
        val key = DedupeKey.compute(extraction(), rawRefId = "raw-1")

        assertThat(DedupeKey.isUnkeyed(key)).isFalse()
    }

    /**
     * A direction the parser could not read is its own bucket rather than a
     * hole. Two UNKNOWNs of the same amount may well be one payment; an UNKNOWN
     * and a DEBIT are not something to merge on a guess.
     */
    @Test
    fun compute_treatsUnknownDirectionAsItsOwnBucket() {
        val unknown = DedupeKey.compute(
            extraction(direction = ExtractedDirection.UNKNOWN),
            rawRefId = "raw-1",
        )

        assertThat(unknown).isEqualTo("78800|UNKNOWN")
        assertThat(unknown).isNotEqualTo(
            DedupeKey.compute(extraction(direction = ExtractedDirection.DEBIT), "raw-2"),
        )
    }
}
