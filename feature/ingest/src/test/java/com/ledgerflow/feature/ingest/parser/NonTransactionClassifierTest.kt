package com.ledgerflow.feature.ingest.parser

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.model.NonTransactionKind
import org.junit.Test

/**
 * The classifier's edges (BUG58). The corpus is its specification for real
 * message shapes (`GoldenCorpusTest`); these pin the bias, which no fixture
 * states on its own: **when a message says money moved, it is never labelled**.
 */
class NonTransactionClassifierTest {

    private fun classify(body: String) = NonTransactionClassifier.classify(body)

    @Test
    fun classify_movementVerbBesideAnAmount_isNeverLabelled() {
        assertThat(classify("Rs.500 debited from a/c XX12. Avl Bal Rs.1,000")).isNull()
        assertThat(classify("INR 250.00 credited to a/c XX12. Cashback offer inside!")).isNull()
        assertThat(classify("₹99 paid to Netflix. Never share your OTP 4821 with anyone.")).isNull()
        // A failed payment whose money came back: the refund is a transaction.
        assertThat(classify("Txn of Rs.300 failed. Amount reversed to your a/c XX12.")).isNull()
    }

    /**
     * A pre-debit notice is the future passive, not money moving (owner's HDFC
     * e-mandate notices, 2026-10-21). The past tense still blocks.
     */
    @Test
    fun classify_willBeDebited_isADueDate_hasBeenDebited_isNot() {
        assertThat(classify("Rs.199.00 will be debited on 01/10/26 for your mandate. Maintain balance"))
            .isEqualTo(NonTransactionKind.STATEMENT_OR_DUE)
        assertThat(classify("Rs.199.00 has been debited for your mandate. Avl bal Rs.1,000"))
            .isNull()
    }

    @Test
    fun classify_aMovementVerbWithNoAmount_doesNotBlockALabel() {
        // "received" here is a scratch card, not money.
        assertThat(classify("You've received a scratch card! Tap to see what you won"))
            .isEqualTo(NonTransactionKind.PROMO)
    }

    @Test
    fun classify_otpNeedsTheCodeItself() {
        assertThat(classify("482913 is your OTP for login.")).isEqualTo(NonTransactionKind.OTP)
        assertThat(classify("Your verification code is 7731")).isEqualTo(NonTransactionKind.OTP)
        // A mention of OTPs with no code is advice, not a code.
        assertThat(classify("Bank never asks for your OTP. Stay safe.")).isNull()
    }

    /**
     * SBI's YONO OTPs vary in wording (owner, 2026-10-21). The real one is a
     * corpus fixture; these are synthetic variants in the same family, so a
     * rewording does not quietly turn them back into ghosts.
     */
    @Test
    fun classify_sbiOtpVariants_areOtps() {
        listOf(
            "482913 is the OTP to login to YONO Web (Internet Banking). Do not share with anyone. " +
                "If not done by you, please call 1800111109.-YONO, SBI",
            "OTP for YONO registration is 482913. Do not share it with anyone.-SBI",
            "Dear Customer, 48291374 is the OTP for your transaction on YONO SBI. " +
                "Valid for 3 mins. Do not share. -SBI",
            "Your One Time Password for SBI Card login is 482913. Do not share it with anyone.",
        ).forEach { body ->
            assertWithMessage(body).that(classify(body)).isEqualTo(NonTransactionKind.OTP)
        }
    }

    @Test
    fun classify_eachKind() {
        assertThat(classify("Your payment of Rs.200 to X has failed."))
            .isEqualTo(NonTransactionKind.FAILED_OR_DECLINED)
        assertThat(classify("Card XX44 txn of Rs.1,200 declined: insufficient funds"))
            .isEqualTo(NonTransactionKind.FAILED_OR_DECLINED)
        assertThat(classify("Asha has requested Rs.500. Tap to review."))
            .isEqualTo(NonTransactionKind.PAYMENT_REQUEST)
        assertThat(classify("Your EMI of Rs.5,000 is due on 05/10/26. Please maintain balance."))
            .isEqualTo(NonTransactionKind.STATEMENT_OR_DUE)
        assertThat(classify("Avl bal in a/c XX12 is Rs.4,000 as on 10/10"))
            .isEqualTo(NonTransactionKind.BALANCE)
        assertThat(classify("Pre-approved loan up to Rs.5,00,000. Apply now"))
            .isEqualTo(NonTransactionKind.PROMO)
    }

    @Test
    fun classify_ordinaryWording_isNotLabelled() {
        assertThat(classify("Some bank wording no rule in this build has ever seen.")).isNull()
        assertThat(classify("")).isNull()
    }
}
