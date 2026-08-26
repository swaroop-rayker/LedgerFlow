package com.ledgerflow.core.data.ingest

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.InstrumentHint
import com.ledgerflow.core.model.Money
import org.junit.Test

/**
 * `pending_transaction.extracted_json` (§6.1). P2-4.
 *
 * The round trip is the point. An encoder with no decoder under test is an
 * assertion that a column *looks* right, and the first thing that would find out
 * otherwise is `:feature:inbox` at P2-6, against a queue a user had already
 * accumulated — at which point the payloads are real and re-encoding them is a
 * migration rather than a fix.
 */
class ExtractedTransactionJsonTest {

    private val full = ExtractedTransaction(
        amount = Money(78_800L),
        currency = "INR",
        direction = ExtractedDirection.DEBIT,
        merchantRaw = "SWIGGY*ORDER",
        accountLast4 = "1234",
        instrumentHint = InstrumentHint.UPI,
        referenceNo = "528612345678",
        occurredAt = 1_700_000_000_000L,
        availableBalance = Money(1_234_500L),
        confidence = 0.82,
    )

    @Test
    fun encodeThenDecode_returnsEveryExtractionTarget() {
        val decoded = ExtractedTransactionJson.decode(ExtractedTransactionJson.encode(full))

        assertThat(decoded).isEqualTo(full)
    }

    /**
     * §5.1's unmatched candidate has to survive the round trip too — it is the
     * payload the never-drop rule actually produces, and it is almost entirely
     * nulls.
     */
    @Test
    fun encodeThenDecode_survivesTheEmptyExtraction() {
        val empty = ExtractedTransaction()

        val decoded = ExtractedTransactionJson.decode(ExtractedTransactionJson.encode(empty))

        assertThat(decoded).isEqualTo(empty)
        assertThat(decoded?.confidence).isEqualTo(0.0)
        assertThat(decoded?.isReviewable).isFalse()
    }

    @Test
    fun encode_stampsTheVersion() {
        assertThat(ExtractedTransactionJson.encode(full))
            .contains("\"v\":${ExtractedTransactionJson.VERSION}")
    }

    /**
     * Law 3 at the wire boundary.
     *
     * The ban is on `Double` for money, and serialisation is where one sneaks
     * back in: `"amount": 788.0` cannot represent every rupee figure exactly, so
     * the column holds the same `Long` the ledger does.
     */
    @Test
    fun encode_writesMoneyAsMinorUnits_neverAsADecimal() {
        val encoded = ExtractedTransactionJson.encode(full)

        assertThat(encoded).contains("\"amountMinor\":78800")
        assertThat(encoded).doesNotContain("788.0")
    }

    /**
     * A payload written by a later build must still open.
     *
     * The alternative is that an app update orphans the user's unreviewed queue,
     * which is `BackupPayload`'s lesson applied one table over: unknown keys are
     * ignored and every field defaults.
     */
    @Test
    fun decode_ignoresFieldsItDoesNotKnow() {
        val fromTheFuture = """
            {"v":99,"amountMinor":78800,"direction":"DEBIT","taxSplitMinor":900}
        """.trimIndent()

        val decoded = ExtractedTransactionJson.decode(fromTheFuture)

        assertThat(decoded?.amount).isEqualTo(Money(78_800L))
        assertThat(decoded?.direction).isEqualTo(ExtractedDirection.DEBIT)
    }

    /**
     * An enum value this build has never heard of degrades to `UNKNOWN` rather
     * than failing the row — the same rule a `parser_rule` with a bad field map
     * already follows. One malformed candidate must not make the Inbox
     * unopenable.
     */
    @Test
    fun decode_degradesAnUnknownEnumRatherThanFailingTheRow() {
        val decoded = ExtractedTransactionJson.decode(
            """{"v":1,"amountMinor":100,"direction":"REFUND","instrumentHint":"CRYPTO"}""",
        )

        assertThat(decoded?.direction).isEqualTo(ExtractedDirection.UNKNOWN)
        assertThat(decoded?.instrumentHint).isEqualTo(InstrumentHint.UNKNOWN)
        assertThat(decoded?.amount).isEqualTo(Money(100L))
    }

    @Test
    fun decode_returnsNull_forSomethingThatIsNotAPayload() {
        assertThat(ExtractedTransactionJson.decode("not json at all")).isNull()
    }
}
