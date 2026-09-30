package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.export.ExportRepository
import com.ledgerflow.core.domain.export.ExportResult
import javax.inject.Inject

/**
 * Writes the whole ledger to a user-chosen location as one XLSX workbook
 * (SPEC.md §5.9, ADR-0004).
 *
 * The sibling of [ExportCsvUseCase], named for the same reason: the export is
 * the one path that takes a complete, unencrypted copy of the user's financial
 * history out from behind the encryption, and every call site should say so.
 */
public class ExportXlsxUseCase @Inject constructor(
    private val export: ExportRepository,
) {
    public suspend operator fun invoke(destinationUri: String): ExportResult =
        export.exportXlsx(destinationUri)

    /** The name offered in the SAF create-document sheet. */
    public fun suggestedFileName(): String = export.suggestedXlsxFileName()
}
