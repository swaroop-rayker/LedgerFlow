package com.ledgerflow.core.data.statement

import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.domain.ingest.ExtractedDirection
import com.ledgerflow.core.domain.statement.StatementAmounts
import com.ledgerflow.core.domain.statement.StatementLine
import com.ledgerflow.core.domain.statement.StatementOpened
import com.ledgerflow.core.domain.statement.StatementReading
import java.io.File
import org.junit.Test

/**
 * `testdata/statements/`: each layout opened, guessed and read end to end, as
 * a user's file would be (V2-4 Import). The corpus only grows: a statement
 * that imports wrongly becomes a fixture here, values made up.
 */
class StatementCorpusTest {

    private val folder: File by lazy {
        listOf(File("../../testdata/statements"), File("testdata/statements")).firstOrNull { it.isDirectory }
            ?: error("testdata/statements not found from ${File("").absolutePath}")
    }

    private data class Expected(
        val amounts: Class<out StatementAmounts>,
        val debits: List<Long>,
        val credits: List<Long>,
    )

    private val expected = mapOf(
        "split-with-preamble.csv" to Expected(
            StatementAmounts.Split::class.java,
            debits = listOf(41_000, 124_050, 231_500),
            credits = listOf(8_500_000),
        ),
        "debit-credit-columns.csv" to Expected(
            StatementAmounts.Split::class.java,
            debits = listOf(41_000, 200_000),
            credits = listOf(8_500_000),
        ),
        "signed-with-drcr.tsv" to Expected(
            StatementAmounts.Signed::class.java,
            debits = listOf(41_000),
            credits = listOf(8_500_000, 41_000),
        ),
    )

    @Test
    fun everyFixture_hasAnExpectedReading() {
        val files = folder.listFiles().orEmpty().map { it.name }.filterNot { it == "README.md" }
        assertWithMessage("fixtures without an expected reading").that(files.toSet() - expected.keys).isEmpty()
    }

    @Test
    fun everyFixture_readsAsExpected() {
        expected.forEach { (name, want) ->
            val document = StatementDocument(name, File(folder, name).readBytes())
            val opened = StatementFiles.open(document, "INR") as StatementOpened.Opened
            val columns = requireNotNull(opened.guess) { "$name: no guess" }.columns
            assertWithMessage("$name: layout").that(columns.amounts).isInstanceOf(want.amounts)

            val lines = StatementReading.read(opened.table, columns, "INR")
            assertWithMessage("$name: unreadable").that(lines.filterIsInstance<StatementLine.Unreadable>()).isEmpty()
            val read = lines.filterIsInstance<StatementLine.Read>()
            assertWithMessage("$name: debits")
                .that(read.filter { it.direction == ExtractedDirection.DEBIT }.map { it.amount.minor })
                .containsExactlyElementsIn(want.debits).inOrder()
            assertWithMessage("$name: credits")
                .that(read.filter { it.direction == ExtractedDirection.CREDIT }.map { it.amount.minor })
                .containsExactlyElementsIn(want.credits).inOrder()
            assertWithMessage("$name: descriptions").that(read.all { !it.description.isNullOrBlank() }).isTrue()
        }
    }
}
