package com.ledgerflow.core.domain.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * §3.1's cross-source dedupe key. P2-4 computes and stores it; P2-5 acts on it.
 *
 * The test that matters most is [aBankSmsAndAnAppNotification_forOnePayment_shareAKey]:
 * the whole reason the key exists is that one UPI payment fires a bank SMS *and*
 * a GPay notification, and the two describe it with different words. If the key
 * does not survive that difference, P2-5 has nothing to work with — and the
 * failure would not show up until a user saw the same ₹788 twice in their Inbox.
 */
class DedupeKeyTest {

    private companion object {
        /** 2023-11-14T22:13:20Z, an exact minute boundary plus 20 s. */
        const val CAPTURED_AT = 1_700_000_000_000L
        const val MINUTE = 60_000L
    }

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
    fun compute_carriesAllFourComponents_ofTheSpecFormula() {
        val key = DedupeKey.compute(
            extraction(amountMinor = 78_800L, accountLast4 = "1234", occurredAt = CAPTURED_AT),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-1",
        )

        // amount | direction | minute | accountLast4, legible on purpose (§3.1).
        assertThat(key).isEqualTo("78800|DEBIT|${CAPTURED_AT / MINUTE}|1234")
    }

    /**
     * **The reason §3.1 exists.** One ₹788 UPI payment, two sources: the bank's
     * SMS names the account, the paying app's notification names the merchant
     * and the same account. Different bodies, different rules, one key.
     */
    @Test
    fun aBankSmsAndAnAppNotification_forOnePayment_shareAKey() {
        val fromSms = DedupeKey.compute(
            extraction(accountLast4 = "1234", occurredAt = CAPTURED_AT),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-sms",
        )
        // The notification arrives eleven seconds later and adds a merchant the
        // SMS did not carry. accountLast4 wins the discriminator either way,
        // which is why it is first in §3.1's `accountLast4 ?: merchantNormalized`.
        val fromNotification = DedupeKey.compute(
            extraction(
                accountLast4 = "1234",
                merchantRaw = "SWIGGY*ORDER",
                occurredAt = CAPTURED_AT,
            ),
            capturedAt = CAPTURED_AT + 11_000L,
            rawRefId = "raw-notif",
        )

        assertThat(fromSms).isEqualTo(fromNotification)
    }

    @Test
    fun compute_fallsBackToTheMerchant_whenNoAccountIsExtracted() {
        val key = DedupeKey.compute(
            extraction(merchantRaw = "Swiggy Ltd", occurredAt = CAPTURED_AT),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-1",
        )

        // MerchantNormalizer, not a local lowercase: dedupe and the taxonomy
        // must not disagree about what counts as the same shop (§5.5).
        assertThat(key).endsWith("|swiggy")
    }

    /**
     * The owner decision at P2-4: the payload keeps a null `occurredAt`, the key
     * falls back to capture time. A message that states no date must still be
     * dedupable against its twin from the other source.
     */
    @Test
    fun compute_usesCaptureTime_whenTheMessageStatesNoDate() {
        val stated = DedupeKey.compute(
            extraction(accountLast4 = "1234", occurredAt = CAPTURED_AT),
            capturedAt = CAPTURED_AT + 5 * MINUTE,
            rawRefId = "raw-1",
        )
        val unstated = DedupeKey.compute(
            extraction(accountLast4 = "1234", occurredAt = null),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-2",
        )

        assertThat(unstated).isEqualTo(stated)
    }

    @Test
    fun compute_bucketsToTheMinute_soSecondsApartCollide() {
        val early = DedupeKey.compute(
            extraction(accountLast4 = "1234", occurredAt = CAPTURED_AT),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-1",
        )
        val late = DedupeKey.compute(
            extraction(accountLast4 = "1234", occurredAt = CAPTURED_AT + 39_000L),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-2",
        )

        assertThat(early).isEqualTo(late)
    }

    @Test
    fun compute_separatesTheTwoBooks_soACreditNeverSuppressesADebit() {
        val debit = DedupeKey.compute(
            extraction(direction = ExtractedDirection.DEBIT, accountLast4 = "1234"),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-1",
        )
        val credit = DedupeKey.compute(
            extraction(direction = ExtractedDirection.CREDIT, accountLast4 = "1234"),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-2",
        )

        assertThat(debit).isNotEqualTo(credit)
    }

    /**
     * **§5.1's never-drop rule, defended one step further along.**
     *
     * Two unparseable messages from the same bank inside one minute have nothing
     * to match on: amount, direction, account and merchant are all absent. A
     * content key would make them identical, and P2-5 would suppress the second
     * as a duplicate of the first — a financial SMS silently dropped by the
     * dedupe layer instead of by the parser, which is the same defect wearing a
     * different hat.
     */
    @Test
    fun twoUnparseableMessages_inOneMinute_neverShareAKey() {
        val first = DedupeKey.compute(
            ExtractedTransaction(),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-1",
        )
        val second = DedupeKey.compute(
            ExtractedTransaction(),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-2",
        )

        assertThat(first).isNotEqualTo(second)
        assertThat(DedupeKey.isUnkeyed(first)).isTrue()
        assertThat(DedupeKey.isUnkeyed(second)).isTrue()
    }

    @Test
    fun isUnkeyed_isFalse_forAKeyWithContent() {
        val key = DedupeKey.compute(
            extraction(accountLast4 = "1234"),
            capturedAt = CAPTURED_AT,
            rawRefId = "raw-1",
        )

        assertThat(DedupeKey.isUnkeyed(key)).isFalse()
    }
}
