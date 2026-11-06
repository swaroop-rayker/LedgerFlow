package com.ledgerflow.core.domain.backup

import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import javax.inject.Inject

/**
 * Restoring a backup (SPEC.md §7.3 step 3, §16 Q11).
 *
 * **Two entrances.** At first run, [restore] is offered in place of onboarding
 * and refuses when a vault already exists (ADR-0026). From Settings, [replace]
 * restores over the phone's vault — the user has seen [preview]'s counts and
 * typed DELETE — and sets that vault aside rather than deleting it, so a
 * restore that does not finish can [putBack] what was there (ADR-0033).
 *
 * **The backup's own phrase protects the restored vault** either way — a fresh
 * DEK is wrapped under the words that opened the backup, so the user keeps the
 * one phrase they already hold rather than acquiring a second.
 */
public interface RestoreRepository {

    /**
     * The `.lfbk` files in a folder the user just chose, newest first — the
     * app's own names carry a UTC timestamp, so name order is time order.
     * Null when the folder cannot be read at all.
     */
    public suspend fun listBackups(treeUri: String): List<String>?

    /**
     * Restores from [source] under [words]. The caller has already checked the
     * checksum ([RestoreFromBackupUseCase] does).
     *
     * On [RestoreOutcome.Done] the restored vault is open but **not yet handed
     * to the app**, so the screen can show what came back — including how many
     * receipt images did not. [finish] hands it over.
     */
    public suspend fun restore(source: RestoreSource, words: List<String>): RestoreOutcome

    /** The user has read the report. The app opens onto the restored ledger. */
    public suspend fun finish()

    /**
     * Opens [source] under [words] **in memory** and counts what is in it and
     * what is on the phone now. Writes nothing, whatever the answer: this is
     * what stands between the words and the DELETE (ADR-0033 b).
     */
    public suspend fun preview(source: RestoreSource, words: List<String>): RestorePreview

    /**
     * Restores from [source] over the phone's vault, which is set aside rather
     * than deleted (ADR-0033 e). Answers like [restore]; a failure once the
     * vault was set aside puts it back and says so ([RestoreOutcome.RolledBack]).
     */
    public suspend fun replace(source: RestoreSource, words: List<String>): RestoreOutcome

    /** An interrupted restore from Settings can be abandoned for the vault it was replacing ([putBack]). */
    public suspend fun canPutBack(): Boolean

    /**
     * Abandons an interrupted restore and returns the vault it set aside. The
     * app opens onto that vault, with no words, as on any launch.
     */
    public suspend fun putBack(): PutBackOutcome
}

/** Entries in a vault, by book, and what is waiting in the Inbox. Never one total (Law 2). */
public data class LedgerCounts(
    val expenses: Int,
    val income: Int,
    val inbox: Int,
)

/** What [RestoreRepository.preview] found. */
public sealed interface RestorePreview {

    /**
     * The backup opens. [sameWords] says whether its words also open the
     * phone's vault; when they do not, restoring changes which words the phone
     * answers to.
     */
    public data class Ready(
        val backup: LedgerCounts,
        val phone: LedgerCounts,
        val sameWords: Boolean,
    ) : RestorePreview

    /** The backup did not open, or could not be read. [reason] says why; nothing changed. */
    public data class Refused(val reason: RestoreOutcome) : RestorePreview
}

/** What [RestoreRepository.putBack] did. */
public sealed interface PutBackOutcome {
    /** The set-aside vault is back and the app is opening it. */
    public data object Done : PutBackOutcome

    /** Nothing was set aside, or it could not be moved back. Nothing was removed. */
    public data object Failed : PutBackOutcome
}

/** Where the backup is. */
public sealed interface RestoreSource {

    /**
     * A `.lfbk` in the backup folder, with its receipt images beside it
     * (ADR-0023). The folder becomes this install's backup folder.
     */
    public data class InFolder(val treeUri: String, val fileName: String) : RestoreSource

