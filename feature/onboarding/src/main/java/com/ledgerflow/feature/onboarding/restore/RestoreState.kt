package com.ledgerflow.feature.onboarding.restore

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.backup.RestoreOutcome
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
    val isWorking: Boolean = false,
    /** The last attempt's outcome, until superseded. A [RestoreOutcome.Done] is final. */
    val result: RestoreOutcome? = null,
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

    /** The report is read; open the restored ledger. */
    public data object Continued : RestoreEvent

    /**
     * The user went back to onboarding (BUG30). The ViewModel outlives the
     * screen — it belongs to the activity — so this, not `onCleared`, is when
     * the words and the chosen backup are forgotten.
     */
    public data object Left : RestoreEvent
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
