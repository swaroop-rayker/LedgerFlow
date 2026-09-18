package com.ledgerflow.core.domain.backup

import com.ledgerflow.core.domain.vault.PhraseValidation
import com.ledgerflow.core.domain.vault.RecoveryPhraseValidator
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * A manual backup: the ledger as a `.lfbk` and the receipt images beside it,
 * into the folder the user chose (SPEC.md §5.9, §16 Q23, ADR-0023).
 *
 * **Manual because nothing else is honest.** A `.lfbk` is sealed under a key
 * derived from the 24 words, and the app never holds them after onboarding
 * (ADR-0011), so a scheduled job has no way to seal one. The owner chose a
 * "Back up now" that asks for the words each time over storing key material a
 * worker could use — which would be a third wrap §7 forbids.
 */
public interface BackupRepository {

    /**
     * Backs up now, with [words] as the phrase. The caller has already checked
     * the checksum ([BackUpNowUseCase] does); this checks the words open *this*
     * vault before sealing anything under them.
     */
    public suspend fun backUpNow(words: List<String>): BackupOutcome

    /** When the last *verified* backup was written, or null if never. */
    public fun lastBackupAt(): Flow<Long?>

    /** Whether a backup folder is chosen and its grant is still held. */
    public suspend fun hasBackupFolder(): Boolean

    /** Records a newly chosen folder. The caller has already persisted the grant. */
    public suspend fun setBackupFolder(treeUri: String)
}

/** What a backup did — each outcome is a sentence the screen owes the user. */
public sealed interface BackupOutcome {

    /** Not a valid phrase at all. Caught before any key derivation. */
    public data class PhraseRejected(val validation: PhraseValidation) : BackupOutcome

    /**
     * A valid phrase that does not open this vault. **Nothing was written**:
     * a backup sealed under it would report success and never restore.
     */
    public data object NotThisVaultsPhrase : BackupOutcome

    /**
     * This install's record of its phrase could not be read, so the words
     * cannot be checked against it. **Nothing was written** — the same reason
     * as [NotThisVaultsPhrase], and a different remedy: the words may be fine.
     */
    public data object PhraseCheckUnavailable : BackupOutcome

    /** No folder chosen, or its grant was revoked. The user must choose one. */
    public data object NoBackupFolder : BackupOutcome

    /** The vault is not open, so there is nothing to back up from. */
    public data object VaultClosed : BackupOutcome

    /**
     * The ledger could not be written **and verified**. `lastBackupAt` is
     * unchanged, older backups are untouched, and no image pass ran.
     */
    public data object WriteFailed : BackupOutcome

    /**
     * The `.lfbk` is written and verified. The image counts are reported
     * separately because a backup of the ledger is still a backup when some
     * images could not be copied — but the user is told how many.
     */
    public data class Done(
        val fileName: String,
        val rows: Int,
        val imagesWritten: Int,
        val imagesAlreadyThere: Int,
        val imagesUnreadable: Int,
        val imagesFailed: Int,
        val olderBackupsRemoved: Int,
    ) : BackupOutcome
}

/**
 * The checksum first, then the repository.
 *
 * CLAUDE.md §7: validate before running the KDF, or a typo costs the user
 * 2048 rounds of HMAC-SHA512 and looks like a hang. The repository's own check
 * — does the phrase open this vault — is the expensive one, and only a
 * well-formed phrase reaches it.
 */
public class BackUpNowUseCase @Inject constructor(
    private val validator: RecoveryPhraseValidator,
    private val repository: BackupRepository,
) {
    public suspend operator fun invoke(words: List<String>): BackupOutcome {
        val validation = validator.validate(words)
        return if (validation is PhraseValidation.Valid) {
            repository.backUpNow(words)
        } else {
            BackupOutcome.PhraseRejected(validation)
        }
    }
}
