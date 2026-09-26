package com.ledgerflow.feature.settings.backup

import androidx.compose.runtime.Immutable
import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.vault.PhraseEntry
import com.ledgerflow.core.domain.vault.PhraseValidation

/**
 * "Back up now" (SPEC.md §16 Q23): the 24 words in, a verified `.lfbk` and
 * the receipt images out, into the folder the user chose.
 *
 * The words are typed through the same [PhraseEntry] the Recovery screen uses,
 * so both screens take a phrase identically — including the keyboard setting
 * that stops the words being learned (BUG25).
 */
@Immutable
public data class BackupNowUiState(
    val entry: PhraseEntry = PhraseEntry(),
    val isWorking: Boolean = false,
    /** The last attempt's outcome, until dismissed or superseded. */
    val result: BackupOutcome? = null,
    /**
     * Where backups go — the folder's own name, or null when none is chosen,
     * its grant is gone, or the folder itself no longer exists (BUG27).
     */
    val folderName: String? = null,
    /** The folder has been asked about. Until then neither the name nor the prompt shows. */
    val folderChecked: Boolean = false,
    /** The user changed folders in this visit; earlier backups stayed in the old one. */
    val folderChanged: Boolean = false,
    /** Nightly backups are already enrolled (ADR-0027), so this backup is an extra one. */
    val nightlyBackupsEnabled: Boolean = false,
    /** The QR scanner is open (ADR-0028); typing stays available behind it. */
    val isScanning: Boolean = false,
    /** Why the last scan was refused, if it was. */
    val scanMessage: String? = null,
    /**
     * The last automatic pass, when it **failed**. Null when it succeeded, was
     * skipped, or has never run — a screen that narrates every quiet success
     * teaches the user to stop reading it.
     */
    val failedNightlyAt: Long? = null,
    /**
     * A new Recovery Kit can be saved from the words the last backup just
     * verified (ADR-0028, amended 2026-09-26) — the only route to a kit with a
     * QR code for an install that onboarded before the QR existed. The words
     * themselves are held by the ViewModel, never in this state.
     */
    val kitOffered: Boolean = false,
    /** The plaintext warning (D-07) is up. */
    val kitConfirming: Boolean = false,
    /** The system "create file" picker should open, once. */
    val kitPickerRequested: Boolean = false,
    /** The kit was written; the words are gone. */
    val kitSaved: Boolean = false,
    /** Why the kit was not written, if it was not. */
    val kitFailed: Boolean = false,
) {
    val canSubmit: Boolean get() = entry.isComplete && !isWorking

    /** The screen offers the picker before any words are typed. */
    val needsFolder: Boolean get() = folderChecked && folderName == null
}

public sealed interface BackupNowEvent {
    public data class DraftChanged(val value: String) : BackupNowEvent
    public data class WordCommitted(val word: String) : BackupNowEvent
    public data class WordRemoved(val index: Int) : BackupNowEvent
    public data object Submitted : BackupNowEvent

    /** The system picker returned; null when the user backed out of it. */
    public data class FolderChosen(val treeUri: String?) : BackupNowEvent

    public data object ResultDismissed : BackupNowEvent

    /**
     * The Recovery Kit scanner (ADR-0028), grouped so the screen's `when` has
     * one branch for "the camera said something" rather than three.
     */
    public sealed interface Scanner : BackupNowEvent {
        /** Open the scanner. Typing stays available behind it. */
        public data object Requested : Scanner

        /** Close it without a scan — "type the words instead", or back. */
        public data object Dismissed : Scanner

        /** A QR code was read; the text is validated before it becomes words. */
        public data class Read(val text: String) : Scanner
    }

    /** A new Recovery Kit from the words just verified (ADR-0028, amended 2026-09-26). */
    public sealed interface Kit : BackupNowEvent {
        /** "Save Recovery Kit": show the warning first. */
        public data object Requested : Kit

        /** The warning was accepted; open the picker. */
        public data object Confirmed : Kit

        /** The warning was cancelled; the offer stays. */
        public data object Cancelled : Kit

        /** The picker has been opened; do not open it again. */
        public data object PickerLaunched : Kit

        /** The picker returned; null when the user backed out of it. */
        public data class FileChosen(val uri: String?) : Kit

        /** "Not now": the words are forgotten and the offer goes. */
        public data object Declined : Kit
    }
}

/**
 * One sentence per outcome, in the user's vocabulary.
 *
 * Every failure says **what did not happen** as well as why, because a backup
 * screen that leaves the user unsure whether a backup exists is the failure
 * that matters most here.
 */
internal fun BackupOutcome.message(): String = when (this) {
    is BackupOutcome.Done -> doneMessage()

    BackupOutcome.NotThisVaultsPhrase ->
        "Those words are a valid phrase, but not the one for this phone's data. " +
            "Nothing was backed up. Check you're using this install's Recovery Kit."

    BackupOutcome.PhraseCheckUnavailable ->
        "This phone's record of its phrase couldn't be read, so the words couldn't be " +
            "checked. Nothing was backed up."

    BackupOutcome.NoBackupFolder ->
        "Choose a folder for your backups first. Nothing was backed up."

    BackupOutcome.VaultClosed ->
        "Your data isn't open right now. Nothing was backed up."

    BackupOutcome.WriteFailed ->
        "The backup couldn't be written and checked, so nothing was saved and your " +
            "earlier backups are untouched. Check the folder has space and try again."

    is BackupOutcome.PhraseRejected -> when (val v = validation) {
        PhraseValidation.ChecksumMismatch ->
            "Every word is valid but the phrase isn't — two words are probably in the wrong order."
        is PhraseValidation.UnknownWord ->
            "Word ${v.position} (\"${v.word}\") isn't in the recovery word list."
        is PhraseValidation.WrongWordCount ->
            "That's ${v.actual} words; a recovery phrase has ${v.expected}."
        PhraseValidation.Valid -> ""
    }
}

/**
 * The success report. The image counts are spelled out because the ledger
 * being safe and every receipt being safe are different claims, and only the
 * first is guaranteed by a `Done`.
 */
private fun BackupOutcome.Done.doneMessage(): String = buildString {
    append("Backed up to $fileName.")
    val newImages = imagesWritten
    val kept = imagesAlreadyThere
    if (newImages + kept > 0) {
        append(" Receipt images: $newImages new, $kept already there.")
    }
    val missed = imagesUnreadable + imagesFailed
    if (missed > 0) {
        append(" $missed couldn't be copied")
        if (imagesUnreadable > 0) append(" — ${plural(imagesUnreadable, "image")} on this phone couldn't be read")
        append(".")
    }
    if (olderBackupsRemoved > 0) {
        append(" ${plural(olderBackupsRemoved, "older backup")} removed; the five newest are kept.")
    }
    // Once, on the backup that enrolled: from here the phone does this by
    // itself, which is a change worth stating exactly when it happens.
    if (nightlyBackupsJustEnabled) {
        append(" Nightly backups are on from now on — no words needed for those.")
    }
}

private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/** Only a finished backup is a success; everything else keeps the words for a retry. */
internal val BackupOutcome.isSuccess: Boolean get() = this is BackupOutcome.Done
