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

    /**
     * The chosen backup folder's name — only while its grant is held **and the
     * folder still exists** (BUG27). Null means the user must choose one.
     */
    public suspend fun backupFolderName(): String?

    /**
     * Records a newly chosen folder, replacing any earlier one. The caller has
     * already persisted the new grant; the previous folder's is released.
     * Backups already written stay where they are.
     */
    public suspend fun setBackupFolder(treeUri: String)

    /**
     * Is this install enrolled for nightly backups (ADR-0027)?
     *
     * Enrolment is one moment: a successful [backUpNow] stores the public key
     * derived from the words it just used. Nothing but that public key is kept,
     * and nothing on the device can open a backup afterwards.
     */
    public fun nightlyBackupsEnabled(): Flow<Boolean>

    /**
     * Tonight's backup, sealed to the stored public key. **No phrase is
     * involved**, which is the whole point — and the reason its verification is
     * weaker than [backUpNow]'s (ADR-0027 decision b).
     */
    public suspend fun backUpNightly(): NightlyBackupOutcome

    /**
     * What the last nightly pass did, and when — null until one has run.
     *
     * A backup that runs unattended has to be able to account for itself. Found
     * the hard way on the owner's phone (2026-09-22): a pass failed three times
     * and nothing could say why, because the app kept no record and the
     * phone's log had rotated by morning.
     */
    public fun lastNightlyAttempt(): Flow<NightlyAttempt?>
}

/**
 * The last nightly pass, as the app remembers it.
 *
 * [outcome] is [NightlyBackupOutcome.record]'s short form rather than the
 * sealed type: it is read back from storage, where a value written by an older
 * build must not become an exhaustive-`when` failure in a newer one.
 */
public data class NightlyAttempt(val at: Long, val outcome: String) {
    /** Did the last pass write a backup? A skip is not a failure, and neither is unknown. */
    public val wroteABackup: Boolean get() = outcome == NightlyBackupOutcome.RECORD_DONE

    /** The one case worth telling the user about: it tried and could not. */
    public val failed: Boolean get() = outcome == NightlyBackupOutcome.RECORD_WRITE_FAILED
}

/** What a nightly pass did. A skip is a reason, not a failure. */
public sealed interface NightlyBackupOutcome {

    /** Why tonight's pass did nothing. None of these retry usefully. */
    public enum class SkipReason {
        /** No words have been given yet, so there is no key to seal to. */
        NotEnrolled,

        /** No backup folder, or its grant no longer answers (BUG27). */
        NoFolder,

        /** The vault is not open — mid-restore, or onboarding never finished. */
        VaultClosed,
    }

    public data class Skipped(val reason: SkipReason) : NightlyBackupOutcome

    /** Written, and the bytes that landed are the bytes that were sealed. */
    public data class Done(
        val fileName: String,
        val rows: Int,
        val imagesWritten: Int,
        val imagesFailed: Int,
        val olderBackupsRemoved: Int,
    ) : NightlyBackupOutcome

    /** Nothing was recorded: `lastBackupAt` is unchanged and older backups are untouched. */
    public data object WriteFailed : NightlyBackupOutcome

    /**
     * The short form stored in `app_meta`, and the only shape a later build has
     * to keep reading. Deliberately not `toString()`, which a refactor renames
     * silently.
     */
    public fun record(): String = when (this) {
        is Done -> RECORD_DONE
        is Skipped -> RECORD_SKIPPED + reason.name
        WriteFailed -> RECORD_WRITE_FAILED
    }

    public companion object {
        public const val RECORD_DONE: String = "done"
        public const val RECORD_SKIPPED: String = "skipped:"
        public const val RECORD_WRITE_FAILED: String = "write-failed"
    }
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
        /**
         * These words also turned nightly backups on (ADR-0027), which the
         * screen says once rather than every time.
         */
        val nightlyBackupsJustEnabled: Boolean = false,
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
