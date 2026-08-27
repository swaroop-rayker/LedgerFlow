package com.ledgerflow.core.data.ingest

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.database.entity.ParserRuleEntity
import com.ledgerflow.core.database.entity.PendingTransactionEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.InstrumentHint
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.PendingWriteOutcome
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import com.ledgerflow.core.model.PendingStatus
import com.ledgerflow.core.model.RawParseStatus
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Capture, end to end, against a real vault (SPEC.md §5.1, §5.2).
 *
 * The seeding half is the reason this is instrumented rather than a JVM test:
 * the curated allowlists ship as **assets**, and an asset that fails to parse,
 * or is not packaged at all, is a failure no unit test with a hand-built list
 * would ever see. D-10's list is only a default if it actually arrives.
 *
 * Its own database and its own Keystore alias, never the app's (BUG1(e)).
 */
class RawIngestRepositoryInstrumentedTest {

    private companion object {
        const val TEST_DATABASE = "lf-test-ingest.db"
        const val KEYSTORE_ALIAS = "lf-test-ingest"
        const val NOW = 1_700_000_000_000L
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDirectory = File(context.filesDir, "keys-$KEYSTORE_ALIAS")

    private lateinit var session: VaultSession
    private lateinit var repository: DefaultRawIngestRepository
    private var now = NOW

    @Before
    fun setUp() = runBlocking {
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)

        val dekManager = DekManager(
            FileWrappedDekStore(keyDirectory),
            AndroidKeystoreKek(KEYSTORE_ALIAS),
            SecureRandom(),
        )
        session = VaultSession(
            context,
            dekManager,
            Bip39PhraseValidator(),
            Dispatchers.IO,
            TEST_DATABASE,
        )
        session.initialize(VaultInitRequest(Bip39.generate(SecureRandom()), "INR"))

        repository = DefaultRawIngestRepository(
            context = context,
            session = session,
            clock = Clock { now },
            ids = Uuid7Generator(SecureRandom()),
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        runCatching { session.requireDatabase().close() }
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
    }

    private fun deleteKeystoreEntry() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEYSTORE_ALIAS)
        }
    }

    private fun sms(body: String, sender: String = "VM-HDFCBK", at: Long = NOW) = RawIngestEvent(
        sourceType = IngestSourceType.SMS,
        sender = sender,
        body = body,
        receivedAt = at,
    )

    /**
     * **The assets parse and land.** A typo in the JSON, or a missing
     * `assets/` directory, fails here rather than as a phone that silently
     * captures nothing.
     */
    @Test
    fun seedAllowlists_loadsTheCuratedListsFromAssets() = runBlocking {
        repository.seedAllowlists()

        val database = session.requireDatabase()
        assertThat(database.packageAllowlistDao().count()).isGreaterThan(0)
        assertThat(database.senderAllowlistDao().count()).isGreaterThan(0)

        // Spot-checks on the two the product is built around (§3.1).
        assertThat(repository.isPackageAllowed("com.google.android.apps.nbu.paisa.user")).isTrue()
        assertThat(repository.isSenderAllowed("VM-HDFCBK")).isTrue()
        assertThat(repository.isPackageAllowed("com.example.definitely.not.a.bank")).isFalse()
    }

    /**
     * The shipped ruleset parses and lands too (§5.1).
     *
     * Same reasoning as the allowlists: the rules live in an **asset**, and an
     * asset that fails to parse or is not packaged is a failure no unit test
     * with a hand-built rule list would ever see. `GoldenCorpusTest` reads the
     * same file off disk and proves the rules *work*; this proves they arrive on
     * a device and survive the round trip through the table.
     */
    @Test
    fun seedParserRules_loadsTheShippedRulesetFromAssets() = runBlocking {
        repository.seedParserRules()

        val rules = repository.parserRules()
        assertThat(rules).isNotEmpty()
        assertThat(rules.map { it.id }).contains("upi-debit-vpa")
        // Priority order is what the engine relies on for first-match-wins.
        assertThat(rules.map { it.priority }).isInOrder()
        // The field map survived JSON round-tripping into the table.
        assertThat(rules.first { it.id == "upi-debit-vpa" }.fieldMap).isNotEmpty()
        // v7's column: a notification rule knows its instrument even though the
        // message never says the word.
        assertThat(rules.first { it.id == "notification-upi-paid" }.instrumentHint)
            .isEqualTo(InstrumentHint.UPI)
    }

    /**
     * Re-seeding replaces the shipped rules and leaves a user's own alone.
     *
     * That asymmetry is the entire reason rules live in a table as well as in
     * the asset (§5.1's rule editor). A ruleset bump that wiped a rule someone
     * wrote would be destroying work with no way to get it back.
     */
    @Test
    fun seedParserRules_neverTouchesAUserWrittenRule() = runBlocking {
        repository.seedParserRules()
        val database = session.requireDatabase()
        database.parserRuleDao().insertAll(
            listOf(
                ParserRuleEntity(
                    id = "mine-1",
                    rulesetVersion = 1,
                    priority = 1,
                    senderPattern = "MYBANK",
                    bodyPattern = "paid (?<a>[0-9]+)",
                    fieldMapJson = """{"amount":"a"}""",
                    direction = "DEBIT",
                    instrumentHint = null,
                    confidenceBase = 0.6,
                    enabled = true,
                    isUserDefined = true,
                ),
            ),
        )

        repository.seedParserRules()

        assertThat(repository.parserRules().map { it.id }).contains("mine-1")
    }

    /** Idempotent: every launch re-seeds, and the second run must change nothing. */
    @Test
    fun seedAllowlists_isIdempotent() = runBlocking {
        repository.seedAllowlists()
        val afterFirst = session.requireDatabase().packageAllowlistDao().count()

        repository.seedAllowlists()

        assertThat(session.requireDatabase().packageAllowlistDao().count()).isEqualTo(afterFirst)
    }

    @Test
    fun record_persistsAnSmsVerbatim() = runBlocking {
        val outcome = repository.record(sms("Rs 240.50 debited from a/c XX1234"))

        assertThat(outcome).isInstanceOf(CaptureOutcome.Recorded::class.java)
        val stored = session.requireDatabase().smsRawDao()
            .withStatus(RawParseStatus.CAPTURED, limit = 10)
            .single()
        assertThat(stored.sender).isEqualTo("VM-HDFCBK")
        assertThat(stored.body).isEqualTo("Rs 240.50 debited from a/c XX1234")
        assertThat(stored.retentionExpiresAt).isGreaterThan(stored.receivedAt)
    }

    /**
     * A re-delivery inside the same minute is absorbed; the same text an hour
     * later is a second transaction and must survive.
     *
     * Two ₹50 top-ups on one day are two payments, and a dedupe that swallowed
     * the second would be losing the user's money from their ledger.
     */
    @Test
    fun record_dedupesARedeliveryButNotAGenuineRepeat() = runBlocking {
        val first = repository.record(sms("Rs 50 debited", at = NOW))
        val redelivered = repository.record(sms("Rs 50 debited", at = NOW + 500))
        val hourLater = repository.record(sms("Rs 50 debited", at = NOW + 3_600_000))

        assertThat(first).isInstanceOf(CaptureOutcome.Recorded::class.java)
        assertThat(redelivered).isEqualTo(CaptureOutcome.AlreadySeen)
        assertThat(hourLater).isInstanceOf(CaptureOutcome.Recorded::class.java)
        assertThat(session.requireDatabase().smsRawDao().count()).isEqualTo(2)
    }

    /**
     * §5.1's order: the row exists first, the allowlist judges it after.
     *
     * A non-financial sender is *marked*, never deleted — the record survives
     * and D-09's retention is what eventually takes the body.
     */
    @Test
    fun triage_marksNonFinancialSendersAndLeavesTheRest() = runBlocking {
        repository.seedAllowlists()
        repository.record(sms("Rs 240 debited", sender = "VM-HDFCBK"))
        repository.record(sms("dinner at 8?", sender = "+919876543210", at = NOW + 60_000))

        val filtered = repository.triageCapturedSms(limit = 50)

        assertThat(filtered).isEqualTo(1)
        val dao = session.requireDatabase().smsRawDao()
        assertThat(dao.count()).isEqualTo(2)
        // The bank message is still in flight, awaiting a ruleset that does not
        // exist yet -- not given a verdict nothing produced.
        assertThat(dao.withStatus(RawParseStatus.CAPTURED, limit = 50).map { it.sender })
            .containsExactly("VM-HDFCBK")
        assertThat(dao.withStatus(RawParseStatus.SENDER_NOT_ALLOWLISTED, limit = 50))
            .hasSize(1)
    }

    /** D-09: past retention the body goes and the row stays. */
    @Test
    fun purgeExpiredBodies_clearsOnlyWhatHasExpired() = runBlocking {
        repository.record(sms("Rs 240 debited"))

        // Nothing is due yet.
        assertThat(repository.purgeExpiredBodies()).isEqualTo(0)

        // Ninety-one days later.
        now = NOW + 91L * 24 * 60 * 60 * 1000
        assertThat(repository.purgeExpiredBodies()).isEqualTo(1)

        val dao = session.requireDatabase().smsRawDao()
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.withStatus(RawParseStatus.CAPTURED, limit = 10).single().body).isEmpty()
    }

    /**
     * **`Bug_SmsWithTheAppClosed_IsStillCaptured`** — §5.1's never-drop rule,
     * for the case that was breaking it in production.
     *
     * Everything that opened the vault ran from `AppViewModel`, so a message
     * arriving with no Activity alive reached a receiver whose database call
     * failed, and the SMS was logged and lost. Found on the owner's device: a
     * live UPI payment vanished, while three credits that landed minutes later
     * with the app open were captured. For the whole of P2, capture only worked
     * while somebody was looking at it.
     *
     * `session.close()` is exactly that state — no database, key material
     * intact — which is what a process the OEM reaped and the system restarted
     * for a broadcast looks like.
     */
    @Test
    fun Bug_SmsWithTheAppClosed_IsStillCaptured() = runBlocking {
        session.close()

        val outcome = repository.record(sms("Sent Rs.69.00 To RAMESH KUMAR"))

        assertThat(outcome).isInstanceOf(CaptureOutcome.Recorded::class.java)
        // On disk, verbatim, before anything judged it -- which is the whole
        // point of writing first (§5.1).
        val rawId = (outcome as CaptureOutcome.Recorded).rawId
        assertThat(session.requireDatabase().smsRawDao().byId(rawId)?.body)
            .isEqualTo("Sent Rs.69.00 To RAMESH KUMAR")
    }

    /**
     * ...and the rest of the pipeline opens it too.
     *
     * `ParseIngestWorker` wakes on its own schedule with no Activity behind it,
     * so a triage or parse pass that needed the UI to have run first would leave
     * captured messages sitting at `CAPTURED` until the user next opened the
     * app — the same defect one step later.
     */
    @Test
    fun theWorkersPasses_openTheVaultToo() = runBlocking {
        repository.seedAllowlists()
        repository.record(sms("Dinner at 8?", sender = "+919876543210"))
        session.close()

        // Each of these is reachable from the worker with nothing else running.
        assertThat(repository.triageCapturedSms(limit = 10)).isEqualTo(1)
        assertThat(repository.isSenderAllowed("VM-HDFCBK-T")).isTrue()
        assertThat(repository.parserRules()).isEmpty()
    }

    /**
     * A vault that genuinely cannot open refuses, and does not throw.
     *
     * The distinction the fix rests on: "locked because nobody opened it yet" is
     * recoverable and must not lose the message, while "there is no vault" is
     * not, and the honest answer is to refuse. §7.3 routes the user to Recovery
     * on their next launch; **nothing here wipes anything**, and a
     * `NotificationListenerService` must not be taken down by an exception
     * either.
     */
    @Test
    fun aVaultThatCannotOpen_refusesRatherThanThrows() = runBlocking {
        session.close()
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()

        assertThat(repository.isPackageAllowed("com.google.android.apps.nbu.paisa.user")).isFalse()
        assertThat(repository.isSenderAllowed("VM-HDFCBK")).isFalse()
        assertThat(repository.record(sms("Rs 240 debited")))
            .isInstanceOf(CaptureOutcome.Failed::class.java)
        assertThat(repository.purgeExpiredBodies()).isEqualTo(0)
        assertThat(repository.triageCapturedSms(limit = 10)).isEqualTo(0)
    }

    /**
     * **The owner's real sender IDs, from their own phone.** Every one carries a
     * trailing DLT suffix, and the shipped allowlist did not.
     *
     * TRAI's DLT header format is `XX-ENTITY-C`, where the final character is
     * the route class -- `T` transactional, `S` service, `P` promotional, `G`
     * government. The seeded patterns were written as `*-HDFCBK`, which is what
     * the *entity* looks like; `GLOB` anchors the whole string, so the real
     * `VM-HDFCBK-T` matched nothing and every bank SMS on the device was triaged
     * `SENDER_NOT_ALLOWLISTED`.
     *
     * Instrumented because `GLOB` is SQLite's, not Kotlin's. A JVM test with
     * `fnmatch` would agree with whatever I believed about the semantics rather
     * than with the database that actually decides.
     */
    @Test
    fun isSenderAllowed_acceptsTheRealDltHeaders_fromTheOwnersDevice() = runBlocking {
        repository.seedAllowlists()

        // Observed on SM-S721B, 2026-08-27. The first is the payment that did
        // not register; the rest are the other financial senders on the device.
        assertThat(repository.isSenderAllowed("VM-HDFCBK-T")).isTrue()
        assertThat(repository.isSenderAllowed("AD-HDFCBK-S")).isTrue()
        assertThat(repository.isSenderAllowed("AX-SBYONO-S")).isTrue()
        assertThat(repository.isSenderAllowed("AD-SBYONO-S")).isTrue()

        // The bare entity form must keep working -- older headers still use it,
        // and a user-written pattern is allowed to be exact.
        assertThat(repository.isSenderAllowed("VM-HDFCBK")).isTrue()
    }

    /**
     * Promotional headers stay out, and that is a decision rather than an
     * oversight.
     *
     * §5.1's never-drop rule makes an allowlisted sender's unparseable message a
     * `PENDING` row with `confidence = 0`. Admitting the `-P` route class would
     * therefore turn every bank marketing SMS into an Inbox item the user has to
     * dismiss by hand -- the never-drop rule weaponised against the queue it
     * exists to protect. A bank's promotional header is not a transaction.
     */
    @Test
    fun isSenderAllowed_rejectsThePromotionalRouteClass() = runBlocking {
        repository.seedAllowlists()

        assertThat(repository.isSenderAllowed("VM-HDFCBK-P")).isFalse()
        assertThat(repository.isSenderAllowed("AD-SBYONO-P")).isFalse()
        // Still nothing like a bank.
        assertThat(repository.isSenderAllowed("VM-PIZZAS-T")).isFalse()
    }

    // ── §16 Q14: re-triage after an allowlist change ─────────────────────────

    /**
     * **The owner's stuck payment, recovered.**
     *
     * A message rejected under the v1 patterns is reconsidered once the
     * allowlist gains a pattern that accepts it. Without this the v1 defect was
     * permanent for everything received before the fix — the pattern change made
     * the *next* bank SMS work and could not reach the last one.
     *
     * The rejection is produced by triage rather than written by hand, so what
     * is under test is the real round trip: rejected by one allowlist,
     * re-admitted by another.
     */
    @Test
    fun retriage_afterTheAllowlistLearnsTheSender_readmitsTheMessage() = runBlocking {
        val database = session.requireDatabase()
        // An allowlist that does not know this bank yet.
        database.senderAllowlistDao().insertMissing(
            listOf(SenderAllowlistEntity("*-OTHERB", "Some other bank", true)),
        )
        val rawId = captureOneSms("Sent Rs.2.00 From HDFC Bank A/C *1234 To RAMESH KUMAR")

        assertThat(repository.triageCapturedSms(limit = 10)).isEqualTo(1)
        assertThat(database.smsRawDao().byId(rawId)?.parseStatus)
            .isEqualTo(RawParseStatus.SENDER_NOT_ALLOWLISTED)

        // The v2 seed arrives, carrying the DLT-suffixed patterns.
        repository.seedAllowlists()

        assertThat(repository.retriageRejectedSms(limit = 50)).isEqualTo(1)
        // Back in the queue at the point it left, not straight to a candidate.
        assertThat(database.smsRawDao().byId(rawId)?.parseStatus)
            .isEqualTo(RawParseStatus.CAPTURED)
        assertThat(repository.capturedEvents(limit = 10).map { it.rawId }).contains(rawId)
    }

    /**
     * It runs once per change, not once per worker pass.
     *
     * The rejected pile is the user's ordinary personal SMS and grows without
     * bound; re-checking all of it every time a text arrives would be the
     * expensive kind of thorough. The fingerprint is what makes the sweep rare.
     */
    @Test
    fun retriage_isANoOp_whenTheAllowlistHasNotChanged() = runBlocking {
        repository.seedAllowlists()
        captureOneSms("Personal message from a friend.", sender = "+919876543210")
        repository.triageCapturedSms(limit = 10)

        // First call establishes the marker...
        repository.retriageRejectedSms(limit = 50)
        // ...and the second finds nothing to reconsider.
        assertThat(repository.retriageRejectedSms(limit = 50)).isEqualTo(0)
    }

    /**
     * A sender the allowlist still does not know stays rejected.
     *
     * Re-triage reconsiders; it does not admit. Personal SMS must not become
     * Inbox rows because someone added their bank.
     */
    @Test
    fun retriage_leavesAStillUnknownSenderRejected() = runBlocking {
        val rawId = captureOneSms("Dinner at 8?", sender = "+919876543210")
        repository.triageCapturedSms(limit = 10)

        repository.seedAllowlists()
        assertThat(repository.retriageRejectedSms(limit = 50)).isEqualTo(0)

        assertThat(session.requireDatabase().smsRawDao().byId(rawId)?.parseStatus)
            .isEqualTo(RawParseStatus.SENDER_NOT_ALLOWLISTED)
    }

    /**
     * **D-09 bounds it.** A body blanked by retention has nothing left to parse,
     * so re-admitting the row would produce a `PENDING` candidate backed by an
     * empty message — worse than leaving it marked.
     *
     * This is why the 90-day window and this feature are the same design: the
     * body is kept precisely so a message stays replayable (§16 Q1), and past
     * that window there is nothing to replay.
     */
    @Test
    fun retriage_skipsRowsWhoseBodyRetentionHasCleared() = runBlocking {
        val database = session.requireDatabase()
        database.senderAllowlistDao().insertMissing(
            listOf(SenderAllowlistEntity("*-OTHERB", "Some other bank", true)),
        )
        val rawId = captureOneSms("Sent Rs.2.00 From HDFC Bank A/C *1234 To RAMESH KUMAR")
        repository.triageCapturedSms(limit = 10)

        // Retention catches up with it.
        now = NOW + TimeUnit.DAYS.toMillis(91)
        assertThat(repository.purgeExpiredBodies()).isEqualTo(1)

        repository.seedAllowlists()
        assertThat(repository.retriageRejectedSms(limit = 50)).isEqualTo(0)
        assertThat(database.smsRawDao().byId(rawId)?.parseStatus)
            .isEqualTo(RawParseStatus.SENDER_NOT_ALLOWLISTED)
    }

    /**
     * A full page leaves the marker alone, so the next run continues.
     *
     * Advancing it on a partial sweep would strand every row the pass did not
     * reach — permanently, because nothing would ever look at them again. That
     * is the failure this feature exists to undo, reintroduced by its own
     * pagination.
     */
    @Test
    fun retriage_whenThePageIsFull_doesNotAdvanceTheMarker() = runBlocking {
        val database = session.requireDatabase()
        database.senderAllowlistDao().insertMissing(
            listOf(SenderAllowlistEntity("*-OTHERB", "Some other bank", true)),
        )
        repeat(3) { index ->
            repository.record(
                sms("Sent Rs.${index + 1}.00 From HDFC Bank A/C *1234 To SHOP", at = NOW + index),
            )
        }
        repository.triageCapturedSms(limit = 10)
        repository.seedAllowlists()

        // One row at a time. The marker must not advance while a backlog remains.
        assertThat(repository.retriageRejectedSms(limit = 1)).isEqualTo(1)
        assertThat(repository.retriageRejectedSms(limit = 1)).isEqualTo(1)
        assertThat(repository.retriageRejectedSms(limit = 1)).isEqualTo(1)

        assertThat(
            database.smsRawDao().withStatus(RawParseStatus.CAPTURED, limit = 10),
        ).hasSize(3)
    }

    // ── P2-4: pending_transaction ────────────────────────────────────────────
    //
    // These are instrumented rather than JVM tests for the reason the P2-4
    // idempotency guarantee actually rests on: it is a *database* transaction
    // spanning two tables. A fake can be written to behave atomically; only real
    // Room and real SQLCipher can be observed to.

    /**
     * A bank SMS as the ruleset extracts one: account, payee, reference.
     *
     * The key is built by [DedupeKey] rather than typed out, so a change to the
     * bucket rule reaches these tests instead of leaving them asserting a shape
     * production no longer writes.
     */
    private fun candidate(
        amountMinor: Long? = 78_800L,
        source: EntrySource = EntrySource.SMS,
        confidence: Double = 0.9,
        merchantRaw: String? = "COFFEE HOUSE",
        accountLast4: String? = "1234",
        referenceNo: String? = null,
        rawRefId: String = "unused",
    ): PendingCandidate {
        val extracted = ExtractedTransaction(
            amount = amountMinor?.let(::Money),
            direction = ExtractedDirection.DEBIT,
            merchantRaw = merchantRaw,
            accountLast4 = accountLast4,
            instrumentHint = InstrumentHint.UPI,
            referenceNo = referenceNo,
            confidence = confidence,
        )
        return PendingCandidate(
            source = source,
            extracted = extracted,
            dedupeKey = DedupeKey.compute(extracted, rawRefId),
        )
    }

    /**
     * The paying app's notification for the same payment: an amount and a payee,
     * no account and no date. That is not a simplification -- 0 of 5 matched
     * notification fixtures extract either.
     */
    private fun notificationCandidate(
        amountMinor: Long = 78_800L,
        confidence: Double = 0.7,
        merchantRaw: String? = "Coffee House",
    ) = candidate(
        amountMinor = amountMinor,
        source = EntrySource.NOTIFICATION,
        confidence = confidence,
        merchantRaw = merchantRaw,
        accountLast4 = null,
    )

    private suspend fun captureOneNotification(body: String = "Paid Rs.788 to Coffee House"): String {
        val outcome = repository.record(
            RawIngestEvent(
                sourceType = IngestSourceType.NOTIFICATION,
                sender = "Google Pay",
                body = body,
                receivedAt = NOW,
                packageName = "com.google.android.apps.nbu.paisa.user",
            ),
        )
        return (outcome as CaptureOutcome.Recorded).rawId
    }

    private suspend fun liveCandidates() = session.requireDatabase().pendingTransactionDao()
        .withStatus(PendingStatus.PENDING, limit = 50)
        .filter { it.suppressedById == null }

    private suspend fun captureOneSms(
        body: String = "Sent Rs.788.00 To COFFEE HOUSE",
        sender: String = "VM-HDFCBK-T",
    ): String {
        val outcome = repository.record(sms(body, sender = sender))
        return (outcome as CaptureOutcome.Recorded).rawId
    }

    /**
     * The candidate lands, and it lands with the verdict.
     *
     * The `PARSED` assertion is not decoration: the two writes are one
     * transaction, and a candidate present with the raw row still `CAPTURED`
     * would mean the atomicity claim is false in the direction that produces
     * duplicates.
     */
    @Test
    fun recordParseOutcome_writesTheCandidateAndTheVerdictTogether() = runBlocking {
        val rawId = captureOneSms()

        val outcome = repository.recordParseOutcome(rawId, "upi-debit-vpa", candidate())

        assertThat(outcome).isInstanceOf(PendingWriteOutcome.Created::class.java)
        val pendingId = (outcome as PendingWriteOutcome.Created).pendingId

        val database = session.requireDatabase()
        val row = database.pendingTransactionDao().byId(pendingId)
        assertThat(row).isNotNull()
        assertThat(row?.status).isEqualTo(PendingStatus.PENDING)
        assertThat(row?.source).isEqualTo(EntrySource.SMS)
        assertThat(row?.rawRefId).isEqualTo(rawId)
        // Amount and direction. The minute and the discriminator moved out of the
        // key at P2-5 -- both diverge by source on real messages (see DedupeKey).
        assertThat(row?.dedupeKey).isEqualTo("78800|DEBIT")
        assertThat(row?.suppressedById).isNull()
        assertThat(row?.approvedEntryId).isNull()
        assertThat(row?.reviewedAt).isNull()
        // The payload is readable back, which is what :feature:inbox needs at P2-6.
        assertThat(row?.extractedJson).contains("\"amountMinor\":78800")

        assertThat(database.smsRawDao().byId(rawId)?.parseStatus).isEqualTo(RawParseStatus.PARSED)
        assertThat(database.smsRawDao().byId(rawId)?.matchedRuleId).isEqualTo("upi-debit-vpa")
    }

    /**
     * **§5.1's never-drop rule, at the table.** A financial SMS no rule
     * understands still becomes a `PENDING` row with `confidence = 0` and
     * `needs_manual_fill = 1`. The owner's own first real HDFC message was one
     * of these.
     */
    @Test
    fun recordParseOutcome_unmatchedMessage_stillWritesAPendingRowWithZeroConfidence() =
        runBlocking {
            val rawId = captureOneSms("Wording no rule in this build has ever seen.")

            val outcome = repository.recordParseOutcome(
                rawId,
                ruleId = null,
                candidate = PendingCandidate(
                    source = EntrySource.SMS,
                    extracted = ExtractedTransaction(),
                    dedupeKey = "raw:$rawId",
                ),
            )

            val pendingId = (outcome as PendingWriteOutcome.Created).pendingId
            val database = session.requireDatabase()
            val row = database.pendingTransactionDao().byId(pendingId)

            assertThat(row?.confidence).isEqualTo(0.0)
            assertThat(row?.needsManualFill).isTrue()
            assertThat(row?.status).isEqualTo(PendingStatus.PENDING)
            assertThat(database.smsRawDao().byId(rawId)?.parseStatus)
                .isEqualTo(RawParseStatus.UNMATCHED)
        }

    /**
     * **Idempotency.** WorkManager re-runs the worker routinely. A raw row that
     * already produced a candidate produces no second one, or the user's Inbox
     * grows a copy of every transaction each time the phone wakes.
     */
    @Test
    fun recordParseOutcome_runTwice_createsOnlyOnePendingRow() = runBlocking {
        val rawId = captureOneSms()

        val first = repository.recordParseOutcome(rawId, "upi-debit-vpa", candidate())
        val second = repository.recordParseOutcome(rawId, "upi-debit-vpa", candidate())

        assertThat(first).isInstanceOf(PendingWriteOutcome.Created::class.java)
        assertThat(second).isInstanceOf(PendingWriteOutcome.AlreadyPending::class.java)
        assertThat((second as PendingWriteOutcome.AlreadyPending).pendingId)
            .isEqualTo((first as PendingWriteOutcome.Created).pendingId)
        assertThat(session.requireDatabase().pendingTransactionDao().count()).isEqualTo(1)
    }

    /**
     * **`Dedupe_SameTxnAcrossSources_ProducesOnePending`** — the named test
     * CLAUDE.md §7 requires, and the reason §3.1 exists.
     *
     * One UPI payment fires a bank SMS *and* a GPay notification. The SMS
     * carries an account and a reference; the notification carries a payee and
     * nothing else. They must reach the user as one row to review, and the other
     * must still be there to look at.
     */
    @Test
    fun Dedupe_SameTxnAcrossSources_ProducesOnePending() = runBlocking {
        val smsRaw = captureOneSms()
        val notifRaw = captureOneNotification()

        val first = repository.recordParseOutcome(smsRaw, "hdfc-upi-sent", candidate())
        val second = repository.recordParseOutcome(
            notifRaw,
            "notification-upi-paid",
            notificationCandidate(),
        )

        assertThat(first).isInstanceOf(PendingWriteOutcome.Created::class.java)
        assertThat(second).isInstanceOf(PendingWriteOutcome.Suppressed::class.java)

        // One row to review...
        assertThat(liveCandidates()).hasSize(1)
        assertThat(liveCandidates().single().source).isEqualTo(EntrySource.SMS)

        // ...and the loser retained and reachable, never discarded (§3.1).
        val database = session.requireDatabase()
        assertThat(database.pendingTransactionDao().count()).isEqualTo(2)
        val suppressed = (second as PendingWriteOutcome.Suppressed)
        assertThat(database.pendingTransactionDao().byId(suppressed.pendingId)?.suppressedById)
            .isEqualTo((first as PendingWriteOutcome.Created).pendingId)

        // §3.1: the suppressed candidate's raw row records it.
        assertThat(database.notificationRawDao().byId(notifRaw)?.parseStatus)
            .isEqualTo(RawParseStatus.DUPLICATE_SUPPRESSED)
        // ...and the winner's does not.
        assertThat(database.smsRawDao().byId(smsRaw)?.parseStatus)
            .isEqualTo(RawParseStatus.PARSED)
    }

    /**
     * The order this actually happens in.
     *
     * The paying app notifies first and sparsely; the bank SMS lands seconds
     * later carrying the account and the reference. §3.1 says keep the
     * higher-confidence extraction, so the incumbent is suppressed in favour of
     * the arrival — the flip is the common path, not the exotic one.
     */
    @Test
    fun recordParseOutcome_whenTheRicherMessageArrivesSecond_theIncumbentIsSuppressed() =
        runBlocking {
            val notifRaw = captureOneNotification()
            val smsRaw = captureOneSms()

            val first = repository.recordParseOutcome(
                notifRaw,
                "notification-upi-paid",
                notificationCandidate(),
            )
            val second = repository.recordParseOutcome(smsRaw, "hdfc-upi-sent", candidate())

            val winner = (second as PendingWriteOutcome.Created)
            assertThat(winner.supersededPendingId)
                .isEqualTo((first as PendingWriteOutcome.Created).pendingId)

            assertThat(liveCandidates()).hasSize(1)
            assertThat(liveCandidates().single().source).isEqualTo(EntrySource.SMS)

            val database = session.requireDatabase()
            // The notification's raw row is re-marked, and keeps the rule that
            // parsed it -- `updateStatusOnly`, not `updateStatus`.
            assertThat(database.notificationRawDao().byId(notifRaw)?.parseStatus)
                .isEqualTo(RawParseStatus.DUPLICATE_SUPPRESSED)
            assertThat(database.notificationRawDao().byId(notifRaw)?.matchedRuleId)
                .isEqualTo("notification-upi-paid")
        }

    /**
     * **An approved candidate is never suppressed retroactively.**
     *
     * It has a `ledger_entry` behind it, and hiding the candidate would orphan
     * the only record of how that entry got there. The DAO's `status = 'PENDING'`
     * predicate is what enforces this; the later, richer arrival yields instead.
     */
    @Test
    fun recordParseOutcome_neverFlipsAnApprovedIncumbent() = runBlocking {
        val notifRaw = captureOneNotification()
        val smsRaw = captureOneSms()
        val database = session.requireDatabase()

        // An already-approved candidate for the same payment, inserted directly
        // rather than by driving approval -- this test is about what dedupe does
        // when it meets one, not about how it got there.
        database.pendingTransactionDao().insert(
            PendingTransactionEntity(
                id = "already-approved",
                source = EntrySource.NOTIFICATION,
                dedupeKey = notificationCandidate().dedupeKey,
                suppressedById = null,
                rawRefId = notifRaw,
                extractedJson = """{"v":1,"amountMinor":78800,"direction":"DEBIT",""" +
                    """"merchantRaw":"Coffee House","confidence":0.7}""",
                confidence = 0.7,
                status = PendingStatus.APPROVED,
                needsManualFill = false,
                createdAt = NOW,
                reviewedAt = NOW,
                approvedEntryId = "entry-1",
            ),
        )

        // The bank SMS arrives after, and scores higher.
        val outcome = repository.recordParseOutcome(smsRaw, "hdfc-upi-sent", candidate())

        // It yields rather than orphaning the entry behind the approved row.
        assertThat(outcome).isInstanceOf(PendingWriteOutcome.Suppressed::class.java)
        assertThat(database.pendingTransactionDao().byId("already-approved")?.suppressedById)
            .isNull()
        assertThat(database.smsRawDao().byId(smsRaw)?.parseStatus)
            .isEqualTo(RawParseStatus.DUPLICATE_SUPPRESSED)
    }

    /**
     * Two genuinely different payments of the same amount, seconds apart, stay
     * two candidates when something they both carry disagrees.
     */
    @Test
    fun recordParseOutcome_differentPayees_areNotDuplicates() = runBlocking {
        val firstRaw = captureOneSms("Sent Rs.788.00 To COFFEE HOUSE")
        val secondRaw = repository.record(
            sms("Sent Rs.788.00 To BOOK SHOP", sender = "AD-HDFCBK", at = NOW + 30_000L),
        ).let { (it as CaptureOutcome.Recorded).rawId }

        repository.recordParseOutcome(firstRaw, "hdfc-upi-sent", candidate())
        val second = repository.recordParseOutcome(
            secondRaw,
            "hdfc-upi-sent",
            candidate(merchantRaw = "BOOK SHOP", accountLast4 = null),
        )

        assertThat(second).isInstanceOf(PendingWriteOutcome.Created::class.java)
        assertThat(liveCandidates()).hasSize(2)
    }

    /**
     * **§5.1's never-drop rule, defended against the dedupe layer.**
     *
     * Two unparseable messages have no amount, so they get non-colliding keys
     * and can never suppress each other. Without that, the second unmatched
     * alert of the window would vanish as a "duplicate" of the first — a
     * financial SMS made invisible by dedupe rather than by the parser.
     */
    @Test
    fun recordParseOutcome_twoUnparseableMessages_bothSurvive() = runBlocking {
        val firstRaw = captureOneSms("Wording no rule understands.")
        val secondRaw = repository.record(
            sms("Different wording no rule understands.", at = NOW + 20_000L),
        ).let { (it as CaptureOutcome.Recorded).rawId }

        fun blank(rawId: String) = PendingCandidate(
            source = EntrySource.SMS,
            extracted = ExtractedTransaction(),
            dedupeKey = DedupeKey.compute(ExtractedTransaction(), rawId),
        )

        repository.recordParseOutcome(firstRaw, null, blank(firstRaw))
        val second = repository.recordParseOutcome(secondRaw, null, blank(secondRaw))

        assertThat(second).isInstanceOf(PendingWriteOutcome.Created::class.java)
        assertThat(liveCandidates()).hasSize(2)
    }

    /**
     * **Law 1 at the table.** A candidate is not a ledger row: nothing P2-4
     * writes reaches `ledger_entry`, and the Inbox queue is invisible to every
     * total in the app.
     */
    @Test
    fun recordParseOutcome_writesNothingIntoTheLedger() = runBlocking {
        val rawId = captureOneSms()

        repository.recordParseOutcome(rawId, "upi-debit-vpa", candidate())

        val database = session.requireDatabase()
        assertThat(database.pendingTransactionDao().count()).isEqualTo(1)
        // Per book, never summed. Law 2: no query touching `ledger_entry` may
        // combine the two, and that holds for a test's assertion as much as for
        // a dashboard figure (ADR-0002).
        assertThat(database.ledgerEntryDao().countForLedger(LedgerType.DEBIT)).isEqualTo(0)
        assertThat(database.ledgerEntryDao().countForLedger(LedgerType.CREDIT)).isEqualTo(0)
    }

    /**
     * A closed vault is opened, not treated as a dead end.
     *
     * `ParseIngestWorker` wakes with no Activity alive, so this path is reached
     * with the vault shut more often than not. Refusing here would leave the raw
     * row at `CAPTURED` until the user next opened the app — the message would
     * survive, but the candidate would not appear until someone looked, which is
     * the same defect `Bug_SmsWithTheAppClosed_IsStillCaptured` covers one step
     * earlier.
     */
    @Test
    fun recordParseOutcome_onAClosedVault_opensItAndWrites() = runBlocking {
        val rawId = captureOneSms()
        session.close()

        assertThat(repository.recordParseOutcome(rawId, "hdfc-upi-sent", candidate()))
            .isInstanceOf(PendingWriteOutcome.Created::class.java)
    }

    /**
     * ...but a vault that cannot be opened still refuses rather than throwing,
     * and leaves the raw row at `CAPTURED` so a later pass can retry it.
     */
    @Test
    fun recordParseOutcome_whenTheVaultCannotOpen_refusesRatherThanThrows() = runBlocking {
        val rawId = captureOneSms()
        session.close()
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()

        assertThat(repository.recordParseOutcome(rawId, null, candidate()))
            .isInstanceOf(PendingWriteOutcome.Failed::class.java)
    }
}
