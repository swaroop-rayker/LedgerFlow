package com.ledgerflow.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.crypto.Dek
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.PackageAllowlistEntity
import com.ledgerflow.core.database.entity.SenderAllowlistEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.model.RawParseStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v6 capture tables, against a real encrypted database.
 *
 * The behaviour that matters here is not "a row can be inserted" but the three
 * properties the pipeline is built on and cannot check for itself: that a
 * re-delivered message is absorbed by the database rather than duplicated, that
 * the sender allowlist matches the way Indian sender IDs actually look, and that
 * D-09's retention takes the body and leaves the record.
 *
 * Instrumented because SQLCipher and `GLOB` are both the real thing or nothing —
 * a JVM stub would assert against a different SQL engine than the one that ships.
 * Opens its **own** database file, never the app's (BUG1(e)).
 */
@RunWith(AndroidJUnit4::class)
class IngestCaptureTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: LedgerFlowDatabase
    private lateinit var databaseFile: File

    private companion object {
        const val TEST_DB = "ingest-capture-test.db"
        val DEK_BYTES = ByteArray(Dek.LENGTH) { (it + 41).toByte() }
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }

    @Before
    fun setUp() {
        databaseFile = context.getDatabasePath(TEST_DB)
        databaseFile.delete()
        File("${databaseFile.path}-wal").delete()
        File("${databaseFile.path}-shm").delete()
        database = LedgerFlowDatabaseFactory.create(
            context = context,
            dek = Dek(DEK_BYTES.copyOf()),
            databaseName = TEST_DB,
        )
    }

    @After
    fun tearDown() {
        database.close()
        databaseFile.delete()
    }

    private fun sms(
        id: String,
        sender: String = "VM-HDFCBK",
        hash: String = "hash-$id",
        receivedAt: Long = 1_700_000_000_000L,
        expiresAt: Long = 1_700_000_000_000L + 90 * DAY_MILLIS,
        body: String = "Rs 240.50 debited",
    ) = SmsRawEntity(
        id = id,
        sender = sender,
        body = body,
        bodyHash = hash,
        receivedAt = receivedAt,
        simSlot = null,
        parseStatus = RawParseStatus.CAPTURED,
        matchedRuleId = null,
        retentionExpiresAt = expiresAt,
    )

    /**
     * The network re-delivers SMS. The unique `body_hash` absorbs that, and the
     * caller sees `-1` rather than an exception — a duplicate is an expected
     * event, not an error.
     */
    @Test
    fun insert_theSameMessageTwice_isAbsorbedByTheDatabase() = runBlocking {
        val dao = database.smsRawDao()

        val first = dao.insert(sms("a", hash = "same"))
        val second = dao.insert(sms("b", hash = "same"))

        assertThat(first).isNotEqualTo(-1L)
        assertThat(second).isEqualTo(-1L)
        assertThat(dao.count()).isEqualTo(1)
    }

    /**
     * Indian sender IDs carry a rotating two-letter operator prefix, so one bank
     * arrives as `VM-HDFCBK`, `AD-HDFCBK` and `JD-HDFCBK`. A pattern is the only
     * thing that catches all three, which is why the column is matched with
     * `GLOB` rather than compared with `=`.
     */
    @Test
    fun senderAllowlist_matchesTheRotatingOperatorPrefix() = runBlocking {
        val dao = database.senderAllowlistDao()
        dao.insertMissing(listOf(SenderAllowlistEntity("*-HDFCBK", "HDFC Bank", enabled = true)))

        assertThat(dao.matches("VM-HDFCBK")).isTrue()
        assertThat(dao.matches("AD-HDFCBK")).isTrue()
        assertThat(dao.matches("JD-HDFCBK")).isTrue()
        // A different bank, and a lookalike, both refused.
        assertThat(dao.matches("VM-ICICIB")).isFalse()
        assertThat(dao.matches("HDFCBK")).isFalse()
    }

    /** A disabled row does not match. Turning one off is the user's, and it has to stick. */
    @Test
    fun senderAllowlist_ignoresDisabledPatterns() = runBlocking {
        val dao = database.senderAllowlistDao()
        dao.insertMissing(listOf(SenderAllowlistEntity("*-HDFCBK", "HDFC Bank", enabled = false)))

        assertThat(dao.matches("VM-HDFCBK")).isFalse()
    }

    /**
     * D-10's seeding is additive.
     *
     * A package the user disabled must stay disabled across app updates —
     * a "curated default" that silently re-enables what someone turned off is
     * not a default, it is an override. Re-running the seeder must be a no-op.
     */
    @Test
    fun packageAllowlist_reSeedingDoesNotRevertTheUsersChoice() = runBlocking {
        val dao = database.packageAllowlistDao()
        val curated = listOf(PackageAllowlistEntity("com.example.pay", "Example Pay", true))

        dao.insertMissing(curated)
        // The user turns it off.
        dao.upsert(listOf(PackageAllowlistEntity("com.example.pay", "Example Pay", false)))
        // A later app update re-seeds.
        dao.insertMissing(curated)

        assertThat(dao.isAllowed("com.example.pay")).isFalse()
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun packageAllowlist_refusesAnythingNotOnIt() = runBlocking {
        val dao = database.packageAllowlistDao()
        dao.insertMissing(listOf(PackageAllowlistEntity("com.example.pay", "Example Pay", true)))

        assertThat(dao.isAllowed("com.example.pay")).isTrue()
        assertThat(dao.isAllowed("com.someone.else")).isFalse()
    }

    /**
     * **D-09: the purge drops the body, not the record.**
     *
     * Retention must not rewrite history — the row, its status and anything
     * derived from it survive. Only the text goes.
     */
    @Test
    fun purge_clearsExpiredBodiesAndKeepsTheRows() = runBlocking {
        val dao = database.smsRawDao()
        val now = 1_700_000_000_000L
        dao.insert(sms("old", hash = "h-old", expiresAt = now - 1))
        dao.insert(sms("fresh", hash = "h-fresh", expiresAt = now + DAY_MILLIS))

        val cleared = dao.purgeExpiredBodies(now)

        assertThat(cleared).isEqualTo(1)
        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.byId("old")?.body).isEmpty()
        assertThat(dao.byId("old")?.parseStatus).isEqualTo(RawParseStatus.CAPTURED)
        assertThat(dao.byId("fresh")?.body).isNotEmpty()
    }

    /** Running the purge twice clears nothing the second time. */
    @Test
    fun purge_isIdempotent() = runBlocking {
        val dao = database.smsRawDao()
        val now = 1_700_000_000_000L
        dao.insert(sms("old", hash = "h-old", expiresAt = now - 1))

        assertThat(dao.purgeExpiredBodies(now)).isEqualTo(1)
        assertThat(dao.purgeExpiredBodies(now)).isEqualTo(0)
    }

    /** A notification's title goes with its body — D-11 puts it in its own column. */
    @Test
    fun purge_alsoClearsANotificationTitle() = runBlocking {
        val dao = database.notificationRawDao()
        val now = 1_700_000_000_000L
        dao.insert(
            NotificationRawEntity(
                id = "n-1",
                packageName = "com.example.pay",
                title = "Paid ₹240 to Store",
                body = "Paid ₹240 to Store",
                bodyHash = "h-n",
                postedAt = now - DAY_MILLIS,
                parseStatus = RawParseStatus.CAPTURED,
                matchedRuleId = null,
                retentionExpiresAt = now - 1,
            ),
        )

        dao.purgeExpiredBodies(now)

        assertThat(dao.byId("n-1")?.body).isEmpty()
        assertThat(dao.byId("n-1")?.title).isNull()
        assertThat(dao.count()).isEqualTo(1)
    }

    /** The worker's queue: only rows nothing has resolved yet. */
    @Test
    fun withStatus_returnsOnlyUnresolvedRowsOldestFirst() = runBlocking {
        val dao = database.smsRawDao()
        dao.insert(sms("second", hash = "h2", receivedAt = 2_000L))
        dao.insert(sms("first", hash = "h1", receivedAt = 1_000L))
        dao.insert(sms("done", hash = "h3", receivedAt = 500L))
        dao.updateStatus("done", RawParseStatus.SENDER_NOT_ALLOWLISTED, null)

        val queue = dao.withStatus(RawParseStatus.CAPTURED, limit = 10)

        assertThat(queue.map { it.id }).containsExactly("first", "second").inOrder()
    }
}
