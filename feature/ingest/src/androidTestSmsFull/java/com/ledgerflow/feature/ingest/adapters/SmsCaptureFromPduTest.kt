package com.ledgerflow.feature.ingest.adapters

import android.content.Intent
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.domain.ingest.IngestSourceType
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SMS capture against the platform's own parser (SPEC.md §5.1). `smsFull` only.
 *
 * Everything else in the SMS path is covered off-device: `normalizeSmsParts` is
 * pure and unit-tested, and the sink, repository and worker have their own
 * suites. **The one link none of those reach is the platform unwrap** —
 * `Telephony.Sms.Intents.getMessagesFromIntent` turning raw PDUs into
 * `SmsMessage` objects — because `SmsMessage` has no public constructor, and
 * because `adb shell am broadcast` is refused for `SMS_RECEIVED` (`BROADCAST_SMS`
 * is signature-level, so the shell cannot deliver a synthetic message either).
 *
 * So this hands the platform real bytes. [SmsPduFactory] encodes them and the
 * assertions compare what comes back to what went in — meaning the fixture
 * cannot be quietly wrong and still pass: if the encoding is bad, Android's
 * parser disagrees and the test fails.
 *
 * It stops at [toSmsParts] and [normalizeSmsParts] rather than driving
 * `SmsIngestReceiver` itself. The receiver's remaining body is `goAsync()` plus
 * a `sink.submit`, and exercising it would need the Hilt test graph and would
 * write into whatever vault happened to be open — the thing CLAUDE.md §8's
 * BUG1(e) exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class SmsCaptureFromPduTest {

    private companion object {
        const val RECEIVED_AT = 1_700_000_000_000L
        const val BANK_SENDER = "VM-HDFCBK"
    }

    private fun intentWith(vararg pdus: ByteArray): Intent =
        Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf(*pdus.map { it as Any }.toTypedArray()))
            putExtra("format", "3gpp")
        }

    /**
     * **The assertion this class exists for.** An alphanumeric bank sender and
     * its body survive the round trip through Android's parser.
     *
     * Alphanumeric rather than a phone number because that is what Indian banks
     * actually send from, and it is the encoding with the awkward
     * semi-octet length field — the numeric case would pass while the real one
     * failed.
     */
    @Test
    fun aBankSmsIsUnwrappedIntact() {
        val body = "Rs.240.50 debited from a/c XX1234 on 26-08-26 to STORE. UPI Ref 123456789012"
        val intent = intentWith(SmsPduFactory.deliver(BANK_SENDER, body))

        val parts = intent.toSmsParts()

        assertThat(parts).hasSize(1)
        assertThat(parts.single().sender).isEqualTo(BANK_SENDER)
        assertThat(parts.single().body).isEqualTo(body)
    }

    /** A plain number works too — not every financial sender is a short code. */
    @Test
    fun aNumericSenderIsUnwrappedIntact() {
        val intent = intentWith(SmsPduFactory.deliver("919876543210", "Rs.50 debited"))

        val parts = intent.toSmsParts()

        assertThat(parts.single().sender).contains("919876543210")
        assertThat(parts.single().body).isEqualTo("Rs.50 debited")
    }

    /**
     * A long bank alert arrives as several PDUs in one intent, and the parts are
     * concatenated in arrival order.
     *
     * This is the case §5.1 cares about: the amount lands in part one and the
     * merchant in part two often enough that parsing the parts separately would
     * fail on exactly the messages that matter most.
     */
    @Test
    fun aSplitMessageIsReassembledInOrder() {
        val first = "Rs.1,240.00 debited from a/c XX1234 on 26-08-26 "
        val second = "to RELIANCE FRESH. Avl Bal Rs.18,300.25"
        val intent = intentWith(
            SmsPduFactory.deliver(BANK_SENDER, first),
            SmsPduFactory.deliver(BANK_SENDER, second),
        )

        val events = normalizeSmsParts(intent.toSmsParts(), RECEIVED_AT)

        assertThat(events).hasSize(1)
        assertThat(events.single().body).isEqualTo(first + second)
        assertThat(events.single().sourceType).isEqualTo(IngestSourceType.SMS)
        // The device's capture time, not the network's timestamp -- the two are
        // different values and conflating them puts a delayed SMS in the wrong day.
        assertThat(events.single().receivedAt).isEqualTo(RECEIVED_AT)
        // SMS has no posting package; null keeps "no such concept" distinct
        // from "empty string" in the raw tables.
        assertThat(events.single().packageName).isNull()
    }

    /**
     * Two senders in one intent stay two messages.
     *
     * Rare, but real, and concatenating across senders would splice one bank's
     * alert onto another's.
     */
    @Test
    fun partsFromDifferentSendersAreNotSpliced() {
        val intent = intentWith(
            SmsPduFactory.deliver(BANK_SENDER, "Rs.240 debited"),
            SmsPduFactory.deliver("AD-ICICIB", "Rs.900 credited"),
        )

        val events = normalizeSmsParts(intent.toSmsParts(), RECEIVED_AT)

        assertThat(events).hasSize(2)
        assertThat(events.map { it.sender }).containsExactly(BANK_SENDER, "AD-ICICIB")
        assertThat(events.first { it.sender == BANK_SENDER }.body).isEqualTo("Rs.240 debited")
    }

    /**
     * An intent carrying nothing usable yields nothing, and does not throw.
     *
     * The receiver runs on every SMS the phone gets, including WAP pushes and
     * malformed PDUs. Throwing here would surface to the user as LedgerFlow
     * crashing whenever a text arrives.
     */
    @Test
    fun anIntentWithNoPdusYieldsNothing() {
        val empty = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)

        assertThat(empty.toSmsParts()).isEmpty()
        assertThat(normalizeSmsParts(empty.toSmsParts(), RECEIVED_AT)).isEmpty()
    }
}
