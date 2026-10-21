package com.ledgerflow.core.data.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.database.entity.NotificationRawEntity
import com.ledgerflow.core.database.entity.SmsRawEntity
import com.ledgerflow.core.model.RawParseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `PendingRepository.capturedText` against a real database (BUG58, owner
 * 2026-10-21): the review screen's "Message" card.
 *
 * Instrumented because the lookup walks `raw_ref_id` into whichever raw table
 * holds the row without being told which, and the only honest check of that is
 * both tables, a receipt's ref that matches neither, and a body retention has
 * already emptied.
 */
@RunWith(AndroidJUnit4::class)
class CapturedTextReadTest {

    private val vault = LedgerTestVault("lf_captured_text_test")
    private lateinit var repository: DefaultPendingRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        repository = DefaultPendingRepository(vault.session, vault.clock, Dispatchers.IO)
    }

    @After
    fun tearDown() = vault.close()

    private val db get() = vault.session.requireDatabase()

    private suspend fun sms(id: String, body: String) = db.smsRawDao().insert(
        SmsRawEntity(
            id = id,
            sender = "VM-SBIINB-S",
            body = body,
            bodyHash = "hash-$id",
            receivedAt = vault.now,
            simSlot = null,
            parseStatus = RawParseStatus.UNMATCHED,
            matchedRuleId = null,
            retentionExpiresAt = vault.now + RETENTION,
        ),
    )

    private suspend fun notification(id: String, body: String) = db.notificationRawDao().insert(
        NotificationRawEntity(
            id = id,
            packageName = "com.amazon.mShop.android.shopping",
            title = null,
            body = body,
            bodyHash = "hash-$id",
            postedAt = vault.now,
            parseStatus = RawParseStatus.UNMATCHED,
            matchedRuleId = null,
            retentionExpiresAt = vault.now + RETENTION,
        ),
    )

    @Test
    fun anSmsCandidate_showsTheSmsBody() = runBlocking<Unit> {
        sms("raw-s", "482913 is the OTP to login to YONO Web")
        val id = vault.seedPendingCandidate(rawRefId = "raw-s")

        assertThat(repository.capturedText(id)).isEqualTo("482913 is the OTP to login to YONO Web")
    }

    @Test
    fun aNotificationCandidate_showsTheNotificationBody() = runBlocking<Unit> {
        notification("raw-n", "Your package was delivered")
        val id = vault.seedPendingCandidate(rawRefId = "raw-n")

        assertThat(repository.capturedText(id)).isEqualTo("Your package was delivered")
    }

    /** Retention empties a body rather than deleting the row; that reads as "not kept". */
    @Test
    fun aBodyPastRetention_isNull() = runBlocking<Unit> {
        sms("raw-old", "")
        val id = vault.seedPendingCandidate(rawRefId = "raw-old")

        assertThat(repository.capturedText(id)).isNull()
    }

    /** A receipt's ref is an attachment id, and matches neither raw table. */
    @Test
    fun aReceiptCandidate_isNull() = runBlocking<Unit> {
        val id = vault.seedPendingCandidate(rawRefId = "attachment-1")

        assertThat(repository.capturedText(id)).isNull()
    }

    private companion object {
        const val RETENTION = 90L * 24 * 60 * 60 * 1000
    }
}
