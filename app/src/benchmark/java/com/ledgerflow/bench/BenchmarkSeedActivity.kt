package com.ledgerflow.bench

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.ledgerflow.core.designsystem.theme.LfTheme
import com.ledgerflow.core.domain.inbox.InboxFilter
import com.ledgerflow.core.domain.inbox.PendingRepository
import com.ledgerflow.core.domain.ingest.CaptureOutcome
import com.ledgerflow.core.domain.ingest.DedupeKey
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.ingest.ExtractedTransaction
import com.ledgerflow.core.domain.ingest.IngestSourceType
import com.ledgerflow.core.domain.ingest.PendingCandidate
import com.ledgerflow.core.domain.ingest.RawIngestEvent
import com.ledgerflow.core.domain.ingest.RawIngestRepository
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.taxonomy.CategoryRepository
import com.ledgerflow.core.domain.taxonomy.MerchantRepository
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.usecase.ApproveTransactionUseCase
import com.ledgerflow.core.domain.vault.VaultInitRequest
import com.ledgerflow.core.domain.vault.VaultOutcome
import com.ledgerflow.core.domain.vault.VaultRepository
import com.ledgerflow.core.domain.vault.VaultState
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.EntrySource
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Money
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Gives the benchmark install a vault worth measuring (P5; owner's choice,
 * 2026-09-29).
 *
 * **Benchmark build type only.** This file is in `src/benchmark`, so debug and
 * release do not contain it, and the install it runs in is `com.ledgerflow.bench`,
 * never a real one.
 *
 * **No new key path.** The vault is made through [VaultRepository.initialize],
 * the call onboarding makes, under the public BIP-39 test phrase (`abandon` ×23,
 * `art`). That phrase protects nothing by design: this vault holds synthetic
 * data and exists to be timed. Every entry goes through
 * [ApproveTransactionUseCase], the one door into the ledger (Law 1), so the
 * rollups are built the way a real user's are.
 *
 * Idempotent: a second launch finds the marker and only reports. The benchmark
 * waits for a line starting "Seeded".
 */
@AndroidEntryPoint
class BenchmarkSeedActivity : ComponentActivity() {

    @Inject lateinit var vault: VaultRepository

    @Inject lateinit var categories: CategoryRepository

    @Inject lateinit var merchants: MerchantRepository

    @Inject lateinit var approve: ApproveTransactionUseCase

    @Inject lateinit var pending: PendingRepository

    @Inject lateinit var rawIngest: RawIngestRepository

