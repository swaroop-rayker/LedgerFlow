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
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.vault.VaultInitRequest
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
}
