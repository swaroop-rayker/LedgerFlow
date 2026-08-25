package com.ledgerflow.feature.ingest.adapters

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.ledgerflow.core.common.di.IoDispatcher
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.feature.ingest.pipeline.IngestEventSink
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SMS capture — **`smsFull` flavour only** (SPEC.md §5.1).
 *
 * ## The 10-second rule
 *
 * CLAUDE.md §7 is unambiguous about this class:
 *
 * > The receiver has ~10 seconds before the system kills it. It does exactly one
 * > thing: **write the raw SMS to `sms_raw` and enqueue a Worker.** No parsing,
 * > no network, no DB joins in the receiver.
 *
 * So it normalizes and hands off, and nothing else. The write-and-enqueue half
 * lives behind [IngestEventSink] and arrives at P2 — the skeleton's sink drops
 * the event — but the *shape* here is the final one, because this class is what
 * P2 builds on rather than replaces.
 *
 * ## Two things that are easy to get wrong
 *
 * `abortBroadcast()` is never called. Other SMS apps must still receive the
 * message; LedgerFlow is an observer, not the default handler, and swallowing a
 * user's messages would be a spectacular bug. `SMS_RECEIVED` is not delivered as
 * an abortable ordered broadcast to a non-default handler anyway, but the rule
 * is written down in CLAUDE.md §7 because it has to survive the day someone
 * changes the filter.
 *
 * The work happens under `goAsync()`, which keeps the process alive past
 * `onReceive` returning. Without it the coroutine would race the system tearing
 * the receiver down, and the symptom — an SMS that vanishes only when the app
 * happens to be cold — is close to undebuggable.
 */
@AndroidEntryPoint
public class SmsIngestReceiver : BroadcastReceiver() {

    @Inject internal lateinit var sink: IngestEventSink

    @Inject internal lateinit var clock: Clock

    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher

    /**
     * There is deliberately **no `super.onReceive(context, intent)` call here**,
     * and the injected fields above are still set before the first line runs.
     *
     * Hilt's docs show that super call, and in Kotlin it does not compile:
     * `BroadcastReceiver.onReceive` is abstract, so `super.onReceive` is
     * "abstract member cannot be accessed directly". The generated
     * `Hilt_SmsIngestReceiver` carries an `@OnReceiveBytecodeInjectionMarker`
     * and the Hilt **Gradle plugin** inserts the call into this method's
     * bytecode after compilation — which is why the Java sample and this file
     * end up with the same behaviour from different source. Removing the Hilt
     * Gradle plugin would leave every field below unset with nothing to say so.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val events = normalizeSmsParts(parts = intent.toSmsParts(), receivedAt = clock.nowMillis())
        if (events.isEmpty()) return

        val pendingResult = goAsync()
        // A capture adapter has no recovery: the message is already out of the
        // intent and there is nothing to retry against. The handler is here so
        // that a failure downstream is logged rather than crashing the app from
        // a background broadcast -- which the user would experience as
        // LedgerFlow crashing every time they receive a text.
        val handler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Failed to hand off ${events.size} captured SMS", throwable)
        }
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher + handler)
        scope.launch {
            try {
                events.forEach { event -> sink.submit(event) }
            } finally {
                pendingResult.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        private const val TAG = "SmsIngest"
    }
}

/** One captured SMS as the platform hands it over: an address and one body part. */
internal data class SmsPart(val sender: String, val body: String)

/**
 * Reassembles multipart messages and builds the source-agnostic events.
 *
 * A long SMS arrives as several `SmsMessage` objects in one intent, and bank
 * alerts are routinely long enough to split — the amount lands in part one and
 * the merchant in part two often enough that parsing the parts separately would
 * fail on exactly the messages that matter most. Parts are concatenated in
 * arrival order, which is the order the carrier numbered them.
 *
 * Grouping is by sender because a single intent can, rarely, carry messages from
 * more than one number. `groupBy` preserves both the group order and the order
 * within each group, so no sort is needed here and none should be added: sorting
 * by body would scramble a split message.
 *
 * Pure, and split out of the receiver for that reason — `SmsMessage` cannot be
 * constructed in a JVM unit test, and this reassembly is the only real logic in
 * the class.
 */
internal fun normalizeSmsParts(parts: List<SmsPart>, receivedAt: Long): List<RawIngestEvent> =
    parts.groupBy { it.sender }
        .map { (sender, senderParts) ->
            RawIngestEvent(
                sourceType = IngestSourceType.SMS,
                sender = sender,
                body = senderParts.joinToString(separator = "") { it.body },
                receivedAt = receivedAt,
                // SMS has no posting package. Null rather than blank, so "no
                // such concept" stays distinguishable in the raw tables at P2.
                packageName = null,
            )
        }

/**
 * Unwraps the PDUs.
 *
 * A part with no address or no body is dropped here rather than carried as an
 * empty string: it is a malformed PDU or a class-0/WAP push, not a financial
 * message. This does not collide with §5.1's "never silently drop a financial
 * SMS" — that rule is about an *allowlisted sender* whose text did not parse,
 * and a part with no sender at all can be neither allowlisted nor parsed.
 */
private fun Intent.toSmsParts(): List<SmsPart> =
    Telephony.Sms.Intents.getMessagesFromIntent(this)
        .orEmpty()
        .filterNotNull()
        .mapNotNull { message: SmsMessage ->
            val sender = message.originatingAddress
            val body = message.messageBody
            if (sender.isNullOrBlank() || body == null) null else SmsPart(sender, body)
        }
