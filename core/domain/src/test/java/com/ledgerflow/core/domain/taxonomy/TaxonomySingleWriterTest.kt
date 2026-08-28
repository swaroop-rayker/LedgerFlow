package com.ledgerflow.core.domain.taxonomy

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ADR-0016's audited doors, enforced mechanically.
 *
 * The sibling of `LedgerSingleWriterTest`, built on the same principle: a rule a
 * reviewer has to notice is not an invariant. What it guards is narrower and, in
 * one respect, more necessary.
 *
 * `PurgeDeletedEntriesUseCase` guards an operation the *schema* also protects —
 * `line_item` cascades, `draft_entry` cascades, and a mistake is at least
 * confined to rows the user already binned. A taxonomy purge has no such
 * backstop. `ledger_entry.merchant_id` is `ON DELETE SET NULL`, so destroying a
 * merchant succeeds and silently strips the shop's name off every entry that
 * ever used it; `category_id` has no foreign key at all, so destroying a
 * category leaves entries holding an id that resolves to nothing. In both cases
 * SQLite reports success. The reference count in the repository is the only
 * thing standing in the way, and these tests are what keep every caller behind
 * it.
 *
 * Source scanning rather than reflection, for the reason `LedgerIsolationTest`
 * records: the interesting facts here are call sites, which survive in source
 * and not in a signature.
 */
class TaxonomySingleWriterTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

    /** Every production Kotlin source in the build, `build/` output excluded. */
    private fun productionSources(): List<File> =
        listOf("core", "feature", "app")
            .map { File(repositoryRoot, it) }
            .filter { it.isDirectory }
            .flatMap { area ->
                area.walkTopDown()
                    .onEnter { it.name != "build" }
                    .filter { it.isFile && it.extension == "kt" }
                    .filter { it.path.replace(File.separatorChar, '/').contains("/src/main/") }
                    .toList()
            }

    @Test
    fun productionSources_areDiscoverable() {
        // A silent zero would make every assertion below vacuously true, which
        // is the failure mode that makes a guard worse than no guard.
        assertThat(productionSources().size).isGreaterThan(MINIMUM_EXPECTED_SOURCES)
    }

    /**
     * The three destroys, behind one regex.
     *
     * `hardDelete` rather than `delete` or `purge` is the name for exactly this
     * test's sake, the same way `softDeleteEntry` is named for
     * `LedgerSingleWriterTest`. All three repositories carry a `delete` that
     * soft-deletes and a `purge` that does not, and a guard matching either
     * would flag correct files every run. A guard that cries wolf is one
     * somebody eventually deletes.
     */
    @Test
    fun onlyThePurgeUseCasesDestroyTaxonomyRows() {
        val permitted = setOf(
            // Declares the statements.
            "Daos.kt",
            // Implements the refusal that has to run before them.
            "DefaultCategoryRepository.kt",
            "DefaultMerchantRepository.kt",
            "DefaultPaymentMethodRepository.kt",
        )
        val call = Regex("""\.hardDelete(Children)?\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /**
     * The repository `purge` methods are reachable only through the use cases.
     *
     * The second door, and the one a feature would actually walk through: the
     * screen already injects all three repositories for its list reads, so
     * calling `merchants.purge(id, null)` from the ViewModel would compile,
     * run, and destroy a merchant with nothing recording that the app's second
     * irreversible operation had been performed.
     *
     * Matched with a leading receiver so `override suspend fun purge(` in the
     * repositories themselves does not count as a call.
     *
     * **The Inbox's own destroy is called `erase`, not `purge`, because of this
     * test.** `PendingRepository.erase` has nothing to do with the taxonomy —
     * it destroys `pending_transaction` rows and carries its own two guards —
     * but the regex here matches any `.purge(` receiver, so naming it `purge`
     * made `InboxUseCases.kt` an offender the moment it was written. Widening
     * the permitted set would have been the wrong fix: it would then also
     * permit a *taxonomy* purge from that file. Renaming is the same move this
     * codebase already made for `hardDelete`, and it is why the UI says "Erase
     * for good" rather than "Purge".
     */
    @Test
    fun onlyThePurgeUseCasesCallPurge() {
        val permitted = setOf(
            "TaxonomyRepositories.kt",
            "DefaultCategoryRepository.kt",
            "DefaultMerchantRepository.kt",
            "DefaultPaymentMethodRepository.kt",
            "TaxonomyUseCases.kt",
        )
        val call = Regex("""\w\.purge\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun thePurgeUseCasesExist() {
        val useCases = productionSources().single { it.name == "TaxonomyUseCases.kt" }.readText()

        assertThat(useCases).contains("class PurgeHiddenCategoryUseCase")
        assertThat(useCases).contains("class PurgeHiddenMerchantUseCase")
        assertThat(useCases).contains("class PurgeHiddenPaymentMethodUseCase")
    }

    /**
     * The two that can orphan entries take a destination; the one that cannot
     * does not.
     *
     * The absent parameter on the payment-method purge is a claim about
     * `DefaultPaymentMethodRepository.delete` — that hiding already cleared
     * `payment_method_id` from every entry in both books. If someone adds a
     * `reassignTo` here it means that claim has stopped holding, and this
     * failing is the cheapest place to find out.
     */
    @Test
    fun onlyTheTwoThatCanOrphanEntriesTakeAReassignTarget() {
        val useCases = productionSources().single { it.name == "TaxonomyUseCases.kt" }.readText()

        assertThat(useCases).contains("categories.purge(id, reassignTo)")
        assertThat(useCases).contains("merchants.purge(id, reassignTo)")
        assertThat(useCases).contains("paymentMethods.purge(id)")
    }

    /**
     * Each `@Query` in a DAO file, keyed by the function it annotates.
     *
     * Kotlin string concatenation is flattened first: a statement written across
     * several literals would otherwise be truncated at the first closing quote,
     * and a guard that matches half a statement passes for the wrong reason.
     */
    private fun queriesOf(file: String): Map<String, String> {
        val flattened = file.replace(Regex(""""\s*\+\s*""""), "")
        return Regex(
            """@Query\(\s*"(.*?)",?\s*\)\s*public suspend fun (\w+)""",
            RegexOption.DOT_MATCHES_ALL,
        )
            .findAll(flattened)
            .associate { it.groupValues[2] to it.groupValues[1] }
    }

    /**
     * The purge counts binned entries, and the soft delete does not.
     *
     * Four statements that look like two, which is exactly why this is asserted
     * rather than left to a comment. Collapsing `countAllForMerchant` into
     * `countForMerchant` would narrow the purge check to visible entries, and
     * nothing would fail until a user restored something from the bin months
     * later and found its merchant gone.
     *
     * **Asserted per statement rather than as a literal, and that rewrite has a
     * history.** The original form matched two exact SQL strings. ADR-0018 then
     * widened `countForCategory` to reach line-grain categories, which aliased
     * the table and reordered the predicate — the statement stayed correct and
     * the guard stopped matching it. It went unnoticed for three sessions
     * because these tests read the repository at runtime, which Gradle cannot
     * see as a task input, so the task was up to date and never ran. The build
     * files now declare those sources; this assertion is on the property, so
     * the next legitimate rewrite of the SQL does not break it either. Same
     * lesson as ADR-0002's amendment about string proxies.
     */
    @Test
    fun thePurgeCountsAreTheOnesThatIncludeBinnedEntries() {
        val queries = queriesOf(
            productionSources().single { it.name == "LedgerTaxonomyDao.kt" }.readText(),
        )

        // The soft-delete counts ask about live rows only...
        listOf("countForCategory", "countForMerchant").forEach { name ->
            assertThat(queries).containsKey(name)
            assertThat(queries.getValue(name)).contains("deleted_at IS NULL")
        }

        // ...and the purge counts must not, or a purge check would ignore
        // everything sitting in the bin.
        listOf("countAllForCategory", "countAllForMerchant").forEach { name ->
            assertThat(queries).containsKey(name)
            assertThat(queries.getValue(name)).doesNotContain("deleted_at")
        }

        val repositories = listOf(
            "DefaultCategoryRepository.kt" to "countAllForCategory",
            "DefaultMerchantRepository.kt" to "countAllForMerchant",
        )
        repositories.forEach { (file, statement) ->
            val source = productionSources().single { it.name == file }.readText()
            assertThat(source).contains(statement)
        }
    }

    /**
     * Every hard delete can only reach a row that is already hidden.
     *
     * `AND deleted_at != 0` is the taxonomy's version of `purgeDeletedEntry`'s
     * `AND deleted_at IS NOT NULL`, and it is a real guard rather than
     * decoration: without it these statements would destroy *live* categories,
     * merchants and payment methods by id — and since the hidden list is the
     * only screen that can reach them, nothing in the UI would ever have shown
     * the difference.
     */
    @Test
    fun noHardDeleteCanReachALiveRow() {
        val daos = productionSources().single { it.name == "Daos.kt" }.readText()

        // Kotlin string concatenation first: the branch sweep is written across
        // two literals, so a regex run over the raw source would stop at the
        // closing quote and never see the predicate it is here to check --
        // passing for the wrong reason, which is the one failure mode a guard
        // must not have.
        val flattened = daos.replace(Regex(""""\s*\+\s*""""), "")

        val destroys = Regex("""DELETE FROM (category|merchant|payment_method) [^"]*""")
            .findAll(flattened)
            .map { it.value }
            .toList()

        // Three single-row destroys plus the category branch sweep.
        assertThat(destroys).hasSize(EXPECTED_TAXONOMY_DESTROYS)
        destroys.forEach { assertThat(it).contains("deleted_at != 0") }
    }

    private companion object {
        /** Well below the real count; this only has to catch a scan that found nothing. */
        private const val MINIMUM_EXPECTED_SOURCES = 20

        /** `category`, `merchant`, `payment_method`, and the category branch sweep. */
        private const val EXPECTED_TAXONOMY_DESTROYS = 4
    }
}