    /**
     * A `.lfbk` on its own — mailed, messaged, copied without its folder. The
     * rows come back and every receipt image is reported as not found.
     */
    public data class SingleFile(val documentUri: String) : RestoreSource
}

/** What a restore did — each one a sentence the screen owes the user. */
public sealed interface RestoreOutcome {

    /** Not a valid phrase at all. Caught before any key derivation. */
    public data class PhraseRejected(val validation: PhraseValidation) : RestoreOutcome

    /** A valid phrase, but not this backup's. The file is intact. Nothing was written. */
    public data object WrongPhrase : RestoreOutcome

    /**
     * Finishing an interrupted restore: the vault it started is already
     * protected by one phrase, and these words are a different one.
     */
    public data object NotTheInterruptedRestoresPhrase : RestoreOutcome

    /** The right words and a damaged or unrecognisable file. Nothing was written. */
    public data object Damaged : RestoreOutcome

    /** Written by a newer LedgerFlow. Nothing was written. */
    public data class NewerVersion(val backupVersion: Int, val supported: Int) : RestoreOutcome

    /** The file or folder could not be read — access lost, or the file is gone. */
    public data object SourceUnreadable : RestoreOutcome

    /** This install already has a vault. Restore only replaces onboarding. */
    public data object AlreadySetUp : RestoreOutcome

    /**
     * Something failed after the vault started to be written. The restore is
     * marked as interrupted, so the app returns to this screen rather than
     * opening a half-built vault; running it again with the same words
     * finishes it.
     */
    public data object Failed : RestoreOutcome

    /**
     * A restore from Settings failed after the phone's vault was set aside, and
     * the vault was put back (ADR-0033 e). The phone is as it was.
     */
    public data object RolledBack : RestoreOutcome

    /**
     * The ledger is back. Images are counted separately: a restored ledger is
     * a restore even when some receipts did not come with it.
     */
    public data class Done(
        val rows: Int,
        val imagesRestored: Int,
        val imagesNotFound: Int,
        val imagesUnreadable: Int,
        val imagesFailed: Int,
    ) : RestoreOutcome
}

/**
 * The checksum first, then the repository (CLAUDE.md §7): a typo must never
 * reach 2048 rounds of HMAC-SHA512, and a restore derives the seed more than
 * once.
 */
public class RestoreFromBackupUseCase @Inject constructor(
    private val validator: RecoveryPhraseValidator,
    private val repository: RestoreRepository,
) {
    public suspend operator fun invoke(source: RestoreSource, words: List<String>): RestoreOutcome {
        val validation = validator.validate(words)
        return if (validation is PhraseValidation.Valid) {
            repository.restore(source, words)
        } else {
            RestoreOutcome.PhraseRejected(validation)
        }
    }
}

/** The checksum first, then the in-memory open (ADR-0033 b). */
public class PreviewRestoreUseCase @Inject constructor(
    private val validator: RecoveryPhraseValidator,
    private val repository: RestoreRepository,
) {
    public suspend operator fun invoke(source: RestoreSource, words: List<String>): RestorePreview {
        val validation = validator.validate(words)
        return if (validation is PhraseValidation.Valid) {
            repository.preview(source, words)
        } else {
            RestorePreview.Refused(RestoreOutcome.PhraseRejected(validation))
        }
    }
}

/** Restore from Settings, over the phone's vault: the checksum first, then [RestoreRepository.replace]. */
public class ReplaceVaultFromBackupUseCase @Inject constructor(
    private val validator: RecoveryPhraseValidator,
    private val repository: RestoreRepository,
) {
    public suspend operator fun invoke(source: RestoreSource, words: List<String>): RestoreOutcome {
        val validation = validator.validate(words)
        return if (validation is PhraseValidation.Valid) {
            repository.replace(source, words)
        } else {
            RestoreOutcome.PhraseRejected(validation)
        }
    }
}