    private var status by mutableStateOf("Starting")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LfTheme { Text(text = status) } }
        val inbox = intent.getStringExtra(EXTRA_INBOX)
        lifecycleScope.launch {
            status = runCatching {
                val seeded = seed()
                when {
                    !seeded.startsWith("Seeded") -> seeded
                    inbox == INBOX_ON -> fillInbox()
                    inbox == INBOX_OFF -> emptyInbox()
                    else -> seeded
                }
            }.getOrElse { "Failed: $it" }
        }
    }

    /**
     * Puts [INBOX_ROWS] candidates in the Inbox for `InboxScrollBenchmark`
     * (PERF-120HZ-PLAN §3). Reports "Inbox: N pending".
     *
     * **Through the capture write, never the ledger.** Each is a raw SMS row
     * (`RawIngestRepository.record`) and its candidate
     * (`recordParseOutcome`), the two writes `ParseIngestWorker` makes, so
     * nothing reaches `ledger_entry` (Law 1). No notification is posted: that
     * is the worker's step, not the write's.
     *
     * **Discarded between runs, restored here.** [emptyInbox] discards them
     * after each benchmark, so Home's "To review" card, the other benchmarks
     * and the store screenshots taken from this vault see no pending rows.
     * Restoring brings the same rows back, so every run scrolls the same list.
     * Only when there is nothing to restore (the 30-day purge, or a first run)
     * are new ones written, with a fresh generation in their bodies so the raw
     * table does not absorb them as re-deliveries.
     *
     * The mix is the Inbox's: mostly parsed debits with Review and Approve, a
     * fifth with no direction read (Review only), and a fifth with nothing read
     * at all (§5.1's never-drop row). Amounts are distinct, so no candidate is
     * suppressed as another's duplicate (§3.1).
     */
    private suspend fun fillInbox(): String {
        pending.observe(InboxFilter.DISCARDED).first()
            .filter { it.extracted.referenceNo?.startsWith(BENCH_REF) == true }
            .forEach { pending.restore(it.id) }
        val existing = pending.observe(InboxFilter.PENDING).first().size
        if (existing >= INBOX_ROWS) return "Inbox: $existing pending"

        val marker = getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE)
        val generation = marker.getInt(MARKER_INBOX_GENERATION, 0) + 1
        marker.edit().putInt(MARKER_INBOX_GENERATION, generation).apply()
        val now = System.currentTimeMillis()
        repeat(INBOX_ROWS - existing) { i ->
            val (name, _) = MERCHANTS[i % MERCHANTS.size]
            val amount = Money(INBOX_BASE_MINOR + i * INBOX_STEP_MINOR)
            val at = now - i * INBOX_SPACING_MS
            val ref = "$BENCH_REF$generation-$i"
            val extracted = when (i % INBOX_MIX) {
                0 -> ExtractedTransaction(referenceNo = ref)
                1 -> ExtractedTransaction(
                    amount = amount, currency = "INR", merchantRaw = name.uppercase(),
                    referenceNo = ref, occurredAt = at, confidence = PARTIAL_CONFIDENCE,
                )
                else -> ExtractedTransaction(
                    amount = amount, currency = "INR", direction = ExtractedDirection.DEBIT,
                    merchantRaw = name.uppercase(), accountLast4 = "6402",
                    referenceNo = ref, occurredAt = at, confidence = FULL_CONFIDENCE,
                )
            }
            val body = "Rs ${amount.minor / 100} debited from A/c XX6402 to ${name.uppercase()}. Ref $ref"
            val raw = rawIngest.record(RawIngestEvent(IngestSourceType.SMS, BENCH_SENDER, body, at))
            if (raw is CaptureOutcome.Recorded) {
                rawIngest.recordParseOutcome(
                    rawId = raw.rawId,
                    ruleId = null,
                    candidate = PendingCandidate(
                        source = EntrySource.SMS,
                        extracted = extracted,
                        dedupeKey = DedupeKey.compute(extracted, raw.rawId),
                    ),
                )
            }
        }
        return "Inbox: ${pending.observe(InboxFilter.PENDING).first().size} pending"
    }

    /** Discards every pending candidate, so the vault is as other benchmarks expect it. */
    private suspend fun emptyInbox(): String {
        pending.observe(InboxFilter.PENDING).first().forEach { pending.discard(it.id) }
        return "Inbox: ${pending.observe(InboxFilter.PENDING).first().size} pending"
    }

    private suspend fun seed(): String {
        val marker = getSharedPreferences(MARKER_FILE, Context.MODE_PRIVATE)
        vault.openOnLaunch()
        when (val state = vault.state.value) {
            VaultState.Unlocked -> if (marker.getBoolean(MARKER_DONE, false)) {
                return "Seeded (already): ${marker.getInt(MARKER_COUNT, 0)} entries"
            }
            VaultState.NeedsOnboarding -> {
                val outcome = vault.initialize(VaultInitRequest(TEST_PHRASE, baseCurrency = "INR"))
                if (outcome != VaultOutcome.Unlocked) return "Failed: vault init $outcome"
            }
            VaultState.Initializing,
            VaultState.Working,
            VaultState.RestoreInterrupted,
            is VaultState.NeedsRecovery,
            is VaultState.Upgrading,
            is VaultState.UpgradeBlocked,
            -> return "Failed: vault is $state"
        }

        val debitIds = ensureCategories(LedgerType.DEBIT, DEBIT_CATEGORIES)
        val creditIds = ensureCategories(LedgerType.CREDIT, CREDIT_CATEGORIES)
        val debitIdByName = categories.observe(LedgerType.DEBIT).first().associate { it.name to it.id }
        // (merchant id, its category id): a purchase is filed where it belongs.
        val shops = MERCHANTS.mapNotNull { (name, category) ->
            val merchantId = merchants.createOrGet(name).valueOrNull()?.id
            val categoryId = debitIdByName[category]
            if (merchantId != null && categoryId != null) merchantId to categoryId else null
        }

        // Resumable: each finished day is recorded, so a run cut short (the
        // benchmark's timeout, a killed process) continues where it stopped
        // instead of writing the early years twice.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var day = marker.getLong(MARKER_NEXT_DAY, -1L).takeIf { it >= 0 }?.let(LocalDate::ofEpochDay)
            ?: today.minusYears(YEARS)
        val random = Random(SEED + day.toEpochDay())
        var written = marker.getInt(MARKER_COUNT, 0)
        val startedAt = System.nanoTime()
        while (!day.isAfter(today)) {
            val requests = buildList {
                repeat(random.nextInt(MAX_DEBITS_PER_DAY + 1)) {
                    val (merchantId, categoryId) = shops.random(random)
                    add(
                        request(
                            LedgerType.DEBIT,
                            Money(random.nextLong(MIN_DEBIT_MINOR, MAX_DEBIT_MINOR)),
                            day, random, categoryId, merchantId,
                        ),
                    )
                }
                if (day.dayOfMonth == 1) {
                    add(request(LedgerType.DEBIT, Money(RENT_MINOR), day, random, debitIds.first(), null))
                    add(request(LedgerType.CREDIT, Money(SALARY_MINOR), day, random, creditIds.first(), null))
                }
            }
            requests.forEach { if (approve(it).valueOrNull() != null) written++ }
            day = day.plusDays(1)
            marker.edit().putLong(MARKER_NEXT_DAY, day.toEpochDay()).putInt(MARKER_COUNT, written).apply()
            if (day.dayOfMonth == 1) {
                status = "Seeding: $written entries, at $day"
                val seconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
                Log.i(TAG, "Seeding: $written entries, at $day, ${seconds}s this run")
            }
        }

        marker.edit().putBoolean(MARKER_DONE, true).putInt(MARKER_COUNT, written).apply()
        return "Seeded: $written entries"
    }

    private suspend fun ensureCategories(ledger: LedgerType, names: List<String>): List<String> {
        val existing = categories.observe(ledger).first().associateBy { it.name }
        return names.mapNotNull { name ->
            existing[name]?.id ?: categories.create(NewCategory(ledger, name)).valueOrNull()?.id
        }
    }

    private fun request(
        ledger: LedgerType,
        amount: Money,
        day: LocalDate,
        random: Random,
        categoryId: String,
        merchantId: String?,
    ) = ApprovalRequest(
        ledger = ledger,
        amount = amount,
        occurredAt = day.atTime(random.nextInt(HOURS_IN_DAY), random.nextInt(MINUTES_IN_HOUR))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        assignment = EntryAssignment(categoryId = categoryId, merchantId = merchantId),
    )

    private companion object {
        /** BIP-39's all-zero 256-bit entropy. Public, and meant to be. */
        val TEST_PHRASE = List(23) { "abandon" } + "art"

        const val MARKER_FILE = "benchmark_seed"
        const val MARKER_DONE = "done"
        const val MARKER_COUNT = "count"
        const val MARKER_NEXT_DAY = "nextEpochDay"
        const val MARKER_INBOX_GENERATION = "inboxGeneration"

        /** `--es inbox on|off`: fill the Inbox for `InboxScrollBenchmark`, or discard what it left. */
        const val EXTRA_INBOX = "inbox"
        const val INBOX_ON = "on"
        const val INBOX_OFF = "off"
        const val INBOX_ROWS = 120
        const val INBOX_MIX = 5
        const val INBOX_BASE_MINOR = 10_000L // Rs 100
        const val INBOX_STEP_MINOR = 137L // distinct amounts: no candidate is another's duplicate
        const val INBOX_SPACING_MS = 3_600_000L // an hour apart
        const val PARTIAL_CONFIDENCE = 0.4
        const val FULL_CONFIDENCE = 0.9
        const val BENCH_REF = "BENCH"
        const val BENCH_SENDER = "AD-BNKSMS"
        const val TAG = "BenchmarkSeed"
        const val NANOS_PER_SECOND = 1_000_000_000L

        const val SEED = 42
        const val YEARS = 5L
        const val MAX_DEBITS_PER_DAY = 2
        const val MIN_DEBIT_MINOR = 5_000L // ₹50
        const val MAX_DEBIT_MINOR = 500_000L // ₹5,000
        const val RENT_MINOR = 2_500_000L // ₹25,000
        const val SALARY_MINOR = 9_000_000L // ₹90,000
        const val HOURS_IN_DAY = 24
        const val MINUTES_IN_HOUR = 60

        val DEBIT_CATEGORIES = listOf(
            "Rent", "Groceries", "Dining", "Transport", "Utilities", "Shopping", "Health", "Entertainment",
        )
        val CREDIT_CATEGORIES = listOf("Salary", "Interest")

        /**
         * Merchant to category. **Generic, made-up names, never a real business**
         * (owner, 2026-10-02): this vault is what the store screenshots are
         * taken from, so a brand here would put someone's trademark on the
         * listing. It used to hold real brands drawn at random against any
         * category, which read as "Ola · Shopping". Rent has no merchant: it is
         * the monthly entry below.
         */
        val MERCHANTS = listOf(
            "Corner Grocers" to "Groceries",
            "Green Basket Market" to "Groceries",
            "Fresh Farm Dairy" to "Groceries",
            "Spice Route Kitchen" to "Dining",
            "Daily Brew Cafe" to "Dining",
            "Tiffin Corner" to "Dining",
            "City Cabs" to "Transport",
            "Metro Rail" to "Transport",
            "Highway Fuel" to "Transport",
            "City Power" to "Utilities",
            "Home Broadband" to "Utilities",
            "Mobile Recharge" to "Utilities",
            "Fashion Street" to "Shopping",
            "Electronics Hub" to "Shopping",
            "Book Nook" to "Shopping",
            "Neighbourhood Pharmacy" to "Health",
            "Family Clinic" to "Health",
            "Cinema Hall" to "Entertainment",
            "Game Zone" to "Entertainment",
        )
    }
}
