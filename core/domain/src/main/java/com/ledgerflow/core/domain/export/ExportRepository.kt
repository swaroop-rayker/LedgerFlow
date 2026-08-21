package com.ledgerflow.core.domain.export

/**
 * Writing the ledger out as files the user owns (SPEC.md §5.9, ADR-0017).
 *
 * The destination is a SAF document URI as a string, not a `File` and not an
 * Android `Uri`. A path would be wrong twice over -- the user picks a location
 * this process has no filesystem access to, and `filesDir` is the one place an
 * export must *not* go (Law 5 is about persistent app data; an export is
 * neither). The `Uri` type stays out because `:core:domain` is Android-free
 * apart from `paging-common` (ADR-0014), and a string round-trips through
 * `Uri.parse` losslessly at the one place that needs it.
 *
 * **Nothing here is encrypted, deliberately.** §5.9 gives this row "none
 * (user's choice, warned)". That is the entire distinction between this and a
 * `.lfbk`: a backup is phrase-derived and safe to put in a Drive folder, and
 * this is a plain zip of the user's complete financial history. The warning is
 * the protection, and the screen owns it -- this port does not second-guess a
 * decision the user has already been asked about, and equally never writes
 * anything it was not explicitly asked for.
 */
public interface ExportRepository {

    /**
     * Writes every table to [destinationUri] as one zipped set of CSV files.
     *
     * Requires an unlocked vault: the rows come from the encrypted database, so
     * a caller reaching this with a locked vault is a bug rather than a state to
     * report, and it surfaces as [ExportResult.Failure].
     */
    public suspend fun exportCsv(destinationUri: String): ExportResult

    /** `LedgerFlow-export-2026-08-21.zip` — dated, so two exports never collide. */
    public fun suggestedFileName(): String
}

/**
 * How an export ended.
 *
 * A typed result rather than an exception, per CLAUDE.md §5. The failure cases
 * are deliberately coarse: every one of them renders as a sentence and none of
 * them is separately actionable by the user beyond "try again somewhere else",
 * so splitting them further would be detail the screen cannot use.
 */
public sealed interface ExportResult {

    /**
     * @param fileCount how many CSV files the zip holds.
     * @param rowCount total rows across all of them, excluding header lines.
     */
    public data class Success(val fileCount: Int, val rowCount: Int) : ExportResult

    /** The vault is locked, so there is nothing to read. */
    public data object VaultLocked : ExportResult

    /**
     * SAF refused the write, the document vanished, or the stream failed
     * partway.
     *
     * @param reason a short technical detail for the log, never shown raw.
     */
    public data class Failure(val reason: String) : ExportResult
}
