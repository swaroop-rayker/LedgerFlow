package com.ledgerflow.core.domain.backup

import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import javax.inject.Inject

/**
 * Restoring a backup onto an install that has no vault yet (SPEC.md §7.3
 * step 3, §16 Q11).
 *
 * **First run only.** Restore is offered in place of onboarding and refuses
 * when a vault already exists: it never replaces data, so it never destroys
 * any. **The backup's own phrase protects the restored vault** — a fresh DEK
 * is wrapped under the words that opened the backup, so the user keeps the one
 * phrase they already hold rather than acquiring a second.
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
