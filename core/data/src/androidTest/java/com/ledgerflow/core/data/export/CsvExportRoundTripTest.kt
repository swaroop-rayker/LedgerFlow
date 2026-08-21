package com.ledgerflow.core.data.export

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.ledgerflow.core.data.ledger.LedgerTestVault
import com.ledgerflow.core.domain.export.ExportResult
import com.ledgerflow.core.domain.ledger.ApprovalRequest
import com.ledgerflow.core.domain.ledger.LedgerResult
import com.ledgerflow.core.domain.taxonomy.NewCategory
import com.ledgerflow.core.domain.taxonomy.TaxonomyResult
import com.ledgerflow.core.model.Category
import com.ledgerflow.core.model.EntryAssignment
import com.ledgerflow.core.model.LedgerEntry
import com.ledgerflow.core.model.LedgerType
import com.ledgerflow.core.model.Merchant
import com.ledgerflow.core.model.Money
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The export against a real vault and a real zip (SPEC.md §5.9, ADR-0017).
 *
 * The unit tests cover the escaping exhaustively; this covers the things they
 * cannot: that the rows actually come out of SQLCipher, that the zip is a zip,
 * and that the two money columns agree with each other on data that went
 * through the whole stack rather than through a fixture.
 *
 * Writes to a `file://` URI rather than a SAF document URI. `ContentResolver`
 * serves both, and the alternative is driving the system document picker from an
 * instrumented test — which would test the picker.
 */
@RunWith(AndroidJUnit4::class)
class CsvExportRoundTripTest {

    private val vault = LedgerTestVault("lf_csv_export_test")

    private lateinit var exporter: DefaultExportRepository
    private lateinit var destination: File

    private lateinit var groceries: Category
    private lateinit var zepto: Merchant

    @Before
    fun setUp() = runBlocking<Unit> {
        vault.open()
        exporter = DefaultExportRepository(
            context = vault.context,
            session = vault.session,
            clock = vault.clock,
            io = Dispatchers.IO,
        )
        destination = File(vault.context.filesDir, "csv-export-test.zip").apply { delete() }

        groceries = vault.categories.create(NewCategory(LedgerType.DEBIT, "Groceries")).success()
        // A name with a comma and a quote in it: the case that silently shifts
        // every column after it if the escaping is wrong.
        zepto = vault.merchants.createOrGet("""Zepto "Express", Powai""").success()
    }

    @After
    fun tearDown() {
        destination.delete()
        vault.close()
    }

    @Test
    fun export_writesAZipOfCsvFiles() = runBlocking<Unit> {
        approve(amountMinor = 25_500L)

        val result = exporter.exportCsv(uri())

        assertThat(result).isInstanceOf(ExportResult.Success::class.java)
        val success = result as ExportResult.Success
        assertThat(destination.exists()).isTrue()

        val entries = readZip()
        assertThat(entries.keys).hasSize(success.fileCount)
        assertThat(entries.keys).containsAtLeast(
            "category.csv",
            "merchant.csv",
            "payment_method.csv",
            "ledger_entry_debit.csv",
            "ledger_entry_credit.csv",
            "line_item.csv",
        )
    }

    /**
     * The two money columns agree, on a value a `Double` renders wrong.
     *
     * ₹8.07 is 807 minor units, and 807 / 100.0 is 8.069999999999999. If a float
     * ever creeps onto this path this is the assertion that catches it, on data
     * that went through the approval use case and SQLCipher rather than through
     * a fixture.
     */
    @Test
    fun amountColumns_agree_onAValueADoubleWouldRoundWrong() = runBlocking<Unit> {
        approve(amountMinor = 807L)

        exporter.exportCsv(uri())

        val row = dataRows("ledger_entry_debit.csv").single()
        val header = headerOf("ledger_entry_debit.csv")
        assertThat(row[header.indexOf("amount_minor")]).isEqualTo("807")
        assertThat(row[header.indexOf("amount")]).isEqualTo("8.07")
    }

    /** A merchant name full of commas and quotes survives as one field. */
    @Test
    fun merchantNameWithCommasAndQuotes_staysOneField() = runBlocking<Unit> {
        exporter.exportCsv(uri())

        val header = headerOf("merchant.csv")
        val row = dataRows("merchant.csv").single { it[header.indexOf("id")] == zepto.id }

        assertThat(row[header.indexOf("canonical_name")]).isEqualTo("""Zepto "Express", Powai""")
        // The real assertion: the row did not gain columns from the embedded
        // commas. A broken escape would push `normalized_key` two places right.
        assertThat(row).hasSize(header.size)
    }

    /**
     * Binned entries and hidden taxonomy are in the export (ADR-0017).
     *
     * The bin's own erase dialog tells the user to export first if they might
     * want something back. That instruction is only true if the export actually
     * contains what they are about to erase.
     */
    @Test
    fun softDeletedRows_areExportedWithTheirDeletedAt() = runBlocking<Unit> {
        val binned = approve(amountMinor = 100_00L)
        vault.ledger.softDeleteEntry(LedgerType.DEBIT, binned.id)
        vault.merchants.delete(zepto.id).success()

        exporter.exportCsv(uri())

        val entryHeader = headerOf("ledger_entry_debit.csv")
        val entryRow = dataRows("ledger_entry_debit.csv")
            .single { it[entryHeader.indexOf("id")] == binned.id }
        assertThat(entryRow[entryHeader.indexOf("deleted_at")]).isNotEmpty()
        assertThat(entryRow[entryHeader.indexOf("deleted_at_iso")]).contains("T")

        val merchantHeader = headerOf("merchant.csv")
        val merchantRow = dataRows("merchant.csv")
            .single { it[merchantHeader.indexOf("id")] == zepto.id }
        assertThat(merchantRow[merchantHeader.indexOf("deleted_at")]).isNotEqualTo("0")
    }

