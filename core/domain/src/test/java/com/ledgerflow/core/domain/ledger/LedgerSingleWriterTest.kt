package com.ledgerflow.core.domain.ledger

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Law 1, enforced mechanically.
 *
 * `SPEC.md` §1.2 P1 says `ledger_entry` rows "can only be created via
 * `ApproveTransactionUseCase`", enforced by a lint rule and a test. This is
 * that test, built on the same principle as `LedgerIsolationTest`: a rule a
 * reviewer has to notice is not an invariant.
 *
 * Two doors are watched. The DAO's insert statements may only be reached from
 * the DAO that declares them and the one repository that implements approval;
 * and [LedgerRepository.approve] may only be called from
 * `ApproveTransactionUseCase`. A feature that injected `LedgerRepository` and
 * called `approve` itself would compile and run and write a perfectly valid
 * row — and would have routed around the single audited write path without
 * anything failing.
 *
 * Source scanning rather than reflection, for the reason `LedgerIsolationTest`
 * records: the interesting facts here are call sites, which survive in source
 * and not in a signature.
 */
class LedgerSingleWriterTest {

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
     * The restore path is the one other writer, and it is deliberate.
     *
     * `DatabaseBackupManager` reinstates rows a human already approved, from a
     * `.lfbk` this install wrote and verified. Law 1 exists so an *automated
     * source* cannot commit without a human; a restore commits nothing new.
     *
     * Routing it through `ApproveTransactionUseCase` would be actively wrong,
     * not merely ceremonial: the use case mints fresh ids and timestamps and
     * re-validates against a taxonomy that is itself mid-restore, so the
     * restored ledger would not be row-equal to the backup — and row-level
     * equality across every table is the P0 exit criterion (§13.1) and the
     * BUG4 countermeasure. The permit is one named file, so a third writer
     * still fails this test.
     */
    @Test
    fun onlyTheApprovalRepositoryReachesTheLedgerInsertStatements() {
        val inserts = Regex("""\b(insertEntryWithLineItems|insertEntry|insertLineItems)\s*\(""")
        val permitted = setOf(
            "Daos.kt",
            "DefaultLedgerRepository.kt",
            "DatabaseBackupManager.kt",
        )

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { inserts.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /**
     * The permit above is only defensible while the restore path really is a
     * restore. If `DatabaseBackupManager` ever grows a way to build an entry
     * from something other than a decrypted backup payload, the exemption stops
     * describing what the file does and this fails first.
     */
    @Test
    fun theBackupWriterPermitCoversARestoreAndNothingElse() {
        val manager = productionSources().single { it.name == "DatabaseBackupManager.kt" }.readText()

        assertThat(manager).contains("payload.ledgerEntries")
        assertThat(manager).contains("payload.lineItems")
    }

    @Test
    fun onlyApproveTransactionUseCaseCallsApprove() {
        // The declaration and the implementation both name the function; every
        // other file that does is calling it.
        val permitted = setOf(
            "LedgerRepository.kt",
            "DefaultLedgerRepository.kt",
            "LedgerUseCases.kt",
        )
        val call = Regex("""\.approve\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /**
     * Deleting is the second door, and it needs the same guard.
     *
     * Law 1 is written about inserts, so a soft delete is not literally covered
     * by it -- which is exactly why this test exists. The law's *purpose* is
     * that every write to `ledger_entry` has one audited entrance, and a delete
     * is the write with the consequence an insert does not have: it changes
     * totals that have already been read. A feature injecting
     * [LedgerRepository] and calling `softDeleteEntry` itself would compile,
     * run, and remove a perfectly real row with nothing recording that it had
     * happened.
     *
     * The name is `softDeleteEntry` rather than `softDelete` or `delete` for
     * this test's sake: the taxonomy DAOs and repositories all carry a
     * `softDelete`, and a guard matching that would flag three correct files
     * every run. A guard that cries wolf is one somebody eventually deletes.
     */
    @Test
    fun onlyDeleteEntryUseCaseRemovesLedgerRows() {
        val permitted = setOf(
            "LedgerRepository.kt",
            "DefaultLedgerRepository.kt",
            "LedgerUseCases.kt",
        )
        val call = Regex("""\.softDeleteEntry\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun theDeletionUseCaseExistsAndIsTheOnlyDoor() {
        val useCases = productionSources().single { it.name == "LedgerUseCases.kt" }.readText()

        assertThat(useCases).contains("class DeleteEntryUseCase")
        assertThat(useCases).contains("ledger.softDeleteEntry(book, id)")
    }

    /**
     * The third door, and the only irreversible one.
     *
     * `softDeleteEntry` can be undone by clearing a column; this cannot be
     * undone at all. If any screen could call it directly, the one operation in
     * the app that destroys committed ledger data would also be the one with no
     * record of who asked for it.
     */
    @Test
    fun onlyPurgeDeletedEntriesUseCaseDestroysLedgerRows() {
        val permitted = setOf(
            "LedgerRepository.kt",
            "DefaultLedgerRepository.kt",
            "LedgerUseCases.kt",
        )
        val call = Regex("""\.purgeDeletedEntries\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun thePurgeUseCaseExistsAndSweepsBothBooks() {
        val useCases = productionSources().single { it.name == "LedgerUseCases.kt" }.readText()

        assertThat(useCases).contains("class PurgeDeletedEntriesUseCase")
        // Both books, one statement each -- never a single statement spanning
        // them, which is what Law 2 forbids.
        assertThat(useCases).contains("LedgerType.entries.sumOf")
    }

    /**
     * Restoring is the fourth door, and the only one that puts rows *back*.
     *
     * Easy to wave through on the grounds that it destroys nothing. It still
     * changes what every total says, and a screen that could call it directly
     * would be able to resurrect an entry with nothing recording that it had.
     * Same rule as the other three.
     */
    @Test
    fun onlyRestoreEntryUseCaseBringsLedgerRowsBack() {
        val permitted = setOf(
            "LedgerRepository.kt",
            "DefaultLedgerRepository.kt",
            "LedgerUseCases.kt",
        )
        val call = Regex("""\.restoreEntry\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /** The single-entry purge is the same door as the sweep, not a side one. */
    @Test
    fun onlyThePurgeUseCaseDestroysASingleBinnedRow() {
        val permitted = setOf(
            "LedgerRepository.kt",
            "DefaultLedgerRepository.kt",
            "LedgerUseCases.kt",
        )
        val call = Regex("""\.purgeDeletedEntry\s*\(""")

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { call.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    @Test
    fun theRestoreUseCaseExists() {
        val useCases = productionSources().single { it.name == "LedgerUseCases.kt" }.readText()

        assertThat(useCases).contains("class RestoreEntryUseCase")
        assertThat(useCases).contains("ledger.restoreEntry(it.ledger, it.id)")
    }

    @Test
    fun theApprovalUseCaseExistsAndIsTheOnlyDoor() {
        val useCases = productionSources().single { it.name == "LedgerUseCases.kt" }.readText()

        assertThat(useCases).contains("class ApproveTransactionUseCase")
        assertThat(useCases).contains("ledger.approve(request)")
    }

    private companion object {
        /** Well below the real count; this only has to catch a scan that found nothing. */
        private const val MINIMUM_EXPECTED_SOURCES = 20
    }
}
