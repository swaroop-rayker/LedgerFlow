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
import com.ledgerflow.core.domain.ingest.CaptureOutcome
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
     * A locked vault answers "no" rather than throwing.
     *
     * That is the correct answer for the privacy gate: reading a notification
     * that could not then be stored would break §5.2's rule for no benefit. It
     * also matters that nothing here propagates — the caller is a
     * `NotificationListenerService` and an exception would take it down.
     */
    @Test
    fun aLockedVault_refusesRatherThanThrows() = runBlocking {
        session.close()

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

    // ── P2-4: pending_transaction ────────────────────────────────────────────
    //
    // These are instrumented rather than JVM tests for the reason the P2-4
    // idempotency guarantee actually rests on: it is a *database* transaction
    // spanning two tables. A fake can be written to behave atomically; only real
    // Room and real SQLCipher can be observed to.

    private fun candidate(
        amountMinor: Long? = 78_800L,
        source: EntrySource = EntrySource.SMS,
        confidence: Double = 0.9,
    ) = PendingCandidate(
        source = source,
        extracted = ExtractedTransaction(
            amount = amountMinor?.let(::Money),
            direction = ExtractedDirection.DEBIT,
            merchantRaw = "COFFEE HOUSE",
            accountLast4 = "1234",
            instrumentHint = InstrumentHint.UPI,
            confidence = confidence,
        ),
        dedupeKey = "78800|DEBIT|28333333|1234",
    )

    private suspend fun captureOneSms(body: String = "Sent Rs.788.00 To COFFEE HOUSE"): String {
        val outcome = repository.record(sms(body))
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
        assertThat(row?.dedupeKey).isEqualTo("78800|DEBIT|28333333|1234")
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
     * Two genuinely different messages are two candidates, even when they share
     * a dedupe key.
     *
     * Storing the key is P2-4; *acting* on a collision is P2-5. If this step
     * suppressed anything, P2-5 would have nothing left to write and the
     * suppressed row §3.1 requires to stay visible would never have existed.
     */
    // `: Unit` is load-bearing, not decoration. This body ends on Truth's
    // `containsExactly`, which returns `Ordered` rather than void -- so an
    // expression-bodied `= runBlocking { ... }` infers that as the return type,
    // JUnit4 rejects the method as "should be void", and it fails the **whole
    // class** with an `initializationError` before a single test runs. Nothing
    // in `preMergeCheck` can see it: the sources compile fine and the check is
    // JUnit's, at load time, on a device.
    @Test
    fun recordParseOutcome_twoRawRows_produceTwoCandidatesEvenOnAKeyCollision(): Unit =
        runBlocking {
            val first = captureOneSms("Sent Rs.788.00 To COFFEE HOUSE")
            val second = repository.record(
                sms("Sent Rs.788.00 to COFFEE HOUSE", sender = "AD-HDFCBK", at = NOW + 90_000L),
            ).let { (it as CaptureOutcome.Recorded).rawId }

            repository.recordParseOutcome(first, "upi-debit-vpa", candidate())
            repository.recordParseOutcome(second, "upi-debit-vpa", candidate())

            val dao = session.requireDatabase().pendingTransactionDao()
            assertThat(dao.count()).isEqualTo(2)
            assertThat(dao.withStatus(PendingStatus.PENDING, limit = 10).map { it.dedupeKey })
                .containsExactly("78800|DEBIT|28333333|1234", "78800|DEBIT|28333333|1234")
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
     * A locked vault refuses rather than throwing, same as every other method
     * here — and leaves the raw row at `CAPTURED` so the next pass retries it.
     */
    @Test
    fun recordParseOutcome_onALockedVault_refusesRatherThanThrows() = runBlocking {
        val rawId = captureOneSms()
        session.close()

        assertThat(repository.recordParseOutcome(rawId, null, candidate()))
            .isInstanceOf(PendingWriteOutcome.Failed::class.java)
    }
}