    /**
     * The two books land in two files and neither leaks into the other.
     *
     * §5.5 promises "separate lists", and an export is the most literal list the
     * app hands over.
     */
    @Test
    fun theTwoBooksAreSeparateFiles() = runBlocking<Unit> {
        val refunds = vault.categories.create(NewCategory(LedgerType.CREDIT, "Refunds")).success()
        val expense = approve(amountMinor = 25_500L)
        val income = approve(
            amountMinor = 90_000L,
            ledger = LedgerType.CREDIT,
            categoryId = refunds.id,
        )

        exporter.exportCsv(uri())

        val debitIds = idsIn("ledger_entry_debit.csv")
        val creditIds = idsIn("ledger_entry_credit.csv")

        assertThat(debitIds).containsExactly(expense.id)
        assertThat(creditIds).containsExactly(income.id)
    }

    /** Every file has a header, even the tables this vault never populated. */
    @Test
    fun emptyTablesStillAppear() = runBlocking<Unit> {
        exporter.exportCsv(uri())

        val entries = readZip()
        assertThat(entries.getValue("category_group.csv").trim())
            .isEqualTo("id,name,color_argb,ledger_scope")
    }

    /**
     * A second export over the same document truncates rather than appending.
     *
     * SAF hands back an existing document when the user picks a name that is
     * already there. Without the `"wt"` mode the tail of the larger previous
     * export would survive, producing a zip that opens and is subtly wrong.
     */
    @Test
    fun exportingTwice_truncatesRatherThanAppending() = runBlocking<Unit> {
        repeat(TWICE) { approve(amountMinor = 25_500L) }
        exporter.exportCsv(uri())
        val large = destination.length()

        vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)
        idsIn("ledger_entry_debit.csv").forEach { id ->
            vault.ledger.softDeleteEntry(LedgerType.DEBIT, id)
        }
        vault.ledger.purgeDeletedEntries(LedgerType.DEBIT)

        exporter.exportCsv(uri())

        assertThat(destination.length()).isLessThan(large)
        assertThat(idsIn("ledger_entry_debit.csv")).isEmpty()
    }

    @Test
    fun suggestedFileName_isDatedAndZip() {
        val name = exporter.suggestedFileName()

        assertThat(name).startsWith("LedgerFlow-export-")
        assertThat(name).endsWith(".zip")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun uri(): String = Uri.fromFile(destination).toString()

    private fun readZip(): Map<String, String> = buildMap {
        ZipInputStream(destination.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun headerOf(fileName: String): List<String> =
        parse(readZip().getValue(fileName)).first()

    private fun dataRows(fileName: String): List<List<String>> =
        parse(readZip().getValue(fileName)).drop(1)

    private fun idsIn(fileName: String): List<String> {
        val rows = parse(readZip().getValue(fileName))
        val idIndex = rows.first().indexOf("id")
        return rows.drop(1).map { it[idIndex] }
    }

    /**
     * A deliberately independent RFC 4180 reader.
     *
     * Not shared with `CsvWriter`: a round-trip through the same escaping logic
     * proves only that the code agrees with itself. This is written from the
     * spec so that a bug in the writer shows up as a parse mismatch here.
     */
    private fun parse(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        while (index < csv.length) {
            val char = csv[index]
            when {
                quoted && char == '"' && csv.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                !quoted && char == ',' -> {
                    row += field.toString()
                    field.clear()
                }
                !quoted && char == '\r' && csv.getOrNull(index + 1) == '\n' -> {
                    row += field.toString()
                    field.clear()
                    rows += row.toList()
                    row.clear()
                    index++
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row.toList()
        }
        return rows
    }

    private suspend fun approve(
        amountMinor: Long,
        ledger: LedgerType = LedgerType.DEBIT,
        categoryId: String? = null,
    ): LedgerEntry = vault.ledger.approve(
        ApprovalRequest(
            ledger = ledger,
            amount = Money(amountMinor),
            occurredAt = OCCURRED_AT,
            assignment = EntryAssignment(
                categoryId = categoryId ?: if (ledger == LedgerType.DEBIT) groceries.id else null,
                merchantId = if (ledger == LedgerType.DEBIT) zepto.id else null,
            ),
        ),
    ).success()

    private fun LedgerResult<LedgerEntry>.success(): LedgerEntry {
        assertThat(this).isInstanceOf(LedgerResult.Success::class.java)
        return (this as LedgerResult.Success).value
    }

    private fun <T> TaxonomyResult<T>.success(): T {
        assertThat(this).isInstanceOf(TaxonomyResult.Success::class.java)
        return (this as TaxonomyResult.Success).value
    }

    private companion object {
        private const val OCCURRED_AT = 1_700_000_000_000L
        private const val TWICE = 2
    }
}
