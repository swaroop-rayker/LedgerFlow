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

    private var status by mutableStateOf("Starting")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LfTheme { Text(text = status) } }
        lifecycleScope.launch { status = runCatching { seed() }.getOrElse { "Failed: $it" } }
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
