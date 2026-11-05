package com.ledgerflow.feature.onboarding.restore

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.backup.RestoreOutcome
import com.ledgerflow.core.domain.backup.RestorePreview
import com.ledgerflow.core.domain.backup.RestoreSource
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.feature.onboarding.phrase.rejectionMessage

/**
 * Restore from a backup at first run (SPEC.md §7.3 step 3, §16 Q11): choose the
 * backup, type its 24 words, and the ledger comes back under those words.
 *
 * The backup is chosen **by folder** — the folder "Back up now" writes into,
 * with the receipt images beside the `.lfbk` (ADR-0023) — and the newest backup
 * in it is preselected. A single `.lfbk` that travelled on its own is the
 * secondary route: its rows come back and its images are counted as not found.
 */
@Immutable
public data class RestoreUiState(
    /** Which of the three restores this is: first run, finishing one, or from Settings. */
    val mode: RestoreMode = RestoreMode.FirstRun,
    /** The folder the user chose. Null until then, or when a single file was chosen instead. */
    val treeUri: String? = null,
    /** The `.lfbk` files in [treeUri], newest first. */
    val backups: List<String> = emptyList(),
    val selectedBackup: String? = null,
    /** The chosen folder could not be read at all. */
    val folderUnreadable: Boolean = false,
    /** A single `.lfbk` chosen instead of a folder. */
    val singleFileUri: String? = null,
    val entry: PhraseEntry = PhraseEntry(),
    /** The QR scanner is open (ADR-0028); typing stays available behind it. */
    val isScanning: Boolean = false,
    /** Why the last scan was refused, if it was. */
    val scanMessage: String? = null,
    val isWorking: Boolean = false,
    /** The last attempt's outcome, until superseded. A [RestoreOutcome.Done] is final. */
    val result: RestoreOutcome? = null,
    /**
     * From Settings: the backup opened and was counted, and the confirmation
     * page is showing (ADR-0033 c). Null on the words page.
     */
    val preview: RestorePreview.Ready? = null,
    /** What the user has typed on the confirmation page; Replace needs DELETE. */
    val confirmation: String = "",
    /** Finishing an interrupted restore from Settings: the vault it set aside can go back. */
    val canPutBack: Boolean = false,
    /** The last put-back did not work. Nothing was removed. */
    val putBackFailed: Boolean = false,
) {
    /** Where the backup is, once one is chosen. */
    val source: RestoreSource?
        get() = when {
            singleFileUri != null -> RestoreSource.SingleFile(singleFileUri)
            treeUri != null && selectedBackup != null -> RestoreSource.InFolder(treeUri, selectedBackup)
            else -> null
        }

    val isDone: Boolean get() = result is RestoreOutcome.Done

    val canSubmit: Boolean get() = source != null && entry.isComplete && !isWorking && !isDone

    /** The confirmation page, between the words and the replace. */
    val isConfirming: Boolean get() = preview != null && !isDone

    /**
     * DELETE, typed (§7.3). Case and surrounding spaces are forgiven — a
     * keyboard capitalises the first letter of a field on its own — and
     * nothing else is.
     */
    val isConfirmed: Boolean get() = confirmation.trim().equals(CONFIRMATION_WORD, ignoreCase = true)

    val canReplace: Boolean get() = isConfirming && isConfirmed && !isWorking

    public companion object {
        /** The word §7.3 asks for before anything is destroyed. */
        public const val CONFIRMATION_WORD: String = "DELETE"
    }
}

/** Which restore the screen is doing. */
public enum class RestoreMode {
    /** At first run, instead of onboarding (ADR-0026). */
    FirstRun,

    /** A restore was interrupted; the screen is the only way on. */
    Resuming,

    /** From Settings, over this phone's ledger (ADR-0033). */
    Replace,
}

public sealed interface RestoreEvent {
    /** The folder picker returned; null when the user backed out of it. */
    public data class FolderChosen(val treeUri: String?) : RestoreEvent
    public data class BackupSelected(val fileName: String) : RestoreEvent

    /** The single-file picker returned; null when the user backed out of it. */
    public data class SingleFileChosen(val documentUri: String?) : RestoreEvent

    public data class DraftChanged(val value: String) : RestoreEvent
    public data class WordCommitted(val word: String) : RestoreEvent
    public data class WordRemoved(val index: Int) : RestoreEvent
    public data object Submitted : RestoreEvent

