package com.ledgerflow.core.data.export

import com.google.common.truth.Truth.assertWithMessage
import com.ledgerflow.core.database.backup.BackupPayload
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * BUG39: every `budget.csv` row carried two cells more than its header.
 *
 * `ba441c0` added `last_alerted_threshold` and `alert_period_start` to the
 * budget document and wrote each value twice, so every budget row ended with
 * two unlabelled columns. Nothing checked a row's width against its header:
 * `ExportCoversEveryTableTest` exports an **empty** payload, where every
 * document has a header and no rows, so no width can ever be wrong.
 *
 * This test exports a payload with **one fully populated row in every table**
 * and requires every row to be exactly as wide as its header. The rows are
 * built from `BackupPayload`'s serial descriptors rather than written by hand,
 * so a table or column added later is covered without anyone editing this
 * file — the same reason `ExportCoversEveryTableTest` reads the committed
 * schema rather than a list.
 */
class Bug39_EveryCsvRowIsAsWideAsItsHeaderTest {

    @Test
    fun everyRowOfEveryDocument_isExactlyAsWideAsItsHeader() {
        val documents = CsvTables.documents(populatedPayload())

        documents.forEach { document ->
            // An empty document would make the width check vacuous — the
            // failure mode that hid this bug in the first place.
            assertWithMessage("${document.fileName} has no rows to check")
                .that(document.rows).isNotEmpty()
            document.rows.forEach { row ->
                assertWithMessage(
                    "${document.fileName}: a row has ${row.size} cells for ${document.header.size} headers",
                ).that(row.size).isEqualTo(document.header.size)
            }
        }
    }

    private fun populatedPayload(): BackupPayload {
        val descriptor = BackupPayload.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).associate { index ->
            val name = descriptor.getElementName(index)
            val element = descriptor.getElementDescriptor(index)
            name to if (element.kind == StructureKind.LIST) {
                rowsFor(name, element.getElementDescriptor(0))
            } else {
                valueFor(name, element)
            }
        }
        return Json.decodeFromJsonElement(BackupPayload.serializer(), JsonObject(fields))
    }

    /** One row per table; `ledger_entry` gets one per book, since the export splits it. */
    private fun rowsFor(table: String, row: SerialDescriptor): JsonArray =
        if (table == "ledgerEntries") {
            JsonArray(listOf("DEBIT", "CREDIT").map { book -> objectFor(row, ledger = book) })
        } else {
            JsonArray(listOf(objectFor(row, ledger = "DEBIT")))
        }

    private fun objectFor(descriptor: SerialDescriptor, ledger: String): JsonObject =
        JsonObject(
            (0 until descriptor.elementsCount).associate { index ->
                val name = descriptor.getElementName(index)
                name to if (name == "ledger") {
                    JsonPrimitive(ledger)
                } else {
                    valueFor(name, descriptor.getElementDescriptor(index))
                }
            },
        )

    /** A non-null value of the right kind, so every nullable column is exercised too. */
    private fun valueFor(name: String, descriptor: SerialDescriptor): JsonElement = when (descriptor.kind) {
        PrimitiveKind.STRING -> JsonPrimitive("x-$name")
        PrimitiveKind.BOOLEAN -> JsonPrimitive(true)
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> JsonPrimitive(SOME_RATIO)
        PrimitiveKind.LONG, PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> JsonPrimitive(SOME_NUMBER)
        StructureKind.LIST -> JsonArray(emptyList())
        StructureKind.CLASS -> objectFor(descriptor, ledger = "DEBIT")
        else -> error("$name: no test value for ${descriptor.kind}")
    }

    private companion object {
        const val SOME_NUMBER = 12_345
        const val SOME_RATIO = 0.5
    }
}
