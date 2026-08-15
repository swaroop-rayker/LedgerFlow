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

    /** Every double-quoted literal that looks like SQL. */
    private fun sqlLiterals(): List<SqlLiteral> {
        val stringLiteral = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        return daoSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                stringLiteral.findAll(file.readText())
                    .map { it.groupValues[1] }
                    .filter { it.contains("FROM", ignoreCase = true) }
                    .map { SqlLiteral(file.name, it) }
            }
            .toList()
    }

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

    @Test
    fun aggregatesReadFromTheViewsNotTheBaseTable() {
        val aggregates = sqlLiterals().filter { it.sql.contains("SUM(", ignoreCase = true) }

        assertThat(aggregates).isNotEmpty()
        aggregates.forEach { literal ->
            assertThat(literal.sql.lowercase()).containsMatch("debit_entries|credit_entries")
        }
    }

    @Test
    fun ledgerViewsAreDefinedWithADisjointPredicate() {
        val entities = File(daoSourceDir.parentFile, "entity/LedgerEntities.kt")
        val text = entities.readText()

        assertThat(text).contains("WHERE ledger = 'DEBIT'")
        assertThat(text).contains("WHERE ledger = 'CREDIT'")
    }
}
