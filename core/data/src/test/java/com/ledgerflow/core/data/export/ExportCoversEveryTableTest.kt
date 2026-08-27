package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.database.LedgerFlowDatabase
import com.ledgerflow.core.database.backup.BackupPayload
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The export covers every table, checked **against the committed schema**
 * (ADR-0017).
 *
 * The failure this prevents is silent and slow. A schema version adds a table;
 * `BackupPayload` gains a list because the round-trip test would fail without
 * one; nobody thinks about the CSV export; and from then on every export a user
 * takes is missing a table, with no error anywhere.
 *
 * **The first version of this test could not see that happen, and it happened.**
 * It counted `BackupPayload`'s own `List` properties and asserted the CSV
 * matched — internally consistent, and blind to the database. Schema v6 added
 * six tables (`sms_raw`, `notification_raw`, `package_allowlist`,
 * `sender_allowlist`, `parser_rule`, `pending_transaction`) that reached neither
 * the backup nor the export, and this file stayed green through v6 and v7. It
 * was harmless while those tables were empty and stopped being harmless the
 * moment P2-4 gave `pending_transaction` a writer: a restore would have silently
 * dropped the user's unreviewed approval queue, their edited allowlists, and any
 * parser rule they wrote by hand.
 *
 * So the authority is now `core/database/schemas/{VERSION}.json`, the file
 * `scripts/guard-schema.sh` already treats as append-only truth. A guard whose
 * only reference is the thing it guards is not a guard — it is a restatement.
 *
 * **The schema file is declared as a task input** in `core/data/build.gradle.kts`.
 * Gradle cannot see a file a test opens at run time, so without that the task
 * stays `UP-TO-DATE` when the schema changes and this stops running exactly when
 * it matters. Three separate guards in this repository have failed that way; the
 * declaration is not optional.
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
        // A silent zero would make the assertions below vacuous, which is the
        // failure mode that makes a guard worse than no guard.
        assertThat(tableCount).isAtLeast(MINIMUM_EXPECTED_TABLES)
    }

    /**
     * **The assertion the old version of this test was missing.**
     *
     * Table names come from Room's committed schema; the covered set comes from
     * the file names `CsvTables` actually emits. Both directions matter — a
     * schema table with no file is a table the user cannot export or restore,
     * and a file naming no table is a file whose name will not survive the next
     * person looking for it.
     */
    @Test
    fun everySchemaTableIsCoveredByTheExport() {
        val exported = CsvTables.documents(EMPTY_PAYLOAD)
            .map { it.fileName.removeSuffix(".csv") }
            .map { it.removeSuffix("_debit").removeSuffix("_credit") }
            .toSet()

        assertThat(exported).containsExactlyElementsIn(schemaTableNames())
    }

    /**
     * And the payload has exactly as many lists as the schema has tables.
     *
     * Together with the name check above this pins all three enumerations to
     * each other: schema -> payload -> CSV. Adding a table now fails here until
     * it is carried through every one of them.
     */
    @Test
    fun payloadHasOneListPerSchemaTable() {
        assertThat(tableCount).isEqualTo(schemaTableNames().size)
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

    /**
     * The tables Room says exist at the current schema version.
     *
     * `entities` only. `views` are the two ledger projections (ADR-0002) and
     * hold no rows of their own — exporting one would duplicate `ledger_entry`
     * under a second name and make a restore ambiguous about which copy is
     * authoritative.
     */
    private fun schemaTableNames(): Set<String> {
        val file = schemaFile()
        check(file.isFile) {
            "Room schema not found at ${file.absolutePath}. It is committed " +
                "(scripts/guard-schema.sh); if it is missing, the build that " +
                "should have written it did not run with exportSchema = true."
        }
        return Json.parseToJsonElement(file.readText())
            .jsonObject.getValue("database")
            .jsonObject.getValue("entities")
            .jsonArray
            .map { it.jsonObject.getValue("tableName").jsonPrimitive.content }
            .toSet()
    }

    /**
     * Walks up to the repository root rather than trusting the test's working
     * directory, which differs between Gradle and an IDE run.
     */
    private fun schemaFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
        }
        checkNotNull(directory) { "could not locate the repository root from ${File("").absolutePath}" }
        return File(directory, "$SCHEMA_DIRECTORY/${LedgerFlowDatabase.VERSION}.json")
    }

    private companion object {
        private const val SCHEMA_DIRECTORY =
            "core/database/schemas/com.ledgerflow.core.database.LedgerFlowDatabase"

        private val EMPTY_PAYLOAD = BackupPayload(
            schemaVersion = LedgerFlowDatabase.VERSION,
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
