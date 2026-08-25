package com.ledgerflow.feature.ingest.adapters

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.IngestSourceType
import org.junit.Test

/**
 * Multipart reassembly — the receiver's only real logic, and the one place a
 * bank alert can be silently mangled (SPEC.md §5.1).
 *
 * `smsFull` only, because the code under test is: `playSafe` has no receiver,
 * no `RECEIVE_SMS`, and no source file to compile this against. Placing it in
 * the shared `src/test` would break the Play build's unit tests, which is a
 * quiet way of discovering the flavour split works.
 */
class SmsIngestReceiverTest {

    private val capturedAt = 1_756_000_000_000L

    @Test
    fun normalizeSmsParts_singlePart_becomesOneEvent() {
        val events = normalizeSmsParts(
            parts = listOf(SmsPart("VM-HDFCBK", "Rs.240.00 debited")),
            receivedAt = capturedAt,
        )

        assertThat(events).hasSize(1)
        assertThat(events.single().sender).isEqualTo("VM-HDFCBK")
        assertThat(events.single().body).isEqualTo("Rs.240.00 debited")
        assertThat(events.single().receivedAt).isEqualTo(capturedAt)
    }

    /**
     * The case that matters. A bank alert long enough to split routinely puts
     * the amount in part one and the merchant in part two; parsing the parts
     * separately would fail on exactly the messages worth capturing.
     *
     * Concatenation is join-with-nothing, not join-with-space: the carrier split
     * mid-sentence and may well have split mid-word.
     */
    @Test
    fun normalizeSmsParts_multipleParts_areConcatenatedInOrder() {
        val events = normalizeSmsParts(
            parts = listOf(
                SmsPart("VM-HDFCBK", "Rs.1,240.00 debited from a/c XX4412 "),
                SmsPart("VM-HDFCBK", "on 24-08-26 to SWIGGY. Ref 4418822."),
            ),
            receivedAt = capturedAt,
        )

        assertThat(events).hasSize(1)
        assertThat(events.single().body).isEqualTo(
            "Rs.1,240.00 debited from a/c XX4412 on 24-08-26 to SWIGGY. Ref 4418822.",
        )
    }

    /** One intent can carry messages from two numbers. They must not merge. */
    @Test
    fun normalizeSmsParts_differentSenders_stayApart() {
        val events = normalizeSmsParts(
            parts = listOf(
                SmsPart("VM-HDFCBK", "Rs.240.00 debited"),
                SmsPart("AD-ICICIB", "Rs.99.00 debited"),
                SmsPart("VM-HDFCBK", " at BIGBASKET"),
            ),
            receivedAt = capturedAt,
        )

        assertThat(events.map { it.sender }).containsExactly("VM-HDFCBK", "AD-ICICIB").inOrder()
        assertThat(events.first { it.sender == "VM-HDFCBK" }.body)
            .isEqualTo("Rs.240.00 debited at BIGBASKET")
    }

    /**
     * Every event this adapter produces is stamped SMS and carries no package.
     * The pipeline past here reads both fields and branches on neither
     * (CLAUDE.md §0); they exist so P2 can write the right raw table and so a
     * suppressed cross-source duplicate can say which source it came from.
     */
    @Test
    fun normalizeSmsParts_stampsTheSourceAndOmitsThePackage() {
        val event = normalizeSmsParts(
            parts = listOf(SmsPart("VM-HDFCBK", "Rs.240.00 debited")),
            receivedAt = capturedAt,
        ).single()

        assertThat(event.sourceType).isEqualTo(IngestSourceType.SMS)
        assertThat(event.packageName).isNull()
    }

    /**
     * An intent with no usable parts produces nothing — and the receiver returns
     * before it calls `goAsync()`, so it does not hold the process open for a
     * message that does not exist.
     */
    @Test
    fun normalizeSmsParts_noParts_producesNothing() {
        assertThat(normalizeSmsParts(parts = emptyList(), receivedAt = capturedAt)).isEmpty()
    }
}
