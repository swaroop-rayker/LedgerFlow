package com.ledgerflow.core.data.ingest

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.common.id.Uuid7Generator
import com.ledgerflow.core.common.time.Clock
import com.ledgerflow.core.crypto.DekManager
import com.ledgerflow.core.crypto.FileWrappedDekStore
import com.ledgerflow.core.crypto.bip39.Bip39
import com.ledgerflow.core.crypto.keystore.AndroidKeystoreKek
import com.ledgerflow.core.data.vault.Bip39PhraseValidator
import com.ledgerflow.core.data.vault.VaultSession
import com.ledgerflow.core.domain.ingest.CaptureGap
import com.ledgerflow.core.domain.ingest.CaptureGapStore
import com.ledgerflow.core.domain.usecase.RecordCaptureGapUseCase
import com.ledgerflow.core.domain.vault.VaultInitRequest
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The notification allowlist editor against a real vault (SPEC §5.2, D-10;
 * V2-PLAN §3.4).
 *
 * The property that matters most is the privacy gate: the capture adapter asks
 * `isPackageAllowed` before reading any body, and the editor must move that
 * answer -- off means not read, removed means not read, added means read.
 * Seeding runs from the real asset, so "built in" is the shipped list.
 *
 * Its own database and its own Keystore alias, never the app's (BUG1(e)).
 */
class PackageAllowlistEditorTest {

    private companion object {
        const val TEST_DATABASE = "lf-test-allowlist.db"
        const val KEYSTORE_ALIAS = "lf-test-allowlist"
        const val PHONEPE = "com.phonepe.app"
        const val ADDED = "com.example.mybank"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyDirectory = File(context.filesDir, "keys-$KEYSTORE_ALIAS")

    private lateinit var session: VaultSession
    private lateinit var ingest: DefaultRawIngestRepository
    private lateinit var editor: DefaultPackageAllowlistRepository

    private val gaps = object : CaptureGapStore {
        override val gap = MutableStateFlow<CaptureGap?>(null)
        override suspend fun recordMissed(atMillis: Long) = Unit
        override suspend fun clear() = Unit
    }

    @Before
    fun setUp() = runBlocking {
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
        val dekManager = DekManager(FileWrappedDekStore(keyDirectory), AndroidKeystoreKek(KEYSTORE_ALIAS), SecureRandom())
        session = VaultSession(context, dekManager, Bip39PhraseValidator(), Dispatchers.IO, TEST_DATABASE)
        session.initialize(VaultInitRequest(Bip39.generate(SecureRandom()), "INR"))
        val ids = Uuid7Generator(SecureRandom())
        ingest = DefaultRawIngestRepository(
            context = context,
            session = session,
            clock = Clock { 0L },
            ids = ids,
            candidateWriter = PendingCandidateWriter(Clock { 0L }, ids),
            recordCaptureGap = RecordCaptureGapUseCase(gaps, Clock { 0L }),
            io = Dispatchers.IO,
        )
        editor = DefaultPackageAllowlistRepository(context, session, Dispatchers.IO)
        ingest.seedAllowlists()
    }

    @After
    fun tearDown() {
        runCatching { session.requireDatabase().close() }
        keyDirectory.deleteRecursively()
        deleteKeystoreEntry()
        context.deleteDatabase(TEST_DATABASE)
    }

    private fun deleteKeystoreEntry() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEYSTORE_ALIAS) }
    }

    @Test
    fun theShippedList_isBuiltIn_enabled_andFirst() = runBlocking {
        val apps = editor.observe().first()
        val phonePe = apps.single { it.packageName == PHONEPE }
        assertThat(phonePe.builtIn).isTrue()
        assertThat(phonePe.enabled).isTrue()
        assertThat(apps.all { it.builtIn }).isTrue()
    }

    @Test
    fun switchingOff_closesTheGate_andOnReopensIt() = runBlocking {
        editor.setEnabled(PHONEPE, false)
        assertWithMessage("a switched-off app's notifications are not read").that(ingest.isPackageAllowed(PHONEPE)).isFalse()
        assertThat(editor.observe().first().single { it.packageName == PHONEPE }.enabled).isFalse()

        editor.setEnabled(PHONEPE, true)
        assertThat(ingest.isPackageAllowed(PHONEPE)).isTrue()
    }

    @Test
    fun aBuiltIn_cannotBeRemoved_onlySwitchedOff() = runBlocking {
        assertThat(editor.remove(PHONEPE)).isFalse()
        assertThat(editor.observe().first().map { it.packageName }).contains(PHONEPE)
    }

    @Test
    fun anAddedApp_isRead_untilRemoved() = runBlocking {
        assertThat(ingest.isPackageAllowed(ADDED)).isFalse()
        editor.add(ADDED, "My Bank")
        assertThat(ingest.isPackageAllowed(ADDED)).isTrue()
        val added = editor.observe().first().single { it.packageName == ADDED }
        assertThat(added.builtIn).isFalse()
        assertThat(added.label).isEqualTo("My Bank")

        assertThat(editor.remove(ADDED)).isTrue()
        assertThat(ingest.isPackageAllowed(ADDED)).isFalse()
    }

    @Test
    fun addingAListedApp_doesNotSwitchItBackOn() = runBlocking {
        editor.setEnabled(PHONEPE, false)
        editor.add(PHONEPE, "PhonePe")
        assertThat(ingest.isPackageAllowed(PHONEPE)).isFalse()
    }

    @Test
    fun theNextLaunchsSeeding_keepsTheUsersChoices() = runBlocking {
        editor.setEnabled(PHONEPE, false)
        editor.add(ADDED, "My Bank")
        ingest.seedAllowlists()
        assertThat(ingest.isPackageAllowed(PHONEPE)).isFalse()
        assertThat(ingest.isPackageAllowed(ADDED)).isTrue()
    }
}
