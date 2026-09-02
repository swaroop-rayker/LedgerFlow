package com.ledgerflow.core.database

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Law 2, enforced mechanically (ADR-0002).
 *
 * The single-table design's honest weakness is that
 * `SELECT SUM(amount_minor) FROM ledger_entry` is a legal statement that
 * silently violates ledger isolation. This is the fourth of the four
 * enforcement levels: every SQL string in every DAO is checked, and any
 * statement naming the base table without binding a ledger discriminator fails
 * the build.
 *
 * **Why source scanning rather than reflection:** Room's `@Query` is declared
 * with `AnnotationRetention.BINARY`, so it is absent from the runtime class and
 * `Method.getAnnotation(Query::class.java)` always returns null. The first
 * version of this test used reflection, found zero queries, and would have
 * passed vacuously forever -- a guard that cannot fail is worse than no guard,
 * because it is trusted. Scanning the source also covers `@RawQuery` bodies and
 * any SQL added outside an annotation.
 *
 * If this ever has to be weakened or given an exemption list to let a
 * "legitimate" query through, that is the signal to reopen ADR-0002 rather than
 * to add the exemption.
 */
class LedgerIsolationTest {

    private data class SqlLiteral(val file: String, val sql: String)

    private val daoSourceDir: File by lazy {
        val candidates = listOf(
            File("src/main/java/com/ledgerflow/core/database/dao"),
            File("core/database/src/main/java/com/ledgerflow/core/database/dao"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("DAO source directory not found from ${File("").absolutePath}")
    }

    /**
     * Every double-quoted literal that looks like SQL.
     *
     * `UPDATE` and `DELETE` are matched as well as `FROM`. The original filter
     * looked for `FROM` alone, which was enough while every DAO query was a
     * `SELECT` -- but an `UPDATE ledger_entry SET category_id = ...` has no
     * `FROM` clause, so the first write query against the base table would have
     * walked straight past a guard everyone believed was watching. Widened when
     * category re-assignment and merchant merging introduced exactly that shape.
     */
    private fun sqlLiterals(): List<SqlLiteral> {
        val stringLiteral = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val statement = Regex("""\b(FROM|UPDATE|DELETE)\b""", RegexOption.IGNORE_CASE)
        return daoSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                stringLiteral.findAll(joinConcatenations(file.readText()))
                    .map { it.groupValues[1] }
                    .filter { statement.containsMatchIn(it) }
                    .map { SqlLiteral(file.name, it) }
            }
            .toList()
    }

    /**
     * Splices `"a " + "b"` back into one literal before scanning.
     *
     * A query long enough to wrap gets split across two literals by ktlint's
     * line length, and the scanner saw each half alone -- so
     * `"UPDATE ledger_entry SET ... " + "WHERE ledger = :ledger"` looked like an
     * unguarded statement in its first half and a harmless fragment in its
     * second. Left unjoined this guard produces false positives on correct code,
     * which is the fastest route to someone "fixing" it by deleting the
     * assertion.
     */
    private fun joinConcatenations(source: String): String =
        source.replace(Regex("\"\\s*\\+\\s*\""), "")

    @Test
    fun sqlLiterals_areDiscoverable() {
        // Guards the scanner itself. A silent zero would make every assertion
        // below vacuously true -- exactly the failure the reflection version had.
        assertThat(sqlLiterals()).isNotEmpty()
    }

    @Test
    fun noQueryTouchesLedgerEntryWithoutBindingALedger() {
        val baseTable = Regex("""\bledger_entry\b""", RegexOption.IGNORE_CASE)

        val offenders = sqlLiterals().filter { literal ->
            baseTable.containsMatchIn(literal.sql) && !literal.sql.contains(":ledger")
        }

        assertThat(offenders.map { "${it.file}: ${it.sql}" }).isEmpty()
    }

    @Test
    fun noQueryCombinesBothLedgersInOneStatement() {
        // A UNION or JOIN across the two views would produce exactly the netted
        // figure Law 2 forbids.
        val offenders = sqlLiterals().filter { literal ->
            val sql = literal.sql.lowercase()
            sql.contains("debit_entries") && sql.contains("credit_entries")
        }

        assertThat(offenders.map { "${it.file}: ${it.sql}" }).isEmpty()
    }

    /**
     * A money aggregate over the ledger must read a per-book view (ADR-0002).
     *
     * **Sharpened 2026-08-25, and it is worth being precise about why**, because
     * an edit to a Law 2 guard is otherwise indistinguishable from someone
     * filing the teeth off it.
     *
     * The rule used to be "any literal containing `SUM(` must mention a view
     * name somewhere". That is a proxy for the real invariant, and it was wrong
     * in both directions:
     *
     * - **Too strict.** ADR-0018's line-item fallback sums
     *   `line_item.total_minor` inside a per-entry subquery to find which
     *   category a bill mostly went to. The bin's copy of that read must name
     *   `ledger_entry` -- the views' predicate is `deleted_at IS NULL`, so no
     *   view can ever return a binned row -- and the old rule failed it. That
     *   aggregate cannot net books: it is scoped by `li.entry_id = e.id`, a line
     *   item belongs to one entry, an entry belongs to one book, and the value
     *   is only ever an `ORDER BY` key for choosing a *name*.
     * - **Too lax.** `pagingDebits` and `pagingCredits` carry the same subquery
     *   and passed anyway, purely because "debit_entries" appears in their outer
     *   `FROM`. The guard was not checking what their `SUM` read from; it was
     *   checking that a view was mentioned in the string at all.
     *
     * What Law 2 actually forbids is netting *entry amounts* across the two
     * books, so that is what is tested now: a `SUM` over the ledger's own money
     * column has to come from a view. `SUM(li.total_minor)` and any future
     * aggregate over a different column are simply not what this rule is about
     * -- `noQueryTouchesLedgerEntryWithoutBindingALedger` above still covers
     * every statement naming the base table, including those.
     *
     * This is a narrowing of scope onto the real invariant rather than an
     * exemption list, which is why it did not reopen ADR-0002 -- but it is
     * recorded there under Consequences so the next reader finds the reasoning
     * rather than a mysteriously specific regex.
     */
    @Test
    fun aggregatesOverEntryAmountsReadFromTheViewsNotTheBaseTable() {
        // SUM over amount_minor, with or without a table alias in front of it.
        val entryAmountSum = Regex("""SUM\(\s*(\w+\.)?amount_minor\s*\)""", RegexOption.IGNORE_CASE)
        val aggregates = sqlLiterals().filter { entryAmountSum.containsMatchIn(it.sql) }

        assertThat(aggregates).isNotEmpty()
        aggregates.forEach { literal ->
            assertThat(literal.sql.lowercase()).containsMatch("debit_entries|credit_entries")
        }
    }

    /**
     * The other half of the sharpening: an aggregate over anything else must
     * still be confined to one entry.
     *
     * Without this, dropping the old blanket rule would leave
     * `SUM(li.total_minor)` unconstrained across the whole `line_item` table --
     * a statement that really could add a debit's items to a credit's. Every
     * such aggregate has to bind an entry, which is what makes it single-book
     * by construction.
     */
    @Test
    fun lineItemAggregatesAreScopedToOneEntry() {
        val lineItemSum = Regex("""SUM\(\s*(\w+\.)?total_minor\s*\)""", RegexOption.IGNORE_CASE)
        val offenders = sqlLiterals()
            .filter { lineItemSum.containsMatchIn(it.sql) }
            .filterNot { Regex("""entry_id\s*=\s*\w+\.id""").containsMatchIn(it.sql) }

        assertThat(offenders.map { "${it.file}: ${it.sql}" }).isEmpty()
    }

    /**
     * **`daily_rollup` is the second partitioned table, and it has no views.**
     *
     * Added at v9 with the table itself, before the queries exist, because the
     * hole it closes is one this codebase has already fallen into once at a
     * different address: `ledger_entry` is safe from an unfiltered read because
     * the predicate lives inside `debit_entries` / `credit_entries` and a DAO
     * physically cannot select from the base table without tripping the rule
     * above. `daily_rollup` has no such object. `SELECT SUM(sum_minor) FROM
     * daily_rollup WHERE local_date BETWEEN :from AND :to` is a legal, natural,
     * plausible-looking statement that nets a month of income against a month
     * of spending, and every chart in §5.6 is one keystroke away from it.
     *
     * Budgets make it sharper still. §5.7 scopes them to the debit ledger and
     * §6.1 gives `budget` no `ledger` column, so "debit only" is *entirely* a
     * property of the read — there is no schema object left to enforce it. This
     * is that enforcement.
     *
     * The rule is the same as the base table's: name the table, bind a ledger.
     * Either a `:ledger` parameter or a literal `'DEBIT'`/`'CREDIT'` counts —
     * the budget queries genuinely are debit-only and should say so in the SQL
     * rather than take a parameter that has exactly one legal value.
     */
    @Test
    fun noQueryTouchesDailyRollupWithoutBindingALedger() {
        val rollupTable = Regex("""\bdaily_rollup\b""", RegexOption.IGNORE_CASE)
        val boundLedger = Regex(""":ledger|'DEBIT'|'CREDIT'""", RegexOption.IGNORE_CASE)

        val offenders = sqlLiterals().filter { literal ->
            rollupTable.containsMatchIn(literal.sql) && !boundLedger.containsMatchIn(literal.sql)
        }

        assertThat(offenders.map { "${it.file}: ${it.sql}" }).isEmpty()
    }

    @Test
    fun ledgerViewsAreDefinedWithADisjointPredicate() {
        val entities = File(daoSourceDir.parentFile, "entity/LedgerEntities.kt")
        val text = entities.readText()

        assertThat(text).contains("WHERE ledger = 'DEBIT'")
        assertThat(text).contains("WHERE ledger = 'CREDIT'")
    }
}
