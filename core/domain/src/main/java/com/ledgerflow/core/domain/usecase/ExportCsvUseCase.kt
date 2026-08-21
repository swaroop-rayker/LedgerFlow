package com.ledgerflow.core.domain.usecase

import com.ledgerflow.core.domain.export.ExportRepository
import com.ledgerflow.core.domain.export.ExportResult
import javax.inject.Inject

/**
 * Writes the whole ledger to a user-chosen location as zipped CSV
 * (SPEC.md §5.9, ADR-0017).
 *
 * A use case for one delegating call, which is worth justifying rather than
 * assuming. It is not here to add logic; it is here because the screen should
 * depend on *an operation* rather than on a repository it could also call
 * `suggestedFileName()` on at the wrong moment, and because the export is the
 * one path in the app that takes a complete copy of the user's financial
 * history out from behind the encryption. That deserves a name that says so at
 * every call site.
 *
 * Unlike the purge use cases (ADR-0016) this is **not** guarded by a
 * source-scanning test, and the difference is real: those exist because a
 * second caller could destroy data, and nothing here writes to the database at
 * all. An export cannot damage the vault; it can only reveal it, and the
 * revealing is what the screen's warning is for.
 */
public class ExportCsvUseCase @Inject constructor(
    private val export: ExportRepository,
) {
    public suspend operator fun invoke(destinationUri: String): ExportResult =
        export.exportCsv(destinationUri)

    /** The name offered in the SAF create-document sheet. */
    public fun suggestedFileName(): String = export.suggestedFileName()
}
