package com.ledgerflow.core.testing.backup

import com.ledgerflow.core.domain.backup.BackupOutcome
import com.ledgerflow.core.domain.backup.BackupRepository
import com.ledgerflow.core.domain.backup.NightlyAttempt
import com.ledgerflow.core.domain.backup.NightlyBackupOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [BackupRepository] that answers with whatever the test sets, and records
 * what it was asked — including **the words**, so a test can assert that a
 * screen handed over exactly what the user typed and nothing it had kept.
 */
public class FakeBackupRepository(
    /** What [backUpNow] returns. */
    public var outcome: BackupOutcome = BackupOutcome.NoBackupFolder,
    /** The chosen folder's name; null when none is chosen or it is gone. */
    public var folderName: String? = "LedgerFlow backups",
) : BackupRepository {

    public val backUpCalls: MutableList<List<String>> = mutableListOf()
    public val chosenFolders: MutableList<String> = mutableListOf()
    public val lastBackup: MutableStateFlow<Long?> = MutableStateFlow(null)

    override suspend fun backUpNow(words: List<String>): BackupOutcome {
        backUpCalls += words
        return outcome
    }

    override fun lastBackupAt(): Flow<Long?> = lastBackup

    override suspend fun backupFolderName(): String? = folderName

    override suspend fun setBackupFolder(treeUri: String) {
        chosenFolders += treeUri
        folderName = treeUri.substringAfterLast('/')
    }

    // ── Nightly backups (ADR-0027) ──────────────────────────────────────────

    /** What [backUpNightly] returns. */
    public var nightlyOutcome: NightlyBackupOutcome =
        NightlyBackupOutcome.Skipped(NightlyBackupOutcome.SkipReason.NotEnrolled)

    public val nightlyEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public var nightlyRuns: Int = 0
        private set

    override fun nightlyBackupsEnabled(): Flow<Boolean> = nightlyEnabled

    override suspend fun backUpNightly(): NightlyBackupOutcome {
        nightlyRuns++
        lastNightly.value = NightlyAttempt(at = 0L, outcome = nightlyOutcome.record())
        return nightlyOutcome
    }

    public val lastNightly: MutableStateFlow<NightlyAttempt?> = MutableStateFlow(null)

    override fun lastNightlyAttempt(): Flow<NightlyAttempt?> = lastNightly
}
