package com.ledgerflow.core.domain.analytics

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * ADR-0006's write path, guarded the way Law 1's is.
 *
 * `daily_rollup` is not `ledger_entry`, so Law 1 does not govern it directly.
 * But the kickoff's point stands: a new write path deserves the same treatment,
 * and this one is easier to get wrong than the ledger's, because a rollup write
 * that goes missing produces no error at all — only a total that is quietly
 * short, on a screen the user has no way to check.
 *
 * Two properties are asserted, and they fail differently.
 *
 * **Nobody writes the table except the DAO that owns it.** A feature that
 * injected the database and adjusted a bucket by hand would compile, run, and
 * write a perfectly valid row — and would have routed around the recompute that
 * makes ADR-0006's idempotence true.
 *
 * **Every door that changes what the ledger shows recomputes.** This is the one
 * that would actually happen. `approve`, `softDeleteEntry` and `restoreEntry`
 * each move rows in or out of `deleted_at IS NULL`, so each must recompute;
 * `purgeDeletedEntry` must not, because the rows it destroys were already
 * excluded. A fifth door added later gets no automatic protection here, which
 * is exactly why `CLAUDE.md` §7 says a fifth writer needs the same guard on the
 * day it appears.
 *
 * Source scanning rather than reflection, for the reason `LedgerIsolationTest`
 * records: the interesting facts here are call sites, which survive in source
 * and not in a signature.
 */
class RollupSingleWriterTest {

    private val repositoryRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Repository root not found from ${File("").absolutePath}")
    }

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
        assertThat(productionSources().size).isGreaterThan(MINIMUM_EXPECTED_SOURCES)
    }

    /**
     * The recompute statements are reachable only from the DAO that declares
     * them and the two callers that legitimately rebuild the whole table.
     *
     * `DatabaseBackupManager` is permitted for the same reason
     * `LedgerSingleWriterTest` permits it on the ledger: a restore reinstates
     * rows a human already approved, and the rollups have to be rebuilt from
     * them because the payload deliberately does not carry the derived table.
     */
    @Test
    fun onlyPermittedFilesReachTheRollupWriteStatements() {
        val writes = Regex("""\b(recompute|recomputeAll|deleteRange|insertRange)\s*\(""")
        val permitted = setOf(
            "DailyRollupDao.kt",
            "DefaultLedgerRepository.kt",
            "DefaultRollupRepository.kt",
            "DatabaseBackupManager.kt",
        )

        val offenders = productionSources()
            .filter { it.name !in permitted }
            .filter { writes.containsMatchIn(it.readText()) }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    /**
     * No production source may name the table **in code** outside
     * `:core:database`.
     *
     * Comments are stripped first, and that is not a loophole — it is the
     * difference between a guard and a vocabulary ban. Five files legitimately
     * *discuss* `daily_rollup` in prose: the CSV writer explains why there is no
     * `daily_rollup.csv`, the repository port explains what reconciliation does
     * to it, and three screens explain what they read. Failing those would teach
     * the next person to describe the table obliquely, which makes the codebase
     * harder to read in exchange for nothing.
     */
    @Test
    fun onlyTheDatabaseModuleNamesTheRollupTableInCode() {
        val offenders = productionSources()
            .filterNot { it.path.replace(File.separatorChar, '/').contains("/core/database/") }
            .filter { withoutComments(it.readText()).contains("daily_rollup") }

        assertThat(offenders.map { it.name }).isEmpty()
    }

    private fun withoutComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")

    /**
     * **The assertion that would actually catch a regression.**
     *
     * Each of the three doors that changes ledger visibility recomputes. If
     * someone simplifies `softDeleteEntry` back to a bare `UPDATE` — which is
     * what it was before P3, and which still looks perfectly reasonable — the
     * rollups go stale until the nightly pass, and the user sees a binned
     * expense still counted in this month's total.
     */
    @Test
    fun everyDoorThatChangesLedgerVisibilityRecomputes() {
        val repository = productionSources()
            .single { it.name == "DefaultLedgerRepository.kt" }
            .readText()

        val doors = listOf("softDeleteEntry", "restoreEntry")
        doors.forEach { door ->
            val body = repository.substringAfter("override suspend fun $door")
                .substringBefore("override ")
            assertThat(body).contains("dailyRollupDao().recompute(")
        }

        // Approval's recompute lives in `commit`, not in `approve` itself.
        assertThat(repository).contains("insertEntryWithLineItems(entity, lineItems)")
        assertThat(repository.substringAfter("insertEntryWithLineItems(entity, lineItems)"))
            .contains("dailyRollupDao().recompute(")
    }

    /**
     * And purge must **not** recompute.
     *
     * Not style: purge is the path that also runs `VACUUM` over the whole
     * encrypted file, and the rows it destroys were already absent from every
     * bucket. A recompute here would rewrite rows to the values they already
     * hold, on the one path where extra work is least welcome.
     */
    @Test
    fun purgeDoesNotRecompute() {
        val repository = productionSources()
            .single { it.name == "DefaultLedgerRepository.kt" }
            .readText()

        val purgeBodies = listOf("purgeDeletedEntry", "purgeDeletedEntries").map { door ->
            repository.substringAfter("override suspend fun $door").substringBefore("override ")
        }

        purgeBodies.forEach { assertThat(it).doesNotContain("recompute(") }
    }

    private companion object {
        /** Well below the real count; this only has to catch a scan that found nothing. */
        private const val MINIMUM_EXPECTED_SOURCES = 20
    }
}