    /** The restore from Settings' own steps (ADR-0033), grouped so the ViewModel routes them in one place. */
    public sealed interface FromSettings : RestoreEvent

    /** The confirmation field changed. */
    public data class ConfirmationChanged(val value: String) : FromSettings

    /** Replace, tapped on the confirmation page with DELETE typed. */
    public data object Replaced : FromSettings

    /** Back from the confirmation page to the words, which are kept. */
    public data object ConfirmationLeft : FromSettings

    /** Abandon an interrupted restore from Settings and put back the ledger it set aside. */
    public data object PutBack : FromSettings

    /** The report is read; open the restored ledger. */
    public data object Continued : RestoreEvent

    /**
     * The user went back to onboarding (BUG30). The ViewModel outlives the
     * screen — it belongs to the activity — so this, not `onCleared`, is when
     * the words and the chosen backup are forgotten.
     */
    public data object Left : RestoreEvent

    /**
     * The Recovery Kit scanner (ADR-0028), grouped so the screen's `when` has
     * one branch for "the camera said something" rather than three.
     */
    public sealed interface Scanner : RestoreEvent {
        /** Open the scanner. Typing stays available behind it. */
        public data object Requested : Scanner

        /** Close it without a scan — "type the words instead", or back. */
        public data object Dismissed : Scanner

        /** A QR code was read; the text is validated before it becomes words. */
        public data class Read(val text: String) : Scanner
    }
}

/**
 * One sentence per outcome. Every failure says **what did not happen** as well
 * as why — a restore that leaves the user unsure whether their data is on the
 * phone is the failure that matters most here.
 */
internal fun RestoreOutcome.message(): String = when (this) {
    is RestoreOutcome.Done -> doneMessage()

    is RestoreOutcome.PhraseRejected -> validation.rejectionMessage()

    RestoreOutcome.WrongPhrase ->
        "Those words are a valid phrase, but not the one this backup was made with. " +
            "Nothing was restored. Check the Recovery Kit from the phone that made it."

    RestoreOutcome.NotTheInterruptedRestoresPhrase ->
        "A restore on this phone was started with a different phrase, and only those " +
            "words can finish it. Use the same 24 words — any backup made with them will do."

    RestoreOutcome.Damaged ->
        "This file is damaged or isn't a LedgerFlow backup. Nothing was restored. " +
            "Try an older backup from the same folder."

    is RestoreOutcome.NewerVersion ->
        "This backup was made by a newer version of LedgerFlow. Update the app, then restore. " +
            "Nothing was restored."

    RestoreOutcome.SourceUnreadable ->
        "The backup couldn't be read — it may have moved, or access to it was lost. " +
            "Choose it again. Nothing was restored."

    RestoreOutcome.AlreadySetUp ->
        "This phone already has a ledger. Nothing was restored — restoring only " +
            "replaces setting up a new one."

    RestoreOutcome.Failed ->
        "The restore didn't finish. Your backup is untouched. Try again with the same words."

    RestoreOutcome.RolledBack ->
        "The restore didn't finish, so nothing changed: this phone's ledger is as it was, " +
            "and your backup is untouched."
}

/**
 * The report. The images are spelled out because the ledger coming back and
 * every receipt coming back are different claims, and only the first is what a
 * [RestoreOutcome.Done] guarantees. "N receipt images weren't found alongside
 * this backup" is ADR-0023's own sentence.
 */
private fun RestoreOutcome.Done.doneMessage(): String = buildString {
    append("Your ledger is back: ${plural(rows, "record")} restored.")
    if (imagesRestored > 0) append(" ${plural(imagesRestored, "receipt image")} restored.")
    if (imagesNotFound > 0) {
        val verb = if (imagesNotFound == 1) "wasn't" else "weren't"
        append(" ${plural(imagesNotFound, "receipt image")} $verb found alongside this backup.")
    }
    if (imagesUnreadable > 0) {
        append(" ${plural(imagesUnreadable, "receipt image")} couldn't be opened — damaged, or made with other words.")
    }
    if (imagesFailed > 0) append(" ${plural(imagesFailed, "receipt image")} couldn't be saved on this phone.")
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

internal val RestoreOutcome.isSuccess: Boolean get() = this is RestoreOutcome.Done
