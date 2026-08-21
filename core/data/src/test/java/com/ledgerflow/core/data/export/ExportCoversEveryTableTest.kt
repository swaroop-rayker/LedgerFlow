package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.backup.BackupPayload
import org.junit.Test

/**
 * The export covers every table, enforced mechanically (ADR-0017).
 *
 * The failure this prevents is silent and slow. Schema v6 adds a table;
 * `BackupPayload` gains a list because the round-trip test would fail without
 * one; nobody thinks about the CSV export; and from then on every export a user
 * takes is missing a table, with no error anywhere. By the time it is noticed,
 * exports have been taken and relied on.
 *
 * Counting by reflection rather than against a hardcoded number is the whole
 * point — a literal `assertThat(documents).hasSize(11)` would fail on the next
 * table too, but it would fail for the person *adding* the table with no
 * explanation of what to do, and the cheapest way to make it pass is to bump
 * the number.
 */
class ExportCoversEveryTableTest {

    /**
     * Every `List` property of the payload is one table.
     *
     * Java reflection rather than `kotlin-reflect`: counting backing fields is
     * all this needs, and `kotlin-reflect` is a ~3 MB dependency to add to a
     * test source set for one `count`. It also happens to be more precise here
     * -- `rowCount` is a computed property with no backing field, so it is
     * absent from `declaredFields` without needing to be filtered out.
     */
    private val tableCount: Int = BackupPayload::class.java.declaredFields
        .count { List::class.java.isAssignableFrom(it.type) }

    @Test
    fun tableCount_isDiscoverable() {
        // A silent zero would make the assertion below vacuous, which is the
        // failure mode that makes a guard worse than no guard.
        assertThat(tableCount).isAtLeast(MINIMUM_EXPECTED_TABLES)
    }

    @Test
    fun everyTableProducesExactlyOneFile_exceptTheLedgerWhichProducesTwo() {
        val documents = CsvTables.documents(EMPTY_PAYLOAD)

        assertThat(documents).hasSize(tableCount + CsvTables.LEDGER_ENTRY_SPLIT_EXTRA)
    }

    @Test
    fun fileNamesAreUniqueAndCsv() {
        val names = CsvTables.documents(EMPTY_PAYLOAD).map { it.fileName }

        assertThat(names).containsNoDuplicates()
        names.forEach { assertThat(it).endsWith(".csv") }
    }

    /**
     * The split is per book and named for it.
     *
     * §5.5 promises the user "separate lists"; an export is the most literal
     * list the app hands over, so the two books arrive as two files rather than
     * one file with a column to filter on.
     */
    @Test
    fun ledgerEntriesSplitPerBook() {
        val names = CsvTables.documents(EMPTY_PAYLOAD).map { it.fileName }

        assertThat(names).containsAtLeast("ledger_entry_debit.csv", "ledger_entry_credit.csv")
        assertThat(names).doesNotContain("ledger_entry.csv")
    }

    /** Every file carries a header even when the table is empty. */
    @Test
    fun emptyTablesStillGetAHeaderRow() {
        CsvTables.documents(EMPTY_PAYLOAD).forEach { document ->
            assertThat(document.header).isNotEmpty()
            assertThat(document.rows).isEmpty()
            assertThat(document.render()).isEqualTo(
                document.header.joinToString(",") + "\r\n",
            )
        }
    }

    private companion object {
        private val EMPTY_PAYLOAD = BackupPayload(
            schemaVersion = 5,
            createdAt = 0L,
            appMeta = emptyList(),
            categories = emptyList(),
            merchants = emptyList(),
            paymentMethods = emptyList(),
            ledgerEntries = emptyList(),
            lineItems = emptyList(),
        )

        /** Well below the real count; this only has to catch reflection finding nothing. */
        private const val MINIMUM_EXPECTED_TABLES = 8
    }
}
